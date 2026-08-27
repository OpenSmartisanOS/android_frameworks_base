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
package com.android.systemui.statusbar.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.Display
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import java.util.IdentityHashMap
import javax.inject.Inject

/**
 * Owns the mutually exclusive HOME, PANEL and ticker presentation of the R2 status bar.
 *
 * Android's visibility policy continues to own the children inside the clock/privacy and
 * notification hosts. This coordinator only moves/hides those stable hosts, so a disable flag,
 * keyguard/occlusion update, lights-out transition, ticker update or privacy update that arrives
 * while PANEL is showing is retained and becomes the source of truth when PANEL exits.
 */
@SysUISingleton
class StatusBarModeCoordinator @Inject constructor() {
    data class State(
        val panelExpanded: Boolean,
        val tickerVisible: Boolean,
        val privacyVisible: Boolean,
        val lightsOut: Boolean,
        val animate: Boolean,
        val generation: Long,
    ) {
        /** HOME's left presentation is replaced by PANEL or ticker in either state. */
        val homeContentSuppressed: Boolean
            get() = panelExpanded || tickerVisible
    }

    fun interface Callback {
        fun onStatusBarModeChanged(state: State)
    }

    private data class HomeHost(
        val root: View,
        val normalContents: View,
        val clockPrivacyHost: View,
        val notificationHost: View,
        val endSideHost: View?,
        var animator: AnimatorSet? = null,
        var panelCommitted: Boolean = false,
    )

    private val hosts = IdentityHashMap<View, HomeHost>()
    private val callbacks = LinkedHashSet<Callback>()
    private var panelExpanded = false
    private var tickerVisible = false
    private var privacyVisible = false
    private var lightsOut = false
    private var generation = 0L
    private var panelAnimationGeneration = 0L

    val state: State
        get() = snapshot(animate = false)

    fun isPanelMode(): Boolean = panelExpanded

    fun isHomeContentSuppressed(): Boolean = panelExpanded || tickerVisible

    fun registerHomeHost(root: View) {
        if (hosts.containsKey(root)) return
        val normalContents = root.findViewById<View>(R.id.status_bar_contents) ?: return
        val clockPrivacyHost =
            root.findViewById<View>(R.id.privacy_highlight)
                ?: root.findViewById(R.id.clock)
                ?: return
        val notificationHost =
            root.findViewById<View>(R.id.status_bar_contents_left)
                ?: root.findViewById(R.id.notification_icon_area)
                ?: return
        val host =
            HomeHost(
                root = root,
                normalContents = normalContents,
                clockPrivacyHost = clockPrivacyHost,
                notificationHost = notificationHost,
                endSideHost = root.findViewById(R.id.status_bar_end_side_content),
            )
        hosts[root] = host
        if (!host.ownsPhoneShadePresentation()) return
        applyTickerState(host)
        applyPanelState(
            host,
            panelExpanded,
            animate = false,
            expectedPanelGeneration = panelAnimationGeneration,
        )
    }

    fun unregisterHomeHost(root: View) {
        hosts.remove(root)?.let { host ->
            host.animator?.cancel()
            host.animator = null
            if (host.ownsPhoneShadePresentation()) {
                host.normalContents.visibility = View.VISIBLE
                restoreStableHost(host.clockPrivacyHost)
                restoreStableHost(host.notificationHost)
                host.endSideHost?.let(::restoreStableHost)
            }
        }
    }

    fun addCallback(callback: Callback) {
        if (callbacks.add(callback)) callback.onStatusBarModeChanged(state)
    }

    fun removeCallback(callback: Callback) {
        callbacks.remove(callback)
    }

    fun setPanelExpanded(expanded: Boolean, animate: Boolean) {
        if (panelExpanded == expanded) return
        panelExpanded = expanded
        generation++
        val currentPanelGeneration = ++panelAnimationGeneration
        hosts.values.filter { it.ownsPhoneShadePresentation() }.forEach { host ->
            applyPanelState(
                host,
                expanded,
                animate,
                currentPanelGeneration,
            )
        }
        dispatch(snapshot(animate))
    }

