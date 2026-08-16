/*
 * Copyright (C) 2026 OpenSmartisanOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:Suppress("unused")

package com.smartisanos.keyguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.android.systemui.res.R

/**
 * Android 16 compatibility root for the original R2 keyguard XML.
 *
 * Authentication deliberately does not live here.  The modern SystemUI host owns lifecycle and
 * security; this class only preserves the original XML class name and drawing contract.
 */
class KeyguardHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    init {
        clipChildren = false
        clipToPadding = false
    }
}

/** Applies the original R2 DST_OUT mask to the complete card, including its own background. */
class RoundCornerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val erasePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = false
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    private var maskResourceId = R.drawable.tpage_round_corner_mask
    private var maskIsNinePatch = true
    private var maskBitmap: Bitmap? = null

    /** Retained for the responsive host; the original nine-patch remains the rendering source. */
    fun setCornerRadius(radius: Float) {
        if (radius >= 0f && width > 0 && height > 0 && maskBitmap == null) rebuildMask(width, height)
    }

    fun setRoundCornerMaskResId(resourceId: Int) {
        if (resourceId <= 0 || maskResourceId == resourceId && !maskIsNinePatch) return
        maskResourceId = resourceId
        maskIsNinePatch = false
        if (width > 0 && height > 0) rebuildMask(width, height)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            super.draw(canvas)
            return
        }
        if (maskBitmap == null) rebuildMask(width, height)
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), layerPaint)
        super.draw(canvas)
        maskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, erasePaint) }
        canvas.restoreToCount(checkpoint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) rebuildMask(w, h)
    }

    override fun onDetachedFromWindow() {
        recycleMask()
        super.onDetachedFromWindow()
    }

    private fun rebuildMask(width: Int, height: Int) {
        recycleMask()
        if (width <= 0 || height <= 0) return
        val source =
            BitmapFactory.decodeResource(resources, maskResourceId)
                ?: return
        maskBitmap =
            if (maskIsNinePatch && NinePatch.isNinePatchChunk(source.ninePatchChunk)) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                    NinePatch(source, source.ninePatchChunk, null)
                        .draw(Canvas(target), RectF(0f, 0f, width.toFloat(), height.toFloat()))
                    source.recycle()
                }
            } else if (source.width == width && source.height == height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true).also { source.recycle() }
            }
    }

    private fun recycleMask() {
        maskBitmap?.takeUnless { it.isRecycled }?.recycle()
        maskBitmap = null
    }
}
