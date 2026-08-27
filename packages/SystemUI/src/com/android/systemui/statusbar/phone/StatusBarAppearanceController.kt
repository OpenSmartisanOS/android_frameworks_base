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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.battery.BatteryView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.AnimatedImageView
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.phone.ui.SystemIconsController
import com.android.systemui.statusbar.pipeline.mobile.ui.view.SignalClusterView
import com.android.systemui.statusbar.pipeline.wifi.ui.view.WifiView
import com.android.systemui.statusbar.policy.Clock
import javax.inject.Inject

/**
 * Canonical owner of the complete Smartisan right-side status area tint.
 *
 * The factory implementation applies one color to the complete SystemIconView in a single pass.
 * Keeping the dispatcher subscription on this root is important: modern Wi-Fi/mobile binders and
 * legacy icon views must never observe different dark-area snapshots during the same frame.
 */
class SystemIconsView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : RelativeLayout(context, attrs),
    com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver {
    private var liveTint = com.android.systemui.plugins.DarkIconDispatcher.DEFAULT_ICON_TINT
    private var liveForegroundTint =
        com.android.systemui.plugins.DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT
    private var staticTint: Int? = null
    private var staticForegroundTint: Int? = null
    private var keyguardTint: Int? = null
    private var keyguardForegroundTint: Int? = null
    private var colorIconsRequested = false
    private var appliedTint: Int? = null
    private var appliedForegroundTint: Int? = null
    private var appliedColorIcons: Boolean? = null

    override fun onDarkChanged(areas: ArrayList<Rect>?, darkIntensity: Float, tint: Int) {
        // DarkIconDispatcher immediately follows this legacy callback with the contrast-aware
        // callback. Applying only there keeps the right side atomic instead of drawing two states.
    }

    override fun onDarkChangedWithContrast(
        areas: ArrayList<Rect>,
        tint: Int,
        contrastTint: Int,
    ) {
        // The host is match_parent so StatusIconMerger can consume the space left of the fixed
        // network cluster. Using that full-width rectangle for area matching would classify a
        // right-side icon group from the empty left half. The always-present battery is the
        // factory SystemIconView's stable right-edge sampling anchor.
        val tintAnchor =
            findViewById<View>(com.android.systemui.res.R.id.battery) ?: this
        liveTint = com.android.systemui.plugins.DarkIconDispatcher.getTint(areas, tintAnchor, tint)
        liveForegroundTint =
            com.android.systemui.plugins.DarkIconDispatcher.getInverseTint(
                areas,
                tintAnchor,
                contrastTint,
            )
        applyResolvedAppearance()
    }

    /** Sets the fixed tint used by the R2 PANEL host. HOME remains dispatcher-driven. */
    fun setStaticAppearance(tint: Int, foregroundTint: Int, colorIcons: Boolean) {
        staticTint = tint
        staticForegroundTint = foregroundTint
        colorIconsRequested = colorIcons
        applyResolvedAppearance()
    }

    /** Applies/removes the wallpaper-owned lockscreen tint without losing the latest app tint. */
    fun setKeyguardTintOverride(tint: Int?, foregroundTint: Int?) {
        if (keyguardTint == tint && keyguardForegroundTint == foregroundTint) return
        keyguardTint = tint
        keyguardForegroundTint = foregroundTint
        applyResolvedAppearance()
    }

    fun setColorIcon(enabled: Boolean) {
        if (colorIconsRequested == enabled) return
        colorIconsRequested = enabled
        applyResolvedAppearance()
    }

    fun currentTint(): Int = resolvedTint()

    fun currentForegroundTint(): Int = resolvedForegroundTint()

    /** A newly attached dynamic icon receives the already committed host state immediately. */
    fun applyCurrentTint(view: StatusIconDisplayable) {
        val tint = resolvedTint()
        view.setStaticDrawableColor(tint, resolvedForegroundTint())
        view.setDecorColor(tint)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        applyResolvedAppearance(force = true)
    }

    private fun resolvedTint(): Int = keyguardTint ?: staticTint ?: liveTint

    private fun resolvedForegroundTint(): Int =
        keyguardForegroundTint ?: staticForegroundTint ?: liveForegroundTint

    private fun applyResolvedAppearance(force: Boolean = false) {
        val tint = resolvedTint()
        val foreground = resolvedForegroundTint()
        val colorIcons =
            colorIconsRequested &&
                tint == com.android.systemui.plugins.DarkIconDispatcher.DEFAULT_ICON_TINT
        if (!force &&
            appliedTint == tint &&
            appliedForegroundTint == foreground &&
            appliedColorIcons == colorIcons
        ) {
            return
        }
        appliedTint = tint
        appliedForegroundTint = foreground
        appliedColorIcons = colorIcons

        findViewById<NetworkSignalCluster>(com.android.systemui.res.R.id.network_signal_cluster)
            ?.apply {
                setHostTint(tint, foreground)
                setColorIcon(colorIcons)
            }
        findViewById<BatteryView>(com.android.systemui.res.R.id.battery)?.apply {
            updateColors(tint, foreground, tint)
            setColorIcon(colorIcons)
        }
        findViewById<ViewGroup>(com.android.systemui.res.R.id.statusIcons)?.let { group ->
            for (index in 0 until group.childCount) {
                (group.getChildAt(index) as? StatusIconDisplayable)?.let(::applyCurrentTint)
            }
        }
        applyAccessoryTint(findViewById<View>(com.android.systemui.res.R.id.otg), tint)
        applyAccessoryTint(findViewById<View>(com.android.systemui.res.R.id.net_speed_type), tint)
        applyAccessoryTint(findViewById<View>(com.android.systemui.res.R.id.net_speed), tint)
        applyAccessoryTint(findViewById<View>(com.android.systemui.res.R.id.net_unit), tint)
    }

    private fun applyAccessoryTint(view: View?, tint: Int) {
        when (view) {
            is TextView -> view.setTextColor(tint)
            is ImageView -> view.imageTintList = ColorStateList.valueOf(tint)
        }
    }
}

