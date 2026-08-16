/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.pin;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class SosKeyguardPinProviderTest {
    @Test
    public void systemUiUid_matchesProviderHostUid() {
        assertThat(SosKeyguardPinProvider.isSystemUiUid(10117, 10117)).isTrue();
    }

    @Test
    public void androidSystemUid_doesNotImpersonateSystemUi() {
        assertThat(SosKeyguardPinProvider.isSystemUiUid(1000, 10117)).isFalse();
    }

    @Test
    public void unrelatedPrivilegedUid_isRejected() {
        assertThat(SosKeyguardPinProvider.isSystemUiUid(10047, 10117)).isFalse();
    }

    @Test
    public void pinContract_systemUiUidDoesNotNeedExternalPermission() {
        assertThat(SosKeyguardPinProvider.isPinContractCallerAllowed(
                10117, 10117, false)).isTrue();
    }

    @Test
    public void pinContract_sidebarWithSignaturePermissionIsAllowed() {
        assertThat(SosKeyguardPinProvider.isPinContractCallerAllowed(
                10047, 10117, true)).isTrue();
    }

    @Test
    public void pinContract_unprivilegedExternalCallerIsRejected() {
        assertThat(SosKeyguardPinProvider.isPinContractCallerAllowed(
                10048, 10117, false)).isFalse();
    }

    @Test
    public void systemUi_canExplicitlySelectCurrentSecondaryUser() {
        assertThat(SosKeyguardPinProvider.resolveUserIdForCaller(
                10117, 10117, 0, 10, true, true)).isEqualTo(10);
    }

    @Test
    public void externalSidebar_cannotOverrideItsCallingUser() {
        assertThat(SosKeyguardPinProvider.resolveUserIdForCaller(
                1010047, 10117, 10, 0, true, true)).isEqualTo(10);
    }

    @Test
    public void pinCurrentTask_ignoresEvenSystemUiOverride() {
        assertThat(SosKeyguardPinProvider.resolveUserIdForCaller(
                10117, 10117, 0, 10, true, false)).isEqualTo(0);
    }
}
