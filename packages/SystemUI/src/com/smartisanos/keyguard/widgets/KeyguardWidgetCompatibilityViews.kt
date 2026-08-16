/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

@file:Suppress("unused")

package com.smartisanos.keyguard.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.android.systemui.keyguard.ui.view.layout.sections.SosR2RecorderWaveView
import com.android.systemui.res.R
import com.smartisanos.keyguard.wallpaper.SosWallpaperCrop

/** Wallpaper surface used by the original host hierarchy. */
class KeyguardViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {
    private val wallpaperPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wallpaperBitmap: Bitmap? = null

    override fun setImageBitmap(bitmap: Bitmap?) {
        wallpaperBitmap = bitmap
        // Do not let ImageView cache a matrix derived from a previous keyguard size.
        super.setImageDrawable(null)
        invalidate()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        wallpaperBitmap = null
        super.setImageDrawable(drawable)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = wallpaperBitmap
        val crop =
            bitmap
                ?.takeUnless { it.isRecycled }
                ?.let { SosWallpaperCrop.sourceRect(it.width, it.height, canvas.width, canvas.height) }
        if (bitmap == null || crop == null) {
            super.onDraw(canvas)
            return
        }
        canvas.drawBitmap(
            bitmap,
            crop,
            Rect(0, 0, canvas.width, canvas.height),
            wallpaperPaint,
        )
    }
}

class TPageMusicWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr)

class TPageRecorderWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr)

