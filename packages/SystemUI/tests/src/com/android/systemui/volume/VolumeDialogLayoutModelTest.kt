/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.volume

import android.graphics.Insets
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class VolumeDialogLayoutModelTest {
    @Test
    fun originalCanvas_matchesR2Geometry() {
        val result = VolumeDialogLayoutModel.calculate(1080, 2242, 2.5f, Insets.NONE, false)

        assertThat(result.scale).isEqualTo(1f)
        assertThat(result.columnWidth).isEqualTo(180)
        assertThat(result.panelHeight).isEqualTo(594)
        assertThat(result.topMargin).isEqualTo(441)
        assertThat(result.mainTop).isEqualTo(126)
        assertThat(result.muteHeight).isEqualTo(90)
        assertThat(result.expandTop).isEqualTo(756)
        assertThat(result.enterTranslation).isEqualTo(144)
        assertThat(result.shadowPaddingHorizontal).isEqualTo(41)
        assertThat(result.shadowPaddingVertical).isEqualTo(71)
        assertThat(result.muteEditorShift).isEqualTo(216)
        assertThat(result.timerHeight).isEqualTo(468)
        assertThat(result.cancelHeight).isEqualTo(126)
        assertThat(result.landscape).isFalse()
    }

    @Test
    fun supportedDisplays_stayInsideSafeHeightAndPreserveThreeColumnRatio() {
        val cases =
            listOf(
                Triple(720, 1600, 2f),
                Triple(1080, 1920, 3f),
                Triple(1080, 2460, 2.625f),
                Triple(1440, 3200, 4f),
                Triple(2208, 1840, 2.5f),
            )

        cases.forEach { (width, height, density) ->
            val result =
                VolumeDialogLayoutModel.calculate(
                    width,
                    height,
                    density,
                    Insets.of(12, 80, 24, 96),
                    false,
                )
            assertThat(result.columnWidth).isGreaterThan(0)
            assertThat(result.panelHeight).isGreaterThan(0)
            assertThat(result.topMargin).isAtLeast(80)
            assertThat(
                    result.topMargin +
                        result.expandTop +
                        result.muteHeight +
                        result.shadowPaddingVertical * 2
                )
                .isAtMost(height - 96)
            assertThat(result.columnWidth * 3).isAtMost((480f * density).toInt())
        }
    }

    @Test
    fun landscape_usesShortTopAnchorAndStillAvoidsInsets() {
        val result =
            VolumeDialogLayoutModel.calculate(
                2460,
                1080,
                2.625f,
                Insets.of(80, 24, 100, 36),
                true,
            )

        assertThat(result.topMargin).isAtLeast(24)
        assertThat(
                result.topMargin +
                    result.expandTop +
                    result.muteHeight +
                    result.shadowPaddingVertical * 2
            )
            .isAtMost(1080 - 36)
        assertThat(result.rightMargin).isAtLeast(100)
        assertThat(result.landscape).isTrue()
        // Landscape uses the original 30px top-anchored retail geometry, rather than scaling a
        // 2242px portrait canvas down to an unusably narrow panel.
        assertThat(result.scale).isGreaterThan(0.9f)
        assertThat(result.timerHeight + result.cancelHeight).isEqualTo(result.panelHeight)
    }
}
