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

package com.android.systemui.statusbar.policy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.systemui.res.R

/** Clock-area privacy presentation matching Smartisan OS 8.5.3 HighlightAlert. */
class PrivacyHighlightView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    enum class PrivacyKind(val backgroundColor: Int, val iconRes: Int, val clockColor: Int) {
        CAMERA(
            0xFFFFBF00.toInt(),
            R.drawable.permission_camera_icon,
            0xB3000000.toInt(),
        ),
        MICROPHONE(0xFFD44D44.toInt(), R.drawable.permission_recorder_icon, 0xFFFFFFFF.toInt()),
        SCREEN_RECORD(0xFFD44D44.toInt(), R.drawable.stat_sys_screen_record, 0xFFFFFFFF.toInt()),
        LOCATION(0xFF5079D6.toInt(), R.drawable.permission_gps_icon, 0xFFFFFFFF.toInt()),
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundBounds = RectF()
    private lateinit var clock: Clock
    private lateinit var privacyIcon: ImageView
    private var activeKind: PrivacyKind? = null
    private var requestedKind: PrivacyKind? = null
    private var backgroundProgress = 0f
    private var transition: AnimatorSet? = null
    private var animationGeneration = 0L
    private val revealClock =
        Runnable {
            val generation = animationGeneration
            if (activeKind == null || requestedKind != activeKind) return@Runnable
            transition?.cancel()
            clock.visibility = View.VISIBLE
            clock.scaleX = CLOCK_SCALE
            clock.scaleY = CLOCK_SCALE
            transition =
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(privacyIcon, View.ALPHA, privacyIcon.alpha, 0f),
                        ObjectAnimator.ofFloat(clock, View.ALPHA, clock.alpha, 1f),
                    )
                    duration = TRANSITION_DURATION_MS
                    interpolator = LinearInterpolator()
                    addGenerationListener(generation) {
                        privacyIcon.visibility = View.INVISIBLE
                        clock.alpha = 1f
                    }
                    start()
                }
        }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        privacyIcon = requireViewById(R.id.privacy_icon)
        bindClock()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bindClock()
    }

    private fun bindClock() {
        if (this::clock.isInitialized) return
        val found =
            (parent as? View)?.findViewById<Clock>(R.id.clock)
                ?: (rootView as? View)?.findViewById(R.id.clock)
        if (found != null) {
            clock = found
        }
    }

    fun setPrivacy(kind: PrivacyKind?, description: CharSequence?) {
        bindClock()
        if (!this::clock.isInitialized) return
        val safeDescription = description.takeIf { kind != null }
        val descriptionChanged = !TextUtils.equals(contentDescription, safeDescription)
        updateAccessibility(kind, safeDescription)
        if (kind == requestedKind) {
            if (descriptionChanged && kind != null && isAttachedToWindow) {
                sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            return
        }
        requestedKind = kind
        val generation = ++animationGeneration
        removeCallbacks(revealClock)
        transition?.cancel()
        transition = buildTransition(kind, generation).also(AnimatorSet::start)
        if (kind != null && isAttachedToWindow) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun buildTransition(kind: PrivacyKind?, generation: Long): AnimatorSet {
        val sequence = ArrayList<Animator>()
        if (activeKind != null) {
            sequence += permissionOutAnimator()
            sequence += clockInAnimator()
        }
        if (kind != null) {
            sequence += clockOutAnimator()
            sequence += permissionInAnimator(kind)
        }
        if (sequence.isEmpty()) {
            applyStableState(null)
            return AnimatorSet()
        }
        return AnimatorSet().apply {
            playSequentially(sequence)
            interpolator = LinearInterpolator()
            addGenerationListener(generation) {
                activeKind = kind
                applyStableState(kind)
                if (kind != null) postDelayed(revealClock, PERMISSION_ICON_DURATION_MS)
            }
        }
    }

    private fun clockOutAnimator(): Animator =
        ObjectAnimator.ofFloat(clock, View.ALPHA, clock.alpha, 0f).apply {
            duration = TRANSITION_DURATION_MS
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        clock.visibility = View.VISIBLE
                        clock.scaleX = 1f
                        clock.scaleY = 1f
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        clock.visibility = View.INVISIBLE
                    }
                }
            )
        }

    private fun clockInAnimator(): Animator =
        ObjectAnimator.ofFloat(clock, View.ALPHA, 0f, 1f).apply {
            duration = TRANSITION_DURATION_MS
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        // The factory controller restores the current host tint before the clock
                        // fades back in; otherwise camera's black clock flashes on bare wallpaper.
                        clock.setPrivacyColorOverride(null)
                        clock.visibility = View.VISIBLE
                        clock.alpha = 0f
                        clock.scaleX = 1f
                        clock.scaleY = 1f
                    }
                }
            )
        }

    private fun permissionInAnimator(kind: PrivacyKind): Animator {
        val progress = progressAnimator(backgroundProgress, 1f)
        val iconAlpha = ObjectAnimator.ofFloat(privacyIcon, View.ALPHA, 0f, 1f)
        val iconScaleX = ObjectAnimator.ofFloat(privacyIcon, View.SCALE_X, 0f, 1f)
        val iconScaleY = ObjectAnimator.ofFloat(privacyIcon, View.SCALE_Y, 0f, 1f)
        return AnimatorSet().apply {
            playTogether(progress, iconAlpha, iconScaleX, iconScaleY)
            duration = TRANSITION_DURATION_MS
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        activeKind = kind
                        paint.color = kind.backgroundColor
                        clock.setPrivacyColorOverride(kind.clockColor)
                        privacyIcon.setImageResource(kind.iconRes)
                        privacyIcon.visibility = View.VISIBLE
                        invalidate()
                    }
                }
            )
        }
    }

    private fun permissionOutAnimator(): Animator {
        val progress = progressAnimator(backgroundProgress, 0f)
        val iconAlpha = ObjectAnimator.ofFloat(privacyIcon, View.ALPHA, privacyIcon.alpha, 0f)
        val iconScaleX = ObjectAnimator.ofFloat(privacyIcon, View.SCALE_X, privacyIcon.scaleX, 0f)
        val iconScaleY = ObjectAnimator.ofFloat(privacyIcon, View.SCALE_Y, privacyIcon.scaleY, 0f)
        val clockAlpha = ObjectAnimator.ofFloat(clock, View.ALPHA, clock.alpha, 0f)
        val clockScaleX = ObjectAnimator.ofFloat(clock, View.SCALE_X, clock.scaleX, 0f)
        val clockScaleY = ObjectAnimator.ofFloat(clock, View.SCALE_Y, clock.scaleY, 0f)
        return AnimatorSet().apply {
            playTogether(
                progress,
                iconAlpha,
                iconScaleX,
                iconScaleY,
                clockAlpha,
                clockScaleX,
                clockScaleY,
            )
            duration = TRANSITION_DURATION_MS
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        privacyIcon.visibility = View.INVISIBLE
                    }
                }
            )
        }
    }

    private fun progressAnimator(from: Float, to: Float): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            addUpdateListener {
                backgroundProgress = it.animatedValue as Float
                invalidate()
            }
        }

    private fun applyStableState(kind: PrivacyKind?) {
        if (kind == null) {
            activeKind = null
            clock.setPrivacyColorOverride(null)
            backgroundProgress = 0f
            privacyIcon.visibility = View.INVISIBLE
            privacyIcon.alpha = 0f
            privacyIcon.scaleX = 0f
            privacyIcon.scaleY = 0f
            clock.visibility = View.VISIBLE
            clock.alpha = 1f
            clock.scaleX = 1f
            clock.scaleY = 1f
        } else {
            activeKind = kind
            paint.color = kind.backgroundColor
            clock.setPrivacyColorOverride(kind.clockColor)
            backgroundProgress = 1f
            privacyIcon.setImageResource(kind.iconRes)
            privacyIcon.visibility = View.VISIBLE
            privacyIcon.alpha = 1f
            privacyIcon.scaleX = 1f
            privacyIcon.scaleY = 1f
            clock.visibility = View.INVISIBLE
            clock.alpha = 0f
            clock.scaleX = 1f
            clock.scaleY = 1f
        }
        invalidate()
    }

    private fun AnimatorSet.addGenerationListener(generation: Long, onEnd: () -> Unit) {
        var cancelled = false
        addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && generation == animationGeneration) onEnd()
                }
            }
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeKind == null) return
        paint.alpha = (255 * backgroundProgress).toInt().coerceIn(0, 255)
        val centerX = width / 2f
        val centerY = height / 2f
        val halfWidth = centerX * backgroundProgress
        val halfHeight = height / 4f * backgroundProgress
        val radius = halfHeight
        backgroundBounds.set(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )
        canvas.drawRoundRect(backgroundBounds, radius, radius, paint)
    }

    private fun updateAccessibility(kind: PrivacyKind?, description: CharSequence?) {
        val active = kind != null
        contentDescription = if (active) description else null
        isClickable = active
        isFocusable = active
        importantForAccessibility =
            if (active) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clock.importantForAccessibility =
            if (active) View.IMPORTANT_FOR_ACCESSIBILITY_NO
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revealClock)
        transition?.cancel()
        transition = null
        animationGeneration++
        requestedKind = null
        applyStableState(null)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val CLOCK_SCALE = 0.77f
        private const val PERMISSION_ICON_DURATION_MS = 5_000L
        private const val TRANSITION_DURATION_MS = 200L
    }
}
