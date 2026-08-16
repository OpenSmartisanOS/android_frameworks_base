/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class SosKeyguardWallpaperCacheTest {
    private lateinit var directory: File
    private lateinit var cache: SosKeyguardWallpaperCache

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "sos-wallpaper-${System.nanoTime()}")
        cache = SosKeyguardWallpaperCache(directory, createDirectory = true)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun saveAndLoad_preservesPixelsLosslessly() {
        val source = bitmap(Color.rgb(12, 34, 56), Color.rgb(210, 180, 90))

        assertThat(cache.save(0, source)).isTrue()
        val restored = cache.load(0)

        assertThat(restored).isNotNull()
        assertThat(restored!!.getPixel(0, 0)).isEqualTo(source.getPixel(0, 0))
        assertThat(restored.getPixel(1, 0)).isEqualTo(source.getPixel(1, 0))
        source.recycle()
        restored.recycle()
    }

    @Test
    fun cache_isIsolatedByUserId() {
        val owner = bitmap(Color.RED, Color.RED)
        val secondary = bitmap(Color.BLUE, Color.BLUE)

        assertThat(cache.save(0, owner)).isTrue()
        assertThat(cache.save(10, secondary)).isTrue()

        val restoredOwner = cache.load(0)!!
        val restoredSecondary = cache.load(10)!!
        assertThat(restoredOwner.getPixel(0, 0)).isEqualTo(Color.RED)
        assertThat(restoredSecondary.getPixel(0, 0)).isEqualTo(Color.BLUE)
        owner.recycle()
        secondary.recycle()
        restoredOwner.recycle()
        restoredSecondary.recycle()
    }

    @Test
    fun corruptCache_isRejectedWithoutThrowing() {
        directory.mkdirs()
        cache.fileForUser(0).writeBytes(byteArrayOf(1, 2, 3, 4))

        assertThat(cache.load(0)).isNull()
    }

    private fun bitmap(first: Int, second: Int): Bitmap =
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, first)
            setPixel(1, 0, second)
        }
}
