/*
 * Copyright (C) 2026 OpenSmartisanOS
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.biometrics.AuthController
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.keyguard.SosKeyguardRuntime
import com.android.systemui.keyguard.SosKeyguardRuntime.OriginalInteractiveTransitionPhase
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.util.kotlin.DisposableHandles
import com.android.systemui.shade.CameraLauncher
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.ui.SystemIconsController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SosKeyguardHostSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val sharedNotificationContainer: SharedNotificationContainer,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    private val cameraLauncher: CameraLauncher,
    private val flashlightController: FlashlightController,
    private val batteryController: BatteryController,
    private val notificationMediaManager: NotificationMediaManager,
    private val navigationModeController: NavigationModeController,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    private val systemIconsController: SystemIconsController,
    private val screenLifecycle: ScreenLifecycle,
    private val authController: AuthController,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
) : KeyguardSection() {
    private var disposableHandle: DisposableHandle? = null
    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() = updateNormalStatusBarVisibility()
        }
    private val statusBarStateCallback =
        object : StatusBarStateController.StateListener {
            override fun onDozingChanged(isDozing: Boolean) = updateNormalStatusBarVisibility()
        }

    private fun updateNormalStatusBarVisibility() {
        val presented =
            keyguardStateController.isShowing &&
                !keyguardStateController.isOccluded &&
                !statusBarStateController.isDozing
        // Feed both the window policy and the modern PhoneStatusBarView binder from one
        // presentation state.  Keyguard transition enums are not a reliable first-frame signal.
        SosKeyguardRuntime.setAwakeLockscreenPresented(presented)
        systemIconsController.setKeyguardPresented(presented)
        statusBarWindowControllerStore.defaultDisplay.setKeyguardForceStatusBarVisible(presented)
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        keyguardStateController.addCallback(keyguardCallback)
        statusBarStateController.addCallback(statusBarStateCallback)
        updateNormalStatusBarVisibility()
        notificationPanelView.findViewById<View?>(R.id.notification_stack_scroller)?.let {
            (it.parent as ViewGroup).removeView(it)
            sharedNotificationContainer.addNotificationStackScrollLayout(it)
        }
        val hostView =
            SosKeyguardHostView(
                context,
                cameraLauncher,
                flashlightController,
                batteryController,
                notificationMediaManager,
                navigationModeController,
                primaryBouncerInteractor,
                statusBarKeyguardViewManager,
                statusBarStateController,
                keyguardStateController,
                systemIconsController,
                screenLifecycle,
                authController,
                mainExecutor,
                backgroundExecutor,
            )
        // Blueprint applies its ConstraintSet after sections have added their views.  Supplying no
        // LayoutParams here lets ConstraintLayout perform an initial wrap-content pass at (0, 0)
        // during a cold SystemUI start; the imported clock can therefore be drawn in the top-left
        // for one frame before applyConstraints() expands the host.  Install the same four parent
        // anchors at creation time so the very first measure already uses the final viewport.
        constraintLayout.addView(
            hostView,
            ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintSet.PARENT_ID
                bottomToBottom = ConstraintSet.PARENT_ID
                startToStart = ConstraintSet.PARENT_ID
                endToEnd = ConstraintSet.PARENT_ID
            },
        )
        constraintLayout.addView(View(context, null).apply { id = R.id.nssl_placeholder })
        hideAospClockViews(constraintLayout)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        // The R2 host owns the complete lockscreen indication surface. Keep Android's indication
        // area suppressed for the whole lifetime of this Blueprint, including while the shade is
        // expanded; tying it to notification visibility made charging/biometric text flash over
        // the R2 panel.
        setAospIndicationHidden(constraintLayout, true)
        disposableHandle =
            DisposableHandles().apply {
                this +=
                    sharedNotificationContainerBinder.bind(
                        sharedNotificationContainer,
                        sharedNotificationContainerViewModel,
                    )
                this +=
                    sharedNotificationContainer.repeatWhenAttached {
                        repeatOnLifecycle(Lifecycle.State.CREATED) {
                            launch {
                                // The shared container is now the sole R2 notification page. Hide
                                // it only on the undisturbed lockscreen; once the physical shade is
                                // expanded, its rows must remain visible. Credential curtain phases
                                // still suppress it to prevent notification flashes during unlock.
                                combine(
                                        sharedNotificationContainerViewModel
                                            .isOnLockscreenWithoutShade,
                                        SosKeyguardRuntime.originalInteractiveTransitionPhase,
                                    ) { isOnLockscreenWithoutShade, phase ->
                                        isOnLockscreenWithoutShade ||
                                            phase != OriginalInteractiveTransitionPhase.IDLE
                                    }
                                    .distinctUntilChanged()
                                    .collect { hidden ->
                                        setNotificationContainerHidden(hidden)
                                    }
                            }
                        }
                    }
                this +=
                    constraintLayout.repeatWhenAttached {
                        repeatOnLifecycle(Lifecycle.State.CREATED) {
                            launch {
                                keyguardClockViewModel.currentClock.collect {
                                    // Clock plugins can replace their view set after Blueprint has
                                    // been installed. Keep every AOSP clock face suppressed; the
                                    // imported R2 time is the only lockscreen clock.
                                    hideAospClockViews(constraintLayout)
                                }
                            }
                        }
                    }
                this +=
                    DisposableHandle {
                        setNotificationContainerHidden(false)
                        setAospIndicationHidden(constraintLayout, false)
                    }
            }
    }

    private fun setNotificationContainerHidden(hidden: Boolean) {
        sharedNotificationContainer.apply {
            // SharedNotificationContainerBinder owns alpha. Forcing alpha back to 1 when
            // Keyguard reaches GONE exposes a full notification page before the shade window has
            // collapsed. Visibility alone suppresses lockscreen cards while preserving the
            // binder's correct unlocked/shade expansion alpha behind it.
            visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            importantForAccessibility =
                if (hidden) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
        }
    }

    private fun setAospIndicationHidden(constraintLayout: ConstraintLayout, hidden: Boolean) {
        constraintLayout.findViewById<View?>(R.id.keyguard_indication_area)?.apply {
            alpha = if (hidden) 0f else 1f
            visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            importantForAccessibility =
                if (hidden) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
        }
    }

    private fun hideAospClockViews(constraintLayout: ConstraintLayout) {
        val ids =
            buildSet {
                add(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL)
                add(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)
                keyguardClockViewModel.currentClock.value?.let { clock ->
                    clock.smallClock.layout.views.forEach { add(it.id) }
                    clock.largeClock.layout.views.forEach { add(it.id) }
                }
            }
        ids.forEach { id ->
            constraintLayout.findViewById<View?>(id)?.apply {
                alpha = 0f
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val resources = context.resources
        val horizontalMargin =
            resources.getDimensionPixelSize(R.dimen.sos_keyguard_notification_horizontal_margin)
        constraintSet.apply {
            buildSet {
                    add(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL)
                    add(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)
                    keyguardClockViewModel.currentClock.value?.let { clock ->
                        clock.smallClock.layout.views.forEach { add(it.id) }
                        clock.largeClock.layout.views.forEach { add(it.id) }
                    }
                }
                .forEach { id ->
                    setVisibility(id, View.GONE)
                    setAlpha(id, 0f)
                }
            constrainWidth(R.id.sos_keyguard_host_view, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.sos_keyguard_host_view, ConstraintSet.MATCH_CONSTRAINT)
            connect(
                R.id.sos_keyguard_host_view,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
            )
            connect(
                R.id.sos_keyguard_host_view,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
            )
            connect(
                R.id.sos_keyguard_host_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            connect(
                R.id.sos_keyguard_host_view,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )

            constrainWidth(R.id.nssl_placeholder, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.nssl_placeholder, ConstraintSet.MATCH_CONSTRAINT)
            connect(
                R.id.nssl_placeholder,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                resources.getDimensionPixelSize(R.dimen.sos_keyguard_notification_top_margin),
            )
            connect(
                R.id.nssl_placeholder,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
                resources.getDimensionPixelSize(R.dimen.sos_keyguard_notification_bottom_margin),
            )
            connect(
                R.id.nssl_placeholder,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                horizontalMargin,
            )
            connect(
                R.id.nssl_placeholder,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                horizontalMargin,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        keyguardStateController.removeCallback(keyguardCallback)
        statusBarStateController.removeCallback(statusBarStateCallback)
        statusBarWindowControllerStore.defaultDisplay.setKeyguardForceStatusBarVisible(false)
        SosKeyguardRuntime.setAwakeLockscreenPresented(false)
        systemIconsController.setKeyguardPresented(false)
        disposableHandle?.dispose()
        disposableHandle = null
        constraintLayout.removeView(R.id.nssl_placeholder)
        constraintLayout.removeView(R.id.sos_keyguard_host_view)
    }
}
