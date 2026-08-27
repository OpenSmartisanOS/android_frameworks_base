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

package com.android.systemui.statusbar.connectivity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiIconsTest {
    @Test
    fun statusBarIcons_usePlatformIsolatedResources() {
        assertThat(WifiIcons.WIFI_FULL_ICONS)
            .asList()
            .containsExactly(
                R.drawable.platform_stat_sys_wifi_signal_0_fully,
                R.drawable.platform_stat_sys_wifi_signal_1_fully,
                R.drawable.platform_stat_sys_wifi_signal_2_fully,
                R.drawable.platform_stat_sys_wifi_signal_3_fully,
                R.drawable.platform_stat_sys_wifi_signal_4_fully,
            )
            .inOrder()
        assertThat(WifiIcons.WIFI_NO_INTERNET_ICONS)
            .asList()
            .containsExactly(
                R.drawable.platform_stat_sys_wifi_signal_0,
                R.drawable.platform_stat_sys_wifi_signal_1,
                R.drawable.platform_stat_sys_wifi_signal_2,
                R.drawable.platform_stat_sys_wifi_signal_3,
                R.drawable.platform_stat_sys_wifi_signal_warning,
            )
            .inOrder()
        assertThat(WifiIcons.WIFI_NO_NETWORK)
            .isEqualTo(R.drawable.platform_stat_sys_wifi_signal_null)
    }

    @Test
    fun quickSettingsIcons_keepPlatformQuickSettingsResources() {
        assertThat(WifiIcons.QS_WIFI_SIGNAL_STRENGTH[0][0])
            .isEqualTo(com.android.settingslib.R.drawable.ic_no_internet_wifi_signal_0)
        assertThat(WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][0])
            .isEqualTo(com.android.internal.R.drawable.ic_wifi_signal_0)
        assertThat(WifiIcons.QS_WIFI_NO_NETWORK)
            .isEqualTo(com.android.internal.R.drawable.ic_wifi_signal_0)
        assertThat(WifiTileModel.Active().icon.resId).isEqualTo(WifiIcons.QS_WIFI_NO_NETWORK)
        assertThat(WifiTileModel.Inactive().icon.resId).isEqualTo(WifiIcons.QS_WIFI_NO_NETWORK)
    }
}