data class StatusBarAppearance(
    val monochromeTint: Int,
    val foregroundTint: Int,
    val colorIconsEnabled: Boolean,
    val host: SystemIconsController.HostAppearance,
    val cutoutMode: StatusBarCutoutMode,
)

@SysUISingleton
class StatusBarAppearanceController
@Inject
constructor() {
    fun interface Listener {
        fun onAppearanceChanged()
    }

    private val listeners = ArrayList<Listener>()
    private val colorIconsEnabled = false

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onAppearanceChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun appearanceFor(
        host: SystemIconsController.HostAppearance,
        cutoutMode: StatusBarCutoutMode,
        homeTint: Int,
        homeForeground: Int,
        forceLight: Boolean,
    ): StatusBarAppearance {
        val (mono, fg) =
            when {
                forceLight -> PANEL_TINT to PANEL_TINT
                host == SystemIconsController.HostAppearance.PANEL -> PANEL_TINT to PANEL_TINT
                else -> homeTint to homeForeground
            }
        // Original Smartisan only used colorful wifi/SIM/battery artwork on the HOME bar when
        // SystemUI was drawing default light-on-dark icons. The shade panel is always 70% white,
        // and notch-hide forces the same white. Lockscreen wallpaper tint is applied to the one
        // canonical HOME host by DarkIconManager's temporary override.
        val allowColorArtwork =
            colorIconsEnabled &&
                !forceLight &&
                host == SystemIconsController.HostAppearance.HOME &&
                mono == com.android.systemui.plugins.DarkIconDispatcher.DEFAULT_ICON_TINT
        return StatusBarAppearance(
            monochromeTint = mono,
            foregroundTint = fg,
            colorIconsEnabled = allowColorArtwork,
            host = host,
            cutoutMode = cutoutMode,
        )
    }

    fun apply(root: View?, appearance: StatusBarAppearance) {
        if (root == null) return
        val colorOnly = appearance.colorIconsEnabled
        visit(root) { view ->
            if (colorOnly) {
                applyColorOnly(view, appearance)
            } else {
                applyView(view, appearance)
            }
        }
    }

    /** HOME keeps its live DarkIconDispatcher tint; only the original artwork preference is
     * applied here. Each Smartisan view resolves it against the tint currently owned by its host. */
    fun applyHomeColorPreference(root: View?) {
        if (root == null) return
        root.findViewById<SystemIconsView>(com.android.systemui.res.R.id.system_icons)?.let {
            it.setColorIcon(colorIconsEnabled)
            return
        }
        visit(root) { view ->
            when (view) {
                is BatteryView -> view.setColorIcon(colorIconsEnabled)
                is NetworkSignalCluster -> view.setColorIcon(colorIconsEnabled)
            }
        }
    }

    private fun applyColorOnly(view: View, appearance: StatusBarAppearance) {
        when (view) {
            is SystemIconsView -> view.setColorIcon(appearance.colorIconsEnabled)
            is WifiView -> view.setColorIcon(appearance.colorIconsEnabled)
            is SignalClusterView -> view.setColorIcon(appearance.colorIconsEnabled)
            is BatteryView -> view.setColorIcon(appearance.colorIconsEnabled)
            is NetworkSignalCluster -> view.setColorIcon(appearance.colorIconsEnabled)
        }
    }

    private fun applyView(view: View, appearance: StatusBarAppearance) {
        val tint = appearance.monochromeTint
        val fg = appearance.foregroundTint
        when (view) {
            is SystemIconsView -> {
                if (appearance.host == SystemIconsController.HostAppearance.PANEL) {
                    view.setStaticAppearance(tint, fg, appearance.colorIconsEnabled)
                } else {
                    view.setKeyguardTintOverride(tint, fg)
                    view.setColorIcon(appearance.colorIconsEnabled)
                }
            }
            is WifiView -> {
                view.setHostTint(tint)
                view.setColorIcon(appearance.colorIconsEnabled)
            }
            is SignalClusterView -> {
                view.setHostTint(tint)
                view.setColorIcon(appearance.colorIconsEnabled)
            }
            is BatteryView -> {
                view.updateColors(tint, fg, tint)
                view.setColorIcon(appearance.colorIconsEnabled)
            }
            is Clock -> view.setTextColor(tint)
            is NetworkSignalCluster -> {
                view.setHostTint(tint)
                view.setColorIcon(appearance.colorIconsEnabled)
            }
            is StatusBarIconView -> {
                if (shouldTintSystemIcon(view)) {
                    view.setStaticDrawableColor(tint, fg)
                    view.setDecorColor(tint)
                }
            }
            is StatusIconDisplayable -> {
                view.setStaticDrawableColor(tint, fg)
                view.setDecorColor(tint)
            }
            is ImageView -> {
                if (
                    view.id == com.android.systemui.res.R.id.status_bar_ticker_icon &&
                        view is AnimatedImageView
                ) {
                    view.setHostTint(tint)
                } else if (isHostTintedAccessory(view)) {
                    view.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                }
            }
            is TextView -> {
                if (view.id == com.android.systemui.res.R.id.network_label ||
                    view.id == com.android.systemui.res.R.id.operator_name ||
                    view.id == com.android.systemui.res.R.id.net_speed ||
                    view.id == com.android.systemui.res.R.id.net_unit ||
                    view.id == com.android.systemui.res.R.id.status_bar_ticker_text
                ) {
                    view.setTextColor(tint)
                }
            }
        }
    }

    private fun shouldTintSystemIcon(view: StatusBarIconView): Boolean {
        val icon: StatusBarIcon? =
            try {
                view.statusBarIcon
            } catch (_: Throwable) {
                null
            }
        if (icon == null) return true
        if (icon.type == StatusBarIcon.Type.SystemIcon) return true
        val pkg = icon.pkg
        return pkg.isNullOrEmpty() ||
            pkg == "android" ||
            pkg == "com.android.systemui"
    }

    private fun isHostTintedAccessory(view: ImageView): Boolean {
        val id = view.id
        return id == com.android.systemui.res.R.id.otg ||
            id == com.android.systemui.res.R.id.no_sim_icon ||
            id == com.android.systemui.res.R.id.sidebar_drag ||
            id == com.android.systemui.res.R.id.net_speed_type
    }

    private fun visit(view: View, block: (View) -> Unit) {
        block(view)
        if (view is SystemIconsView) return
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                visit(view.getChildAt(i), block)
            }
        }
    }

    companion object {
        const val PANEL_TINT = 0xB3FFFFFF.toInt()
    }
}
