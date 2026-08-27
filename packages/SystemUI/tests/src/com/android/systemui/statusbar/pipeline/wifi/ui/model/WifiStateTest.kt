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

import android.net.wifi.ScanResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.DefaultTransport
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiStateTest {
    @Test
    fun disabledForceHiddenAndCarrierMerged_areHidden() {
        assertThat(state(network = active(), enabled = false).visible).isFalse()
        assertThat(state(network = active(), forceHidden = true).visible).isFalse()
        assertThat(state(network = WifiNetworkModel.CarrierMerged.of(1, 2, 4)).visible).isFalse()
    }

    @Test
    fun originalLevel_usesR2FourBucketRssiMapping() {
        assertThat(WifiState.originalLevel(-100, 4)).isEqualTo(1)
        assertThat(WifiState.originalLevel(-85, 4)).isEqualTo(2)
        assertThat(WifiState.originalLevel(-70, 4)).isEqualTo(3)
        assertThat(WifiState.originalLevel(-55, 0)).isEqualTo(4)
    }

    @Test
    fun disconnected_phoneHidden_wifiOnlyShowsNullState() {
        val phone = state(network = WifiNetworkModel.Inactive(), hasDataCapabilities = true)
        val wifiOnly = state(network = WifiNetworkModel.Inactive(), hasDataCapabilities = false)

        assertThat(phone.visible).isFalse()
        assertThat(wifiOnly.visible).isTrue()
        assertThat(wifiOnly.connected).isFalse()
        assertThat(wifiOnly.contentDescription).isEqualTo("disconnected")
    }

    @Test
    fun connectedNonDefault_remainsVisible() {
        val state =
            state(network = active(level = 2), defaultConnections = DefaultConnectionModel())

        assertThat(state.visible).isTrue()
        assertThat(state.connected).isTrue()
    }

    @Test
    fun validationFollowsGlobalDefaultNetwork() {
        val unvalidated = state(network = active(), defaultConnections = DefaultConnectionModel())
        val validated =
            state(
                network = active(),
                defaultConnections = DefaultConnectionModel(isValidated = true),
            )

        assertThat(unvalidated.inetCondition).isEqualTo(WifiState.INET_CONDITION_UNVALIDATED)
        assertThat(validated.inetCondition).isEqualTo(WifiState.INET_CONDITION_VALIDATED)
    }

    @Test
    fun mobileDefaultWithAvoidBadWifi_requestsWarning() {
        val defaultConnections =
            DefaultConnectionModel(
                mobile = DefaultConnectionModel.Mobile(true),
                isValidated = true,
                defaultTransport = DefaultTransport.MOBILE,
            )
        val state =
            state(
                network = active(level = 2),
                defaultConnections = defaultConnections,
                warning = WifiState.shouldWarn(defaultConnections, avoidBadWifi = true),
            )

        assertThat(state.warning).isTrue()
        assertThat(state.inetCondition).isEqualTo(WifiState.INET_CONDITION_VALIDATED)
    }

    @Test
    fun ethernetDefault_neverRequestsMobileWarning() {
        val defaultConnections =
            DefaultConnectionModel(
                ethernet = DefaultConnectionModel.Ethernet(true),
                isValidated = true,
                defaultTransport = DefaultTransport.ETHERNET,
            )
        val state =
            state(
                network = active(level = 2),
                defaultConnections = defaultConnections,
                warning = WifiState.shouldWarn(defaultConnections, avoidBadWifi = true),
            )

        assertThat(state.warning).isFalse()
    }

    @Test
    fun vpnOverMobile_neverRequestsMobileWarning() {
        val defaultConnections =
            DefaultConnectionModel(
                mobile = DefaultConnectionModel.Mobile(true),
                isValidated = true,
                isVpn = true,
                defaultTransport = DefaultTransport.VPN,
            )

        assertThat(WifiState.shouldWarn(defaultConnections, avoidBadWifi = true)).isFalse()
    }

    @Test
    fun connectedWifiWithCellularDefault_warns_butWifiDefaultDoesNot() {
        val cellularDefault =
            DefaultConnectionModel(
                mobile = DefaultConnectionModel.Mobile(true),
                defaultTransport = DefaultTransport.MOBILE,
            )
        val wifiDefault =
            DefaultConnectionModel(
                wifi = DefaultConnectionModel.Wifi(true),
                defaultTransport = DefaultTransport.WIFI,
            )

        assertThat(WifiState.shouldWarn(cellularDefault, avoidBadWifi = true)).isTrue()
        assertThat(WifiState.shouldWarn(wifiDefault, avoidBadWifi = true)).isFalse()
    }

    @Test
    fun defaultTransport_isPreservedEvenWhenWifiIconIsForceHidden() {
        val wifiDefault =
            DefaultConnectionModel(
                wifi = DefaultConnectionModel.Wifi(true),
                defaultTransport = DefaultTransport.WIFI,
            )

        val hidden =
            state(
                network = active(),
                forceHidden = true,
                defaultConnections = wifiDefault,
            )

        assertThat(hidden.visible).isFalse()
        assertThat(hidden.defaultTransport).isEqualTo(DefaultTransport.WIFI)
    }

    @Test
    fun wifiSix_onlyAxGetsBadge_wifiSevenDoesNot() {
        val wifiSix = state(network = active(wifiStandard = ScanResult.WIFI_STANDARD_11AX))
        val wifiSeven = state(network = active(wifiStandard = ScanResult.WIFI_STANDARD_11BE))

        assertThat(wifiSix.wifi6).isTrue()
        assertThat(wifiSeven.wifi6).isFalse()
    }

    @Test
    fun activityMapsAllDirections() {
        val state =
            state(
                network = active(),
                activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true),
            )

        assertThat(state.activity).isEqualTo(WifiState.Activity.INOUT)
    }

    private fun state(
        network: WifiNetworkModel,
        enabled: Boolean = true,
        forceHidden: Boolean = false,
        hasDataCapabilities: Boolean = true,
        defaultConnections: DefaultConnectionModel = DefaultConnectionModel(),
        warning: Boolean = false,
        activity: DataActivityModel = DataActivityModel(false, false),
    ): WifiState =
        WifiState.fromInputs(
            enabled = enabled,
            forceHidden = forceHidden,
            hasDataCapabilities = hasDataCapabilities,
            network = network,
            defaultConnections = defaultConnections,
            warning = warning,
            activity = activity,
            connectedDescription = { "level $it" },
            disconnectedDescription = "disconnected",
        )

    private fun active(
        level: Int = 2,
        wifiStandard: Int = ScanResult.WIFI_STANDARD_11AC,
    ): WifiNetworkModel =
        WifiNetworkModel.Active.of(isValidated = true, level = level, wifiStandard = wifiStandard)
}
