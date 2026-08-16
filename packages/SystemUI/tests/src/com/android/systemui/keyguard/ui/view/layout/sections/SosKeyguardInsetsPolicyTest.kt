/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SosKeyguardInsetsPolicyTest {
    @Test
    fun gesturalMode_doesNotReserveHiddenHomeHandle() {
        val resolved =
            resolve(
                gestural = true,
                navigation = SosKeyguardLayoutModel.Insets(bottom = 63f),
            )

        assertThat(resolved).isEqualTo(SosKeyguardLayoutModel.Insets(top = 76f))
    }

    @Test
    fun threeButtonMode_keepsBottomNavigationRegion() {
        val resolved =
            resolve(
                gestural = false,
                navigation = SosKeyguardLayoutModel.Insets(bottom = 63f),
            )

        assertThat(resolved.bottom).isEqualTo(63f)
    }

    @Test
    fun movableNavigationBar_keepsLandscapeSideSafe() {
        val resolved =
            resolve(
                gestural = false,
                navigation = SosKeyguardLayoutModel.Insets(),
                canMove = true,
                rotation = SosKeyguardInsetsPolicy.ROTATION_90,
            )

        assertThat(resolved.right).isEqualTo(84f)
        assertThat(resolved.bottom).isEqualTo(0f)
    }

    private fun resolve(
        gestural: Boolean,
        navigation: SosKeyguardLayoutModel.Insets,
        canMove: Boolean = false,
        rotation: Int = 0,
    ) =
        SosKeyguardInsetsPolicy.resolve(
            SosKeyguardInsetsPolicy.Input(
                nonNavigationInsets = SosKeyguardLayoutModel.Insets(top = 76f),
                navigationInsets = navigation,
                isGesturalMode = gestural,
                showNavigationBar = true,
                navigationBarCanMove = canMove,
                rotation = rotation,
                navigationBarHeight = 63f,
                navigationBarWidth = 84f,
            )
        )
}
