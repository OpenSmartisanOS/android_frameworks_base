/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.android.systemui.res.R
import java.util.Locale
import kotlin.math.max

/** Live 256-point waveform compatible with the original R2 recorder widget geometry. */
open class SosR2RecorderWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val samples = FloatArray(SAMPLE_COUNT)
    private val marks = ArrayDeque<Int>()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val timePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC4C4C4.toInt()
            textAlign = Paint.Align.RIGHT
        }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var elapsedSeconds = 0L
    private var writeIndex = 0

    init {
        setBackgroundResource(R.drawable.lockscreen_recorder_wave_bg)
    }

    fun setSmartisanTypeface(typeface: Typeface) {
        timePaint.typeface = typeface
        invalidate()
    }

    fun setElapsedSeconds(seconds: Long) {
        elapsedSeconds = max(0L, seconds)
        invalidate()
    }

    fun addAmplitude(amplitude: Int) {
        samples[writeIndex % SAMPLE_COUNT] = (amplitude / 32767f).coerceIn(0.02f, 1f)
        writeIndex++
        invalidate()
    }

    fun addMark() {
        marks.addLast(writeIndex)
        while (marks.size > MAX_VISIBLE_MARKS) marks.removeFirst()
        invalidate()
    }

    fun reset() {
        samples.fill(0f)
        marks.clear()
        elapsedSeconds = 0L
        writeIndex = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val spacing = width / SAMPLE_COUNT.toFloat()
        wavePaint.strokeWidth = max(1f, spacing * 0.72f)
        val centerY = height / 2f
        val maximumHalfHeight = height * 0.38f
        for (offset in 0 until SAMPLE_COUNT) {
            val sampleIndex = (writeIndex - 1 - offset).floorMod(SAMPLE_COUNT)
            val halfHeight = samples[sampleIndex] * maximumHalfHeight
            val x = width - offset * spacing
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, wavePaint)
        }

        markPaint.textAlign = Paint.Align.CENTER
        markPaint.textSize = height * 0.17f
        val markPath = Path()
        marks.forEachIndexed { index, samplePosition ->
            val age = writeIndex - samplePosition
            val x = width - age * spacing
            if (x >= 0f) {
                markPath.reset()
                markPath.moveTo(x, 0f)
                markPath.lineTo(x - height * 0.08f, height * 0.14f)
                markPath.lineTo(x + height * 0.08f, height * 0.14f)
                markPath.close()
                canvas.drawPath(markPath, markPaint)
                canvas.drawText((index + 1).toString(), x, height * 0.30f, markPaint)
            }
        }

        timePaint.textSize = height * 0.22f
        canvas.drawText(formatElapsed(elapsedSeconds), width - height * 0.10f, height * 0.27f, timePaint)
    }

    private fun formatElapsed(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds / 60) % 60
        val remainder = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainder)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remainder)
        }
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private companion object {
        const val SAMPLE_COUNT = 256
        const val MAX_VISIBLE_MARKS = 99
    }
}
