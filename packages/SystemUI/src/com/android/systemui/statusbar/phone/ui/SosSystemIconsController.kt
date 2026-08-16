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

import com.android.systemui.dagger.SysUISingleton
import java.util.IdentityHashMap
import javax.inject.Inject

/**
 * Single registration point for every Smartisan phone status-bar host.
 *
 * The platform icon controller remains the source of truth for slots and state.  This class makes
 * HOME, KEYGUARD and PANEL share that state and the same [IconManager] rendering policy while still
 * allowing each surface to own an independent View instance.
 */
@SysUISingleton
class SosSystemIconsController
@Inject
constructor(private val statusBarIconController: StatusBarIconController) {
    fun interface KeyguardThemeListener {
        fun onKeyguardWallpaperThemeChanged(supportsDarkText: Boolean)
    }

    fun interface HomeKeyguardThemeListener {
        fun onHomeKeyguardThemeChanged(active: Boolean, supportsDarkText: Boolean)
    }

    enum class HostAppearance {
        HOME,
        KEYGUARD,
        PANEL,
    }

    private val hosts = IdentityHashMap<IconManager, HostAppearance>()
    private val keyguardThemeListeners =
        java.util.Collections.newSetFromMap(
            IdentityHashMap<KeyguardThemeListener, Boolean>()
        )
    private val homeKeyguardThemeListeners =
        java.util.Collections.newSetFromMap(
            IdentityHashMap<HomeKeyguardThemeListener, Boolean>()
        )
    private var keyguardWallpaperSupportsDarkText = false
    private var keyguardPresented = false

    @Synchronized
    fun registerHost(iconManager: IconManager, appearance: HostAppearance) {
        if (hosts.put(iconManager, appearance) == null) {
            statusBarIconController.addIconGroup(iconManager)
        }
        if (appearance == HostAppearance.KEYGUARD) {
            applyKeyguardTheme(iconManager)
        }
    }

    @Synchronized
    fun unregisterHost(iconManager: IconManager) {
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
        hosts.forEach { (host, appearance) ->
            if (appearance == HostAppearance.KEYGUARD) applyKeyguardTheme(host)
        }
        keyguardThemeListeners.toList().forEach {
            it.onKeyguardWallpaperThemeChanged(supportsDarkText)
        }
        notifyHomeThemeListeners()
    }

    @Synchronized
    fun addKeyguardThemeListener(listener: KeyguardThemeListener) {
        keyguardThemeListeners.add(listener)
        listener.onKeyguardWallpaperThemeChanged(keyguardWallpaperSupportsDarkText)
    }

    @Synchronized
    fun removeKeyguardThemeListener(listener: KeyguardThemeListener) {
        keyguardThemeListeners.remove(listener)
    }

    @Synchronized
    fun setKeyguardPresented(presented: Boolean) {
        if (keyguardPresented == presented) return
        keyguardPresented = presented
        notifyHomeThemeListeners()
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

    private fun applyKeyguardTheme(iconManager: IconManager) {
        (iconManager as? TintedIconManager)?.setTint(
            if (keyguardWallpaperSupportsDarkText) LIGHT_WALLPAPER_ICON_TINT
            else DARK_WALLPAPER_ICON_TINT,
            if (keyguardWallpaperSupportsDarkText) LIGHT_WALLPAPER_NUMBER_TINT
            else DARK_WALLPAPER_NUMBER_TINT,
        )
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
