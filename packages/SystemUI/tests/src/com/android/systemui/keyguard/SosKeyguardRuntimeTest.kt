/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.keyguard.SosKeyguardRuntime.OriginalInteractiveTransitionPhase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class SosKeyguardRuntimeTest {
    @Before
    fun setUp() {
        resetRuntime()
        SosKeyguardRuntime.clearOriginalUnlockAnimationCompletion()
    }

    @After
    fun tearDown() {
        resetRuntime()
        SosKeyguardRuntime.clearOriginalUnlockAnimationCompletion()
    }

    private fun resetRuntime() {
        SosKeyguardRuntime.getOriginalCredentialGeneration().takeIf { it != 0L }?.let {
            SosKeyguardRuntime.finishOriginalCredentialTransition(it)
        }
        SosKeyguardRuntime.finishOriginalInteractiveTransition()
    }

    @Test
    fun credentialTimeline_blocksGoneUntilCommit() {
        val generation = SosKeyguardRuntime.beginOriginalCredentialTransition(10)

        assertThat(SosKeyguardRuntime.originalInteractiveTransitionPhase.value)
            .isEqualTo(OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN)
        assertThat(SosKeyguardRuntime.blocksAndroidGoneTransition()).isTrue()

        assertThat(SosKeyguardRuntime.prepareOriginalCredentialTransition(generation)).isTrue()
        assertThat(SosKeyguardRuntime.originalInteractiveTransitionPhase.value)
            .isEqualTo(OriginalInteractiveTransitionPhase.AUTHENTICATED_PREPARING)
        assertThat(SosKeyguardRuntime.blocksAndroidGoneTransition()).isTrue()

        assertThat(SosKeyguardRuntime.commitOriginalCredentialTransition(generation)).isTrue()
        assertThat(SosKeyguardRuntime.originalInteractiveTransitionPhase.value)
            .isEqualTo(OriginalInteractiveTransitionPhase.COMMITTING)
        assertThat(SosKeyguardRuntime.blocksAndroidGoneTransition()).isFalse()
    }

    @Test
    fun r2Presentation_isCompileTimeEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertThat(SosKeyguardRuntime.isEnabled(context)).isTrue()
        assertThat(SosKeyguardRuntime.isEnabledForDisplay(android.view.Display.DEFAULT_DISPLAY)).isTrue()
        assertThat(SosKeyguardRuntime.isEnabledForDisplay(42)).isFalse()
    }

    @Test
    fun credentialSession_isBoundToGenerationAndUser() {
        val generation = SosKeyguardRuntime.beginOriginalCredentialTransition(10)

        assertThat(SosKeyguardRuntime.isOriginalCredentialSession(generation, 10)).isTrue()
        assertThat(SosKeyguardRuntime.isOriginalCredentialSession(generation, 11)).isFalse()
        assertThat(SosKeyguardRuntime.isOriginalCredentialSession(generation + 1, 10)).isFalse()
    }

    @Test
    fun earlyReady_isLatchedForOnlyTheActiveGeneration() {
        val oldGeneration = SosKeyguardRuntime.beginOriginalCredentialTransition()
        assertThat(SosKeyguardRuntime.deferReadyForKeyguardDone()).isTrue()
        assertThat(SosKeyguardRuntime.consumeDeferredReadyForKeyguardDone(oldGeneration)).isTrue()

        assertThat(SosKeyguardRuntime.prepareOriginalCredentialTransition(oldGeneration)).isTrue()
        assertThat(SosKeyguardRuntime.deferReadyForKeyguardDone()).isTrue()
        val newGeneration = SosKeyguardRuntime.beginOriginalCredentialTransition()

        assertThat(SosKeyguardRuntime.consumeDeferredReadyForKeyguardDone(oldGeneration)).isFalse()
        assertThat(newGeneration).isNotEqualTo(oldGeneration)
    }

    @Test
    fun staleGeneration_cannotPrepareCommitOrCancelNewCurtain() {
        val stale = SosKeyguardRuntime.beginOriginalCredentialTransition()
        val active = SosKeyguardRuntime.beginOriginalCredentialTransition()

        assertThat(SosKeyguardRuntime.prepareOriginalCredentialTransition(stale)).isFalse()
        assertThat(SosKeyguardRuntime.commitOriginalCredentialTransition(stale)).isFalse()
        assertThat(SosKeyguardRuntime.cancelOriginalCredentialTransition(stale)).isFalse()
        assertThat(SosKeyguardRuntime.getOriginalCredentialGeneration()).isEqualTo(active)
        assertThat(SosKeyguardRuntime.originalInteractiveTransitionPhase.value)
            .isEqualTo(OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN)
    }

    @Test
    fun staleGeneration_cannotFinishNewCurtain() {
        val stale = SosKeyguardRuntime.beginOriginalCredentialTransition(10)
        val active = SosKeyguardRuntime.beginOriginalCredentialTransition(10)

        assertThat(SosKeyguardRuntime.finishOriginalCredentialTransition(stale)).isFalse()
        assertThat(SosKeyguardRuntime.getOriginalCredentialGeneration()).isEqualTo(active)
        assertThat(SosKeyguardRuntime.finishOriginalCredentialTransition(active)).isTrue()
        assertThat(SosKeyguardRuntime.getOriginalCredentialGeneration()).isEqualTo(0L)
    }

    @Test
    fun cancellation_neverOpensGoneGateAsACommit() {
        val generation = SosKeyguardRuntime.beginOriginalCredentialTransition()
        assertThat(SosKeyguardRuntime.prepareOriginalCredentialTransition(generation)).isTrue()

        assertThat(SosKeyguardRuntime.cancelOriginalCredentialTransition(generation)).isTrue()
        assertThat(SosKeyguardRuntime.originalInteractiveTransitionPhase.value)
            .isEqualTo(OriginalInteractiveTransitionPhase.CANCELLING)
        assertThat(SosKeyguardRuntime.blocksAndroidGoneTransition()).isTrue()
        assertThat(SosKeyguardRuntime.commitOriginalCredentialTransition(generation)).isFalse()
    }
}
