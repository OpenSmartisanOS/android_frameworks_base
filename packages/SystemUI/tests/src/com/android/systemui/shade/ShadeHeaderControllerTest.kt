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

import android.content.ContentResolver
import android.os.Handler
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryStateController
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.phone.StatusBarAccessoryController
import com.android.systemui.statusbar.phone.StatusBarAppearanceController
import com.android.systemui.statusbar.phone.StatusBarModeCoordinator
import com.android.systemui.statusbar.phone.StatusBarTickerController
import com.android.systemui.statusbar.phone.ui.SystemIconsController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ShadeHeaderControllerTest : SysuiTestCase() {

    private val modeCoordinator = mock<StatusBarModeCoordinator>()
    private lateinit var underTest: ShadeHeaderController

    @Before
    fun setUp() {
        underTest =
            ShadeHeaderController(
                mock<SystemIconsController>(),
                mock<TintedIconManager.Factory>(),
                mock<UserTracker>(),
                mock<ConfigurationController>(),
                mock<TunerService>(),
                mock<Handler>(),
                mock<ContentResolver>(),
                mock<FeatureFlags>(),
                mock<BatteryController>(),
                mock<BatteryStateController>(),
                mock<StatusBarContentInsetsProviderStore>(),
                mock<StatusBarAppearanceController>(),
                mock<StatusBarAccessoryController>(),
                modeCoordinator,
                mock<StatusBarTickerController>(),
            )
    }

    @Test
    fun initDoesNotCreateASecondHeaderHost() {
        underTest.init()

        verify(modeCoordinator, never()).setPanelExpanded(true, false)
    }

    @Test
    fun physicalExpansionDrivesCanonicalPanelState() {
        underTest.setExpansion(expandedHeight = 1f, maxPanelHeight = 100f, shadeContentAllowed = true)

        verify(modeCoordinator).setPanelExpanded(true, true)
    }

    @Test
    fun detachAlwaysReleasesPanelOwnership() {
        underTest.detach()

        verify(modeCoordinator).setPanelExpanded(false, false)
    }
}
