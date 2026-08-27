/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone.ui

import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.privacy.PrivacyDialogController
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.PrivacyHighlightView
import com.android.systemui.statusbar.phone.StatusBarTickerController
import com.android.systemui.statusbar.phone.StatusBarAccessoryController
import com.android.systemui.statusbar.phone.StatusBarModeCoordinator
import com.android.systemui.statusbar.policy.PrivacyHighlightView.PrivacyKind
import java.util.IdentityHashMap
import javax.inject.Inject

/** Shared Smartisan privacy state for every attached clock-area host. */
@SysUISingleton
class PrivacyHighlightController
@Inject
constructor(
    private val privacyItemController: PrivacyItemController,
    private val privacyDialogController: PrivacyDialogController,
    private val tickerController: StatusBarTickerController,
    private val accessoryController: StatusBarAccessoryController,
    private val modeCoordinator: StatusBarModeCoordinator,
    @Main private val mainHandler: Handler,
) : PrivacyItemController.Callback {
    private data class HostRegistration(
        val view: PrivacyHighlightView,
        val root: View,
        var attached: Boolean,
        var laidOut: Boolean,
    )

    private val hosts = IdentityHashMap<PrivacyHighlightView, HostRegistration>()
    private val replacementActiveCallbacks = LinkedHashSet<(Boolean) -> Unit>()
    private var privacyItems: List<PrivacyItem> = emptyList()
    private var currentKind: PrivacyKind? = null
    private var initialSnapshotApplied = false

    private val replacementHostReady = HashMap<Int, Boolean>()
    private val replacementActive = HashMap<Int, Boolean>()
    @Volatile private var activePrivacyItemCount = 0
    @Volatile private var locationOnlyActive = false
    private val platformPrivacyAnimationSuppressed = HashSet<Int>()

    private val attachStateListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val privacyView = view as? PrivacyHighlightView ?: return
                val host = hosts[privacyView] ?: return
                host.attached = true
                host.laidOut = view.isLaidOut && view.width > 0 && view.height > 0
                syncPresentation()
            }

            override fun onViewDetachedFromWindow(view: View) {
                val privacyView = view as? PrivacyHighlightView ?: return
                val host = hosts[privacyView] ?: return
                host.attached = false
                host.laidOut = false
                syncPresentation()
            }
        }

    private val layoutChangeListener =
        View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val privacyView =
                view as? PrivacyHighlightView ?: return@OnLayoutChangeListener
            val host = hosts[privacyView] ?: return@OnLayoutChangeListener
            val laidOut = right > left && bottom > top
            if (host.laidOut != laidOut) {
                host.laidOut = laidOut
                syncPresentation()
            }
        }

    fun registerHost(view: PrivacyHighlightView) {
        runOnMain { registerHostOnMain(view) }
    }

    private fun registerHostOnMain(view: PrivacyHighlightView) {
        if (hosts.containsKey(view)) return
        val wasEmpty = hosts.isEmpty()
        // A replacement host is valid only inside the concrete HOME status-bar subtree. Never
        // fall back to NotificationShadeWindowView: it contains duplicate HOME/PANEL ids and
        // would make ticker/accessory lookup silently bind the wrong presentation.
        val root = findStatusBarRoot(view) ?: return
        hosts[view] =
            HostRegistration(
                view = view,
                root = root,
                attached = view.isAttachedToWindow,
                laidOut = view.isLaidOut && view.width > 0 && view.height > 0,
            )
        view.addOnAttachStateChangeListener(attachStateListener)
        view.addOnLayoutChangeListener(layoutChangeListener)
        tickerController.registerHost(root)
        accessoryController.registerHost(root)
        view.setOnClickListener { privacyDialogController.showDialog(view.context) }
        if (wasEmpty) {
            privacyItemController.addCallback(this)
        }
        updateHost(view)
        syncPresentation()
    }

    fun unregisterHost(view: PrivacyHighlightView) {
        runOnMain { unregisterHostOnMain(view) }
    }

    private fun unregisterHostOnMain(view: PrivacyHighlightView) {
        val registration = hosts.remove(view) ?: return
        view.removeOnAttachStateChangeListener(attachStateListener)
        view.removeOnLayoutChangeListener(layoutChangeListener)
        view.setOnClickListener(null)
        view.setPrivacy(null, null)
        if (hosts.values.none { it.root === registration.root }) {
            tickerController.unregisterHost(registration.root)
            accessoryController.unregisterHost(registration.root)
        }
        if (hosts.isEmpty()) {
            privacyItemController.removeCallback(this)
            // Publish loss of the replacement while the last known privacy snapshot is still
            // available. PrivacyDotViewController uses that snapshot to restore Android's
            // fail-safe indicator immediately when the R2 host is torn down mid-session.
            replacementHostReady.clear()
            modeCoordinator.setPrivacyVisible(false)
            publishReplacementState(emptyMap())
            privacyItems = emptyList()
            currentKind = null
            initialSnapshotApplied = false
            activePrivacyItemCount = 0
            locationOnlyActive = false
            tickerController.setPrivacyActive(false)
            return
        }
        syncPresentation()
    }

    /**
     * Observes whether the R2 host is ready and can represent every currently active privacy type.
     * A physical but unattached/unmeasured host is deliberately not sufficient to suppress Android's
     * fail-safe privacy indicator.
     */
    fun addReplacementActiveCallback(callback: (Boolean) -> Unit) {
        runOnMain {
            if (replacementActiveCallbacks.add(callback)) callback(replacementActive.values.any())
        }
    }

    fun removeReplacementActiveCallback(callback: (Boolean) -> Unit) {
        runOnMain { replacementActiveCallbacks.remove(callback) }
    }

    fun hasReplacementHost(displayId: Int): Boolean = replacementHostReady[displayId] == true

    fun isReplacementActive(displayId: Int): Boolean = replacementActive[displayId] == true

    fun hasActivePrivacyItems(): Boolean = activePrivacyItemCount > 0

    fun activePrivacyItemsAreLocationOnly(): Boolean = locationOnlyActive

    fun beginPlatformPrivacyAnimationSuppression(displayId: Int): Boolean {
        if (!isReplacementActive(displayId)) return false
        platformPrivacyAnimationSuppressed += displayId
        return true
    }

    fun endPlatformPrivacyAnimationSuppression(displayId: Int) {
        platformPrivacyAnimationSuppressed -= displayId
    }

    fun isPlatformPrivacyAnimationSuppressed(displayId: Int): Boolean =
        displayId in platformPrivacyAnimationSuppressed

    override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onPrivacyItemsChanged(privacyItems) }
            return
        }
        this.privacyItems = privacyItems.filterNot(PrivacyItem::paused)
        activePrivacyItemCount = this.privacyItems.size
        locationOnlyActive =
            this.privacyItems.isNotEmpty() &&
                this.privacyItems.all { it.privacyType == PrivacyType.TYPE_LOCATION }
        initialSnapshotApplied = true
        val kind = resolveKind()
        if (kind != currentKind) {
            currentKind = kind
        }
        tickerController.setPrivacyActive(this.privacyItems.isNotEmpty())
        hosts.keys.toList().forEach(::updateHost)
        syncPresentation()
    }

    private fun updateHost(view: PrivacyHighlightView) {
        val kind = resolveKind()
        val description =
            privacyItems
                .map { it.privacyType.getName(view.context) }
                .distinct()
                .joinToString()
                .takeIf { it.isNotEmpty() }
        view.setPrivacy(kind, description)
    }

    private fun resolveKind(): PrivacyKind? =
        if (!allActiveTypesSupported()) {
            null
        } else when {
            privacyItems.any { it.privacyType == PrivacyType.TYPE_CAMERA } -> PrivacyKind.CAMERA
            privacyItems.any { it.privacyType == PrivacyType.TYPE_MICROPHONE } ->
                PrivacyKind.MICROPHONE
            privacyItems.any { it.privacyType == PrivacyType.TYPE_MEDIA_PROJECTION } ->
                PrivacyKind.SCREEN_RECORD
            privacyItems.any { it.privacyType == PrivacyType.TYPE_LOCATION } ->
                PrivacyKind.LOCATION
            else -> null
        }

    private fun allActiveTypesSupported(): Boolean =
        privacyItems.all {
            it.privacyType == PrivacyType.TYPE_CAMERA ||
                it.privacyType == PrivacyType.TYPE_MICROPHONE ||
                it.privacyType == PrivacyType.TYPE_LOCATION ||
                it.privacyType == PrivacyType.TYPE_MEDIA_PROJECTION
        }

    private fun syncPresentation() {
        val readyByDisplay =
            hosts.values
                .groupBy { it.root.context.displayId }
                .mapValues { (_, registrations) -> registrations.any { it.isReady() } }
        replacementHostReady.clear()
        replacementHostReady.putAll(readyByDisplay)
        val kind = currentKind
        // StatusBarModeCoordinator owns the one phone-shade presentation on the default display.
        // External R2 hosts render their own Highlight view but must never mutate default-display
        // PANEL/Ticker ownership while the default host is being rebuilt.
        modeCoordinator.setPrivacyVisible(
            shouldShowDefaultPresentation(readyByDisplay, kind != null)
        )
        val canReplace =
            initialSnapshotApplied &&
                privacyItems.isNotEmpty() &&
                allActiveTypesSupported() &&
                kind != null
        publishReplacementState(readyByDisplay.mapValues { (_, ready) -> ready && canReplace })
    }

    private fun HostRegistration.isReady(): Boolean = attached && laidOut

    private fun publishReplacementState(activeByDisplay: Map<Int, Boolean>) {
        if (replacementActive == activeByDisplay) return
        replacementActive.clear()
        replacementActive.putAll(activeByDisplay)
        val anyActive = replacementActive.values.any()
        replacementActiveCallbacks.toList().forEach { it(anyActive) }
    }

    companion object {
        @JvmStatic
        internal fun shouldShowDefaultPresentation(
            readyByDisplay: Map<Int, Boolean>,
            hasPrivacyKind: Boolean,
        ): Boolean =
            hasPrivacyKind && readyByDisplay[Display.DEFAULT_DISPLAY] == true
    }

    private fun findStatusBarRoot(view: View): View? {
        var candidate: View? = view
        while (candidate != null) {
            if (candidate.id == R.id.status_bar) return candidate
            candidate = candidate.parent as? View
        }
        return null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
