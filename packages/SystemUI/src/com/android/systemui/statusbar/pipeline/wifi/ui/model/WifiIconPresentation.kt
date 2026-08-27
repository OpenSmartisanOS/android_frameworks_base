/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.wifi.ui.model

import android.annotation.DrawableRes
import com.android.systemui.res.R

/** Resolves the immutable R2 Wi-Fi state to the original monochrome or full-color artwork. */
internal object WifiIconPresentation {
    private val normalValidated =
        intArrayOf(
            0,
            R.drawable.stat_sys_wifi_signal_1_fully,
            R.drawable.stat_sys_wifi_signal_2_fully,
            R.drawable.stat_sys_wifi_signal_3_fully,
            R.drawable.stat_sys_wifi_signal_4_fully,
        )
    private val normalUnvalidated =
        intArrayOf(
            0,
            R.drawable.stat_sys_wifi_signal_1,
            R.drawable.stat_sys_wifi_signal_2,
            R.drawable.stat_sys_wifi_signal_3,
            R.drawable.stat_sys_wifi_signal_4,
        )
    private val coloredValidated =
        intArrayOf(
            0,
            R.drawable.colored_stat_sys_wifi_signal_1_fully,
            R.drawable.colored_stat_sys_wifi_signal_2_fully,
            R.drawable.colored_stat_sys_wifi_signal_3_fully,
            R.drawable.colored_stat_sys_wifi_signal_4_fully,
        )
    private val coloredUnvalidated =
        intArrayOf(
            0,
            R.drawable.colored_stat_sys_wifi_signal_1,
            R.drawable.colored_stat_sys_wifi_signal_2,
            R.drawable.colored_stat_sys_wifi_signal_3,
            R.drawable.colored_stat_sys_wifi_signal_4,
        )

    @DrawableRes
    fun resolveSignal(state: WifiState, colorIcon: Boolean): Int {
        if (!state.connected) return R.drawable.stat_sys_wifi_signal_null
        if (state.warning) {
            return if (colorIcon) {
                R.drawable.colored_stat_sys_wifi_signal_warning
            } else {
                R.drawable.stat_sys_wifi_signal_warning
            }
        }

        val level =
            state.level.coerceIn(WifiState.MIN_CONNECTED_LEVEL, WifiState.MAX_CONNECTED_LEVEL)
        return when {
            colorIcon && state.inetCondition == WifiState.INET_CONDITION_VALIDATED ->
                coloredValidated[level]
            colorIcon -> coloredUnvalidated[level]
            state.inetCondition == WifiState.INET_CONDITION_VALIDATED -> normalValidated[level]
            else -> normalUnvalidated[level]
        }
    }

    @DrawableRes
    fun resolveWifi6(colorIcon: Boolean): Int =
        if (colorIcon) {
            R.drawable.colored_stat_sys_wifi_signal_wifi6
        } else {
            R.drawable.stat_sys_wifi_signal_wifi6
        }

    @DrawableRes
    fun resolveActivity(activity: WifiState.Activity): Int =
        when (activity) {
            WifiState.Activity.NONE -> R.drawable.stat_sys_wifi_inout_null
            WifiState.Activity.IN -> R.drawable.stat_sys_wifi_in
            WifiState.Activity.OUT -> R.drawable.stat_sys_wifi_out
            WifiState.Activity.INOUT -> R.drawable.stat_sys_wifi_inout
        }
}
