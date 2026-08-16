/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

/** Pure policy that keeps the R2 surface aligned with the navigation UI that is actually shown. */
internal object SosKeyguardInsetsPolicy {
    data class Input(
        val nonNavigationInsets: SosKeyguardLayoutModel.Insets,
        val navigationInsets: SosKeyguardLayoutModel.Insets,
        val isGesturalMode: Boolean,
        val showNavigationBar: Boolean,
        val navigationBarCanMove: Boolean,
        val rotation: Int,
        val navigationBarHeight: Float,
        val navigationBarWidth: Float,
    )

    fun resolve(input: Input): SosKeyguardLayoutModel.Insets {
        val safe = input.nonNavigationInsets
        if (input.isGesturalMode || !input.showNavigationBar) return safe

        var left = maxOf(safe.left, input.navigationInsets.left)
        var top = maxOf(safe.top, input.navigationInsets.top)
        var right = maxOf(safe.right, input.navigationInsets.right)
        var bottom = maxOf(safe.bottom, input.navigationInsets.bottom)
        if (input.navigationBarCanMove && input.rotation == ROTATION_90) {
            right = maxOf(right, input.navigationBarWidth)
        } else if (input.navigationBarCanMove && input.rotation == ROTATION_270) {
            left = maxOf(left, input.navigationBarWidth)
        } else {
            bottom = maxOf(bottom, input.navigationBarHeight)
        }
        return SosKeyguardLayoutModel.Insets(left, top, right, bottom)
    }

    internal const val ROTATION_90 = 1
    internal const val ROTATION_270 = 3
}