/** Android 16 implementation of the original recorder clock referenced by the R2 XML. */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val digitDrawables = SparseArray<Drawable>(10)

    private lateinit var hourColon: ImageView
    private lateinit var hourFirst: ImageView
    private lateinit var hourSecond: ImageView
    private lateinit var minuteFirst: ImageView
    private lateinit var minuteSecond: ImageView
    private lateinit var secondFirst: ImageView
    private lateinit var secondSecond: ImageView
    private lateinit var millSecondFirst: ImageView
    private lateinit var millSecondSecond: ImageView
    private lateinit var millSecondPoint: ImageView

    private var hourFirstValue = 0
    private var hourSecondValue = 0
    private var minuteFirstValue = 0
    private var minuteSecondValue = 0
    private var secondFirstValue = 0
    private var secondSecondValue = 0
    private var millSecondFirstValue = 0
    private var millSecondSecondValue = 0

    @Volatile private var lastElapsedTimeMs = 0L

    init {
        DIGIT_RESOURCES.forEachIndexed { digit, resourceId ->
            context.getDrawable(resourceId)?.let { digitDrawables.put(digit, it) }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        hourSecond = requireViewById(R.id.hour_second)
        hourFirst = requireViewById(R.id.hour_first)
        minuteSecond = requireViewById(R.id.minute_second)
        minuteFirst = requireViewById(R.id.minute_first)
        secondSecond = requireViewById(R.id.second_second)
        secondFirst = requireViewById(R.id.second_first)
        millSecondSecond = requireViewById(R.id.mill_second)
        millSecondFirst = requireViewById(R.id.mill_first)
        hourColon = requireViewById(R.id.hour_colon)
        millSecondPoint = requireViewById(R.id.mill_second_point)
    }

    fun reset() = timeChanged(0L)

    fun reset(elapsedTimeMs: Long) = timeChanged(elapsedTimeMs)

    fun getLastElapsedTime(): Long = lastElapsedTimeMs

    fun timeChanged(elapsedTimeMs: Long) {
        val elapsed = parseElapsedTime(elapsedTimeMs)
        lastElapsedTimeMs = elapsedTimeMs
        val moreThanHour = elapsed[HOUR_TENS] > 0 || elapsed[HOUR_ONES] > 0

        if (moreThanHour) {
            millSecondFirst.visibility = View.GONE
            millSecondSecond.visibility = View.GONE
            millSecondPoint.visibility = View.GONE
        } else {
            millSecondFirst.visibility = View.VISIBLE
            millSecondSecond.visibility = View.VISIBLE
            millSecondPoint.visibility = View.VISIBLE
            if (millSecondFirstValue != elapsed[MILLISECOND_TENS]) {
                millSecondFirst.setImageDrawable(digitDrawables[elapsed[MILLISECOND_TENS]])
                millSecondFirstValue = elapsed[MILLISECOND_TENS]
            }
            if (millSecondSecondValue != elapsed[MILLISECOND_HUNDREDS]) {
                millSecondSecond.setImageDrawable(digitDrawables[elapsed[MILLISECOND_HUNDREDS]])
                millSecondSecondValue = elapsed[MILLISECOND_HUNDREDS]
            }
        }

        if (secondFirstValue != elapsed[SECOND_ONES]) {
            secondFirst.setImageDrawable(digitDrawables[elapsed[SECOND_ONES]])
            secondFirstValue = elapsed[SECOND_ONES]
        }
        if (secondSecondValue != elapsed[SECOND_TENS]) {
            secondSecond.setImageDrawable(digitDrawables[elapsed[SECOND_TENS]])
            secondSecondValue = elapsed[SECOND_TENS]
        }
        if (minuteFirstValue != elapsed[MINUTE_ONES]) {
            minuteFirst.setImageDrawable(digitDrawables[elapsed[MINUTE_ONES]])
            minuteFirstValue = elapsed[MINUTE_ONES]
        }
        if (minuteSecondValue != elapsed[MINUTE_TENS]) {
            minuteSecond.setImageDrawable(digitDrawables[elapsed[MINUTE_TENS]])
            minuteSecondValue = elapsed[MINUTE_TENS]
        }

        if (!moreThanHour) {
            hourFirst.visibility = View.GONE
            hourSecond.visibility = View.GONE
            hourColon.visibility = View.GONE
            return
        }

        hourFirst.visibility = View.VISIBLE
        hourSecond.visibility = View.VISIBLE
        hourColon.visibility = View.VISIBLE
        if (hourFirstValue != elapsed[HOUR_ONES]) {
            hourFirst.setImageDrawable(digitDrawables[elapsed[HOUR_ONES]])
            hourFirstValue = elapsed[HOUR_ONES]
        }
        if (hourSecondValue != elapsed[HOUR_TENS]) {
            hourSecond.setImageDrawable(digitDrawables[elapsed[HOUR_TENS]])
            hourSecondValue = elapsed[HOUR_TENS]
        }
    }

    companion object {
        private const val HOUR_TENS = 0
        private const val HOUR_ONES = 1
        private const val MINUTE_TENS = 2
        private const val MINUTE_ONES = 3
        private const val SECOND_TENS = 4
        private const val SECOND_ONES = 5
        private const val MILLISECOND_HUNDREDS = 6
        private const val MILLISECOND_TENS = 7

        private val DIGIT_RESOURCES =
            intArrayOf(
                R.drawable.no0,
                R.drawable.no1,
                R.drawable.no2,
                R.drawable.no3,
                R.drawable.no4,
                R.drawable.no5,
                R.drawable.no6,
                R.drawable.no7,
                R.drawable.no8,
                R.drawable.no9,
            )

        private fun parseElapsedTime(elapsedTimeMs: Long): IntArray {
            val elapsed = IntArray(8)
            if (elapsedTimeMs <= 0L) return elapsed

            val milliseconds = (elapsedTimeMs % 1_000L).toInt()
            elapsed[MILLISECOND_TENS] = (milliseconds / 10) % 10
            elapsed[MILLISECOND_HUNDREDS] = milliseconds / 100

            val seconds = ((elapsedTimeMs / 1_000L) % 60L).toInt()
            elapsed[SECOND_ONES] = seconds % 10
            elapsed[SECOND_TENS] = seconds / 10

            val minutes = ((elapsedTimeMs / 60_000L) % 60L).toInt()
            elapsed[MINUTE_ONES] = minutes % 10
            elapsed[MINUTE_TENS] = minutes / 10

            val hours = (elapsedTimeMs / 3_600_000L).toInt()
            elapsed[HOUR_ONES] = hours % 10
            elapsed[HOUR_TENS] = hours / 10
            return elapsed
        }

        @JvmStatic
        fun toSeconds(elapsedTimeMs: Long): Long {
            val elapsed = parseElapsedTime(elapsedTimeMs)
            var seconds = elapsed[HOUR_TENS] * 10L * 3_600L
            seconds += elapsed[HOUR_ONES] * 3_600L
            seconds += elapsed[MINUTE_TENS] * 10L * 60L
            seconds += elapsed[MINUTE_ONES] * 60L
            seconds += elapsed[SECOND_TENS] * 10L
            seconds += elapsed[SECOND_ONES]
            if (
                elapsedTimeMs > 0L &&
                    (elapsed[MILLISECOND_HUNDREDS] > 0 || elapsed[MILLISECOND_TENS] > 0)
            ) {
                seconds++
            }
            return seconds
        }

        /** The legacy analytics event is intentionally omitted from SystemUI. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun onRecordTimeEvent(elapsedTimeMs: Long) = Unit
    }
}

class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SosR2RecorderWaveView(context, attrs, defStyleAttr)

open class RepeatingImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageButton(context, attrs, defStyleAttr) {
    private var repeatAction: Runnable? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                repeatAction = Runnable {
                    if (isPressed) {
                        performClick()
                        postDelayed(repeatAction, REPEAT_INTERVAL_MS)
                    }
                }.also { postDelayed(it, INITIAL_REPEAT_DELAY_MS) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatAction?.let(::removeCallbacks)
                repeatAction = null
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        repeatAction?.let(::removeCallbacks)
        repeatAction = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val INITIAL_REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 150L
    }
}

class ProgressableButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageButton(context, attrs, defStyleAttr) {
    fun setProgress(progress: Float) {
        alpha = 0.45f + 0.55f * progress.coerceIn(0f, 1f)
    }
}

class MarqueeableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {
    override fun isFocused(): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isSelected = true
    }
}
