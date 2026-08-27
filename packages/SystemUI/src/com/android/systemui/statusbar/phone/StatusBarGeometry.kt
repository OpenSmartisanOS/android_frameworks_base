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
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import com.android.internal.policy.SystemBarUtils
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure, device-name independent geometry for the Smartisan status bar. */
data class StatusBarMetrics(
    val scale: Float,
    val longBar: Boolean,
    val safeWidth: Int,
    val safeInsets: Insets,
    val statusBarHeight: Int,
    val iconHeight: Int,
    val batteryWidth: Int,
    val itemMarginStart: Int,
    val itemMarginEnd: Int,
    val contentMarginStart: Int,
    val contentMarginEnd: Int,
    val notificationSlotWidth: Int,
    val networkSpeedWidth: Int,
    val tickerPaddingStart: Int,
    val tickerPaddingEnd: Int,
)

object StatusBarGeometry {
    private const val DESIGN_WIDTH = 1080f
    private const val MAX_CONTENT_WIDTH_DP = 480f
    private const val LONG_BAR_THRESHOLD = 66f

    @JvmStatic
    fun calculate(
        safeWidthPx: Int,
        density: Float,
        statusBarHeightPx: Int,
        insets: Insets = Insets.NONE,
    ): StatusBarMetrics {
        val insetSafeWidth = (safeWidthPx - insets.left - insets.right).coerceAtLeast(1)
        val contentWidth = min(insetSafeWidth.toFloat(), MAX_CONTENT_WIDTH_DP * density)
        val scale = (contentWidth / DESIGN_WIDTH).coerceAtLeast(0.01f)
        val normalizedBarHeight = statusBarHeightPx / scale
        val longBar = normalizedBarHeight >= LONG_BAR_THRESHOLD
        val iconDesign = if (longBar) 39f else 32f
        val batteryDesign = if (longBar) 51f else 42f
        val iconHeight = scaled(iconDesign, scale)
        val marginStart = scaled(6f, scale)
        val marginEnd = scaled(7f, scale)
        return StatusBarMetrics(
            scale = scale,
            longBar = longBar,
            safeWidth = insetSafeWidth,
            safeInsets = insets,
            statusBarHeight = statusBarHeightPx,
            iconHeight = iconHeight,
            batteryWidth = scaled(batteryDesign, scale),
            itemMarginStart = marginStart,
            itemMarginEnd = marginEnd,
            contentMarginStart = scaled(24f, scale),
            contentMarginEnd = scaled(23f, scale),
            notificationSlotWidth = iconHeight + marginStart + marginEnd,
            networkSpeedWidth = scaled(150f, scale),
            // The factory 192/72px ticker padding was part of Smartisan's private cutout
            // composition. Modern builds already constrain the status-bar root with the active
            // display's overlay-provided Insets, so retaining that padding shifts the whole
            // ticker inward a second time. Align it with the normal status-bar content instead;
            // PhoneStatusBarView's own padding still keeps it outside rounded/cutout safe areas.
            tickerPaddingStart = scaled(24f, scale),
            tickerPaddingEnd = scaled(23f, scale),
        )
    }

    @JvmStatic
    fun calculate(context: Context): StatusBarMetrics {
        val resources = context.resources
        val bounds = resources.configuration.windowConfiguration.bounds
        val width = bounds.width().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height =
            SystemBarUtils.getStatusBarHeight(context).takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height)
        return calculate(width, resources.displayMetrics.density, height)
    }

    /** Calculates from the active status-bar window rather than the display's maximum bounds. */
    @JvmStatic
    fun calculate(view: View): StatusBarMetrics {
        val resources = view.resources
        val root = view.rootView
        val bounds = resources.configuration.windowConfiguration.bounds
        val width =
            root.width.takeIf { it > 0 }
                ?: view.width.takeIf { it > 0 }
                ?: bounds.width().takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
        val statusBar = root.findViewById<View>(com.android.systemui.res.R.id.status_bar)
        val height =
            statusBar?.height?.takeIf { it > 0 }
                ?: SystemBarUtils.getStatusBarHeight(view.context).takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height)
        val windowInsets = root.rootWindowInsets
        val systemInsets =
            windowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) ?: Insets.NONE
        val cutout = windowInsets?.displayCutout
        val safeInsets =
            Insets.of(
                maxOf(systemInsets.left, cutout?.safeInsetLeft ?: 0),
                0,
                maxOf(systemInsets.right, cutout?.safeInsetRight ?: 0),
                0,
            )
        return calculate(width, resources.displayMetrics.density, height, safeInsets)
    }

    private fun scaled(value: Float, scale: Float): Int =
        (value * scale).roundToInt().coerceAtLeast(1)
}
