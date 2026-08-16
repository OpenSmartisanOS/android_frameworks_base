/*
 * Copyright (C) 2026 OpenSmartisanOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry model for the SmartisanOS R2/Delta lock screen.
 *
 * The original UI was authored against a 1080 x 2242 pixel content area. Device density is not a
 * useful scaling input for those measurements: two 1080-wide phones must render the same visual
 * proportions even when their user-selected display sizes differ. Width therefore determines the
 * visual scale. Height only reduces that scale when the original scene would otherwise be clipped,
 * and surplus height is assigned to flexible gaps.
 */
object SosKeyguardLayoutModel {
    const val DESIGN_WIDTH = 1080f
    const val DESIGN_HEIGHT = 2242f

    private const val MAX_CONTENT_WIDTH_DP = 480f
    private const val WEATHER_TOP = 297f
    private const val WEATHER_HEIGHT = 280f
    private const val WEATHER_MUSIC_GAP = 3f
    private const val MUSIC_HEIGHT = 888f
    private const val MUSIC_RECORDER_GAP = 48f
    private const val RECORDER_HEIGHT = 384f
    private const val BOTTOM_FLEX = 342f
    private const val HORIZONTAL_PADDING = 96f
    private const val RECORDER_WIDTH = 600f
    private const val TORCH_GAP = 48f
    private const val CORNER_ICON_SIZE = 114f
    private const val CORNER_MARGIN = 30f
    private const val MAIN_TIME_BOTTOM = 39f
    private const val MAIN_TIME_HEIGHT = 117f
    private const val UNLOCK_HANDLE_SIZE = 114f
    private const val MIN_TOUCH_TARGET_DP = 48f
    private const val AOD_TIME_LEFT = 90f
    private const val AOD_TIME_TOP = 6f
    private const val AOD_TIME_WIDTH = 440f
    private const val AOD_TIME_HEIGHT = 760f
    private const val AOD_SECOND_LEFT = 545f
    private const val AOD_SECOND_TOP = 155f
    private const val AOD_SECOND_HEIGHT = 72f
    private const val AOD_UDFPS_CLEARANCE = 24f
    private const val WALLPAPER_SWIPE_THRESHOLD = 198f
    private const val QUICK_SELECTOR_ICON_SIZE = 153f
    private const val QUICK_SELECTOR_BOTTOM = 15f
    private const val QUICK_SELECTOR_LABEL_HEIGHT = 72f
    private const val QUICK_PICKER_HEIGHT = 805f

    enum class Scene {
        HOST_MAIN,
        MAIN,
        TPAGE,
        SECURITY,
        PIN,
        PATTERN,
        PASSWORD,
        SIM_PIN,
        SIM_PUK,
        AOD,
        PIN_ROOT,
        CHARGING,
    }

