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

import android.graphics.Rect
import android.view.DisplayCutout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
class StatusBarCutoutClassifierTest : SysuiTestCase() {
    @Test
    fun classify_nullOrEmpty_isNone() {
        assertThat(StatusBarCutoutClassifier.classify(null, 1080))
            .isEqualTo(StatusBarCutoutMode.NONE)
        val empty = mock(DisplayCutout::class.java)
        whenever(empty.isEmpty).thenReturn(true)
        assertThat(StatusBarCutoutClassifier.classify(empty, 1080))
            .isEqualTo(StatusBarCutoutMode.NONE)
    }

    @Test
    fun classify_rectCrossingCenter_isCenter() {
        assertThat(classify(Rect(500, 0, 580, 80), 1080))
            .isEqualTo(StatusBarCutoutMode.CENTER)
    }

    @Test
    fun classify_leftHole_isLeft() {
        assertThat(classify(Rect(20, 0, 120, 80), 1080)).isEqualTo(StatusBarCutoutMode.LEFT)
    }

    @Test
    fun classify_rightHole_isRight() {
        assertThat(classify(Rect(960, 0, 1060, 80), 1080)).isEqualTo(StatusBarCutoutMode.RIGHT)
    }

    private fun classify(bounds: Rect, width: Int): StatusBarCutoutMode {
        val cutout = mock(DisplayCutout::class.java)
        whenever(cutout.isEmpty).thenReturn(false)
        whenever(cutout.boundingRectTop).thenReturn(bounds)
        return StatusBarCutoutClassifier.classify(cutout, width)
    }
}
