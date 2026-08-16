/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.smartisanos.keyguard.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Surface
import android.widget.ImageView
import com.smartisanos.keyguard.wallpaper.SosWallpaperCrop
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure Kotlin reproduction of R2 BlurView + StackBlur without the vendor ARM libblur.so. */
class BlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val radiusHistory = ArrayDeque<Int>(RADIUS_HISTORY_SIZE)
    private var worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var progress = 0f
    private var prepared = false
    private var originalCrop: Bitmap? = null
    private var blurFrames = arrayOfNulls<Bitmap>(MAX_RADIUS + 1)
    private var previousRadius = 0
    private var sourceBitmap: Bitmap? = null
    private var sourceRelease: ((Bitmap) -> Unit)? = null
    private var sourceCopyComplete = true

    fun setOrgBitmap(bitmap: Bitmap?, onSourceReleased: ((Bitmap) -> Unit)? = null) {
        val previousSource = sourceBitmap
        val previousRelease = sourceRelease
        val previousCopyComplete = sourceCopyComplete
        sourceBitmap = bitmap
        sourceRelease = onSourceReleased
        sourceCopyComplete = bitmap == null
        generation++
        setImageBitmap(null)
        prepared = false
        recycleOwnedBitmaps()
        if (previousSource != null && previousCopyComplete) {
            previousRelease?.invoke(previousSource)
        }
        if (bitmap == null || bitmap.isRecycled) {
            invalidate()
            return
        }
        schedulePrepare()
    }

    private fun schedulePrepare() {
        val bitmap = sourceBitmap?.takeUnless { it.isRecycled } ?: return
        val targetWidth = width
        val targetHeight = height
        // The old implementation coerced an unmeasured view to 1x1.  That crop could win the
        // asynchronous race and be stretched for the first keyguard frame.
        if (targetWidth <= 0 || targetHeight <= 0) {
            sourceCopyComplete = true
            return
        }
        val targetRotation = display?.rotation ?: Surface.ROTATION_0
        sourceCopyComplete = false
        val request = ++generation
        val releaseSource = sourceRelease
        ensureWorker()
        worker.execute {
            var crop: Bitmap? = null
            var thumb: Bitmap? = null
            var frames = arrayOfNulls<Bitmap>(MAX_RADIUS + 1)
            runCatching {
                    crop = SosWallpaperCrop.createOwnedCrop(bitmap, targetWidth, targetHeight)
                    thumb =
                        Bitmap.createScaledBitmap(
                            crop!!,
                            max(1, crop!!.width / IMAGE_SCALE_DIVISOR),
                            max(1, crop!!.height / IMAGE_SCALE_DIVISOR),
                            true,
                        )
                    for (radius in 1..MAX_RADIUS) {
                        frames[radius] =
                            thumb!!.copy(Bitmap.Config.ARGB_8888, true).also {
                                SosStackBlur.blur(it, radius)
                            }
                    }
                }
                .onFailure { error ->
                    android.util.Log.w(TAG, "Unable to prepare R2 blur frames", error)
                }
            thumb?.takeUnless { it.isRecycled }?.recycle()
            post {
                if (
                    request != generation ||
                        sourceBitmap !== bitmap ||
                        width != targetWidth ||
                        height != targetHeight ||
                        (display?.rotation ?: Surface.ROTATION_0) != targetRotation ||
                        !isAttachedToWindow
                ) {
                    crop?.takeUnless { it.isRecycled }?.recycle()
                    frames.forEach { frame -> frame?.takeUnless { it.isRecycled }?.recycle() }
                    if (sourceBitmap !== bitmap) releaseSource?.invoke(bitmap)
                    return@post
                }
                sourceCopyComplete = true
                if (crop == null || frames.none { it != null }) {
                    prepared = false
                    applyProgress()
                    return@post
                }
                recycleOwnedBitmaps()
                originalCrop = crop
                blurFrames = frames
                previousRadius = 0
                radiusHistory.clear()
                prepared = true
                applyProgress()
            }
        }
    }

    fun setBlurProgress(value: Float) {
        progress = abs(value).coerceIn(0f, 1f).let { if (it < MIN_PROGRESS) 0f else it }
        applyProgress()
    }

    fun clearCachedBlurProgress() {
        radiusHistory.clear()
        previousRadius = 0
    }

    fun cleanup() {
        val releaseCurrent = sourceBitmap
        val releaseCallback = sourceRelease
        val releaseImmediately = sourceCopyComplete
        generation++
        sourceBitmap = null
        sourceRelease = null
        sourceCopyComplete = true
        setImageBitmap(null)
        prepared = false
        progress = 0f
        recycleOwnedBitmaps()
        if (releaseCurrent != null && releaseImmediately) {
            releaseCallback?.invoke(releaseCurrent)
        }
        worker.shutdown()
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            generation++
            setImageBitmap(null)
            prepared = false
            recycleOwnedBitmaps()
            schedulePrepare()
        }
    }

    override fun onDraw(canvas: Canvas) {
        runCatching { super.onDraw(canvas) }
        if (progress > 0f) {
            originalCrop?.takeUnless { it.isRecycled }?.let { original ->
                clearPaint.alpha = clearImageAlpha(progress)
                if (clearPaint.alpha > 0) {
                    canvas.drawBitmap(
                        original,
                        Rect(0, 0, original.width, original.height),
                        Rect(0, 0, canvas.width, canvas.height),
                        clearPaint,
                    )
                }
            }
        }
        val darkAlpha = (ORIGINAL_MAX_DARK_ALPHA * min(progress * 3f, 1f)).toInt()
        if (darkAlpha > 0) canvas.drawColor(Color.argb(darkAlpha, 0, 0, 0))
    }

    private fun applyProgress() {
        if (!prepared || progress <= 0f) {
            setImageBitmap(null)
            visibility = if (progress > 0f) VISIBLE else INVISIBLE
            invalidate()
            return
        }
        val rawRadius =
            (MAX_RADIUS * (min(progress, MAX_PROGRESS) / MAX_PROGRESS)).toInt().coerceIn(1, MAX_RADIUS)
        radiusHistory.addFirst(rawRadius)
        while (radiusHistory.size > RADIUS_HISTORY_SIZE) radiusHistory.removeLast()
        val radius = radiusHistory.sum() / radiusHistory.size
        if (radius != previousRadius) {
            previousRadius = radius
        }
        val frame = blurFrames[radius] ?: blurFrames.take(radius + 1).lastOrNull { it != null }
        setImageBitmap(frame)
        visibility = VISIBLE
        invalidate()
    }

    private fun clearImageAlpha(value: Float): Int =
        if (value < CLEAR_IMAGE_THRESHOLD) {
            ((1f - value / CLEAR_IMAGE_THRESHOLD) * 255f).toInt()
        } else {
            0
        }

    private fun recycleOwnedBitmaps() {
        setImageBitmap(null)
        originalCrop?.takeUnless { it.isRecycled }?.recycle()
        blurFrames.forEach { bitmap -> bitmap?.takeUnless { it.isRecycled }?.recycle() }
        originalCrop = null
        blurFrames = arrayOfNulls(MAX_RADIUS + 1)
    }

    private fun ensureWorker() {
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
    }

    companion object {
        private const val IMAGE_SCALE_DIVISOR = 8
        private const val RADIUS_HISTORY_SIZE = 5
        private const val MAX_RADIUS = 25
        private const val MAX_PROGRESS = 0.4f
        private const val CLEAR_IMAGE_THRESHOLD = 0.08f
        private const val ORIGINAL_MAX_DARK_ALPHA = 76.5f
        private const val MIN_PROGRESS = 0.01f
        private const val TAG = "R2BlurView"
    }
}
