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
package com.android.systemui.shade

import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class NotificationShadeBackgroundModelTest {
    @Test
    fun originalWidth_usesFixedOriginalBlurRadiusAtEveryExpandedHeight() {
        val quarter = NotificationShadeBackgroundModel.calculate(1080, 2.5f, 600f, 2400f, true, true)
        val half = NotificationShadeBackgroundModel.calculate(1080, 2.5f, 1200f, 2400f, true, true)
        val full = NotificationShadeBackgroundModel.calculate(1080, 2.5f, 2400f, 2400f, true, true)

        assertThat(quarter.blurRadius).isEqualTo(73)
        assertThat(half.blurRadius).isEqualTo(quarter.blurRadius)
        assertThat(full.blurRadius).isEqualTo(quarter.blurRadius)
        assertThat(quarter.blurBounds.bottom).isEqualTo(600)
        assertThat(half.blurBounds.bottom).isEqualTo(1200)
        assertThat(full.blurBounds.bottom).isEqualTo(2400)
    }

    @Test
    fun cropTracksPhysicalEdgeAndClampsToPanelHeight() {
        val collapsed = NotificationShadeBackgroundModel.calculate(1080, 2.5f, 0f, 2400f, true, true)
        val overshot = NotificationShadeBackgroundModel.calculate(1080, 2.5f, 3000f, 2400f, true, true)

        assertThat(collapsed.blurBounds.bottom).isEqualTo(0)
        assertThat(collapsed.blurVisible).isFalse()
        assertThat(overshot.blurBounds.bottom).isEqualTo(2400)
        assertThat(overshot.expansionFraction).isEqualTo(1f)
    }

    @Test
    fun scrimAlpha_matchesOriginalCosineCurve() {
        assertThat(NotificationShadeBackgroundModel.calculateScrimAlpha(0f)).isEqualTo(0f)
        assertThat(NotificationShadeBackgroundModel.calculateScrimAlpha(0.25f))
            .isWithin(0.0001f)
            .of(0.0536f)
        assertThat(NotificationShadeBackgroundModel.calculateScrimAlpha(0.5f))
            .isWithin(0.0001f)
            .of(0.4420f)
        assertThat(NotificationShadeBackgroundModel.calculateScrimAlpha(1f)).isEqualTo(0.62f)
    }

    @Test
    fun unavailableBlur_keepsScrimAndCropButDoesNotExposeBlurDrawable() {
        val result = NotificationShadeBackgroundModel.calculate(1080, 2.5f, 1200f, 2400f, true, false)

        assertThat(result.blurVisible).isFalse()
        assertThat(result.blurBounds.bottom).isEqualTo(1200)
        assertThat(result.scrimAlpha).isGreaterThan(0f)
    }

    @Test
    fun wideDisplay_capsRadiusUsing480DpContentColumn() {
        val result = NotificationShadeBackgroundModel.calculate(2208, 2.625f, 1000f, 2400f, true, true)

        assertThat(result.blurRadius).isEqualTo(85)
    }

    @Test
    fun calculateInto_reusesStateAndBoundsAcrossFrames() {
        val result = NotificationShadeBackgroundModel.State()
        val bounds = result.blurBounds

        NotificationShadeBackgroundModel.calculateInto(result, 1080, 2.5f, 600f, 2400f, true, true)
        assertThat(result.blurBounds).isSameInstanceAs(bounds)
        assertThat(result.blurBounds.bottom).isEqualTo(600)

        NotificationShadeBackgroundModel.calculateInto(result, 1080, 2.5f, 1200f, 2400f, true, true)
        assertThat(result.blurBounds).isSameInstanceAs(bounds)
        assertThat(result.blurBounds.bottom).isEqualTo(1200)
    }
}
