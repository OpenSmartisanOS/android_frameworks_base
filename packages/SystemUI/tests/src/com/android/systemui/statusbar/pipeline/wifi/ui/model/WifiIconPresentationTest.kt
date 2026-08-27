/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.pipeline.wifi.ui.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiIconPresentationTest {
    @Test
    fun resolveSignal_connected_mapsOriginalLevelsAndInetCondition() {
        val validated =
            listOf(
                R.drawable.stat_sys_wifi_signal_1_fully,
                R.drawable.stat_sys_wifi_signal_2_fully,
                R.drawable.stat_sys_wifi_signal_3_fully,
                R.drawable.stat_sys_wifi_signal_4_fully,
            )
        val unvalidated =
            listOf(
                R.drawable.stat_sys_wifi_signal_1,
                R.drawable.stat_sys_wifi_signal_2,
                R.drawable.stat_sys_wifi_signal_3,
                R.drawable.stat_sys_wifi_signal_4,
            )

        (1..4).forEach { level ->
            assertThat(
                    WifiIconPresentation.resolveSignal(
                        state =
                            WifiState(
                                connected = true,
                                level = level,
                                inetCondition = WifiState.INET_CONDITION_VALIDATED,
                            ),
                        colorIcon = false,
                    )
                )
                .isEqualTo(validated[level - 1])
            assertThat(
                    WifiIconPresentation.resolveSignal(
                        state = WifiState(connected = true, level = level),
                        colorIcon = false,
                    )
                )
                .isEqualTo(unvalidated[level - 1])
        }
    }

    @Test
    fun resolveSignal_warning_isIndependentOfStrength() {
        assertThat(
                WifiIconPresentation.resolveSignal(
                    WifiState(connected = true, level = 2, warning = true),
                    colorIcon = false,
                )
            )
            .isEqualTo(R.drawable.stat_sys_wifi_signal_warning)
        assertThat(
                WifiIconPresentation.resolveSignal(
                    WifiState(connected = true, level = 4, warning = true),
                    colorIcon = true,
                )
            )
            .isEqualTo(R.drawable.colored_stat_sys_wifi_signal_warning)
    }

    @Test
    fun resolveSignal_colored_usesUntintedArtwork() {
        assertThat(
                WifiIconPresentation.resolveSignal(
                    WifiState(
                        connected = true,
                        level = 3,
                        inetCondition = WifiState.INET_CONDITION_VALIDATED,
                    ),
                    colorIcon = true,
                )
            )
            .isEqualTo(R.drawable.colored_stat_sys_wifi_signal_3_fully)
    }

    @Test
    fun resolveWifi6_isSeparateNormalOrColoredLayer() {
        assertThat(WifiIconPresentation.resolveWifi6(false))
            .isEqualTo(R.drawable.stat_sys_wifi_signal_wifi6)
        assertThat(WifiIconPresentation.resolveWifi6(true))
            .isEqualTo(R.drawable.colored_stat_sys_wifi_signal_wifi6)
    }

    @Test
    fun resolveActivity_mapsAllDirectionsIncludingIdle() {
        assertThat(WifiIconPresentation.resolveActivity(WifiState.Activity.NONE))
            .isEqualTo(R.drawable.stat_sys_wifi_inout_null)
        assertThat(WifiIconPresentation.resolveActivity(WifiState.Activity.IN))
            .isEqualTo(R.drawable.stat_sys_wifi_in)
        assertThat(WifiIconPresentation.resolveActivity(WifiState.Activity.OUT))
            .isEqualTo(R.drawable.stat_sys_wifi_out)
        assertThat(WifiIconPresentation.resolveActivity(WifiState.Activity.INOUT))
            .isEqualTo(R.drawable.stat_sys_wifi_inout)
    }
}
