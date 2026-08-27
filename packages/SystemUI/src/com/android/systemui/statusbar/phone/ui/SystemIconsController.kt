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

import android.view.Display
import android.widget.TextView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.phone.StatusBarAppearanceController
import com.android.systemui.statusbar.phone.StatusBarCarrierTextController
import com.android.systemui.statusbar.phone.StatusBarCutoutMode
import com.android.systemui.res.R
import java.util.IdentityHashMap
import javax.inject.Inject

/**
 * Single registration point for every Smartisan phone status-bar host.
 *
 * The platform icon controller remains the source of truth for slots and state.  This class makes
 * HOME and PANEL share that state and the same [IconManager] rendering policy while still allowing
 * each display to own an independent View instance. The lockscreen reuses the default HOME host.
 */
@SysUISingleton
class SystemIconsController
@Inject
constructor(
    private val statusBarIconController: StatusBarIconController,
    private val appearanceController: StatusBarAppearanceController,
    private val carrierTextController: StatusBarCarrierTextController,
) {
    fun interface HomeKeyguardThemeListener {
        fun onHomeKeyguardThemeChanged(active: Boolean, supportsDarkText: Boolean)
    }

    enum class HostAppearance {
        HOME,
        PANEL,
    }

    private val hosts = IdentityHashMap<IconManager, HostAppearance>()
    private val carrierHosts = IdentityHashMap<IconManager, TextView>()
    private val homeKeyguardThemeListeners =
        java.util.Collections.newSetFromMap(
            IdentityHashMap<HomeKeyguardThemeListener, Boolean>()
        )
    private var keyguardWallpaperSupportsDarkText = false
    private var keyguardPresented = false

    init {
        appearanceController.addListener { applyAllHosts() }
    }

    @Synchronized
    fun registerHost(iconManager: IconManager, appearance: HostAppearance) {
        if (hosts.put(iconManager, appearance) == null) {
            statusBarIconController.addIconGroup(iconManager)
        }
        iconManager.findContentsRoot()?.findViewById<TextView>(R.id.network_label)?.let { label ->
            carrierHosts.put(iconManager, label)?.takeIf { it !== label }?.let(
                carrierTextController::unregisterHost
            )
            carrierTextController.registerHost(label)
        }
        applyHost(iconManager, appearance)
    }

    @Synchronized
    fun unregisterHost(iconManager: IconManager) {
        carrierHosts.remove(iconManager)?.let(carrierTextController::unregisterHost)
        if (hosts.remove(iconManager) != null) {
            statusBarIconController.removeIconGroup(iconManager)
        }
    }

    /**
     * Mirrors Smartisan's status-bar decoration contract: the lockscreen owns wallpaper analysis,
     * while every status icon remains owned by SystemUI's shared icon controller.
     */
    @Synchronized
    fun setKeyguardWallpaperTheme(supportsDarkText: Boolean) {
        if (keyguardWallpaperSupportsDarkText == supportsDarkText) return
        keyguardWallpaperSupportsDarkText = supportsDarkText
        notifyHomeThemeListeners()
        applyAllHosts()
    }

    @Synchronized
    fun setKeyguardPresented(presented: Boolean) {
        if (keyguardPresented == presented) return
        keyguardPresented = presented
        notifyHomeThemeListeners()
        applyAllHosts()
    }

    @Synchronized
    fun addHomeKeyguardThemeListener(listener: HomeKeyguardThemeListener) {
        homeKeyguardThemeListeners.add(listener)
        listener.onHomeKeyguardThemeChanged(keyguardPresented, keyguardWallpaperSupportsDarkText)
    }

    @Synchronized
    fun removeHomeKeyguardThemeListener(listener: HomeKeyguardThemeListener) {
        homeKeyguardThemeListeners.remove(listener)
    }

    @Synchronized
    fun keyguardWallpaperSupportsDarkText(): Boolean = keyguardWallpaperSupportsDarkText

    private fun applyAllHosts() {
        hosts.forEach { (host, appearance) -> applyHost(host, appearance) }
    }

    private fun applyHost(iconManager: IconManager, appearance: HostAppearance) {
        val keyguardThemeActive =
            keyguardPresented && iconManager.displayId == Display.DEFAULT_DISPLAY
        if (appearance == HostAppearance.HOME && !keyguardThemeActive) {
            appearanceController.applyHomeColorPreference(iconManager.findContentsRoot())
            return
        }
        val homeTint =
            if (keyguardThemeActive) {
                iconTintForWallpaper(keyguardWallpaperSupportsDarkText)
            } else {
                DarkIconDispatcher.DEFAULT_ICON_TINT
            }
        val homeFg =
            if (keyguardThemeActive) {
                foregroundTintForWallpaper(keyguardWallpaperSupportsDarkText)
            } else {
                DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT
            }
        val snapshot =
            appearanceController.appearanceFor(
                host = appearance,
                cutoutMode = StatusBarCutoutMode.NONE,
                homeTint = homeTint,
                homeForeground = homeFg,
                forceLight = false,
            )
        appearanceController.apply(iconManager.findContentsRoot(), snapshot)
    }

    private fun notifyHomeThemeListeners() {
        homeKeyguardThemeListeners.toList().forEach {
            it.onHomeKeyguardThemeChanged(keyguardPresented, keyguardWallpaperSupportsDarkText)
        }
    }

    companion object {
        // Values are taken from KeyguardSmartisan's light/dark status-bar resources.
        private const val DARK_WALLPAPER_ICON_TINT = 0xEEFFFFFF.toInt()
        private const val DARK_WALLPAPER_NUMBER_TINT = 0xEE000000.toInt()
        private const val LIGHT_WALLPAPER_ICON_TINT = 0x9E000000.toInt()
        private const val LIGHT_WALLPAPER_NUMBER_TINT = 0xCCFFFFFF.toInt()

        @JvmStatic
        fun iconTintForWallpaper(supportsDarkText: Boolean): Int =
            if (supportsDarkText) LIGHT_WALLPAPER_ICON_TINT else DARK_WALLPAPER_ICON_TINT

        @JvmStatic
        fun foregroundTintForWallpaper(supportsDarkText: Boolean): Int =
            if (supportsDarkText) LIGHT_WALLPAPER_NUMBER_TINT else DARK_WALLPAPER_NUMBER_TINT
    }
}
