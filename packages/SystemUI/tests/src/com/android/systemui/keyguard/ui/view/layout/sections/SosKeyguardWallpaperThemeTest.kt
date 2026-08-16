/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SosKeyguardWallpaperThemeTest {
    @Test
    fun darkWallpaper_usesOriginalTranslucentWhite() {
        val theme = SosKeyguardWallpaperThemeClassifier.classifyPixels(intArrayOf(0xFF204050.toInt()))

        assertThat(theme).isEqualTo(SosKeyguardWallpaperTheme.DARK_WALLPAPER)
        assertThat(theme.textColor).isEqualTo(0xE6FFFFFF.toInt())
    }

    @Test
    fun lightWallpaper_usesOriginalDarkGray() {
        val theme = SosKeyguardWallpaperThemeClassifier.classifyPixels(intArrayOf(0xFFF4F4F4.toInt()))

        assertThat(theme).isEqualTo(SosKeyguardWallpaperTheme.LIGHT_WALLPAPER)
        assertThat(theme.textColor).isEqualTo(0xFF454545.toInt())
    }

    @Test
    fun alphaChannel_doesNotChangeOriginalRgbDecision() {
        val transparentWhite = SosKeyguardWallpaperThemeClassifier.classifyPixels(intArrayOf(0x00FFFFFF))
        val opaqueWhite = SosKeyguardWallpaperThemeClassifier.classifyPixels(intArrayOf(0xFFFFFFFF.toInt()))

        assertThat(transparentWhite).isEqualTo(opaqueWhite)
        assertThat(transparentWhite).isEqualTo(SosKeyguardWallpaperTheme.LIGHT_WALLPAPER)
    }

    @Test
    fun threshold_matchesOriginal192Boundary() {
        assertThat(SosKeyguardWallpaperThemeClassifier.classifyPixels(intArrayOf(0xFFBFBFBF.toInt())))
            .isEqualTo(SosKeyguardWallpaperTheme.DARK_WALLPAPER)
        assertThat(SosKeyguardWallpaperThemeClassifier.classifyPixels(intArrayOf(0xFFC0C0C0.toInt())))
            .isEqualTo(SosKeyguardWallpaperTheme.LIGHT_WALLPAPER)
    }
}
