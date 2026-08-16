/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.android.keyguard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure translation of the R2 KeyguardHostView/SecureViewWrapper gesture geometry. */
object SosBouncerVisualCoordinator {
    data class State(
        val blurProgress: Float,
        val securityTranslationY: Float,
        val securityAlpha: Float,
        val hostTranslationY: Float,
        val timeAlpha: Float,
        val shortcutTranslation: Float,
        val pinTranslationY: Float,
    )

    enum class SettleTarget {
        MAIN,
        BOUNCER,
    }

    @JvmStatic
    @JvmOverloads
    fun calculate(
        offsetY: Float,
        screenHeight: Float,
        shortcutTravel: Float = ORIGINAL_SHORTCUT_TRAVEL_PX,
        pinTravel: Float = ORIGINAL_PIN_TRAVEL_PX,
    ): State {
        if (screenHeight <= 0f) return State(0f, 0f, 0f, 0f, 1f, 0f, 0f)
        val tripleOffset = offsetY * 3f
        val blur = min(abs(tripleOffset) / screenHeight, 1f)
        // MainPageAnimHelper compresses the complete time/shortcut transition into the first
        // 10 percent of the security-page progress. This deliberately is not a linear fade.
        val mainProgress = min(blur / MAIN_PAGE_TRANSITION_FRACTION, 1f)
        val timeAlpha = sqrt(1f - mainProgress)
        val securityY: Float
        val securityAlpha: Float
        when {
            offsetY == 0f -> {
                securityY = screenHeight
                securityAlpha = 0f
            }
            offsetY > 0f -> {
                securityY = max(0f, 0.2f * (screenHeight - tripleOffset))
                securityAlpha = min(tripleOffset / screenHeight, 1f)
            }
            else -> {
                securityY = offsetY
                securityAlpha = 1f
            }
        }
        return State(
            blurProgress = blur,
            securityTranslationY = securityY,
            securityAlpha = securityAlpha,
            hostTranslationY = min(0f, offsetY),
            timeAlpha = timeAlpha,
            shortcutTranslation = shortcutTravel * mainProgress,
            pinTranslationY = -pinTravel * mainProgress,
        )
    }

    /** Matches KeyguardHostView.handleActionUpEvent(). */
    @JvmStatic
    fun settleTarget(
        offsetY: Float,
        velocityY: Float,
        screenHeight: Float,
        scale: Float = 1f,
    ): SettleTarget {
        val upwardMinimum = ORIGINAL_UPWARD_FLING_MIN_DISTANCE_PX * scale
        val velocityThreshold = ORIGINAL_FLING_VELOCITY_PX_PER_SECOND * scale
        return when {
            velocityY > velocityThreshold -> SettleTarget.MAIN
            velocityY < -velocityThreshold && offsetY > upwardMinimum -> SettleTarget.BOUNCER
            offsetY < screenHeight / ORIGINAL_DISTANCE_THRESHOLD_DIVISOR -> SettleTarget.MAIN
            else -> SettleTarget.BOUNCER
        }
    }

    /** Matches snapToTop(): velocity-derived duration clamped to 250..600ms. */
    @JvmStatic
    fun showDurationMillis(
        offsetY: Float,
        velocityY: Float,
        screenHeight: Float,
        scale: Float = 1f,
    ): Long {
        val targetScrollerY = -ORIGINAL_UNLOCK_OVERSHOOT_PX * scale
        val currentScrollerY = screenHeight - offsetY
        val remaining = targetScrollerY - currentScrollerY
        val effectiveVelocity =
            if (velocityY < 0f) {
                velocityY
            } else {
                // The original supplies this synthetic velocity for a distance-triggered settle.
                (-ORIGINAL_SLIDE_BAR_HEIGHT_PX * scale - currentScrollerY) * 2f
            }
        return abs(remaining * ORIGINAL_TOP_VELOCITY_FACTOR / effectiveVelocity.coerceAtMost(-1f))
            .coerceIn(ORIGINAL_TOP_DURATION_MIN_MS, ORIGINAL_TOP_DURATION_MAX_MS)
            .toLong()
    }

    /** Matches snapToBottom()/snapToBottomNature(), including the 300ms upper bound. */
    @JvmStatic
    fun hideDurationMillis(offsetY: Float, velocityY: Float, scale: Float = 1f): Long {
        val velocityThreshold = ORIGINAL_FLING_VELOCITY_PX_PER_SECOND * scale
        val duration =
            if (velocityY > velocityThreshold) {
                min(
                    sqrt(abs(offsetY * ORIGINAL_SNAP_DOWN_RATIO)),
                    abs(offsetY * ORIGINAL_BOTTOM_VELOCITY_FACTOR / velocityY),
                )
            } else {
                abs(offsetY).pow(0.25f) * ORIGINAL_NATURAL_REBOUND_FACTOR
            }
        return min(duration, ORIGINAL_BOTTOM_DURATION_MAX_MS).toLong()
    }

    @JvmStatic
    fun completedOffset(screenHeight: Float, scale: Float = 1f): Float =
        screenHeight + ORIGINAL_UNLOCK_OVERSHOOT_PX * scale

    /**
     * Exact R2 TPageMgr alpha-layer lookup. The original allocates one entry per physical row,
     * truncates the division, and clamps overshoot to the last (alpha=1) entry.
     */
    @JvmStatic
    fun noSecurityRevealAlpha(offsetY: Float, screenHeight: Int): Int {
        if (screenHeight <= 0) return 255
        val index = offsetY.toInt().coerceIn(0, screenHeight - 1)
        return 255 - ((index / screenHeight.toFloat()) * 255f).toInt()
    }

    const val ORIGINAL_IGNORE_MOVE_PX = 60f
    private const val ORIGINAL_SHORTCUT_TRAVEL_PX = 150f
    private const val ORIGINAL_PIN_TRAVEL_PX = 90f
    private const val MAIN_PAGE_TRANSITION_FRACTION = 0.1f
    private const val ORIGINAL_FLING_VELOCITY_PX_PER_SECOND = 700f
    private const val ORIGINAL_UPWARD_FLING_MIN_DISTANCE_PX = 100f
    private const val ORIGINAL_DISTANCE_THRESHOLD_DIVISOR = 4f
    private const val ORIGINAL_SLIDE_BAR_HEIGHT_PX = 157f
    private const val ORIGINAL_UNLOCK_OVERSHOOT_PX = 200f
    private const val ORIGINAL_TOP_VELOCITY_FACTOR = 1200f
    private const val ORIGINAL_BOTTOM_VELOCITY_FACTOR = 1500f
    private const val ORIGINAL_SNAP_DOWN_RATIO = 500f
    private const val ORIGINAL_NATURAL_REBOUND_FACTOR = 79.7699966430664f
    private const val ORIGINAL_TOP_DURATION_MIN_MS = 250f
    private const val ORIGINAL_TOP_DURATION_MAX_MS = 600f
    private const val ORIGINAL_BOTTOM_DURATION_MAX_MS = 300f
}
