/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Source-compatible rendering of the R2 TPage battery, authored in the 327 x 174 design box. */
open class SosR2BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()
    private var level = 0
    private var charging = false
    private var typeface: Typeface? = null

    fun setBatteryState(level: Int, charging: Boolean) {
        val clamped = level.coerceIn(0, 100)
        if (this.level == clamped && this.charging == charging) return
        this.level = clamped
        this.charging = charging
        invalidate()
    }

    fun setSmartisanTypeface(typeface: Typeface) {
        this.typeface = typeface
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate((width - DESIGN_WIDTH * scale) / 2f, 0f)
        canvas.scale(scale, scale)

        val color =
            when {
                level <= 10 -> COLOR_LOW
                else -> COLOR_NORMAL
            }
        paint.color = color
        paint.strokeWidth = 6f
        paint.style = Paint.Style.FILL

        // Exact small-mode geometry from TPageBatteryView.drawBattery().  The original draws in
        // the top 90px of its 327x174 container; vertically centering it changes the weather
        // card's optical baseline.
        val shell = RectF(175f, 4f, 323f, 88f)
        val cap = RectF(166f, 35.5f, 178f, 56.5f)
        canvas.drawRoundRect(cap, 3f, 3f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(shell, 18f, 18f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 102
        canvas.drawRoundRect(shell, 18f, 18f, paint)
        paint.alpha = 255

        paint.typeface = typeface ?: Typeface.DEFAULT_BOLD
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = if (level == 100) 60f else 66f
        paint.color = Color.argb(204, 255, 255, 255)
        val label = level.toString()
        paint.getTextBounds(label, 0, label.length, textBounds)
        val textX = 170f + (151f - textBounds.width()) / 2f
        val textY = (90f + textBounds.height()) / 2f + 1f
        canvas.drawText(label, textX, textY, paint)
        canvas.restore()
    }

    private companion object {
        const val DESIGN_WIDTH = 327f
        const val DESIGN_HEIGHT = 174f
        const val COLOR_NORMAL = -8201941
        const val COLOR_LOW = -1225150
    }
}
