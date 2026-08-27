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

package com.android.systemui.statusbar.phone.ui

import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.StatusBarAppearance
import com.android.systemui.statusbar.phone.StatusBarAppearanceController
import com.android.systemui.statusbar.phone.StatusBarCarrierTextController
import com.android.systemui.statusbar.phone.StatusBarCutoutMode
import com.android.systemui.statusbar.phone.ui.SystemIconsController.HostAppearance
import com.android.systemui.statusbar.phone.ui.SystemIconsController.HomeKeyguardThemeListener
import com.google.common.truth.Truth.assertThat
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
class SystemIconsControllerTest : SysuiTestCase() {
    private val platformController = mock<StatusBarIconController>()
    private val appearance = mock<StatusBarAppearanceController>()
    private val carrierTextController = mock<StatusBarCarrierTextController>()
    private val home = mock<IconManager>()
    private val panel = mock<IconManager>()
    private lateinit var underTest: SystemIconsController

    @Before
    fun setup() {
        whenever(
                appearance.appearanceFor(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                )
            )
            .thenReturn(
                StatusBarAppearance(
                    0,
                    0,
                    true,
                    HostAppearance.HOME,
                    StatusBarCutoutMode.NONE,
                )
            )
        underTest =
            SystemIconsController(platformController, appearance, carrierTextController)
    }

    @Test
    fun registerHosts_eachIndependentViewReceivesSharedState() {
        underTest.registerHost(home, HostAppearance.HOME)
        underTest.registerHost(panel, HostAppearance.PANEL)

        verify(platformController).addIconGroup(home)
        verify(platformController).addIconGroup(panel)
    }

    @Test
    fun registerHost_twice_doesNotDuplicateGroup() {
        underTest.registerHost(home, HostAppearance.HOME)
        underTest.registerHost(home, HostAppearance.HOME)

        verify(platformController, times(1)).addIconGroup(home)
    }

    @Test
    fun unregisterHost_removesOnlyRegisteredView() {
        underTest.registerHost(home, HostAppearance.HOME)
        underTest.unregisterHost(home)
        underTest.unregisterHost(panel)

        verify(platformController).removeIconGroup(home)
        verify(platformController, never()).removeIconGroup(panel)
    }

    @Test
    fun reRegisterAfterDetach_addsFreshViewGroup() {
        underTest.registerHost(panel, HostAppearance.PANEL)
        underTest.unregisterHost(panel)
        underTest.registerHost(panel, HostAppearance.PANEL)

        verify(platformController, times(2)).addIconGroup(panel)
        verify(platformController).removeIconGroup(panel)
    }

    @Test
    fun homeThemeListener_onlyActivatesForPresentedKeyguard() {
        var lastState = false to false
        val listener = HomeKeyguardThemeListener { active, supportsDarkText ->
            lastState = active to supportsDarkText
        }
        underTest.addHomeKeyguardThemeListener(listener)

        underTest.setKeyguardWallpaperTheme(true)
        assertThat(lastState).isEqualTo(false to true)

        underTest.setKeyguardPresented(true)
        assertThat(lastState).isEqualTo(true to true)

        underTest.setKeyguardPresented(false)
        assertThat(lastState).isEqualTo(false to true)
    }

    @Test
    fun externalHomeHost_neverReceivesDefaultDisplayKeyguardTheme() {
        val externalHome = mock<IconManager>()
        whenever(externalHome.displayId).thenReturn(Display.DEFAULT_DISPLAY + 1)
        underTest.registerHost(externalHome, HostAppearance.HOME)

        underTest.setKeyguardWallpaperTheme(true)
        underTest.setKeyguardPresented(true)

        verify(appearance, times(3)).applyHomeColorPreference(null)
    }
}
