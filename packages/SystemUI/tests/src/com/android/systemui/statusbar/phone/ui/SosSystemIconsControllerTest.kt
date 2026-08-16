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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.ui.SosSystemIconsController.HostAppearance
import com.android.systemui.statusbar.phone.ui.SosSystemIconsController.HomeKeyguardThemeListener
import com.google.common.truth.Truth.assertThat
import com.android.systemui.util.mockito.mock
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
class SosSystemIconsControllerTest : SysuiTestCase() {
    private val platformController = mock<StatusBarIconController>()
    private val home = mock<IconManager>()
    private val keyguard = mock<IconManager>()
    private val panel = mock<IconManager>()
    private val underTest = SosSystemIconsController(platformController)

    @Test
    fun registerHosts_eachIndependentViewReceivesSharedState() {
        underTest.registerHost(home, HostAppearance.HOME)
        underTest.registerHost(keyguard, HostAppearance.KEYGUARD)
        underTest.registerHost(panel, HostAppearance.PANEL)

        verify(platformController).addIconGroup(home)
        verify(platformController).addIconGroup(keyguard)
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
        underTest.registerHost(keyguard, HostAppearance.KEYGUARD)
        underTest.unregisterHost(keyguard)
        underTest.registerHost(keyguard, HostAppearance.KEYGUARD)

        verify(platformController, times(2)).addIconGroup(keyguard)
        verify(platformController).removeIconGroup(keyguard)
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
}
