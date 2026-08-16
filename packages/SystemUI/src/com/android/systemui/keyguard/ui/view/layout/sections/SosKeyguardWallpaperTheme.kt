/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.graphics.Bitmap

/** The two wallpaper themes used by the original R2 keyguard. */
internal enum class SosKeyguardWallpaperTheme(val textColor: Int) {
    DARK_WALLPAPER(0xE6FFFFFF.toInt()),
    LIGHT_WALLPAPER(0xFF454545.toInt()),
}

/** Byte-for-byte equivalent of the original whole-wallpaper luminance decision. */
internal object SosKeyguardWallpaperThemeClassifier {
    private const val LIGHT_WALLPAPER_THRESHOLD = 192.0

    fun classify(bitmap: Bitmap): SosKeyguardWallpaperTheme {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return SosKeyguardWallpaperTheme.DARK_WALLPAPER
        }

        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) {
                // The original ignores alpha and averages every RGB pixel in the wallpaper.
                redTotal += (pixel ushr 16) and 0xFF
                greenTotal += (pixel ushr 8) and 0xFF
                blueTotal += pixel and 0xFF
            }
        }
        return classifyTotals(
            redTotal,
            greenTotal,
            blueTotal,
            bitmap.width.toLong() * bitmap.height.toLong(),
        )
    }

    internal fun classifyPixels(pixels: IntArray): SosKeyguardWallpaperTheme {
        if (pixels.isEmpty()) return SosKeyguardWallpaperTheme.DARK_WALLPAPER
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        for (pixel in pixels) {
            redTotal += (pixel ushr 16) and 0xFF
            greenTotal += (pixel ushr 8) and 0xFF
            blueTotal += pixel and 0xFF
        }
        return classifyTotals(redTotal, greenTotal, blueTotal, pixels.size.toLong())
    }

    private fun classifyTotals(
        redTotal: Long,
        greenTotal: Long,
        blueTotal: Long,
        pixelCount: Long,
    ): SosKeyguardWallpaperTheme {
        if (pixelCount <= 0L) return SosKeyguardWallpaperTheme.DARK_WALLPAPER
        val luminance =
            redTotal * 0.21 / pixelCount +
                greenTotal * 0.72 / pixelCount +
                blueTotal * 0.07 / pixelCount
        return if (luminance < LIGHT_WALLPAPER_THRESHOLD) {
            SosKeyguardWallpaperTheme.DARK_WALLPAPER
        } else {
            SosKeyguardWallpaperTheme.LIGHT_WALLPAPER
        }
    }
}
