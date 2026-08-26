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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ContentResolver
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.os.Handler
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.battery.SosBatteryStateController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.layout.StatusBarContentInsetsChangedListener
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.SosLeftCarrierNotificationController
import com.android.systemui.statusbar.phone.SosStatusBarAccessoryController
import com.android.systemui.statusbar.phone.SosStatusBarAppearanceController
import com.android.systemui.statusbar.phone.SosStatusBarCutoutClassifier
import com.android.systemui.statusbar.phone.SosStatusBarCutoutLayout
import com.android.systemui.statusbar.phone.SosStatusBarCutoutMode
import com.android.systemui.statusbar.phone.SosStatusBarGeometry
import com.android.systemui.statusbar.phone.SosStatusBarModeCoordinator
import com.android.systemui.statusbar.phone.SosStatusBarTickerController
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.ui.SosSystemIconsController
import com.android.systemui.statusbar.phone.ui.SosSystemIconsController.HostAppearance
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tuner.TunerService
import javax.inject.Inject

@SysUISingleton
class ShadeHeaderController
@Inject
constructor(
    private val sosSystemIconsController: SosSystemIconsController,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val userTracker: UserTracker,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val tunerService: TunerService,
    @Main private val mainHandler: Handler,
    private val contentResolver: ContentResolver,
    private val featureFlags: FeatureFlags,
    private val batteryController: BatteryController,
    private val sosBatteryStateController: SosBatteryStateController,
    private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
    private val appearanceController: SosStatusBarAppearanceController,
    private val accessoryController: SosStatusBarAccessoryController,
    private val modeCoordinator: SosStatusBarModeCoordinator,
    private val tickerController: SosStatusBarTickerController,
) {
    /** Kept for the platform shade contract; the R2 panel has no clickable alarm clock. */
    var shadeCollapseAction: Runnable? = null

    var shadeExpandedFraction: Float = 0f

    var qsExpandedFraction: Float = 0f
    var qsVisible: Boolean = false
    var qsScrollY: Int = 0
    var largeScreenActive: Boolean = false

    private var root: View? = null
    private var panelStatusBar: View? = null
    private var accessoryHost: View? = null
    private var panelStatusBarContent: View? = null
    private var batteryView: BatteryMeterView? = null
    private var iconManager: TintedIconManager? = null
    private var batteryMeterViewController: BatteryMeterViewController? = null
    private var statusBarContentInsetsProvider: StatusBarContentInsetsProvider? = null
    private var iconGroupRegistered = false
    // The shade can move to another display. Its PANEL remains R2 there, but only the default
    // display's panel is allowed to suppress the built-in HOME status bar and ticker.
    private var controlsBuiltInHome = true
    private var visible = false
    private var panelExpanded = false
    private var topInset = 0
    private var cutoutMode = SosStatusBarCutoutMode.NONE
    private var leftCarrier: SosLeftCarrierNotificationController? = null
    private var animator: Animator? = null
    private var animationGeneration = 0L
    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyContentInsets()
            }
        }
    private val contentInsetsCallback =
        object : StatusBarContentInsetsChangedListener {
            override fun onStatusBarContentInsetsChanged() {
                applyTopInset()
                applyContentInsets()
            }
        }
    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                applyTopInset()
                applyContentInsets()
                applyVisibleState(animate = false)
            }
        }

    fun attach(rootView: View) {
        if (root === rootView) {
            applyVisibleState(animate = false)
            return
        }
        detach()

        root = rootView
        controlsBuiltInHome = rootView.context.displayId == Display.DEFAULT_DISPLAY
        val panel = rootView.requireViewById<View>(R.id.shade_panel_status_bar)
        panelStatusBar = panel
        accessoryHost = panel
        panelStatusBarContent = panel.requireViewById(R.id.shade_panel_status_bar_content)
        panelStatusBarContent?.addOnLayoutChangeListener(layoutChangeListener)
        val iconContainer = panel.requireViewById<ViewGroup>(R.id.statusIcons)
        val battery = panel.requireViewById<BatteryMeterView>(R.id.battery)
        batteryView = battery
        statusBarContentInsetsProvider =
            statusBarContentInsetsProviderStore.forDisplay(
                rootView.display?.displayId ?: rootView.context.displayId
            )
        statusBarContentInsetsProvider?.addCallback(contentInsetsCallback)
        configurationController.addCallback(configurationListener)

        iconManager =
            tintedIconManagerFactory.create(iconContainer, StatusBarLocation.QS).apply {
                setTint(PANEL_TINT, Color.BLACK)
            }

        batteryMeterViewController =
            BatteryMeterViewController(
                    battery,
                    StatusBarLocation.QS,
                    userTracker,
                    configurationController,
                    tunerService,
                    mainHandler,
                    contentResolver,
                    featureFlags,
                    batteryController,
                    sosBatteryStateController,
                )
                .apply {
                    init()
                    ignoreTunerUpdates()
                }
        batteryView?.apply {
            visibility = View.VISIBLE
            updateColors(PANEL_TINT, Color.BLACK, PANEL_TINT)
        }

        val left = panel.findViewById<ViewGroup>(R.id.status_bar_contents_left)
        if (left != null) {
            leftCarrier = SosLeftCarrierNotificationController(left)
        }

        applyTopInset()
        applyContentInsets()
        accessoryController.registerHost(panel)
        setIconGroupRegistered(true)
        applyVisibleState(animate = false)
    }

    /** Initialization is completed once the canonical shade root is attached. */
    fun init() = Unit

    fun disable(state1: Int, state2: Int, animate: Boolean) {
        val disabled = state2 and android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS != 0
        if (disabled) {
            setVisible(false)
        } else {
            applyVisibleState(animate)
        }
    }

    fun startCustomizingAnimation(show: Boolean, duration: Long) {
        // R2 keeps a single shade header while QS details/customizer replace only the page body.
        panelStatusBar?.animate()?.cancel()
        panelStatusBar?.alpha = 1f
    }

    fun launchClockActivity() {
        shadeCollapseAction?.run()
    }

    fun detach() {
        val oldAccessoryHost = accessoryHost
        visible = false
        panelExpanded = false
        if (controlsBuiltInHome) {
            modeCoordinator.setPanelExpanded(false, animate = false)
        }
        animationGeneration++
        animator?.cancel()
        animator = null
        setIconGroupRegistered(false)
        if (oldAccessoryHost != null) accessoryController.unregisterHost(oldAccessoryHost)
        statusBarContentInsetsProvider?.removeCallback(contentInsetsCallback)
        configurationController.removeCallback(configurationListener)
        statusBarContentInsetsProvider = null
        batteryMeterViewController?.destroy()
        batteryMeterViewController = null
        iconManager = null
        batteryView = null
        leftCarrier?.destroy()
        leftCarrier = null
        panelStatusBarContent?.removeOnLayoutChangeListener(layoutChangeListener)
        panelStatusBarContent = null
        panelStatusBar = null
        accessoryHost = null
        root = null
        iconGroupRegistered = false
        controlsBuiltInHome = true
    }

    fun setVisible(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        if (!isVisible && panelExpanded) {
            panelExpanded = false
            if (controlsBuiltInHome) {
                modeCoordinator.setPanelExpanded(false, animate = true)
            }
        }
        applyVisibleState(animate = true)
    }

    /** Receives the physical shade height; the factory PANEL transition starts with the shade. */
    fun setExpansion(expandedHeight: Float, maxPanelHeight: Float, shadeContentAllowed: Boolean) {
        val expanded =
            shadeContentAllowed &&
                maxPanelHeight > 0f &&
                expandedHeight > EXPANSION_START_TOLERANCE_PX
        if (panelExpanded == expanded) return
        panelExpanded = expanded
        if (controlsBuiltInHome) {
            modeCoordinator.setPanelExpanded(expanded, animate = true)
        }
        applyVisibleState(animate = true)
    }

    fun setTopInset(inset: Int) {
        topInset = inset
        applyTopInset()
        applyContentInsets()
    }

    private fun applyVisibleState(animate: Boolean) {
        val panel = panelStatusBar ?: return
        val showPanel = visible && panelExpanded
        if (!animate) {
            animationGeneration++
            animator?.cancel()
            animator = null
            leftCarrier?.animationPending = false
            panel.visibility = if (showPanel) View.VISIBLE else View.GONE
            panel.alpha = if (showPanel) 1f else 0f
            panel.translationY = 0f
            return
        }
        animatePanel(showPanel)
    }

    private fun animatePanel(show: Boolean) {
        val panel = panelStatusBar ?: return
        val generation = ++animationGeneration
        animator?.cancel()
        animator = null
        val duration =
            panel.resources.getInteger(R.integer.sos_status_bar_panel_anim_duration).toLong()
        val childDuration =
            panel.resources.getInteger(R.integer.sos_status_bar_panel_anim_child_duration).toLong()
        val tickerDelay =
            if (controlsBuiltInHome && modeCoordinator.state.tickerVisible) {
                panel.resources.getInteger(R.integer.sos_status_bar_panel_ticker_delay).toLong()
            } else {
                0L
            }
        val height =
            (
                    panelStatusBarContent?.height?.takeIf { it > 0 }
                        ?: panelStatusBarContent?.layoutParams?.height?.takeIf { it > 0 }
                        ?: SystemBarUtils.getStatusBarHeight(panel.context)
                    )
                .toFloat()
        leftCarrier?.animationPending = true
        if (show) {
            val wasHidden = panel.visibility != View.VISIBLE
            panel.visibility = View.VISIBLE
            // Start from the factory hidden endpoint only for a genuinely hidden PANEL. When a
            // closing animation is reversed, retain its current alpha/translation so there is no
            // one-frame jump back above the status bar.
            if (wasHidden) {
                panel.alpha = 0f
                panel.translationY = -height
            }
        }
        val alpha =
            ObjectAnimator.ofFloat(panel, View.ALPHA, panel.alpha, if (show) 1f else 0f).apply {
                interpolator = DecelerateInterpolator(1.5f)
                this.duration = duration
            }
        val translate =
            ObjectAnimator.ofFloat(
                    panel,
                    View.TRANSLATION_Y,
                    panel.translationY,
                    if (show) 0f else -height,
                )
                .apply {
                    interpolator = DecelerateInterpolator(2.5f)
                    this.duration = childDuration
                }
        val set = AnimatorSet()
        set.playTogether(alpha, translate)
        set.startDelay = tickerDelay
        set.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (generation == animationGeneration && show && controlsBuiltInHome) {
                        // R2 waits for an active ticker's 500 ms grace period, then disables it at
                        // the exact start of the 300 ms PANEL transition.
                        tickerController.disableForPanel()
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (generation != animationGeneration || animator !== animation) return
                    if (!show) {
                        panel.visibility = View.GONE
                    }
                    panel.translationY = 0f
                    leftCarrier?.animationPending = false
                    leftCarrier?.refresh()
                    animator = null
                }
            }
        )
        animator = set
        set.start()
    }

    private fun applyTopInset() {
        val context = root?.context ?: return
        val rotation = context.display?.rotation ?: android.view.Surface.ROTATION_0
        val overlayHeight = SystemBarUtils.getStatusBarHeightForRotation(context, rotation)
        val statusBarHeight = if (overlayHeight > 0) overlayHeight else topInset
        panelStatusBar?.layoutParams =
            panelStatusBar?.layoutParams?.apply { height = statusBarHeight }

        panelStatusBarContent?.layoutParams =
            panelStatusBarContent?.layoutParams?.apply { height = statusBarHeight }
    }

    private fun applyContentInsets() {
        val resources = root?.resources ?: return
        val content = panelStatusBarContent ?: return
        val provider = statusBarContentInsetsProvider
        val insets =
            provider?.getStatusBarContentInsetsForCurrentRotation()
                ?: root
                    ?.rootWindowInsets
                    ?.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                    )
                ?: Insets.of(
                    resources.getDimensionPixelSize(R.dimen.sos_status_bar_contents_margin_start),
                    0,
                    resources.getDimensionPixelSize(R.dimen.sos_status_bar_contents_margin_end),
                    0,
                )
        val metrics = SosStatusBarGeometry.calculate(content)
        (content.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginStart = metrics.contentMarginStart
            lp.marginEnd = metrics.contentMarginEnd
            content.layoutParams = lp
        }
        content.setPadding(
            (insets.left - metrics.contentMarginStart).coerceAtLeast(0),
            0,
            (insets.right - metrics.contentMarginEnd).coerceAtLeast(0),
            0,
        )
        content.findViewById<View>(R.id.network_label)?.let { label ->
            (label.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = metrics.itemMarginStart
                lp.marginEnd = metrics.itemMarginEnd
                label.layoutParams = lp
            }
        }
        updateCutoutLayout()
    }

    private fun updateCutoutLayout() {
        val content = panelStatusBarContent ?: return
        val cutout = root?.rootWindowInsets?.displayCutout
        val screenWidth = root?.width?.takeIf { it > 0 } ?: content.width
        val previousMode = cutoutMode
        cutoutMode = SosStatusBarCutoutClassifier.classify(cutout, screenWidth)
        val bounds = cutout?.boundingRectTop?.let { Rect(it) }
        SosStatusBarCutoutLayout.apply(
            content as? ViewGroup,
            cutoutMode,
            bounds,
        )
        if (previousMode != cutoutMode) {
            applyVisibleState(animate = false)
        }
        iconManager?.let { manager ->
            appearanceController.apply(
                manager.findContentsRoot(),
                appearanceController.appearanceFor(
                    host = HostAppearance.PANEL,
                    cutoutMode = cutoutMode,
                    homeTint = PANEL_TINT,
                    homeForeground = PANEL_TINT,
                    keyguardSupportsDarkText = false,
                    forceLight = false,
                ),
            )
        }
    }

    private fun setIconGroupRegistered(registered: Boolean) {
        if (iconGroupRegistered == registered) {
            return
        }
        val manager = iconManager ?: return
        if (registered) {
            sosSystemIconsController.registerHost(manager, HostAppearance.PANEL)
        } else {
            sosSystemIconsController.unregisterHost(manager)
        }
        iconGroupRegistered = registered
    }

    companion object {
        private const val PANEL_TINT = 0xB3FFFFFF.toInt()
        private const val EXPANSION_START_TOLERANCE_PX = 0f
    }
}
