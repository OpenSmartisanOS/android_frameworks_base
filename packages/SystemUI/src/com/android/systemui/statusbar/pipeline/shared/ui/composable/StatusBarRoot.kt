/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.theme.PlatformTheme
import com.android.systemui.compose.modifiers.sysUiResTagContainer
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarAlwaysUseRegionSampling
import com.android.systemui.statusbar.events.domain.interactor.SystemStatusEventAnimationInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.StatusBarModeCoordinator
import com.android.systemui.statusbar.phone.LeftCarrierNotificationController
import com.android.systemui.statusbar.phone.ui.DarkIconManager
import com.android.systemui.statusbar.phone.ui.SystemIconsController
import com.android.systemui.statusbar.phone.ui.SystemIconsController.HostAppearance
import com.android.systemui.statusbar.phone.ui.PrivacyHighlightController
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarTouchExclusionRegionBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel.HomeStatusBarViewModelFactory
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.PrivacyHighlightView
import com.android.systemui.statusbar.ui.viewmodel.StatusBarRegionSamplingViewModel
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation

/** Factory to simplify the dependency management for [StatusBarRoot] */
@PerDisplaySingleton
class StatusBarRootFactory
@Inject
constructor(
    private val notificationIconsBinder: NotificationIconContainerStatusBarViewBinder,
    private val darkIconManagerFactory: DarkIconManager.Factory,
    private val systemIconsController: SystemIconsController,
    private val privacyHighlightController: PrivacyHighlightController,
    private val statusBarModeCoordinator: StatusBarModeCoordinator,
    private val eventAnimationInteractor: SystemStatusEventAnimationInteractor,
    @DisplayAware private val darkIconDispatcher: DarkIconDispatcher,
    @DisplayAware private val homeStatusBarViewBinder: HomeStatusBarViewBinder,
    @DisplayAware private val homeStatusBarViewModelFactory: HomeStatusBarViewModelFactory,
    private val statusBarRegionSamplingViewModelFactory: StatusBarRegionSamplingViewModel.Factory,
) {
    fun create(root: ViewGroup, andThen: (ViewGroup) -> Unit): ComposeView {
        val composeView = ComposeView(root.context)
        composeView.apply {
            setContent {
                PlatformTheme {
                    StatusBarRoot(
                        parent = root,
                        statusBarViewModelFactory = homeStatusBarViewModelFactory,
                        statusBarViewBinder = homeStatusBarViewBinder,
                        notificationIconsBinder = notificationIconsBinder,
                        darkIconManagerFactory = darkIconManagerFactory,
                        systemIconsController = systemIconsController,
                        privacyHighlightController = privacyHighlightController,
                        statusBarModeCoordinator = statusBarModeCoordinator,
                        darkIconDispatcher = darkIconDispatcher,
                        eventAnimationInteractor = eventAnimationInteractor,
                        statusBarRegionSamplingViewModelFactory =
                            statusBarRegionSamplingViewModelFactory,
                        onViewCreated = andThen,
                        modifier = Modifier.sysUiResTagContainer(),
                    )
                }
            }
        }

        return composeView
    }
}

/**
 * Creates and binds the canonical status bar for a display.
 *
 * @param onViewCreated called immediately after [PhoneStatusBarView] is inflated so the per-display
 *   component can attach its platform state and window controllers.
 */
