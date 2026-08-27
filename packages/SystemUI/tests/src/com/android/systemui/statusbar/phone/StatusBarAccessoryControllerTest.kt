/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.statusbar.phone

import android.hardware.usb.UsbPortStatus
import android.net.TrafficStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.DefaultTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarAccessoryControllerTest {
    @Test
    fun sidebar_requiresFeatureAndInnerDrag_andIsSuppressedInDemo() {
        assertThat(
                StatusBarAccessoryController.shouldShowSidebar(
                    sideBarMode = true,
                    innerDrag = true,
                    demoMode = false,
                )
            )
            .isTrue()
        assertThat(
                StatusBarAccessoryController.shouldShowSidebar(
                    sideBarMode = false,
                    innerDrag = true,
                    demoMode = false,
                )
            )
            .isFalse()
        assertThat(
                StatusBarAccessoryController.shouldShowSidebar(
                    sideBarMode = true,
                    innerDrag = false,
                    demoMode = false,
                )
            )
            .isFalse()
        assertThat(
                StatusBarAccessoryController.shouldShowSidebar(
                    sideBarMode = true,
                    innerDrag = true,
                    demoMode = true,
                )
            )
            .isFalse()
    }

    @Test
    fun otg_onlyShowsForConnectedSourcePowerRole() {
        assertThat(
                StatusBarAccessoryController.isSourcePort(
                    connected = true,
                    powerRole = UsbPortStatus.POWER_ROLE_SOURCE,
                )
            )
            .isTrue()
        assertThat(
                StatusBarAccessoryController.isSourcePort(
                    connected = true,
                    powerRole = UsbPortStatus.POWER_ROLE_SINK,
                )
            )
            .isFalse()
        assertThat(
                StatusBarAccessoryController.isSourcePort(
                    connected = false,
                    powerRole = UsbPortStatus.POWER_ROLE_SOURCE,
                )
            )
            .isFalse()
    }

    @Test
    fun trafficCombinesRxAndTx_andRejectsUnsupportedAndSaturatesOverflow() {
        assertThat(StatusBarAccessoryController.combineTrafficBytes(1_000L, 500L))
            .isEqualTo(1_500L)
        assertThat(
                StatusBarAccessoryController.combineTrafficBytes(
                    TrafficStats.UNSUPPORTED.toLong(),
                    500L,
                )
            )
            .isNull()
        assertThat(
                StatusBarAccessoryController.combineTrafficBytes(Long.MAX_VALUE - 2L, 5L)
            )
            .isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun trafficResetOrCounterRollback_startsAZeroDeltaBaseline() {
        val sample = StatusBarAccessoryController.TrafficSample(rx = 50L, tx = 20L)
        assertThat(StatusBarAccessoryController.calculateDelta(previous = null, current = sample))
            .isEqualTo(0L)
        assertThat(
                StatusBarAccessoryController.calculateDelta(
                    previous = StatusBarAccessoryController.TrafficSample(rx = 100L, tx = 20L),
                    current = StatusBarAccessoryController.TrafficSample(rx = 50L, tx = 100L),
                )
            )
            .isEqualTo(0L)
        assertThat(
                StatusBarAccessoryController.calculateDelta(
                    previous = StatusBarAccessoryController.TrafficSample(rx = 100L, tx = 20L),
                    current = StatusBarAccessoryController.TrafficSample(rx = 150L, tx = 40L),
                )
            )
            .isEqualTo(70L)
    }

    @Test
    fun speedEligibility_usesSharedValidatedDefaultIdentity() {
        assertThat(
                StatusBarAccessoryController.isUsableDefaultNetwork(
                    DefaultConnectionModel(
                        isValidated = true,
                        defaultTransport = DefaultTransport.WIFI,
                    )
                )
            )
            .isTrue()
        assertThat(
                StatusBarAccessoryController.isUsableDefaultNetwork(
                    DefaultConnectionModel(
                        isValidated = true,
                        isVpn = true,
                        defaultTransport = DefaultTransport.VPN,
                    )
                )
            )
            .isTrue()
        assertThat(
                StatusBarAccessoryController.isUsableDefaultNetwork(
                    DefaultConnectionModel(
                        isValidated = false,
                        defaultTransport = DefaultTransport.MOBILE,
                    )
                )
            )
            .isFalse()
        assertThat(
                StatusBarAccessoryController.isUsableDefaultNetwork(
                    DefaultConnectionModel(isValidated = true)
                )
            )
            .isFalse()
    }

    @Test
    fun speedFormatting_matchesOriginalKiloMegaThresholdAndPrecision() {
        assertThat(StatusBarAccessoryController.formatSpeed(0.0)).isEqualTo("0.0" to "K")
        assertThat(StatusBarAccessoryController.formatSpeed(9.5 * 1024.0))
            .isEqualTo("9.5" to "K")
        assertThat(StatusBarAccessoryController.formatSpeed(50.0 * 1024.0))
            .isEqualTo("50" to "K")
        assertThat(StatusBarAccessoryController.formatSpeed(100.0 * 1024.0))
            .isEqualTo("0.1" to "M")
    }
}