    fun setTickerVisible(visible: Boolean) {
        if (tickerVisible == visible) return
        tickerVisible = visible
        generation++
        panelAnimationGeneration++
        hosts.values.filter { it.ownsPhoneShadePresentation() }.forEach { host ->
            host.animator?.cancel()
            host.animator = null
            // Ticker is a stronger whole-HOME replacement. Snap the underlying PANEL state so
            // ending ticker can never expose a half-finished HOME transition.
            applyPanelEndState(host, panelExpanded)
        }
        dispatch(snapshot(animate = false))
    }

    fun setPrivacyVisible(visible: Boolean) {
        if (privacyVisible == visible) return
        privacyVisible = visible
        generation++
        // HighlightAlert is the only privacy indication on the default R2 display. If privacy
        // changes while PANEL owns the bar, atomically expose only its clock-area host and keep
        // HOME notifications/network/battery hidden. This avoids both a missing indicator and a
        // duplicate HOME/PANEL system-icon row.
        hosts.values.filter { it.ownsPhoneShadePresentation() }.forEach { host ->
            if (panelExpanded) {
                host.animator?.cancel()
                host.animator = null
                applyPanelEndState(host, expanded = true)
            } else {
                applyTickerState(host)
            }
        }
        dispatch(snapshot(animate = false))
    }

    fun setLightsOut(active: Boolean) {
        if (lightsOut == active) return
        lightsOut = active
        generation++
        dispatch(snapshot(animate = false))
    }

    private fun applyTickerState(host: HomeHost) {
        when {
            host.panelCommitted && !privacyVisible ->
                host.normalContents.visibility = View.INVISIBLE
            // The ticker controller owns the two-layer push/fade animation. Do not make its
            // outgoing HOME layer non-drawing before that animation has had a chance to run.
            tickerVisible -> Unit
            else -> host.normalContents.visibility = View.VISIBLE
        }
    }

