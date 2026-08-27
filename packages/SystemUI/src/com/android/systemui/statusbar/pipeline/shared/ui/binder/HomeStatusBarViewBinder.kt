/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingIn
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingOut
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.RunningChipAnim
import com.android.systemui.statusbar.phone.LeftCarrierNotificationController
import com.android.systemui.statusbar.phone.StatusBarTickerController
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.statusbar.policy.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STATUS_BAR_FADE_IN_DURATION = 320
private const val STATUS_BAR_FADE_OUT_DURATION = 160
private const val STATUS_BAR_FADE_IN_DELAY = 50

/** Binds the canonical R2 status-bar view to [HomeStatusBarViewModel]. */
interface HomeStatusBarViewBinder {
    /**
     * The optional system-event animations coordinate the canonical system icon area with
     * Android's privacy event lifecycle without constructing a second status-bar view.
     */
    fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        leftCarrierNotificationController: LeftCarrierNotificationController?,
    )
}

@PerDisplaySingleton
class HomeStatusBarViewBinderImpl
@Inject
constructor(
    private val statusBarTickerController: StatusBarTickerController,
) : HomeStatusBarViewBinder {
    private data class ClockState(
        val hideForHun: Boolean,
        val visibilityModel: VisibilityModel,
    )

    override fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        leftCarrierNotificationController: LeftCarrierNotificationController?,
    ) {
        // Set some top-level views to gone before we get started
        // Android policy owns the inner icon row. The R2 PANEL coordinator independently owns the
        // status_bar_end_side_content wrapper, so the two state machines never overwrite the same
        // View's visibility or animation properties.
        val systemInfoView = view.requireViewById<View>(R.id.system_icons)
        val leftClock: Clock = view.requireViewById(R.id.clock)
        val notificationIconsArea: View = view.requireViewById(R.id.notificationIcons)

        // The canonical R2 host has one clock and one right-side icon region. There are no
        // alternate clock or network-traffic compatibility views to initialize.

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val clockState =
                    MutableStateFlow(
                        ClockState(
                            hideForHun = false,
                            visibilityModel = VisibilityModel(View.GONE, true),
                        )
                    )

                val lightsOutView: View = view.requireViewById(R.id.notification_lights_out)
                launch {
                    viewModel.areNotificationsLightsOut.collect { show ->
                        statusBarTickerController.setLightsOut(show)
                        animateLightsOutView(lightsOutView, show)
                    }
                }

                if (com.android.media.projection.flags.Flags.showStopDialogPostCallEnd()) {
                    launch {
                        viewModel.mediaProjectionStopDialogDueToCallEndedState.collect { stopDialog
                            ->
                            if (stopDialog is MediaProjectionStopDialogModel.Shown) {
                                stopDialog.createAndShowDialog()
                            }
                        }
                    }
                }

                view.findViewById<View>(R.id.operator_name_frame)?.let { operatorNameView ->
                    operatorNameView.isVisible = false
                    StatusBarOperatorNameViewBinder.bind(
                        operatorNameView,
                        viewModel.operatorNameViewModel,
                        viewModel.areaTint,
                    )
                    launch {
                        viewModel.shouldShowOperatorNameView.collect {
                            operatorNameView.isVisible = it
                        }
                    }
                }

                launch {
                    combine(
                            viewModel.isClockVisible,
                            viewModel.hideStartSideContentForHeadsUp,
                        ) { visibilityModel, hideForHun ->
                            visibilityModel to hideForHun
                        }
                        .collect { (visibilityModel, hideForHun) ->
                            clockState.update { current ->
                                current.copy(
                                    visibilityModel = visibilityModel,
                                    hideForHun = hideForHun,
                                )
                            }
                        }
                }

                launch {
                    clockState.collect { state ->
                        val finalVisibility =
                            if (
                                state.visibilityModel.visibility == View.VISIBLE &&
                                    !state.hideForHun
                            ) {
                                state.visibilityModel
                            } else {
                                state.visibilityModel.copy(visibility = View.GONE)
                            }
                        leftClock.adjustVisibility(finalVisibility)
                    }
                }

                launch {
                    viewModel.isNotificationIconContainerVisible.collect { visibility ->
                        if (leftCarrierNotificationController != null) {
                            leftCarrierNotificationController.setPlatformNotificationsAllowed(
                                allowed = visibility.visibility == View.VISIBLE,
                                animate = visibility.shouldAnimateChange,
                                hiddenVisibility = visibility.visibility,
                            )
                        } else {
                            notificationIconsArea.adjustVisibility(visibility)
                        }
                    }
                }

                launch {
                    viewModel.systemInfoCombinedVis.collect { (baseVis, animState) ->
                        if (animState.isAnimatingChip()) {
                            systemInfoView.visibility = baseVis.visibility
                            when (animState) {
                                AnimatingIn -> systemEventChipAnimateIn?.invoke(systemInfoView)
                                AnimatingOut -> systemEventChipAnimateOut?.invoke(systemInfoView)
                                else -> {
                                    // Running state keeps the current event transform.
                                }
                            }
                        } else {
                            systemInfoView.adjustVisibility(baseVis)
                        }
                    }
                }
            }
        }
    }

    private fun SystemEventAnimationState.isAnimatingChip() =
        when (this) {
            AnimatingIn,
            AnimatingOut,
            RunningChipAnim -> true
            else -> false
        }

    private fun animateLightsOutView(view: View, visible: Boolean) {
        view.animate().cancel()

        val alpha = if (visible) 1f else 0f
        val duration = if (visible) 750L else 250L
        val visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }

        view
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = alpha
                        view.visibility = visibility
                        // Unset the listener, otherwise this may persist for
                        // another view property animation
                        view.animate().setListener(null)
                    }
                }
            )
            .start()
    }

    private fun View.adjustVisibility(model: VisibilityModel) {
        if (model.visibility == View.VISIBLE) {
            this.show(model.shouldAnimateChange)
        } else {
            this.hide(model.visibility, model.shouldAnimateChange)
        }
    }

    /**
     * Hide the view for initialization, but skip if it's already hidden and does not cancel
     * animations.
     */
    private fun View.hideInitially(state: Int = View.INVISIBLE) {
        if (visibility == View.INVISIBLE || visibility == View.GONE) {
            return
        }
        alpha = 0f
        visibility = state
    }

    // Canonical status-bar hide animation.
    private fun View.hide(state: Int = View.INVISIBLE, shouldAnimateChange: Boolean) {
        animate().cancel()

        if (
            (visibility == View.INVISIBLE && state == View.INVISIBLE) ||
                (visibility == View.GONE && state == View.GONE)
        ) {
            return
        }
        val isAlreadyHidden = visibility == View.INVISIBLE || visibility == View.GONE
        if (!shouldAnimateChange || isAlreadyHidden) {
            alpha = 0f
            visibility = state
            return
        }

        animate()
            .alpha(0f)
            .setDuration(STATUS_BAR_FADE_OUT_DURATION.toLong())
            .setStartDelay(0)
            .setInterpolator(Interpolators.ALPHA_OUT)
            .withEndAction { visibility = state }
    }

    // Canonical status-bar show animation.
    private fun View.show(shouldAnimateChange: Boolean) {
        animate().cancel()
        if (visibility == View.VISIBLE && alpha >= 1f) {
            return
        }
        visibility = View.VISIBLE
        if (!shouldAnimateChange) {
            alpha = 1f
            return
        }
        animate()
            .alpha(1f)
            .setDuration(STATUS_BAR_FADE_IN_DURATION.toLong())
            .setInterpolator(Interpolators.ALPHA_IN)
            .setStartDelay(STATUS_BAR_FADE_IN_DELAY.toLong())
            // We need to clean up any pending end action from animateHide if we call both hide and
            // show in the same frame before the animation actually gets started.
            // cancel() doesn't really remove the end action.
            .withEndAction(null)

        // TODO(b/364360986): Synchronize the motion with the Keyguard fading if necessary.
    }
}
