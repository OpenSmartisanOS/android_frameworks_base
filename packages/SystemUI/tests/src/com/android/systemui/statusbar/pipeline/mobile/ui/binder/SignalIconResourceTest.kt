/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.pipeline.mobile.ui.binder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SignalIconResourceTest {
    @Test
    fun shouldShowRat_usesActualWifiDefaultRatherThanWifiConnection() {
        assertThat(
                SignalIconResource.shouldShowRat(
                    hasDataType = true,
                    roaming = false,
                    wifiDefault = true,
                )
            )
            .isFalse()
        assertThat(
                SignalIconResource.shouldShowRat(
                    hasDataType = true,
                    roaming = false,
                    wifiDefault = false,
                )
            )
            .isTrue()
        assertThat(
                SignalIconResource.shouldShowRat(
                    hasDataType = true,
                    roaming = true,
                    wifiDefault = true,
                )
            )
            .isTrue()
    }

    @Test
    fun resolvePresentation_dualSimAndValidation_usesR2Families() {
        val sim1 =
            listOf(
                R.drawable.stat_sys_signal_sim1_0_fully,
                R.drawable.stat_sys_signal_sim1_1_fully,
                R.drawable.stat_sys_signal_sim1_2_fully,
                R.drawable.stat_sys_signal_sim1_3_fully,
                R.drawable.stat_sys_signal_sim1_4_fully,
                R.drawable.stat_sys_signal_sim1_5_fully,
            )
        val sim2Unvalidated =
            listOf(
                R.drawable.stat_sys_signal_sim2_0,
                R.drawable.stat_sys_signal_sim2_1,
                R.drawable.stat_sys_signal_sim2_2,
                R.drawable.stat_sys_signal_sim2_3,
                R.drawable.stat_sys_signal_sim2_4,
                R.drawable.stat_sys_signal_sim2_5,
            )
        (0..5).forEach { level ->
            assertThat(
                    SignalIconResource.resolvePresentation(
                        slot = 0,
                        level = level,
                        showExclamationMark = false,
                        carrierNetworkChange = false,
                    )
                    .signalRes
                )
                .isEqualTo(sim1[level])
            assertThat(
                    SignalIconResource.resolvePresentation(
                        slot = 1,
                        level = level,
                        showExclamationMark = true,
                        carrierNetworkChange = false,
                    )
                    .signalRes
                )
                .isEqualTo(sim2Unvalidated[level])
        }
    }

    @Test
    fun resolvePresentation_genericAndBounds_supportsLevelFive() {
        assertThat(
                SignalIconResource.resolvePresentation(
                        slot = 2,
                        level = 99,
                        showExclamationMark = false,
                        carrierNetworkChange = false,
                    )
                    .signalRes
            )
            .isEqualTo(R.drawable.stat_sys_signal_5_fully)
    }

    @Test
    fun resolvePresentation_carrierChange_usesAnimation() {
        assertThat(
                SignalIconResource.resolvePresentation(
                        slot = null,
                        level = 0,
                        showExclamationMark = false,
                        carrierNetworkChange = true,
                    )
                    .signalRes
            )
            .isEqualTo(R.drawable.stat_sys_signal_carrier_network_change_animation)
    }

    @Test
    fun resolvePresentation_colorIcon_usesColoredArtwork() {
        assertThat(
                SignalIconResource.resolvePresentation(
                        slot = 0,
                        level = 3,
                        showExclamationMark = false,
                        carrierNetworkChange = false,
                        colorIcon = true,
                    )
                    .signalRes
            )
            .isEqualTo(R.drawable.colored_stat_sys_signal_sim1_3_fully)
    }

    @Test
    fun resolveNetworkType_mapsSupportedRatAndFallsBack() {
        val description: ContentDescription? = null
        val expected =
            mapOf(
                TelephonyIcons.ICON_G to R.drawable.stat_sys_data_fully_connected_g,
                TelephonyIcons.ICON_E to R.drawable.stat_sys_data_fully_connected_e,
                TelephonyIcons.ICON_1X to R.drawable.stat_sys_data_fully_connected_1x,
                TelephonyIcons.ICON_3G to R.drawable.stat_sys_data_fully_connected_3g,
                TelephonyIcons.ICON_H to R.drawable.stat_sys_data_fully_connected_h,
                TelephonyIcons.ICON_H_PLUS to R.drawable.stat_sys_data_fully_connected_hp,
                TelephonyIcons.ICON_4G to R.drawable.stat_sys_data_fully_connected_4g,
                TelephonyIcons.ICON_LTE to R.drawable.stat_sys_data_fully_connected_4g,
                TelephonyIcons.ICON_4G_PLUS to R.drawable.stat_sys_data_fully_connected_4gp,
                TelephonyIcons.ICON_LTE_PLUS to R.drawable.stat_sys_data_fully_connected_4gp,
                TelephonyIcons.ICON_5G to R.drawable.stat_sys_data_fully_connected_5g,
                TelephonyIcons.ICON_5G_PLUS to R.drawable.stat_sys_data_fully_connected_5g,
                TelephonyIcons.ICON_5G_E to R.drawable.stat_sys_data_fully_connected_5g,
            )
        expected.forEach { (input, output) ->
            assertThat(
                    SignalIconResource.resolveNetworkType(Icon.Resource(input, description))
                        .resId
                )
                .isEqualTo(output)
        }

        val unsupported = Icon.Resource(TelephonyIcons.ICON_CWF, description)
        assertThat(SignalIconResource.resolveNetworkType(unsupported)).isEqualTo(unsupported)
    }

    @Test
    fun resolveActivity_hiddenUnlessRoamingAndDataConnected() {
        assertThat(
                SignalIconResource.resolveActivity(
                    hasActivityIn = true,
                    hasActivityOut = true,
                    roaming = false,
                    dataConnected = true,
                )
            )
            .isEqualTo(0)
        assertThat(
                SignalIconResource.resolveActivity(
                    hasActivityIn = true,
                    hasActivityOut = true,
                    roaming = true,
                    dataConnected = false,
                )
            )
            .isEqualTo(0)
    }

    @Test
    fun resolveActivity_roaming_mapsDirectionsAndIdlePlaceholder() {
        assertThat(
                SignalIconResource.resolveActivity(
                    hasActivityIn = false,
                    hasActivityOut = false,
                    roaming = true,
                    dataConnected = true,
                )
            )
            .isEqualTo(R.drawable.stat_sys_roaming_signal_inout_null)
        assertThat(
                SignalIconResource.resolveActivity(
                    hasActivityIn = true,
                    hasActivityOut = false,
                    roaming = true,
                    dataConnected = true,
                )
            )
            .isEqualTo(R.drawable.stat_sys_roaming_signal_in)
        assertThat(
                SignalIconResource.resolveActivity(
                    hasActivityIn = false,
                    hasActivityOut = true,
                    roaming = true,
                    dataConnected = true,
                )
            )
            .isEqualTo(R.drawable.stat_sys_roaming_signal_out)
        assertThat(
                SignalIconResource.resolveActivity(
                    hasActivityIn = true,
                    hasActivityOut = true,
                    roaming = true,
                    dataConnected = true,
                )
            )
            .isEqualTo(R.drawable.stat_sys_roaming_signal_inout)
    }
}