    private fun applyPanelState(
        host: HomeHost,
        expanded: Boolean,
        animate: Boolean,
        expectedPanelGeneration: Long,
    ) {
        host.animator?.cancel()
        host.animator = null
        host.panelCommitted = false

        applyTickerState(host)

        if (!animate || !host.root.isLaidOut || tickerVisible) {
            applyPanelEndState(host, expanded)
            return
        }

        val clockDistance = clockDistance(host)
        val notificationDistance = notificationDistance(host)
        if (clockDistance <= 0f && notificationDistance <= 0f) {
            applyPanelEndState(host, expanded)
            return
        }

        // A committed PANEL keeps HOME hosts INVISIBLE. Seed those stable hidden endpoints once,
        // but preserve live properties when reversing an in-flight animation.
        if (!expanded && host.notificationHost.visibility != View.VISIBLE) {
            host.notificationHost.translationX = -notificationDistance
            host.notificationHost.setTransitionAlpha(0f)
        }
        if (
            !expanded &&
                !privacyVisible &&
                host.clockPrivacyHost.visibility != View.VISIBLE
        ) {
            host.clockPrivacyHost.translationX = -clockDistance
        }

        val keepPrivacyVisible = expanded && privacyVisible
        val startClock = if (keepPrivacyVisible) 0f else host.clockPrivacyHost.translationX
        val endClock = if (keepPrivacyVisible) 0f else if (expanded) -clockDistance else 0f
        val startNotifications = host.notificationHost.translationX
        val endNotifications = if (expanded) -notificationDistance else 0f
        val startNotificationAlpha = host.notificationHost.transitionAlpha
        val endNotificationAlpha = if (expanded) 0f else 1f

        host.clockPrivacyHost.visibility = View.VISIBLE
        host.notificationHost.visibility = View.VISIBLE
        host.clockPrivacyHost.translationX = startClock
        host.notificationHost.translationX = startNotifications
        host.notificationHost.setTransitionAlpha(startNotificationAlpha)

        val clockAnimator =
            ValueAnimator.ofFloat(startClock, endClock).apply {
                duration = PANEL_ANIMATION_DURATION_MS
                interpolator = DecelerateInterpolator(2.5f)
                addUpdateListener {
                    host.clockPrivacyHost.translationX = it.animatedValue as Float
                }
            }
        val notificationTranslation =
            ValueAnimator.ofFloat(startNotifications, endNotifications).apply {
                duration = PANEL_ANIMATION_DURATION_MS
                interpolator = DecelerateInterpolator(2.5f)
                addUpdateListener {
                    host.notificationHost.translationX = it.animatedValue as Float
                }
            }
        val notificationAlpha =
            ValueAnimator.ofFloat(startNotificationAlpha, endNotificationAlpha).apply {
                duration = PANEL_ANIMATION_DURATION_MS
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    // transitionAlpha composes with lights-out's ordinary alpha instead of
                    // overwriting it when PANEL exits concurrently.
                    host.notificationHost.setTransitionAlpha(it.animatedValue as Float)
                }
            }
        val set = AnimatorSet().apply {
            playTogether(clockAnimator, notificationTranslation, notificationAlpha)
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (
                            cancelled ||
                                expectedPanelGeneration != panelAnimationGeneration ||
                                panelExpanded != expanded ||
                                host.animator !== animation
                        ) {
                            return
                        }
                        applyPanelEndState(host, expanded)
                        host.animator = null
                    }
                }
            )
        }
        host.animator = set
        set.start()
    }

    private fun applyPanelEndState(host: HomeHost, expanded: Boolean) {
        host.clockPrivacyHost.animate().cancel()
        host.notificationHost.animate().cancel()
        host.clockPrivacyHost.setTransitionAlpha(1f)
        host.notificationHost.setTransitionAlpha(1f)
        if (expanded) {
            host.panelCommitted = true
            host.clockPrivacyHost.translationX =
                if (privacyVisible) 0f else -clockDistance(host)
            host.notificationHost.translationX = -notificationDistance(host)
            host.notificationHost.setTransitionAlpha(0f)
            host.clockPrivacyHost.visibility =
                if (privacyVisible) View.VISIBLE else View.INVISIBLE
            host.notificationHost.visibility = View.INVISIBLE
            host.endSideHost?.visibility = View.INVISIBLE
        } else {
            host.panelCommitted = false
            host.clockPrivacyHost.translationX = 0f
            host.notificationHost.translationX = 0f
            // Only the stable wrappers are restored. Their Clock/privacy/notification children
            // retain the latest visibility and alpha selected by their Android policy owners.
            host.clockPrivacyHost.visibility = View.VISIBLE
            host.notificationHost.visibility = View.VISIBLE
            // Android policy owns the inner system_icons View. PANEL owns only this stable outer
            // wrapper, so restoring it cannot resurrect a policy-hidden icon row.
            host.endSideHost?.let(::restoreStableHost)
        }
        // PANEL owns a complete QS-positioned icon/battery row. Keep HOME's right side only while
        // cross-fading, then hide the whole HOME contents to avoid a doubled battery animation or
        // brighter overlapping signal icons.
        applyTickerState(host)
    }

    private fun restoreStableHost(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        view.setTransitionAlpha(1f)
        view.visibility = View.VISIBLE
    }

    /** Android owns one phone shade; opening it must not suppress R2 bars on other displays. */
    private fun HomeHost.ownsPhoneShadePresentation(): Boolean =
        root.context.displayId == Display.DEFAULT_DISPLAY

    private fun clockDistance(host: HomeHost): Float =
        (host.clockPrivacyHost.left + host.clockPrivacyHost.width)
            .coerceAtLeast(host.clockPrivacyHost.width)
            .toFloat()

    private fun notificationDistance(host: HomeHost): Float =
        (host.notificationHost.width + host.clockPrivacyHost.width)
            .coerceAtLeast(host.notificationHost.width)
            .toFloat()

    private fun snapshot(animate: Boolean) =
        State(
            panelExpanded = panelExpanded,
            tickerVisible = tickerVisible,
            privacyVisible = privacyVisible,
            lightsOut = lightsOut,
            animate = animate,
            generation = generation,
        )

    private fun dispatch(state: State) {
        callbacks.toList().forEach { it.onStatusBarModeChanged(state) }
    }

    private companion object {
        const val PANEL_ANIMATION_DURATION_MS = 300L
    }
}
