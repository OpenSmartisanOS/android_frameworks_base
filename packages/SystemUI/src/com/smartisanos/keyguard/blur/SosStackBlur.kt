/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.smartisanos.keyguard.blur

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Cross-ABI, in-place implementation of the two-pass stack blur used by R2's libblur. */
internal object SosStackBlur {
    fun blur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1 || bitmap.width < 1 || bitmap.height < 1) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val red = IntArray(pixels.size)
        val green = IntArray(pixels.size)
        val blue = IntArray(pixels.size)
        val div = radius * 2 + 1
        val radiusPlusOne = radius + 1
        val divSum = radiusPlusOne * radiusPlusOne
        val divide = IntArray(256 * divSum) { it / divSum }
        val stack = Array(div) { IntArray(3) }
        val vMin = IntArray(max(width, height))

        var yi = 0
        var yw = 0
        for (y in 0 until height) {
            var redIn = 0
            var greenIn = 0
            var blueIn = 0
            var redOut = 0
            var greenOut = 0
            var blueOut = 0
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            for (i in -radius..radius) {
                val pixel = pixels[yi + min(width - 1, max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = pixel shr 16 and 0xff
                sir[1] = pixel shr 8 and 0xff
                sir[2] = pixel and 0xff
                val weight = radiusPlusOne - kotlin.math.abs(i)
                redSum += sir[0] * weight
                greenSum += sir[1] * weight
                blueSum += sir[2] * weight
                if (i > 0) {
                    redIn += sir[0]
                    greenIn += sir[1]
                    blueIn += sir[2]
                } else {
                    redOut += sir[0]
                    greenOut += sir[1]
                    blueOut += sir[2]
                }
            }
            var stackPointer = radius
            for (x in 0 until width) {
                red[yi] = divide[redSum]
                green[yi] = divide[greenSum]
                blue[yi] = divide[blueSum]
                redSum -= redOut
                greenSum -= greenOut
                blueSum -= blueOut
                var stackStart = stackPointer - radius + div
                var sir = stack[stackStart % div]
                redOut -= sir[0]
                greenOut -= sir[1]
                blueOut -= sir[2]
                if (y == 0) vMin[x] = min(x + radiusPlusOne, width - 1)
                val pixel = pixels[yw + vMin[x]]
                sir[0] = pixel shr 16 and 0xff
                sir[1] = pixel shr 8 and 0xff
                sir[2] = pixel and 0xff
                redIn += sir[0]
                greenIn += sir[1]
                blueIn += sir[2]
                redSum += redIn
                greenSum += greenIn
                blueSum += blueIn
                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer]
                redOut += sir[0]
                greenOut += sir[1]
                blueOut += sir[2]
                redIn -= sir[0]
                greenIn -= sir[1]
                blueIn -= sir[2]
                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            var redIn = 0
            var greenIn = 0
            var blueIn = 0
            var redOut = 0
            var greenOut = 0
            var blueOut = 0
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            var yp = -radius * width
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = red[yi]
                sir[1] = green[yi]
                sir[2] = blue[yi]
                val weight = radiusPlusOne - kotlin.math.abs(i)
                redSum += red[yi] * weight
                greenSum += green[yi] * weight
                blueSum += blue[yi] * weight
                if (i > 0) {
                    redIn += sir[0]
                    greenIn += sir[1]
                    blueIn += sir[2]
                } else {
                    redOut += sir[0]
                    greenOut += sir[1]
                    blueOut += sir[2]
                }
                if (i < height - 1) yp += width
            }
            yi = x
            var stackPointer = radius
            for (y in 0 until height) {
                pixels[yi] =
                    (pixels[yi] and -0x1000000) or
                        (divide[redSum] shl 16) or
                        (divide[greenSum] shl 8) or
                        divide[blueSum]
                redSum -= redOut
                greenSum -= greenOut
                blueSum -= blueOut
                var stackStart = stackPointer - radius + div
                var sir = stack[stackStart % div]
                redOut -= sir[0]
                greenOut -= sir[1]
                blueOut -= sir[2]
                if (x == 0) vMin[y] = min(y + radiusPlusOne, height - 1) * width
                val p = x + vMin[y]
                sir[0] = red[p]
                sir[1] = green[p]
                sir[2] = blue[p]
                redIn += sir[0]
                greenIn += sir[1]
                blueIn += sir[2]
                redSum += redIn
                greenSum += greenIn
                blueSum += blueIn
                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer]
                redOut += sir[0]
                greenOut += sir[1]
                blueOut += sir[2]
                redIn -= sir[0]
                greenIn -= sir[1]
                blueIn -= sir[2]
                yi += width
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