@Composable
fun StatusBarRoot(
    parent: ViewGroup,
    statusBarViewModelFactory: HomeStatusBarViewModelFactory,
    statusBarViewBinder: HomeStatusBarViewBinder,
    notificationIconsBinder: NotificationIconContainerStatusBarViewBinder,
    darkIconManagerFactory: DarkIconManager.Factory,
    systemIconsController: SystemIconsController,
    privacyHighlightController: PrivacyHighlightController,
    statusBarModeCoordinator: StatusBarModeCoordinator,
    darkIconDispatcher: DarkIconDispatcher,
    eventAnimationInteractor: SystemStatusEventAnimationInteractor,
    statusBarRegionSamplingViewModelFactory: StatusBarRegionSamplingViewModel.Factory,
    onViewCreated: (ViewGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayId = parent.context.displayId
    val statusBarViewModel =
        rememberViewModel("HomeStatusBar") { statusBarViewModelFactory.create() }
    val appHandlesViewModel =
        rememberViewModel("AppHandleBounds") {
            statusBarViewModel.appHandlesViewModelFactory.create(displayId)
        }
    var touchableExclusionRegionDisposableHandle: DisposableHandle? = null
    var homeIconManager by remember { mutableStateOf<DarkIconManager?>(null) }
    var privacyHighlightView by remember { mutableStateOf<PrivacyHighlightView?>(null) }
    var modeHostView by remember { mutableStateOf<View?>(null) }
    var leftCarrierController by remember {
        mutableStateOf<LeftCarrierNotificationController?>(null)
    }
    var homeThemeListener by remember {
        mutableStateOf<SystemIconsController.HomeKeyguardThemeListener?>(null)
    }

    Box { // TODO(b/433578931): Remove this Box once the full solution for b/433578931 is settled.
        AndroidView(
            factory = { context ->
                val inflater = LayoutInflater.from(context)
                val phoneStatusBarView =
                    inflater.inflate(
                        R.layout.status_bar,
                        parent,
                        false,
                    ) as PhoneStatusBarView
                statusBarModeCoordinator.registerHomeHost(phoneStatusBarView)
                modeHostView = phoneStatusBarView
                privacyHighlightView =
                    phoneStatusBarView.requireViewById(R.id.privacy_highlight)
                privacyHighlightController.registerHost(privacyHighlightView!!)

                touchableExclusionRegionDisposableHandle =
                    HomeStatusBarTouchExclusionRegionBinder.bind(
                        phoneStatusBarView,
                        appHandlesViewModel,
                    )

                // For notifications, first inflate the [NotificationIconContainer]
                val notificationIconArea =
                    phoneStatusBarView.requireViewById<ViewGroup>(R.id.notification_icon_area)
                inflater.inflate(
                    R.layout.notification_icon_area,
                    notificationIconArea,
                    true,
                )
                // Then bind it using the icons binder
                val notificationIconContainer =
                    phoneStatusBarView.requireViewById<NotificationIconContainer>(
                        R.id.notificationIcons
                    )
                leftCarrierController =
                    phoneStatusBarView
                        .findViewById<ViewGroup>(R.id.status_bar_contents_left)
                        ?.let { LeftCarrierNotificationController(it, allowCarrier = true) }

                phoneStatusBarView.requireViewById<View>(R.id.system_icons).visibility = View.VISIBLE
                phoneStatusBarView.requireViewById<View>(R.id.battery).visibility = View.VISIBLE
                val statusIconContainer =
                    phoneStatusBarView.requireViewById<StatusIconContainer>(R.id.statusIcons)
                val darkIconManager =
                    darkIconManagerFactory.create(
                        statusIconContainer,
                        StatusBarLocation.HOME,
                        darkIconDispatcher,
                    )
                homeIconManager = darkIconManager
                systemIconsController.registerHost(darkIconManager, HostAppearance.HOME)
                val clock = phoneStatusBarView.findViewById<Clock>(R.id.clock)
                if (displayId == Display.DEFAULT_DISPLAY) {
                    homeThemeListener =
                        SystemIconsController.HomeKeyguardThemeListener {
                                active,
                                supportsDarkText,
                            ->
                            val iconTint =
                                if (active) {
                                    SystemIconsController.iconTintForWallpaper(supportsDarkText)
                                } else {
                                    null
                                }
                            val foregroundTint =
                                if (active) {
                                    SystemIconsController.foregroundTintForWallpaper(supportsDarkText)
                                } else {
                                    null
                                }
                            darkIconManager.setKeyguardTintOverride(iconTint, foregroundTint)
                            clock.setKeyguardColorOverride(iconTint)
                            leftCarrierController?.setKeyguardPresented(active)
                        }
                    systemIconsController.addHomeKeyguardThemeListener(homeThemeListener!!)
                }
                HomeStatusBarIconBlockListBinder.bind(
                    statusIconContainer,
                    darkIconManager,
                    statusBarViewModel.iconBlockList,
                )

                notificationIconsBinder.bindWhileAttached(
                    notificationIconContainer,
                    context.displayId,
                )

                if (StatusBarAlwaysUseRegionSampling.isAnyRegionSamplingEnabled) {
                    bindRegionSamplingViewModel(
                        context.displayId,
                        phoneStatusBarView,
                        statusBarRegionSamplingViewModelFactory,
                    )
                }

                // This binder handles everything else
                statusBarViewBinder.bind(
                    context.displayId,
                    phoneStatusBarView,
                    statusBarViewModel,
                    systemEventChipAnimateIn = { view ->
                        if (
                            !privacyHighlightController.isPlatformPrivacyAnimationSuppressed(
                                context.displayId
                            )
                        ) {
                            eventAnimationInteractor.animateStatusBarContentForChipEnter(view)
                        }
                    },
                    systemEventChipAnimateOut = { view ->
                        if (
                            !privacyHighlightController.isPlatformPrivacyAnimationSuppressed(
                                context.displayId
                            )
                        ) {
                            eventAnimationInteractor.animateStatusBarContentForChipExit(view)
                        }
                    },
                    leftCarrierNotificationController = leftCarrierController,
                )
                onViewCreated(phoneStatusBarView)
                phoneStatusBarView
            },
            modifier = modifier,
            onRelease = {
                touchableExclusionRegionDisposableHandle?.dispose()
                homeIconManager?.let { manager ->
                    systemIconsController.unregisterHost(manager)
                }
                homeIconManager = null
                homeThemeListener?.let(systemIconsController::removeHomeKeyguardThemeListener)
                homeThemeListener = null
                leftCarrierController?.destroy()
                leftCarrierController = null
                privacyHighlightView?.let(privacyHighlightController::unregisterHost)
                privacyHighlightView = null
                // This host may be released while PANEL or ticker is still active. Remove it
                // before a later generation attempts to restore stale Views.
                modeHostView?.let(statusBarModeCoordinator::unregisterHomeHost)
                modeHostView = null
            },
        )
    }
}

private fun bindRegionSamplingViewModel(
    displayId: Int,
    phoneStatusBarView: PhoneStatusBarView,
    statusBarRegionSamplingViewModelFactory: StatusBarRegionSamplingViewModel.Factory,
) {
    phoneStatusBarView.repeatWhenAttached {
        phoneStatusBarView.viewModel(
            traceName = "StatusBarRegionSamplingViewModel",
            minWindowLifecycleState = WindowLifecycleState.ATTACHED,
            factory = {
                statusBarRegionSamplingViewModelFactory.create(
                    displayId = displayId,
                    attachStateView = phoneStatusBarView,
                    startSideContainerView =
                        phoneStatusBarView.findViewById(R.id.status_bar_contents_left)
                            ?: phoneStatusBarView.requireViewById(R.id.status_bar_contents_left),
                    startSideIconView = phoneStatusBarView.requireViewById(R.id.clock),
                    endSideContainerView =
                        phoneStatusBarView.findViewById(R.id.status_bar_end_side_content)
                            ?: phoneStatusBarView.requireViewById(
                                R.id.status_bar_end_side_content
                            ),
                    endSideIconView = phoneStatusBarView.requireViewById(R.id.system_icons),
                )
            },
        ) {
            awaitCancellation()
        }
    }
}
