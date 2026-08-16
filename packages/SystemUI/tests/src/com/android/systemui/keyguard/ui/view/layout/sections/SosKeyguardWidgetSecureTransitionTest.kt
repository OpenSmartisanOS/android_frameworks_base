/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.ui.view.layout.sections

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class SosKeyguardWidgetSecureTransitionTest {
    @Test
    fun pagePosition_matchesOriginalTPageMgrAtKeyProgressValues() {
        val height = 2400f
        val progressValues = listOf(0f, 0.1f, 0.5f, 0.9f, 1f)

        progressValues.forEach { progress ->
            val offset = progress * height / 3f
            assertThat(
                    SosKeyguardHostView.widgetSecurePagePosition(
                        offset = offset,
                        screenHeight = height,
                    )
                )
                .isWithin(0.0001f)
                .of(progress)
        }
    }

    @Test
    fun pagePosition_clampsOvershootAndRejectsNegativeDrag() {
        assertThat(SosKeyguardHostView.widgetSecurePagePosition(-100f, 2400f)).isEqualTo(0f)
        assertThat(SosKeyguardHostView.widgetSecurePagePosition(1200f, 2400f)).isEqualTo(1f)
    }

    @Test
    fun widgetSecurityTransition_pinsBlurWhileIndependentProgressesCross() {
        val crossingProgresses =
            listOf(
                1f to 0f,
                0.9f to 0.1f,
                0.5f to 0.5f,
                0.1f to 0.9f,
                0f to 1f,
            )

        crossingProgresses.forEach { (page, bouncer) ->
            assertThat(
                    SosKeyguardHostView.combinedBlurProgress(
                        pageProgress = page,
                        bouncerProgress = bouncer,
                        forceWidgetSecurityBlur = true,
                    )
                )
                .isEqualTo(1f)
        }
    }

    @Test
    fun ordinaryPages_continueUsingLargerPageOrBouncerBlur() {
        assertThat(SosKeyguardHostView.combinedBlurProgress(0.8f, 0.2f, false)).isEqualTo(0.8f)
        assertThat(SosKeyguardHostView.combinedBlurProgress(0.2f, 0.8f, false)).isEqualTo(0.8f)
    }
}
