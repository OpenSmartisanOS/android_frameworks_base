/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.statusbar.phone

import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.phone.VolteStateController.State
import com.android.systemui.statusbar.phone.VolteStateController.SubscriptionSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class VolteStateControllerTest {
    @Test
    fun noVoiceDeviceOrNoSubscription_isHidden() {
        assertThat(
                VolteStateController.resolveState(
                    deviceVoiceCapable = false,
                    snapshots = listOf(ready()),
                )
            )
            .isEqualTo(State.HIDDEN)
        assertThat(
                VolteStateController.resolveState(
                    deviceVoiceCapable = true,
                    snapshots = emptyList(),
                )
            )
            .isEqualTo(State.HIDDEN)
    }

    @Test
    fun unsupportedOrUnknownIms_isHidden() {
        assertThat(resolve(SubscriptionSnapshot())).isEqualTo(State.HIDDEN)
        assertThat(resolve(SubscriptionSnapshot(supportKnown = true, supported = false)))
            .isEqualTo(State.HIDDEN)
    }

    @Test
    fun carrierExplicitlyDisablesVolte_isHiddenEvenWhenImsReportsSupport() {
        assertThat(resolve(ready().copy(carrierAvailable = false))).isEqualTo(State.HIDDEN)
    }

    @Test
    fun unavailableCarrierConfig_doesNotOverrideLiveImsProof() {
        assertThat(resolve(ready().copy(carrierAvailable = null))).isEqualTo(State.READY)
        assertThat(resolve(ready().copy(carrierAvailable = true))).isEqualTo(State.READY)
    }

    @Test
    fun supportedInServiceButUnavailable_isDisabled() {
        assertThat(
                resolve(
                    SubscriptionSnapshot(
                        supportKnown = true,
                        supported = true,
                        inService = true,
                    )
                )
            )
            .isEqualTo(State.DISABLED)
    }

    @Test
    fun registeredVoiceCapability_isReady() {
        assertThat(resolve(ready())).isEqualTo(State.READY)
    }

    @Test
    fun activeImsCall_isCalling() {
        assertThat(resolve(ready(callState = TelephonyManager.CALL_STATE_OFFHOOK)))
            .isEqualTo(State.CALLING)
    }

    @Test
    fun ringingDoesNotPretendCallIsAlreadyActive() {
        assertThat(resolve(ready(callState = TelephonyManager.CALL_STATE_RINGING)))
            .isEqualTo(State.READY)
    }

    @Test
    fun multipleSubscriptions_anyReadyWins_andOnlyReadyCallCanEnterCalling() {
        val unavailableCall =
            ready(callState = TelephonyManager.CALL_STATE_OFFHOOK).copy(
                registeredOnCellular = false,
                voiceCapable = false,
            )

        assertThat(resolve(unavailableCall, ready())).isEqualTo(State.READY)
        assertThat(
                resolve(
                    unavailableCall,
                    ready(callState = TelephonyManager.CALL_STATE_OFFHOOK),
                )
            )
            .isEqualTo(State.CALLING)
    }

    private fun resolve(vararg snapshots: SubscriptionSnapshot): State =
        VolteStateController.resolveState(
            deviceVoiceCapable = true,
            snapshots = snapshots.toList(),
        )

    private fun ready(
        callState: Int = TelephonyManager.CALL_STATE_IDLE
    ): SubscriptionSnapshot =
        SubscriptionSnapshot(
            carrierAvailable = true,
            supportKnown = true,
            supported = true,
            userEnabled = true,
            inService = true,
            registeredOnCellular = true,
            voiceCapable = true,
            callState = callState,
        )
}
