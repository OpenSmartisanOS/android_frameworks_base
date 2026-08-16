/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.server.wm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyguardBackgroundControllerTests {
    @Test
    public void retryDelay_usesFastBurstThenSlowRecovery() {
        assertEquals(120L, KeyguardBackgroundController.retryDelayForAttempt(0));
        assertEquals(120L, KeyguardBackgroundController.retryDelayForAttempt(7));
        assertEquals(2_000L, KeyguardBackgroundController.retryDelayForAttempt(8));
        assertEquals(2_000L, KeyguardBackgroundController.retryDelayForAttempt(100));
    }

    @Test
    public void captureClassification_secureAndProtectedAreFailClosed() {
        assertEquals(KeyguardBackgroundController.CaptureResult.UNSAFE,
                KeyguardBackgroundController.classifyCaptureResultProperties(
                        true, true, true, false));
        assertEquals(KeyguardBackgroundController.CaptureResult.UNSAFE,
                KeyguardBackgroundController.classifyCaptureResultProperties(
                        true, false, true, true));
        assertEquals(KeyguardBackgroundController.CaptureResult.UNSAFE,
                KeyguardBackgroundController.classifyCaptureResultProperties(
                        true, true, false, false));
    }

    @Test
    public void captureClassification_missingOrInvalidAreTransient() {
        assertEquals(KeyguardBackgroundController.CaptureResult.TRANSIENT,
                KeyguardBackgroundController.classifyCaptureResultProperties(
                        false, false, false, false));
        assertEquals(KeyguardBackgroundController.CaptureResult.TRANSIENT,
                KeyguardBackgroundController.classifyCaptureResultProperties(
                        true, false, false, false));
        assertEquals(KeyguardBackgroundController.CaptureResult.SUCCESS,
                KeyguardBackgroundController.classifyCaptureResultProperties(
                        true, false, true, false));
    }
}
