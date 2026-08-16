/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.smartisanos.keyguard.wallpaper

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * One center-crop definition shared by the sharp R2 wallpaper and its blurred copy.
 *
 * ImageView's matrix is updated after measurement and can therefore lag a keyguard re-attach by
 * one frame.  R2 draws both layers from this immutable source rectangle instead, so a wallpaper
 * generation can never be sharp-cropped one way and blurred-cropped another way.
 */
object SosWallpaperCrop {
    fun sourceRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Rect? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val targetAspect = targetWidth.toDouble() / targetHeight
        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect > targetAspect) {
            cropHeight = sourceHeight
            cropWidth = (cropHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
        } else {
            cropWidth = sourceWidth
            cropHeight = (cropWidth / targetAspect).roundToInt().coerceIn(1, sourceHeight)
        }
        val left = (sourceWidth - cropWidth) / 2
        val top = (sourceHeight - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    /** Returns an owned bitmap which callers may recycle without touching [source]. */
    fun createOwnedCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? {
        val crop = sourceRect(source.width, source.height, targetWidth, targetHeight) ?: return null
        val result = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        return if (result === source) source.copy(Bitmap.Config.ARGB_8888, false) else result
    }
}
