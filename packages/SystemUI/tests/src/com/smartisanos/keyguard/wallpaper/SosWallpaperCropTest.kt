/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.smartisanos.keyguard.wallpaper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SosWallpaperCropTest {
    @Test
    fun tallTarget_cropsSourceHorizontallyAroundCenter() {
        val crop = SosWallpaperCrop.sourceRect(1440, 2560, 1080, 2460)!!

        assertThat(crop.left).isEqualTo(158)
        assertThat(crop.right).isEqualTo(1282)
        assertThat(crop.top).isEqualTo(0)
        assertThat(crop.bottom).isEqualTo(2560)
    }

    @Test
    fun wideTarget_cropsSourceVerticallyAroundCenter() {
        val crop = SosWallpaperCrop.sourceRect(1080, 2460, 1920, 1080)!!

        assertThat(crop.left).isEqualTo(0)
        assertThat(crop.right).isEqualTo(1080)
        assertThat(crop.top).isEqualTo(926)
        assertThat(crop.bottom).isEqualTo(1534)
    }

    @Test
    fun zeroTarget_returnsNullInsteadOfProducingOnePixelCrop() {
        assertThat(SosWallpaperCrop.sourceRect(1080, 2460, 0, 0)).isNull()
    }
}
