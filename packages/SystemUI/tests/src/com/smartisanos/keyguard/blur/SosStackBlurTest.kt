/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.smartisanos.keyguard.blur

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SosStackBlurTest : SysuiTestCase() {
    @Test
    fun uniformBitmapRemainsUniformAndKeepsAlpha() {
        val source = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.argb(173, 40, 90, 140))

        SosStackBlur.blur(source, 4)

        assertThat(source.getPixel(4, 4)).isEqualTo(Color.argb(173, 40, 90, 140))
    }

    @Test
    fun impulseSpreadsAcrossNeighbouringPixels() {
        val source = Bitmap.createBitmap(9, 1, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        source.setPixel(4, 0, Color.WHITE)

        SosStackBlur.blur(source, 2)

        assertThat(Color.red(source.getPixel(3, 0))).isGreaterThan(0)
        assertThat(Color.red(source.getPixel(4, 0))).isLessThan(255)
        assertThat(Color.alpha(source.getPixel(4, 0))).isEqualTo(255)
    }
}