    data class Insets(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    )

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun inset(horizontal: Float, vertical: Float): Bounds =
            Bounds(left + horizontal, top + vertical, right - horizontal, bottom - vertical)
    }

    data class Input(
        val widthPx: Float,
        val heightPx: Float,
        val density: Float,
        val fontScale: Float = 1f,
        val insets: Insets = Insets(),
        val scene: Scene = Scene.MAIN,
        val udfpsBounds: Bounds? = null,
    )

    data class Metrics(
        val safeBounds: Bounds,
        val contentBounds: Bounds,
        val scale: Float,
        val constrainedFontScale: Float,
        val topFlexibleSpace: Float,
        val bottomFlexibleSpace: Float,
        val minimumTouchTarget: Float,
        val mainTime: Bounds,
        val widgetShortcut: Bounds,
        val cameraShortcut: Bounds,
        val quickSelectorSlots: List<Bounds>,
        val quickSelectorLabel: Bounds,
        val quickPicker: Bounds,
        val weather: Bounds,
        val music: Bounds,
        val recorder: Bounds,
        val torch: Bounds,
        val unlockHandle: Bounds,
        val aodTime: Bounds,
        val aodSecond: Bounds,
        val aodBottomTranslationY: Float,
        val securityContent: Bounds,
        val credentialCanvas: Bounds,
        val credentialMessage: Bounds,
        val pinEntry: Bounds,
        val pinKeyboard: Bounds,
        val patternGrid: Bounds,
        val passwordEntry: Bounds,
        val simEntry: Bounds,
        val credentialTextSize: Float,
        val patternPathWidth: Float,
        val pinRoot: Bounds,
        val chargingContent: Bounds,
        val horizontalPageThreshold: Float,
        val verticalDismissThreshold: Float,
        val wallpaperSwipeThreshold: Float,
        val originalPageThreshold: Float,
        val originalCameraThreshold: Float,
    )

    fun calculate(input: Input): Metrics {
        require(input.widthPx > 0f) { "widthPx must be positive" }
        require(input.heightPx > 0f) { "heightPx must be positive" }
        require(input.density > 0f) { "density must be positive" }

        val safeLeft = input.insets.left.coerceIn(0f, input.widthPx)
        val safeTop = input.insets.top.coerceIn(0f, input.heightPx)
        val safeRight = (input.widthPx - input.insets.right).coerceAtLeast(safeLeft)
        val safeBottom = (input.heightPx - input.insets.bottom).coerceAtLeast(safeTop)
        val safeBounds = Bounds(safeLeft, safeTop, safeRight, safeBottom)

        val maximumContentWidth = MAX_CONTENT_WIDTH_DP * input.density
        val targetContentWidth = min(safeBounds.width, maximumContentWidth)
        val widthScale = targetContentWidth / DESIGN_WIDTH
        val heightScale = safeBounds.height / DESIGN_HEIGHT
        val scale = min(widthScale, heightScale).coerceAtLeast(0.01f)
        val actualContentWidth = DESIGN_WIDTH * scale
        val contentLeft = safeBounds.left + (safeBounds.width - actualContentWidth) / 2f
        val contentRight = contentLeft + actualContentWidth
        val referenceHeight = DESIGN_HEIGHT * scale
        val extraHeight = max(0f, safeBounds.height - referenceHeight)

        // The original page has 297px of flexible space before the widget stack and 342px after
        // it. Preserve that optical balance on taller aspect ratios without enlarging controls.
        val flexibleTotal = WEATHER_TOP + BOTTOM_FLEX
        val topFlexibleSpace = WEATHER_TOP * scale + extraHeight * WEATHER_TOP / flexibleTotal
        val bottomFlexibleSpace = BOTTOM_FLEX * scale + extraHeight * BOTTOM_FLEX / flexibleTotal

        val contentBounds = Bounds(contentLeft, safeTop, contentRight, safeBottom)
        val widgetLeft = contentLeft + HORIZONTAL_PADDING * scale
        val widgetRight = contentRight - HORIZONTAL_PADDING * scale
        val weatherTop = safeTop + topFlexibleSpace
        val weather =
            Bounds(widgetLeft, weatherTop, widgetRight, weatherTop + WEATHER_HEIGHT * scale)
        val musicTop = weather.bottom + WEATHER_MUSIC_GAP * scale
        val music = Bounds(widgetLeft, musicTop, widgetRight, musicTop + MUSIC_HEIGHT * scale)
        val recorderTop = music.bottom + MUSIC_RECORDER_GAP * scale
        val recorder =
            Bounds(
                widgetLeft,
                recorderTop,
                widgetLeft + RECORDER_WIDTH * scale,
                recorderTop + RECORDER_HEIGHT * scale,
            )
        val torch =
            Bounds(
                recorder.right + TORCH_GAP * scale,
                recorder.top,
                widgetRight,
                recorder.bottom,
            )

        val iconSize = CORNER_ICON_SIZE * scale
        val iconMargin = CORNER_MARGIN * scale
        val shortcutBottom = safeBottom - iconMargin
        val widgetShortcut =
            Bounds(
                contentLeft + iconMargin,
                shortcutBottom - iconSize,
                contentLeft + iconMargin + iconSize,
                shortcutBottom,
            )
        val cameraShortcut =
            Bounds(
                contentRight - iconMargin - iconSize,
                shortcutBottom - iconSize,
                contentRight - iconMargin,
                shortcutBottom,
            )

        val selectorSize = QUICK_SELECTOR_ICON_SIZE * scale
        val selectorBottom = safeBottom - QUICK_SELECTOR_BOTTOM * scale
        val quickSelectorSlots =
            List(3) { index ->
                val centerX = contentLeft + actualContentWidth * (index * 2f + 1f) / 6f
                Bounds(
                    centerX - selectorSize / 2f,
                    selectorBottom - selectorSize,
                    centerX + selectorSize / 2f,
                    selectorBottom,
                )
            }
        val quickSelectorLabel =
            Bounds(
                contentLeft,
                quickSelectorSlots.first().top - QUICK_SELECTOR_LABEL_HEIGHT * scale,
                contentRight,
                quickSelectorSlots.first().top,
            )
        val quickPicker =
            Bounds(
                contentLeft,
                max(safeTop, safeBottom - QUICK_PICKER_HEIGHT * scale),
                contentRight,
                safeBottom,
            )

        val mainTimeBottom = safeBottom - MAIN_TIME_BOTTOM * scale
        val mainTime =
            Bounds(
                contentLeft,
                mainTimeBottom - MAIN_TIME_HEIGHT * scale,
                contentRight,
                mainTimeBottom,
            )
        val unlockSize = UNLOCK_HANDLE_SIZE * scale
        val unlockBottom = safeBottom - CORNER_MARGIN * scale
        val unlockHandle =
            Bounds(
                contentBounds.centerX - unlockSize / 2f,
                unlockBottom - unlockSize,
                contentBounds.centerX + unlockSize / 2f,
                unlockBottom,
            )

        val aodTime =
            Bounds(
                contentLeft + AOD_TIME_LEFT * scale,
                safeTop + AOD_TIME_TOP * scale,
                contentLeft + (AOD_TIME_LEFT + AOD_TIME_WIDTH) * scale,
                safeTop + (AOD_TIME_TOP + AOD_TIME_HEIGHT) * scale,
            )
        val aodSecond =
            Bounds(
                contentLeft + AOD_SECOND_LEFT * scale,
                safeTop + AOD_SECOND_TOP * scale,
                contentRight,
                safeTop + (AOD_SECOND_TOP + AOD_SECOND_HEIGHT) * scale,
            )
        var aodBottomTranslationY = max(0f, safeBottom - aodTime.bottom)
        input.udfpsBounds?.let { udfps ->
            val udfpsTop = udfps.top - AOD_UDFPS_CLEARANCE * scale
            aodBottomTranslationY =
                min(aodBottomTranslationY, max(0f, udfpsTop - aodTime.bottom))
        }

        val securityWidth = min(actualContentWidth, 888f * scale)
        val securityHeight = min(safeBounds.height, 1260f * scale)
        val securityContent =
            Bounds(
                safeBounds.centerX - securityWidth / 2f,
                safeBounds.centerY - securityHeight / 2f,
                safeBounds.centerX + securityWidth / 2f,
                safeBounds.centerY + securityHeight / 2f,
            )
        val credentialTop = safeBounds.top + (safeBounds.height - referenceHeight) / 2f
        val credentialCanvas =
            Bounds(contentLeft, credentialTop, contentRight, credentialTop + referenceHeight)
        fun credentialBounds(left: Float, top: Float, right: Float, bottom: Float): Bounds =
            Bounds(
                contentLeft + left * scale,
                credentialTop + top * scale,
                contentLeft + right * scale,
                credentialTop + bottom * scale,
            )
        val credentialMessage = credentialBounds(0f, 588f, DESIGN_WIDTH, 668f)
        val pinEntry = credentialBounds(141f, 740f, 939f, 860f)
        val pinKeyboard = credentialBounds(48f, 1300f, 1032f, 2164f)
        val patternGrid = credentialBounds(0f, 793f, DESIGN_WIDTH, 1873f)
        val passwordEntry = credentialBounds(141f, 704f, 939f, 836f)
        val simEntry = credentialBounds(141f, 646f, 939f, 778f)
        val pinRoot = Bounds(contentLeft, safeTop, contentRight, safeBottom)
        val chargingContent = Bounds(contentLeft, safeTop, contentRight, safeBottom)

        return Metrics(
            safeBounds = safeBounds,
            contentBounds = contentBounds,
            scale = scale,
            constrainedFontScale = input.fontScale.coerceIn(1f, 1.3f),
            topFlexibleSpace = topFlexibleSpace,
            bottomFlexibleSpace = bottomFlexibleSpace,
            minimumTouchTarget = MIN_TOUCH_TARGET_DP * input.density,
            mainTime = mainTime,
            widgetShortcut = widgetShortcut,
            cameraShortcut = cameraShortcut,
            quickSelectorSlots = quickSelectorSlots,
            quickSelectorLabel = quickSelectorLabel,
            quickPicker = quickPicker,
            weather = weather,
            music = music,
            recorder = recorder,
            torch = torch,
            unlockHandle = unlockHandle,
            aodTime = aodTime,
            aodSecond = aodSecond,
            aodBottomTranslationY = aodBottomTranslationY,
            securityContent = securityContent,
            credentialCanvas = credentialCanvas,
            credentialMessage = credentialMessage,
            pinEntry = pinEntry,
            pinKeyboard = pinKeyboard,
            patternGrid = patternGrid,
            passwordEntry = passwordEntry,
            simEntry = simEntry,
            credentialTextSize = 48f * scale * input.fontScale.coerceIn(1f, 1.3f),
            patternPathWidth = 10f * scale,
            pinRoot = pinRoot,
            chargingContent = chargingContent,
            horizontalPageThreshold = actualContentWidth * 0.18f,
            verticalDismissThreshold = safeBounds.height * 0.08f,
            wallpaperSwipeThreshold = WALLPAPER_SWIPE_THRESHOLD * scale,
            originalPageThreshold = 200f * scale,
            originalCameraThreshold = 400f * scale,
        )
    }
}
