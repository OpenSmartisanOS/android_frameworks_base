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

package com.android.systemui.shade

import android.content.ContentResolver
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.android.compose.theme.PlatformTheme
import com.android.systemui.compose.modifiers.sysUiResTagContainer
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.core.RudimentaryBattery
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.layout.StatusBarContentInsetsChangedListener
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithChargeStatus
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithPercent
import com.android.systemui.statusbar.pipeline.battery.ui.composable.ShowPercentMode
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryNextToPercentViewModel
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.view.SystemStatusIconsLayoutHelper
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tuner.TunerService
import javax.inject.Inject

@SysUISingleton
class SosPanelStatusBarController
@Inject
constructor(
    private val statusBarIconController: StatusBarIconController,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val userTracker: UserTracker,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val tunerService: TunerService,
    @Main private val mainHandler: Handler,
    private val contentResolver: ContentResolver,
    private val featureFlags: FeatureFlags,
    private val batteryController: BatteryController,
    private val unifiedBatteryViewModelFactory: BatteryViewModel.BasedOnUserSetting.Factory,
    private val tandemBatteryViewModelFactory: BatteryNextToPercentViewModel.Factory,
    private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
) {
    private var root: View? = null
    private var panelStatusBar: View? = null
    private var panelStatusBarContent: View? = null
    private var displayCutoutSpace: View? = null
    private var statusBarContentInsetsProvider: StatusBarContentInsetsProvider? = null
    private var statusIconContainer: StatusIconContainer? = null
    private var batteryView: BatteryMeterView? = null
    private var batteryComposeView: ComposeView? = null
    private var iconManager: TintedIconManager? = null
    private var batteryMeterViewController: BatteryMeterViewController? = null
    private var iconGroupRegistered = false
    private var visible = false
    private var topInset = 0
    private val contentInsetsCallback =
        object : StatusBarContentInsetsChangedListener {
            override fun onStatusBarContentInsetsChanged() {
                applyContentInsets()
            }
        }

    fun attach(rootView: View) {
        if (!rootView.resources.getBoolean(R.bool.config_sos_legacy_shade)) {
            return
        }
        if (root === rootView) {
            applyVisibleState()
            return
        }
        detach()

        root = rootView
        val panel = rootView.requireViewById<View>(R.id.sos_panel_status_bar)
        panelStatusBar = panel
        panelStatusBarContent = panel.requireViewById(R.id.sos_panel_status_bar_content)
        displayCutoutSpace = panel.requireViewById(R.id.sos_panel_display_cutout_space)
        val iconContainer =
            panel.requireViewById<StatusIconContainer>(R.id.statusIcons)
        val battery = panel.requireViewById<BatteryMeterView>(R.id.battery)
        statusIconContainer = iconContainer
        batteryView = battery
        statusBarContentInsetsProvider =
            statusBarContentInsetsProviderStore.forDisplay(
                rootView.display?.displayId ?: rootView.context.displayId
            )
        statusBarContentInsetsProvider?.addCallback(contentInsetsCallback)

        iconContainer.setShouldRestrictIcons(false)
        iconManager =
            tintedIconManagerFactory.create(iconContainer, StatusBarLocation.HOME).apply {
                setTint(SOS_STATUS_BAR_TINT, Color.BLACK)
            }

        if (NewStatusBarIcons.isEnabled) {
            SystemStatusIconsLayoutHelper.configurePaddingForNewStatusBarIcons(
                panel.requireViewById<LinearLayout>(R.id.system_icons)
            )
            battery.visibility = View.GONE
            batteryComposeView = createBatteryComposeView(panel).also { composeView ->
                panel.requireViewById<ViewGroup>(R.id.system_icons).addView(composeView, 1)
            }
        } else {
            batteryMeterViewController =
                BatteryMeterViewController(
                        battery,
                        StatusBarLocation.HOME,
                        userTracker,
                        configurationController,
                        tunerService,
                        mainHandler,
                        contentResolver,
                        featureFlags,
                        batteryController,
                    )
                    .apply {
                        init()
                        ignoreTunerUpdates()
                    }
            batteryView?.apply {
                visibility = View.VISIBLE
                updateColors(SOS_STATUS_BAR_TINT, Color.BLACK, SOS_STATUS_BAR_TINT)
            }
        }

        applyTopInset()
        applyContentInsets()
        applyVisibleState()
    }

    fun detach() {
        setIconGroupRegistered(false)
        statusBarContentInsetsProvider?.removeCallback(contentInsetsCallback)
        statusBarContentInsetsProvider = null
        batteryComposeView?.let { composeView ->
            (composeView.parent as? ViewGroup)?.removeView(composeView)
        }
        batteryComposeView = null
        batteryMeterViewController?.destroy()
        batteryMeterViewController = null
        iconManager = null
        batteryView = null
        statusIconContainer = null
        displayCutoutSpace = null
        panelStatusBarContent = null
        panelStatusBar = null
        root = null
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        applyVisibleState()
    }

    fun setTopInset(inset: Int) {
        topInset = inset
        applyTopInset()
        applyContentInsets()
    }

    private fun applyVisibleState() {
        panelStatusBar?.visibility = if (visible) View.VISIBLE else View.GONE
        setIconGroupRegistered(visible)
    }

    private fun applyTopInset() {
        val roundRadius =
            root?.resources?.getDimensionPixelSize(R.dimen.sos_status_bar_round_radius) ?: return
        panelStatusBar?.layoutParams =
            panelStatusBar?.layoutParams?.apply { height = topInset + roundRadius }

        panelStatusBarContent?.layoutParams =
            panelStatusBarContent?.layoutParams?.apply { height = topInset }
    }

    private fun applyContentInsets() {
        val resources = root?.resources ?: return
        val provider = statusBarContentInsetsProvider
        val insets =
            provider?.getStatusBarContentInsetsForCurrentRotation()
                ?: Insets.of(
                    resources.getDimensionPixelSize(R.dimen.status_bar_padding_start),
                    resources.getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    resources.getDimensionPixelSize(R.dimen.status_bar_padding_end),
                    0,
                )
        val paddingTop = provider?.getStatusBarPaddingTop() ?: insets.top
        panelStatusBarContent?.setPaddingRelative(insets.left, paddingTop, insets.right, 0)
        updateCutoutSpace(provider)
    }

    private fun updateCutoutSpace(provider: StatusBarContentInsetsProvider?) {
        val cutout = root?.rootWindowInsets?.displayCutout
        val cutoutWidth =
            if (cutout == null ||
                cutout.isEmpty ||
                provider?.currentRotationHasCornerCutout() == true
            ) {
                0
            } else {
                cutout.boundingRectTop.width()
            }
        displayCutoutSpace?.layoutParams =
            displayCutoutSpace?.layoutParams?.apply { width = cutoutWidth }
    }

    private fun setIconGroupRegistered(registered: Boolean) {
        if (iconGroupRegistered == registered) {
            return
        }
        val manager = iconManager ?: return
        if (registered) {
            statusBarIconController.addIconGroup(manager)
        } else {
            statusBarIconController.removeIconGroup(manager)
        }
        iconGroupRegistered = registered
    }

    companion object {
        private const val SOS_STATUS_BAR_TINT = 0xB3FFFFFF.toInt()
        private val SOS_PANEL_IS_DARK = IsAreaDark { _: Rect -> true }
    }

    private fun createBatteryComposeView(panel: View): ComposeView {
        return ComposeView(panel.context).apply {
            setContent {
                PlatformTheme {
                    if (RudimentaryBattery.isEnabled) {
                        BatteryWithChargeStatus(
                            viewModelFactory = tandemBatteryViewModelFactory,
                            isDarkProvider = { SOS_PANEL_IS_DARK },
                            showPercentMode = ShowPercentMode.FollowSetting,
                            modifier = Modifier.sysUiResTagContainer().wrapContentSize(),
                        )
                    } else {
                        val viewModel =
                            rememberViewModel(traceName = "SosPanelUnifiedBattery") {
                                unifiedBatteryViewModelFactory.create()
                            }
                        BatteryWithPercent(
                            modifier = Modifier.sysUiResTagContainer().wrapContentWidth(),
                            viewModel = viewModel,
                            isDarkProvider = { SOS_PANEL_IS_DARK },
                            showPercent = viewModel.isBatteryPercentSettingEnabled,
                        )
                    }
                }
            }
        }
    }
}
