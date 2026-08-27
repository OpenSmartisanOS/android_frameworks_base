/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.phone

import android.graphics.Insets
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class StatusBarGeometryTest {
    @Test
    fun r2Width_longBar_usesOriginal39And51PixelGeometry() {
        val result = StatusBarGeometry.calculate(1080, 2.5f, 96)

        assertThat(result.longBar).isTrue()
        assertThat(result.iconHeight).isEqualTo(39)
        assertThat(result.batteryWidth).isEqualTo(51)
        assertThat(result.itemMarginStart).isEqualTo(6)
        assertThat(result.itemMarginEnd).isEqualTo(7)
        assertThat(result.tickerPaddingStart).isEqualTo(result.contentMarginStart)
        assertThat(result.tickerPaddingEnd).isEqualTo(result.contentMarginEnd)
    }

    @Test
    fun shortBar_usesOriginal32And42PixelGeometry() {
        val result = StatusBarGeometry.calculate(1080, 2.5f, 63)

        assertThat(result.longBar).isFalse()
        assertThat(result.iconHeight).isEqualTo(32)
        assertThat(result.batteryWidth).isEqualTo(42)
    }

    @Test
    fun foldable_capsContentColumnAt480Dp() {
        val result = StatusBarGeometry.calculate(2208, 2.625f, 126)

        assertThat(result.scale).isWithin(0.001f).of(1260f / 1080f)
        assertThat(result.iconHeight).isEqualTo(46)
    }

    @Test
    fun safeInsets_reduceAvailableWidthWithoutDeviceSpecialCase() {
        val result =
            StatusBarGeometry.calculate(
                720,
                2f,
                56,
                Insets.of(30, 0, 30, 0),
            )

        assertThat(result.scale).isWithin(0.001f).of(660f / 1080f)
        assertThat(result.iconHeight).isAtLeast(20)
        assertThat(result.tickerPaddingStart).isEqualTo(result.contentMarginStart)
        assertThat(result.tickerPaddingEnd).isEqualTo(result.contentMarginEnd)
    }
}
