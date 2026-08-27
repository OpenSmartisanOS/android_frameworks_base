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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.app.animation.Interpolators
import com.android.systemui.res.R
import com.android.systemui.statusbar.widget.NotificationCountView

/**
 * Carrier label and notification icons are mutually exclusive. Updates are deferred while a panel
 * animation is pending.
 *
 * @param allowCarrier whether this R2 host owns an operator slot.
 */
class LeftCarrierNotificationController
@JvmOverloads
constructor(
    private val left: ViewGroup,
    private val allowCarrier: Boolean = true,
) {
    private val networkLabel: TextView? = left.findViewById(R.id.network_label)
    private val operatorName: View? = left.findViewById(R.id.operator_name)
    private val carrierView: View? = operatorName ?: networkLabel
    private val sidebar: View? = left.findViewById(R.id.sidebar_drag)
    private val notificationArea: View? = left.findViewById(R.id.notification_icon_area)
    private val notificationCount: NotificationCountView? =
        notificationArea?.findViewById(R.id.notification_count)
    private val notificationIcons: LeftIconMerger? =
        notificationArea?.findViewById(R.id.notificationIcons)
    private val layoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
    private val textWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = refresh()

            override fun afterTextChanged(s: Editable?) = Unit
        }
    var animationPending = false
        set(value) {
            field = value
            if (!value) refresh()
        }
    private var keyguardPresented = false
    private var platformNotificationsAllowed = true
    private var platformHiddenVisibility = View.GONE
    private var policyAnimationRunning = false
    private var policyAnimationGeneration = 0L

    init {
        left.addOnLayoutChangeListener(layoutListener)
        notificationArea?.addOnLayoutChangeListener(layoutListener)
        notificationCount?.addOnLayoutChangeListener(layoutListener)
        notificationCount?.setCountChangeListener { refresh() }
        notificationIcons?.setLogicalVisibilityListener(::refresh)
        (carrierView as? TextView)?.addTextChangedListener(textWatcher)
        refresh()
    }

    fun destroy() {
        policyAnimationGeneration++
        policyAnimationRunning = false
        notificationArea?.animate()?.setListener(null)
        notificationArea?.animate()?.cancel()
        left.removeOnLayoutChangeListener(layoutListener)
        notificationArea?.removeOnLayoutChangeListener(layoutListener)
        notificationCount?.removeOnLayoutChangeListener(layoutListener)
        notificationCount?.setCountChangeListener(null)
        notificationIcons?.setLogicalVisibilityListener(null)
        (carrierView as? TextView)?.removeTextChangedListener(textWatcher)
    }

    fun refresh() = refresh(animateNotifications = false, force = false)

    fun setKeyguardPresented(presented: Boolean) {
        if (keyguardPresented == presented) return
        keyguardPresented = presented
        refresh()
        left.post { refresh() }
    }

    /**
     * Merges Android's disable/HUN/fullscreen policy with the factory carrier/notification
     * exclusivity rule. This controller owns the final outer presentation and its animation so the
     * R2 count glyph and icon row cannot diverge or overwrite each other's visibility.
     */
    fun setPlatformNotificationsAllowed(
        allowed: Boolean,
        animate: Boolean = false,
        hiddenVisibility: Int = View.GONE,
    ) {
        if (
            platformNotificationsAllowed == allowed &&
                platformHiddenVisibility == hiddenVisibility
        ) {
            return
        }
        platformNotificationsAllowed = allowed
        platformHiddenVisibility = hiddenVisibility
        refresh(animateNotifications = animate, force = true)
    }

    private fun hasNotifications(): Boolean {
        if (!platformNotificationsAllowed) return false
        val count =
            notificationCount
                ?: (notificationArea as? ViewGroup)?.findViewById(R.id.notification_count)
        if (count?.count?.let { it > 0 } == true) return true
        val icons =
            notificationIcons
                ?: (notificationArea as? ViewGroup)?.findViewById(R.id.notificationIcons)
        if (icons?.hasLogicallyVisibleIcons() == true) return true
        return false
    }

    private fun hasCarrierText(): Boolean {
        return (carrierView as? TextView)?.text?.isNotBlank() == true
    }

    private fun isSidebarVisible(): Boolean = sidebar?.visibility == View.VISIBLE

    private fun setCarrierVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        carrierView?.visibility = vis
        if (carrierView !== networkLabel) networkLabel?.visibility = View.GONE
        if (carrierView !== operatorName) operatorName?.visibility = View.GONE
        (operatorName?.parent as? View)?.let { parent ->
            if (parent.id == R.id.operator_name_frame) {
                parent.visibility = vis
            }
        }
    }

    private fun setNotificationsVisible(visible: Boolean, animate: Boolean = false) {
        // Keep the area measurable while the carrier owns the pixels. New icons can then trigger
        // the layout listener immediately instead of being trapped under a GONE parent.
        val target =
            when {
                visible && platformNotificationsAllowed -> View.VISIBLE
                !platformNotificationsAllowed -> platformHiddenVisibility
                else -> View.INVISIBLE
            }
        val area = notificationArea ?: return
        val generation = ++policyAnimationGeneration
        area.animate().setListener(null)
        area.animate().cancel()
        policyAnimationRunning = false
        if (!animate) {
            area.alpha = if (target == View.VISIBLE) 1f else 0f
            area.visibility = target
            return
        }
        if (target == View.VISIBLE) {
            policyAnimationRunning = true
            area.visibility = View.VISIBLE
            area.alpha = 0f
            area
                .animate()
                .alpha(1f)
                .setDuration(STATUS_BAR_FADE_IN_DURATION_MS)
                .setStartDelay(STATUS_BAR_FADE_IN_DELAY_MS)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (generation != policyAnimationGeneration) return
                            policyAnimationRunning = false
                            area.alpha = 1f
                            area.animate().setListener(null)
                            refresh(animateNotifications = false, force = true)
                        }
                    }
                )
                .start()
        } else {
            if (area.visibility != View.VISIBLE) {
                area.alpha = 0f
                area.visibility = target
                return
            }
            policyAnimationRunning = true
            area
                .animate()
                .alpha(0f)
                .setDuration(STATUS_BAR_FADE_OUT_DURATION_MS)
                .setStartDelay(0L)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (generation != policyAnimationGeneration) return
                            policyAnimationRunning = false
                            area.visibility = target
                            area.animate().setListener(null)
                            refresh(animateNotifications = false, force = true)
                        }
                    }
                )
                .start()
        }
    }

    private fun refresh(animateNotifications: Boolean, force: Boolean) {
        if (policyAnimationRunning && !force) return
        if (animationPending) return
        if (!allowCarrier) {
            setCarrierVisible(false)
            setNotificationsVisible(true, animateNotifications)
            return
        }
        val hasNotifications = hasNotifications()
        val hasCarrier = hasCarrierText()
        if (hasNotifications) {
            setNotificationsVisible(true, animateNotifications)
            setCarrierVisible(false)
        } else {
            setNotificationsVisible(false, animateNotifications)
            setCarrierVisible(hasCarrier && !keyguardPresented && !isSidebarVisible())
        }
    }

    private companion object {
        const val STATUS_BAR_FADE_IN_DURATION_MS = 320L
        const val STATUS_BAR_FADE_OUT_DURATION_MS = 160L
        const val STATUS_BAR_FADE_IN_DELAY_MS = 50L
    }
}
