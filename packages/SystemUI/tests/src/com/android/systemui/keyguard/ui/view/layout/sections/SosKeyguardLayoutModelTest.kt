/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardLayoutModel.Input
import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardLayoutModel.Insets
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SosKeyguardLayoutModelTest {
    @Test
    fun originalCanvas_matchesOriginalGeometry() {
        val metrics = SosKeyguardLayoutModel.calculate(Input(1080f, 2242f, density = 3.5f))

        assertThat(metrics.scale).isEqualTo(1f)
        assertThat(metrics.weather.left).isEqualTo(96f)
        assertThat(metrics.weather.top).isEqualTo(297f)
        assertThat(metrics.weather.height).isEqualTo(280f)
        assertThat(metrics.music.top).isEqualTo(580f)
        assertThat(metrics.music.width).isEqualTo(888f)
        assertThat(metrics.music.height).isEqualTo(888f)
        assertThat(metrics.recorder.left).isEqualTo(96f)
        assertThat(metrics.recorder.top).isEqualTo(1516f)
        assertThat(metrics.recorder.width).isEqualTo(600f)
        assertThat(metrics.recorder.height).isEqualTo(384f)
        assertThat(metrics.torch.left).isEqualTo(744f)
        assertThat(metrics.torch.width).isEqualTo(240f)
        assertThat(metrics.quickSelectorSlots.map { it.centerX })
            .containsExactly(180f, 540f, 900f)
            .inOrder()
        assertThat(metrics.quickSelectorSlots.all { it.width == 153f }).isTrue()
    }

    @Test
    fun tallPhone_usesWidthScaleAndOnlyExpandsFlexibleSpace() {
        val metrics = SosKeyguardLayoutModel.calculate(Input(1080f, 2460f, density = 2.625f))

        assertThat(metrics.scale).isEqualTo(1f)
        assertThat(metrics.music.width).isEqualTo(888f)
        assertThat(metrics.music.height).isEqualTo(888f)
        assertThat(metrics.topFlexibleSpace).isGreaterThan(297f)
        assertThat(metrics.bottomFlexibleSpace).isGreaterThan(342f)
        assertThat(metrics.recorder.bottom).isLessThan(metrics.unlockHandle.top)
    }

    @Test
    fun shortPhone_scalesWholeSceneToFitHeight() {
        val metrics = SosKeyguardLayoutModel.calculate(Input(1080f, 1920f, density = 3f))

        assertThat(metrics.scale).isWithin(0.0001f).of(1920f / 2242f)
        assertThat(metrics.weather.top).isWithin(0.001f).of(297f * metrics.scale)
        assertThat(metrics.recorder.bottom).isAtMost(1920f)
        assertThat(metrics.unlockHandle.bottom).isAtMost(1920f)
    }

    @Test
    fun largeWidth_capsContentAt480dpAndCentersIt() {
        val metrics = SosKeyguardLayoutModel.calculate(Input(2200f, 2800f, density = 2f))

        assertThat(metrics.contentBounds.width).isEqualTo(960f)
        assertThat(metrics.contentBounds.left).isEqualTo(620f)
        assertThat(metrics.contentBounds.right).isEqualTo(1580f)
    }

    @Test
    fun cutoutAndNavigationInsets_keepInteractiveContentInsideSafeBounds() {
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1080f,
                    2400f,
                    density = 3f,
                    insets = Insets(left = 24f, top = 96f, right = 12f, bottom = 120f),
                )
            )

        assertThat(metrics.widgetShortcut.left).isAtLeast(metrics.safeBounds.left)
        assertThat(metrics.cameraShortcut.right).isAtMost(metrics.safeBounds.right)
        assertThat(metrics.weather.top).isAtLeast(metrics.safeBounds.top)
        assertThat(metrics.unlockHandle.bottom).isAtMost(metrics.safeBounds.bottom)
    }

    @Test
    fun fontScale_isBoundedForActionableLabels() {
        val small = SosKeyguardLayoutModel.calculate(Input(1080f, 2400f, 3f, fontScale = .8f))
        val large = SosKeyguardLayoutModel.calculate(Input(1080f, 2400f, 3f, fontScale = 2f))

        assertThat(small.constrainedFontScale).isEqualTo(1f)
        assertThat(large.constrainedFontScale).isEqualTo(1.3f)
    }

    @Test
    fun supportedPhoneMatrix_neverOverlapsOrLeavesSafeArea() {
        val cases =
            listOf(
                Input(720f, 1600f, density = 2f),
                Input(1080f, 1920f, density = 3f),
                Input(1080f, 2340f, density = 3f, insets = Insets(top = 90f, bottom = 90f)),
                Input(1080f, 2460f, density = 3.5f, insets = Insets(left = 18f, top = 96f)),
                Input(1440f, 3200f, density = 4f, insets = Insets(right = 30f, bottom = 126f)),
            )

        cases.forEach { input ->
            val metrics = SosKeyguardLayoutModel.calculate(input)
            assertInside(metrics.weather, metrics.safeBounds)
            assertInside(metrics.music, metrics.safeBounds)
            assertInside(metrics.recorder, metrics.safeBounds)
            assertInside(metrics.torch, metrics.safeBounds)
            assertInside(metrics.widgetShortcut, metrics.safeBounds)
            assertInside(metrics.cameraShortcut, metrics.safeBounds)
            metrics.quickSelectorSlots.forEach { assertInside(it, metrics.safeBounds) }
            assertInside(metrics.quickPicker, metrics.safeBounds)
            assertInside(metrics.unlockHandle, metrics.safeBounds)
            assertThat(metrics.weather.bottom).isAtMost(metrics.music.top)
            assertThat(metrics.music.bottom).isAtMost(metrics.recorder.top)
            assertThat(metrics.recorder.right).isAtMost(metrics.torch.left)
            assertThat(metrics.minimumTouchTarget).isAtLeast(48f * input.density)
        }
    }

    @Test
    fun waterfallAndAsymmetricCutouts_centerContentInsideRemainingSafeWidth() {
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1440f,
                    3200f,
                    density = 3f,
                    insets = Insets(left = 80f, top = 120f, right = 24f, bottom = 150f),
                )
            )

        assertThat(metrics.contentBounds.centerX).isWithin(.001f).of(metrics.safeBounds.centerX)
        assertThat(metrics.contentBounds.width).isAtMost(480f * 3f)
    }

    @Test
    fun foldableInnerDisplay_capsAt480dpAndDoesNotScaleCardsToPanelWidth() {
        val metrics = SosKeyguardLayoutModel.calculate(Input(2208f, 1840f, density = 2.5f))

        assertThat(metrics.contentBounds.width).isAtMost(1200f)
        assertThat(metrics.scale).isAtMost(1840f / SosKeyguardLayoutModel.DESIGN_HEIGHT)
        assertThat(metrics.recorder.bottom).isAtMost(metrics.safeBounds.bottom)
    }

    @Test
    fun aodBottomPosition_staysAboveUdfpsExclusion() {
        val udfps = SosKeyguardLayoutModel.Bounds(420f, 2050f, 660f, 2290f)
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1080f,
                    2460f,
                    density = 3f,
                    scene = SosKeyguardLayoutModel.Scene.AOD,
                    udfpsBounds = udfps,
                )
            )

        assertThat(metrics.aodTime.bottom + metrics.aodBottomTranslationY)
            .isLessThan(udfps.top)
    }

    @Test
    fun originalAod_usesThe400DpiDreamGeometry() {
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1080f,
                    2242f,
                    density = 2.5f,
                    scene = SosKeyguardLayoutModel.Scene.AOD,
                )
            )

        assertThat(metrics.aodTime.left).isEqualTo(90f)
        assertThat(metrics.aodTime.top).isEqualTo(6f)
        assertThat(metrics.aodSecond.left).isEqualTo(545f)
        assertThat(metrics.aodSecond.top).isEqualTo(155f)
    }

    @Test
    fun stableSystemBars_anchorMainTimeAboveNavigationRegion() {
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1080f,
                    2460f,
                    density = 2.625f,
                    insets = Insets(top = 76f, bottom = 63f),
                )
            )

        assertThat(metrics.scale).isEqualTo(1f)
        assertThat(metrics.mainTime.bottom).isEqualTo(2358f)
        assertThat(metrics.mainTime.top).isEqualTo(2241f)
    }

    @Test
    fun v60GesturalSurface_matchesR2BottomAnchorsWithoutHiddenHandleInset() {
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1080f,
                    2460f,
                    density = 2.625f,
                    insets = Insets(top = 76f),
                )
            )

        assertThat(metrics.scale).isEqualTo(1f)
        assertThat(metrics.mainTime.bottom).isEqualTo(2421f)
        assertThat(metrics.widgetShortcut.bottom).isEqualTo(2430f)
        assertThat(metrics.cameraShortcut.bottom).isEqualTo(2430f)
        assertThat(metrics.weather.top).isEqualTo(439f)
        assertThat(metrics.music.top).isEqualTo(722f)
        assertThat(metrics.recorder.top).isEqualTo(1658f)
    }

    @Test
    fun originalCredentialCanvas_matchesR2SecurityCoordinates() {
        val metrics =
            SosKeyguardLayoutModel.calculate(
                Input(
                    1080f,
                    2242f,
                    density = 3f,
                    scene = SosKeyguardLayoutModel.Scene.PIN,
                )
            )

        assertThat(metrics.credentialCanvas.left).isEqualTo(0f)
        assertThat(metrics.credentialCanvas.top).isEqualTo(0f)
        assertThat(metrics.credentialMessage.top).isEqualTo(588f)
        assertThat(metrics.pinEntry.left).isEqualTo(141f)
        assertThat(metrics.pinKeyboard.bottom).isEqualTo(2164f)
        assertThat(metrics.patternGrid.top).isEqualTo(793f)
        assertThat(metrics.patternPathWidth).isEqualTo(10f)
        assertThat(metrics.originalPageThreshold).isEqualTo(200f)
        assertThat(metrics.originalCameraThreshold).isEqualTo(400f)
    }

    @Test
    fun credentialMatrix_staysCenteredAndInsideSafeArea() {
        val cases =
            listOf(
                Input(720f, 1600f, 2f, scene = SosKeyguardLayoutModel.Scene.PIN),
                Input(1080f, 1920f, 3f, scene = SosKeyguardLayoutModel.Scene.PATTERN),
                Input(1080f, 2460f, 3f, scene = SosKeyguardLayoutModel.Scene.PASSWORD),
                Input(1440f, 3200f, 4f, scene = SosKeyguardLayoutModel.Scene.SIM_PUK),
                Input(2208f, 1840f, 2.5f, scene = SosKeyguardLayoutModel.Scene.SIM_PIN),
            )

        cases.forEach { input ->
            val metrics = SosKeyguardLayoutModel.calculate(input)
            assertInside(metrics.credentialCanvas, metrics.safeBounds)
            assertInside(metrics.credentialMessage, metrics.credentialCanvas)
            assertInside(metrics.pinEntry, metrics.credentialCanvas)
            assertInside(metrics.pinKeyboard, metrics.credentialCanvas)
            assertInside(metrics.patternGrid, metrics.credentialCanvas)
            assertThat(metrics.credentialCanvas.centerX)
                .isWithin(.001f)
                .of(metrics.safeBounds.centerX)
        }
    }

    private fun assertInside(
        child: SosKeyguardLayoutModel.Bounds,
        parent: SosKeyguardLayoutModel.Bounds,
    ) {
        assertThat(child.left).isAtLeast(parent.left)
        assertThat(child.top).isAtLeast(parent.top)
        assertThat(child.right).isAtMost(parent.right)
        assertThat(child.bottom).isAtMost(parent.bottom)
    }
}
