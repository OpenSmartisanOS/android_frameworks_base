/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.statusbar.phone

import android.app.Notification
import android.app.NotificationManager
import android.os.Handler
import android.os.UserHandle
import android.text.TextUtils
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ImageSwitcher
import android.widget.TextSwitcher
import android.widget.TextView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import java.util.IdentityHashMap
import java.util.function.Consumer
import javax.inject.Inject

/** Modern notification-pipeline owner for the original R2 segmented status-bar ticker. */
@SysUISingleton
class StatusBarTickerController
@Inject
constructor(
    private val notifPipeline: NotifPipeline,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val headsUpManager: HeadsUpManager,
    private val keyguardVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val modeCoordinator: StatusBarModeCoordinator,
    @Main private val handler: Handler,
) {
    private enum class Phase {
        IDLE,
        ACTIVE,
        EXITING,
    }

    private data class Segment(
        val key: String,
        var offset: Int = 0,
    )

    private data class Host(
        val root: View,
        val normalContents: View,
        val ticker: View,
        val iconSwitcher: ImageSwitcher,
        val textSwitcher: TextSwitcher,
        val textView: TextView,
        var hasContent: Boolean = false,
        var transitionGeneration: Long = 0L,
    )

    // Hosts have an explicit attach/detach lifecycle. A weak set of wrapper objects is incorrect:
    // the wrapper itself has no other owner and may disappear while the status bar is still alive.
    private val hosts = IdentityHashMap<View, Host>()
    private val segments = ArrayDeque<Segment>()
    // Keep only a non-reversible presentation fingerprint. The ticker always re-reads the current
    // NotificationEntry before drawing so private text is never retained outside NotifPipeline.
    private val acceptedTextHashes = mutableMapOf<String, Int>()
    private val controllerCreatedAtMillis = System.currentTimeMillis()
    private var phase = Phase.IDLE
    private var privacyActive = false
    private var lightsOut = false

    private val advance = Runnable { advanceNow() }
    private val notificationStateChangedListener =
        NotificationLockscreenUserManager.NotificationStateChangedListener {
            revalidateCurrentPresentation()
        }
    private val keyguardVisibilityChangedListener = Consumer<String> {
        revalidateCurrentPresentation()
    }
    private val userChangedListener =
        object : NotificationLockscreenUserManager.UserChangedListener {
            override fun onUserChanged(userId: Int) {
                acceptedTextHashes.clear()
                halt()
            }

            override fun onCurrentProfilesChanged(
                currentProfiles: android.util.SparseArray<android.content.pm.UserInfo>
            ) {
                val eligibleProfileIds = mutableSetOf<Int>()
                for (index in 0 until currentProfiles.size()) {
                    val profile = currentProfiles.valueAt(index)
                    if (profile.isEnabled && !profile.isQuietModeEnabled) {
                        eligibleProfileIds += profile.id
                    }
                }
                segments.removeAll { segment ->
                    val entry = notifPipeline.getEntry(segment.key)
                    entry == null ||
                        (entry.sbn.userId != UserHandle.USER_ALL &&
                            entry.sbn.userId !in eligibleProfileIds) ||
                        !eligible(entry)
                }
                if (segments.isEmpty()) {
                    halt()
                } else {
                    segments.first().offset = 0
                    renderCurrent(animate = false)
                }
            }
        }
    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) = enqueue(entry, isUpdate = false)

            override fun onEntryUpdated(entry: NotificationEntry) = enqueue(entry, isUpdate = true)

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                acceptedTextHashes.remove(entry.key)
                remove(entry.key)
            }
        }
    private val headsUpListener =
        object : OnHeadsUpChangedListener {
            override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
                if (isHeadsUp) remove(entry.key)
            }
        }

    init {
        // The factory ticker is a process-level notification consumer, not a View-level listener.
        // Register as soon as the singleton is created so notifications posted while the status
        // bar root is inflating (notably boot USB/system notifications) are not lost. Host attach
        // only controls where an already prepared presentation is rendered.
        notifPipeline.addCollectionListener(collectionListener)
        headsUpManager.addListener(headsUpListener)
        lockscreenUserManager.addNotificationStateChangedListener(notificationStateChangedListener)
        lockscreenUserManager.addUserChangedListener(userChangedListener)
        keyguardVisibilityProvider.addOnStateChangedListener(keyguardVisibilityChangedListener)
    }

    fun registerHost(root: View) {
        val normalContents = root.findViewById<View>(R.id.status_bar_contents) ?: return
        val ticker = root.findViewById<View>(R.id.status_bar_ticker_view) ?: return
        val iconSwitcher = ticker.findViewById<ImageSwitcher>(R.id.status_bar_ticker_icon_switcher) ?: return
        val textSwitcher = ticker.findViewById<TextSwitcher>(R.id.status_bar_ticker_text_switcher) ?: return
        val text = textSwitcher.getChildAt(0) as? TextView ?: return
        if (hosts.containsKey(root)) return
        if (hosts.isEmpty() && phase == Phase.IDLE && segments.isEmpty()) {
            seedEntriesPostedBeforeFirstHost()
        }
        iconSwitcher.inAnimation =
            AnimationUtils.loadAnimation(root.context, com.android.internal.R.anim.push_up_in)
        iconSwitcher.outAnimation =
            AnimationUtils.loadAnimation(root.context, com.android.internal.R.anim.push_up_out)
        textSwitcher.inAnimation =
            AnimationUtils.loadAnimation(root.context, com.android.internal.R.anim.push_up_in)
        textSwitcher.outAnimation =
            AnimationUtils.loadAnimation(root.context, com.android.internal.R.anim.push_up_out)
        val host = Host(root, normalContents, ticker, iconSwitcher, textSwitcher, text)
        hosts[root] = host
        if (phase == Phase.ACTIVE) {
            renderCurrent(animate = false, expose = false)
            startHostEntryTransition(host)
        } else if (phase == Phase.EXITING) {
            restoreHost(host)
        }
    }

    private fun seedEntriesPostedBeforeFirstHost() {
        notifPipeline.allNotifs
            .asSequence()
            .filter { entry ->
                // The original pipeline inflated and ticked active legacy entries after creating
                // the status-bar host. Preserve that boot behaviour for explicit tickerText, while
                // only replaying modern compatibility text that arrived during this controller's
                // own construction/Host race.
                !entry.sbn.notification.tickerText.isNullOrBlank() ||
                    entry.sbn.postTime >= controllerCreatedAtMillis
            }
            .sortedBy { it.sbn.postTime }
            .forEach { enqueue(it, isUpdate = false) }
    }

    fun unregisterHost(root: View) {
        hosts.remove(root)?.let(::restoreHost)
    }

    fun isTickerVisible(): Boolean = phase == Phase.ACTIVE

    private fun enqueue(entry: NotificationEntry, isUpdate: Boolean) {
        // PANEL, HighlightAlert and lights-out own the entire R2 status-bar presentation. Do not
        // retain text received while another owner is active: replaying it later could expose stale
        // lockscreen/profile content and is not how the factory ticker behaves.
        if (modeCoordinator.isPanelMode() || privacyActive || lightsOut) {
            remove(entry.key)
            return
        }
        if (!eligible(entry)) {
            remove(entry.key)
            return
        }
        val sourceText = sourceTickerText(entry)
        if (sourceText == null) {
            remove(entry.key)
            return
        }
        val textHash = sourceText.toString().hashCode()
        if (isUpdate && acceptedTextHashes[entry.key] == textHash) return
        acceptedTextHashes[entry.key] = textHash
        val existing = segments.firstOrNull { it.key == entry.key }
        if (existing != null) {
            existing.offset = 0
            if (segments.firstOrNull() === existing) {
                renderCurrent(animate = true)
            }
            return
        }
        segments.addLast(Segment(entry.key))
        if (phase != Phase.ACTIVE) {
            if (phase == Phase.EXITING) {
                hosts.values.toList().forEach(::cancelHostExitForRestart)
            }
            // Factory Ticker fills both switchers before atomically changing the two Host
            // visibilities. This matters when a Host is rebuilt and for accessibility snapshots.
            renderCurrent(animate = false, expose = false)
            phase = Phase.ACTIVE
            setPresentationVisible(true)
            hosts.values.toList().forEach(::startHostEntryTransition)
        }
    }

    private fun eligible(entry: NotificationEntry): Boolean {
        val userId = entry.sbn.userId
        return (userId == UserHandle.USER_ALL || lockscreenUserManager.isCurrentProfile(userId)) &&
            !entry.isRowDismissed &&
            !headsUpManager.isHeadsUpEntry(entry.key) &&
            !keyguardVisibilityProvider.shouldHideNotification(entry) &&
            !entry.shouldSuppressStatusBar() &&
            !entry.ranking.isSuspended &&
            entry.ranking.matchesInterruptionFilter()
    }

    private fun remove(key: String, render: Boolean = true) {
        val current = segments.firstOrNull()?.key == key
        segments.removeAll { it.key == key }
        if (!render) return
        if (segments.isEmpty()) {
            finishNaturally()
        } else if (current) {
            handler.removeCallbacks(advance)
            renderCurrent(animate = true)
        }
    }

    private fun renderCurrent(animate: Boolean, expose: Boolean = true) {
        val context = hosts.values.firstOrNull()?.root?.context ?: return
        var segment: Segment
        var entry: NotificationEntry
        var text: CharSequence
        while (true) {
            val first = segments.firstOrNull()
            if (first == null) {
                finishNaturally()
                return
            }
            segment = first
            val currentEntry = notifPipeline.getEntry(segment.key)
            if (currentEntry == null) {
                segments.removeFirst()
                continue
            }
            entry = currentEntry
            val currentText = resolveText(entry, context)
            if (currentText == null || segment.offset >= currentText.length) {
                segments.removeFirst()
                continue
            }
            text = currentText
            break
        }
        val startOffset = segment.offset
        val hostSnapshot = hosts.values.toList()
        val nextOffset =
            hostSnapshot.minOfOrNull { host -> pageFor(host, text, startOffset).second }
                ?: text.length
        hostSnapshot.forEach { host ->
            if (expose) host.ticker.visibility = View.VISIBLE
            // All displays consume the same safe source range so a wider external bar never shows
            // text that the narrowest/cutout host will repeat on the next segment. Each Host still
            // lays that range out against its own width and cutout.
            val page = pageFor(host, text, startOffset, nextOffset)
            val animateSwitch = animate && host.hasContent
            host.iconSwitcher.setAnimateFirstView(animateSwitch)
            host.textSwitcher.setAnimateFirstView(animateSwitch)
            if (!host.hasContent) {
                host.iconSwitcher.reset()
                host.textSwitcher.reset()
            }
            (host.iconSwitcher.nextView as? ImageView)?.tag = entry.sbn.packageName
            val icon = loadTickerIcon(entry, host.root.context)
            val hostIcon =
                icon?.constantState?.newDrawable(host.root.resources)?.mutate() ?: icon?.mutate()
            host.iconSwitcher.setImageDrawable(hostIcon)
            (host.iconSwitcher.currentView as? ImageView)?.setImageLevel(
                entry.sbn.notification.iconLevel
            )
            host.textSwitcher.setText(page.first)
            host.hasContent = true
            host.ticker.contentDescription = accessibilityDescription(entry, text, context)
            if (host.ticker.alpha != 1f || host.ticker.translationY != 0f) {
                host.ticker.alpha = 1f
                host.ticker.translationY = 0f
            }
            if (!expose) {
                // The entry transition will publish and announce the fully prepared Host.
            } else if (animateSwitch) {
                host.ticker.announceForAccessibility(host.ticker.contentDescription)
            } else {
                host.ticker.sendAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                )
            }
        }
        segment.offset = maxOf(startOffset, nextOffset)
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, SEGMENT_DELAY_MS)
    }

    private fun advanceNow() {
        val segment = segments.firstOrNull() ?: run {
            finishNaturally()
            return
        }
        val context = hosts.values.firstOrNull()?.root?.context ?: run {
            halt()
            return
        }
        val text = notifPipeline.getEntry(segment.key)?.let { resolveText(it, context) }
        if (text == null || segment.offset >= text.length) {
            segments.removeFirst()
        }
        if (segments.isEmpty()) finishNaturally() else renderCurrent(animate = true)
    }

    /** Returns rendered text and the next source offset. */
    private fun pageFor(
        host: Host,
        text: CharSequence,
        offset: Int,
        endOffset: Int = text.length,
    ): Pair<CharSequence, Int> {
        val safeOffset = offset.coerceIn(0, text.length)
        val safeEnd = endOffset.coerceIn(safeOffset, text.length)
        val source = text.subSequence(safeOffset, safeEnd)
        if (source.isEmpty()) return "" to safeEnd
        val paint = host.textView.paint
        val width = host.textSwitcher.width.takeIf { it > 0 } ?: host.root.width
        if (width <= 0) return source to text.length
        val cutout = host.root.rootWindowInsets?.displayCutout?.boundingRectTop
        val centerCutout =
            cutout?.takeIf {
                !it.isEmpty && it.left < host.root.width / 2 && it.right > host.root.width / 2
            }
        if (centerCutout == null) {
            val count = paint.breakText(source, 0, source.length, true, width.toFloat(), null)
            return source.subSequence(0, count.coerceAtLeast(1)) to
                (safeOffset + count.coerceAtLeast(1))
        }
        val location = IntArray(2)
        host.textSwitcher.getLocationOnScreen(location)
        val leftWidth = (centerCutout.left - location[0]).coerceAtLeast(0)
        val rightWidth = (location[0] + width - centerCutout.right).coerceAtLeast(0)
        var leftCount = paint.breakText(source, 0, source.length, true, leftWidth.toFloat(), null)
        var wordBoundarySuffix: CharSequence = ""
        if (
            leftCount > 2 &&
                leftCount < source.length &&
                source[leftCount - 1].isAsciiLetter() &&
                source[leftCount].isAsciiLetter()
        ) {
            wordBoundarySuffix = if (source[leftCount - 2].isAsciiLetter()) "- " else "  "
            leftCount--
        }
        val remaining = source.subSequence(leftCount, source.length)
        val rightCount =
            paint.breakText(remaining, 0, remaining.length, true, rightWidth.toFloat(), null)
        val spaces =
            " ".repeat(
                (centerCutout.width() / paint.measureText(" ").coerceAtLeast(1f)).toInt() + 2
            )
        val page =
            TextUtils.concat(
                source.subSequence(0, leftCount),
                wordBoundarySuffix,
                spaces,
                remaining.subSequence(0, rightCount),
            )
        val consumed = (leftCount + rightCount).coerceAtLeast(1)
        return page to (safeOffset + consumed)
    }

    private fun resolveText(entry: NotificationEntry, context: android.content.Context): CharSequence? {
        if (!eligible(entry)) return null
        val raw = sourceTickerText(entry) ?: return null
        val isPrivate =
            lockscreenUserManager.isLockscreenPublicMode(entry.sbn.userId) &&
                lockscreenUserManager.getRedactionType(entry) !=
                    NotificationLockscreenUserManager.REDACTION_TYPE_NONE
        return if (isPrivate) {
            context.getText(R.string.status_bar_private_notification)
        } else {
            raw
        }
    }

    /**
     * Android 8 applications normally supplied [Notification.tickerText] explicitly. Most modern
     * Android applications no longer do because the platform removed the visual ticker. R2 still
     * needs a presentation string, so use the explicit factory field first and then derive the
     * same user-visible text from standard Notification extras without mutating the notification.
     *
     * Compatibility fallback is intentionally limited to alerting, non-ongoing leaf entries.
     * Explicit legacy ticker text keeps the exact factory behaviour for system/ongoing notices.
     */
    private fun sourceTickerText(entry: NotificationEntry): CharSequence? {
        val notification = entry.sbn.notification
        notification.tickerText?.takeIf { it.isNotBlank() }?.let { return it }
        if (entry.ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return null
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val extras = notification.extras
        extras.getCharSequence(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.lastOrNull { !it.isNullOrBlank() }
            ?.let { return it }
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
    }

    private fun halt() {
        finishTicker(interrupted = true)
    }

    private fun finishNaturally() {
        finishTicker(interrupted = false)
    }

    private fun finishTicker(interrupted: Boolean) {
        handler.removeCallbacks(advance)
        segments.clear()
        if (phase == Phase.IDLE) return
        phase = Phase.EXITING
        setPresentationVisible(false)
        hosts.values.toList().forEach { host ->
            if (host.root.isLaidOut) {
                startHostExitTransition(host, interrupted)
            } else {
                restoreHost(host)
            }
        }
        if (hosts.values.none { it.root.isLaidOut }) phase = Phase.IDLE
    }

    /** The factory PANEL mode disables and clears the current ticker when its transition starts. */
    fun disableForPanel() {
        if (phase != Phase.IDLE || segments.isNotEmpty()) halt()
    }

    /** HighlightAlert owns the clock area and preempts the factory ticker. */
    fun disableForPrivacy() {
        if (phase != Phase.IDLE || segments.isNotEmpty()) halt()
    }

    /** Blocks new segments for the complete privacy lifetime, including platform-only types. */
    fun setPrivacyActive(active: Boolean) {
        if (privacyActive == active) return
        privacyActive = active
        if (active) disableForPrivacy()
    }

    /** R2 lights-out discards the current ticker instead of resuming a stale segment later. */
    fun setLightsOut(active: Boolean) {
        if (lightsOut == active) return
        lightsOut = active
        modeCoordinator.setLightsOut(active)
        if (active) halt()
    }

    private fun revalidateCurrentPresentation() {
        segments.removeAll { segment ->
            notifPipeline.getEntry(segment.key)?.let(::eligible) != true
        }
        if (segments.isEmpty()) {
            halt()
        } else {
            segments.first().offset = 0
            if (phase == Phase.ACTIVE) renderCurrent(animate = false)
        }
    }

    private fun restoreHost(host: Host) {
        host.transitionGeneration++
        host.normalContents.clearAnimation()
        host.ticker.clearAnimation()
        host.normalContents.visibility =
            if (modeCoordinator.isPanelMode() && !modeCoordinator.state.privacyVisible) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
        host.ticker.visibility = View.GONE
        host.ticker.alpha = 1f
        host.ticker.translationY = 0f
        host.iconSwitcher.reset()
        host.textSwitcher.reset()
        host.hasContent = false
        host.ticker.contentDescription = null
    }

    /**
     * The four resource ids used by R2's MyTicker are framework push/fade animations. Smali
     * confirms that JADX's names for 0x010a0088/8a/8c/8d were stale; the real resources are
     * push_down_in/out and push_up_in/out. Android 16 ships the same geometry and the same
     * 500/400 ms framework timing, so use those resources directly instead of approximating them.
     */
    private fun startHostEntryTransition(host: Host) {
        host.transitionGeneration++
        host.normalContents.clearAnimation()
        host.ticker.clearAnimation()
        // Legacy View animations deliberately draw the outgoing GONE View until completion. This
        // is the exact factory MyTicker ordering, not a property-animation approximation.
        host.normalContents.visibility = View.GONE
        host.ticker.visibility = View.VISIBLE
        val tickerIn =
            AnimationUtils.loadAnimation(
                host.root.context,
                com.android.internal.R.anim.push_down_in,
            )
        val homeOut =
            AnimationUtils.loadAnimation(
                host.root.context,
                com.android.internal.R.anim.push_down_out,
            )
        host.normalContents.startAnimation(homeOut)
        host.ticker.startAnimation(tickerIn)
    }

    private fun startHostExitTransition(host: Host, interrupted: Boolean) {
        val generation = ++host.transitionGeneration
        host.normalContents.clearAnimation()
        host.ticker.clearAnimation()
        val animateHome =
            !modeCoordinator.isPanelMode() && !privacyActive && !lightsOut
        val showHome = !modeCoordinator.isPanelMode() || privacyActive
        host.normalContents.visibility = if (showHome) View.VISIBLE else View.INVISIBLE
        host.ticker.visibility = View.GONE
        val homeIn =
            AnimationUtils.loadAnimation(
                host.root.context,
                if (interrupted) android.R.anim.fade_in
                else com.android.internal.R.anim.push_up_in,
            )
        val tickerOut =
            AnimationUtils.loadAnimation(
                host.root.context,
                if (interrupted) android.R.anim.fade_out
                else com.android.internal.R.anim.push_up_out,
            )
        tickerOut.setAnimationListener(
            object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationRepeat(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    if (generation != host.transitionGeneration || phase == Phase.ACTIVE) return
                    host.ticker.clearAnimation()
                    host.iconSwitcher.reset()
                    host.textSwitcher.reset()
                    host.hasContent = false
                    host.ticker.contentDescription = null
                    phase = Phase.IDLE
                }
            }
        )
        if (animateHome) host.normalContents.startAnimation(homeIn)
        host.ticker.startAnimation(tickerOut)
    }

    private fun cancelHostExitForRestart(host: Host) {
        host.transitionGeneration++
        host.normalContents.clearAnimation()
        host.ticker.clearAnimation()
        host.iconSwitcher.reset()
        host.textSwitcher.reset()
        host.hasContent = false
        host.ticker.contentDescription = null
    }

    private fun setPresentationVisible(value: Boolean) {
        if (modeCoordinator.state.tickerVisible == value) return
        modeCoordinator.setTickerVisible(value)
    }

    private fun loadTickerIcon(
        entry: NotificationEntry,
        context: android.content.Context,
    ): android.graphics.drawable.Drawable? {
        val notification = entry.sbn.notification
        if (notification.icon != 0) {
            try {
                val packageContext = entry.sbn.getPackageContext(context)
                packageContext.getDrawable(notification.icon)?.let { return it.mutate() }
            } catch (_: RuntimeException) {}
        }
        return try {
            val userId =
                entry.sbn.userId.takeUnless { it == UserHandle.USER_ALL }
                    ?: UserHandle.USER_SYSTEM
            notification.smallIcon?.loadDrawableAsUser(context, userId)?.mutate()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun accessibilityDescription(
        entry: NotificationEntry,
        text: CharSequence,
        context: android.content.Context,
    ): CharSequence {
        val label =
            try {
                val packageContext = entry.sbn.getPackageContext(context)
                packageContext.applicationInfo.loadLabel(context.packageManager)
            } catch (_: RuntimeException) {
                entry.sbn.packageName
            }
        return TextUtils.concat(label, ": ", text)
    }

    private companion object {
        const val SEGMENT_DELAY_MS = 3_000L
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
