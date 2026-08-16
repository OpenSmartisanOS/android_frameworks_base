/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.ComponentName
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SosQuickLaunchConfigCodecTest {
    @Test
    fun roundTrip_preservesOrderKindsAndEmptySlot() {
        val slots =
            listOf(
                SosQuickLaunchTarget(SosQuickLaunchTarget.Kind.ROLE_DIALER),
                null,
                SosQuickLaunchTarget(
                    SosQuickLaunchTarget.Kind.ACTIVITY,
                    ComponentName("example.notes", "example.notes.MainActivity"),
                ),
            )

        assertThat(SosQuickLaunchConfigCodec.decode(SosQuickLaunchConfigCodec.encode(slots)))
            .isEqualTo(slots)
    }

    @Test
    fun malformedOrUnsupportedState_fallsBackInsteadOfThrowing() {
        assertThat(SosQuickLaunchConfigCodec.decode("not-json")).isNull()
        assertThat(
                SosQuickLaunchConfigCodec.decode(
                    """{"version":99,"slots":[null,null,null]}"""
                )
            )
            .isNull()
    }

    @Test
    fun duplicateTargets_areRejectedBeforePersistence() {
        val camera = SosQuickLaunchTarget(SosQuickLaunchTarget.Kind.SECURE_CAMERA)

        assertThrows(IllegalArgumentException::class.java) {
            SosQuickLaunchConfigCodec.encode(listOf(camera, camera, null))
        }
    }
}
