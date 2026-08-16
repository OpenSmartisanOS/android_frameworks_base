/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class SosCredentialCompletionGuardTest {
    @Test
    public void activeCommittedSession_canFinish() {
        assertThat(isValid(9L, 10, 9L, 10, 10,
                true, true, false, false, true, false)).isTrue();
    }

    @Test
    public void staleGeneration_cannotFinishNewSession() {
        assertThat(isValid(8L, 10, 9L, 10, 10,
                true, true, false, false, true, false)).isFalse();
    }

    @Test
    public void switchedUser_cannotFinishOldSession() {
        assertThat(isValid(9L, 10, 9L, 10, 11,
                true, true, false, false, true, false)).isFalse();
    }

    @Test
    public void relockOrCancellation_cannotFinishSession() {
        assertThat(isValid(9L, 10, 9L, 10, 10,
                true, true, false, true, true, false)).isFalse();
        assertThat(isValid(9L, 10, 9L, 10, 10,
                true, true, false, false, true, true)).isFalse();
    }

    @Test
    public void remoteSession_requiresExactGenerationAndUser() {
        assertThat(KeyguardViewMediator.isSameOriginalCredentialRemoteSession(
                9L, 10, 9L, 10)).isTrue();
        assertThat(KeyguardViewMediator.isSameOriginalCredentialRemoteSession(
                8L, 10, 9L, 10)).isFalse();
        assertThat(KeyguardViewMediator.isSameOriginalCredentialRemoteSession(
                9L, 11, 9L, 10)).isFalse();
        assertThat(KeyguardViewMediator.isSameOriginalCredentialRemoteSession(
                0L, 10, 0L, 10)).isFalse();
    }

    @Test
    public void staleCancel_preservesNewerGenerationForSelectedUser() {
        assertThat(KeyguardViewMediator.isDifferentOriginalCredentialSession(
                8L, 10, 9L, 10, 10)).isTrue();
        assertThat(KeyguardViewMediator.isDifferentOriginalCredentialSession(
                8L, 10, 8L, 10, 10)).isFalse();
        assertThat(KeyguardViewMediator.isDifferentOriginalCredentialSession(
                8L, 10, 9L, 11, 10)).isFalse();
    }

    @Test
    public void canceledAfterWmDispatchBeforeStart_waitsForTerminalCallback() {
        assertThat(KeyguardViewMediator.shouldAwaitCanceledSurfaceBehindCallback(
                false, 12L, 12L)).isTrue();
    }

    @Test
    public void canceledBeforeWmDispatch_doesNotCreateTombstone() {
        assertThat(KeyguardViewMediator.shouldAwaitCanceledSurfaceBehindCallback(
                false, 12L, 0L)).isFalse();
        assertThat(KeyguardViewMediator.shouldAwaitCanceledSurfaceBehindCallback(
                false, 12L, 11L)).isFalse();
    }

    @Test
    public void canceledRunningRemote_doesNotLeaveLateStartTombstone() {
        assertThat(KeyguardViewMediator.shouldAwaitCanceledSurfaceBehindCallback(
                true, 12L, 12L)).isFalse();
    }

    private static boolean isValid(long expectedGeneration, int expectedUser,
            long activeGeneration, int activeUser, int selectedUser, boolean committed,
            boolean interactive, boolean goingToSleep, boolean pendingLock, boolean showing,
            boolean canceled) {
        return KeyguardViewMediator.isOriginalCredentialCompletionValid(
                expectedGeneration, expectedUser, activeGeneration, activeUser, selectedUser,
                committed, interactive, goingToSleep, pendingLock, showing, canceled);
    }
}
