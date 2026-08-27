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

import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.phone.ui.SystemIconsController.HostAppearance
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
@RunWithLooper(setAsMainLooper = true)
class StatusBarAppearanceControllerTest : SysuiTestCase() {
    private lateinit var controller: StatusBarAppearanceController

    @Before
    fun setUp() {
        controller =
            StatusBarAppearanceController()
    }

    @Test
    fun appearanceFor_panel_usesSeventyPercentWhite() {
        val appearance =
            controller.appearanceFor(
                host = HostAppearance.PANEL,
                cutoutMode = StatusBarCutoutMode.NONE,
                homeTint = DarkIconDispatcher.DEFAULT_ICON_TINT,
                homeForeground = DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT,
                forceLight = false,
            )
        assertThat(appearance.monochromeTint).isEqualTo(StatusBarAppearanceController.PANEL_TINT)
        assertThat(appearance.foregroundTint).isEqualTo(StatusBarAppearanceController.PANEL_TINT)
        assertThat(appearance.colorIconsEnabled).isFalse()
    }

    @Test
    fun appearanceFor_homeDefaultTint_keepsFactoryMonochromeArtwork() {
        val appearance =
            controller.appearanceFor(
                host = HostAppearance.HOME,
                cutoutMode = StatusBarCutoutMode.NONE,
                homeTint = DarkIconDispatcher.DEFAULT_ICON_TINT,
                homeForeground = DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT,
                forceLight = false,
            )
        assertThat(appearance.colorIconsEnabled).isFalse()
    }

    @Test
    fun appearanceFor_homeDarkTint_disablesColorArtwork() {
        val appearance =
            controller.appearanceFor(
                host = HostAppearance.HOME,
                cutoutMode = StatusBarCutoutMode.NONE,
                homeTint = 0xFF000000.toInt(),
                homeForeground = 0xFFFFFFFF.toInt(),
                forceLight = false,
            )
        assertThat(appearance.colorIconsEnabled).isFalse()
    }

    @Test
    fun appearanceFor_home_keepsSystemBarTint() {
        val appearance =
            controller.appearanceFor(
                host = HostAppearance.HOME,
                cutoutMode = StatusBarCutoutMode.NONE,
                homeTint = 0xFF112233.toInt(),
                homeForeground = 0xFF445566.toInt(),
                forceLight = false,
            )
        assertThat(appearance.monochromeTint).isEqualTo(0xFF112233.toInt())
        assertThat(appearance.foregroundTint).isEqualTo(0xFF445566.toInt())
    }

    @Test
    fun appearanceFor_forceLight_overridesHostTint() {
        val appearance =
            controller.appearanceFor(
                host = HostAppearance.HOME,
                cutoutMode = StatusBarCutoutMode.CENTER,
                homeTint = 0xFF000000.toInt(),
                homeForeground = 0xFFFFFFFF.toInt(),
                forceLight = true,
            )
        assertThat(appearance.monochromeTint).isEqualTo(StatusBarAppearanceController.PANEL_TINT)
        assertThat(appearance.colorIconsEnabled).isFalse()
    }

    @Test
    fun systemIconsView_darkChange_updatesRightSideInOneHostState() {
        val root = SystemIconsView(context)
        val otg = ImageView(context).apply { id = com.android.systemui.res.R.id.otg }
        val speed = TextView(context).apply { id = com.android.systemui.res.R.id.net_speed }
        root.addView(otg)
        root.addView(speed)

        root.onDarkChangedWithContrast(arrayListOf(), Color.BLACK, Color.WHITE)

        assertThat(root.currentTint()).isEqualTo(Color.BLACK)
        assertThat(root.currentForegroundTint()).isEqualTo(Color.WHITE)
        assertThat(otg.imageTintList?.defaultColor).isEqualTo(Color.BLACK)
        assertThat(speed.currentTextColor).isEqualTo(Color.BLACK)
    }

    @Test
    fun systemIconsView_keyguardOverride_restoresLatestApplicationTint() {
        val root = SystemIconsView(context)
        root.onDarkChangedWithContrast(arrayListOf(), Color.BLACK, Color.WHITE)
        root.setKeyguardTintOverride(Color.WHITE, Color.BLACK)

        root.onDarkChangedWithContrast(arrayListOf(), 0xFF334455.toInt(), 0xFFCCDDEE.toInt())
        assertThat(root.currentTint()).isEqualTo(Color.WHITE)
        assertThat(root.currentForegroundTint()).isEqualTo(Color.BLACK)

        root.setKeyguardTintOverride(null, null)
        assertThat(root.currentTint()).isEqualTo(0xFF334455.toInt())
        assertThat(root.currentForegroundTint()).isEqualTo(0xFFCCDDEE.toInt())
    }

    @Test
    fun apply_panelAppearance_commitsWholeSystemIconsHost() {
        val root = SystemIconsView(context)
        val unit = TextView(context).apply { id = com.android.systemui.res.R.id.net_unit }
        root.addView(unit)
        val appearance =
            controller.appearanceFor(
                host = HostAppearance.PANEL,
                cutoutMode = StatusBarCutoutMode.NONE,
                homeTint = Color.BLACK,
                homeForeground = Color.WHITE,
                forceLight = false,
            )

        controller.apply(root, appearance)

        assertThat(root.currentTint()).isEqualTo(StatusBarAppearanceController.PANEL_TINT)
        assertThat(unit.currentTextColor).isEqualTo(StatusBarAppearanceController.PANEL_TINT)
    }
}
