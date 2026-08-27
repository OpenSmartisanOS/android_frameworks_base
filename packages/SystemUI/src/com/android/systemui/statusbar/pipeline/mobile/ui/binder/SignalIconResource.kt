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

package com.android.systemui.statusbar.pipeline.mobile.ui.binder

import android.annotation.DrawableRes
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R

/** R2 artwork selected for the independently-updating pieces of one mobile status icon. */
internal data class MobileIconPresentation(
    @DrawableRes val signalRes: Int,
    @DrawableRes val networkTypeRes: Int? = null,
    @DrawableRes val activityRes: Int? = null,
    @DrawableRes val roamingRes: Int? = null,
    @DrawableRes val noSimRes: Int = R.drawable.no_sim_icon,
)

internal object SignalIconResource {
    /** R2 hides the RAT only while Wi-Fi actually owns the default route, unless roaming. */
    fun shouldShowRat(hasDataType: Boolean, roaming: Boolean, wifiDefault: Boolean): Boolean =
        hasDataType && (roaming || !wifiDefault)

    private val sim1 =
        intArrayOf(
            R.drawable.stat_sys_signal_sim1_0,
            R.drawable.stat_sys_signal_sim1_1,
            R.drawable.stat_sys_signal_sim1_2,
            R.drawable.stat_sys_signal_sim1_3,
            R.drawable.stat_sys_signal_sim1_4,
            R.drawable.stat_sys_signal_sim1_5,
        )
    private val sim1Fully =
        intArrayOf(
            R.drawable.stat_sys_signal_sim1_0_fully,
            R.drawable.stat_sys_signal_sim1_1_fully,
            R.drawable.stat_sys_signal_sim1_2_fully,
            R.drawable.stat_sys_signal_sim1_3_fully,
            R.drawable.stat_sys_signal_sim1_4_fully,
            R.drawable.stat_sys_signal_sim1_5_fully,
        )
    private val sim2 =
        intArrayOf(
            R.drawable.stat_sys_signal_sim2_0,
            R.drawable.stat_sys_signal_sim2_1,
            R.drawable.stat_sys_signal_sim2_2,
            R.drawable.stat_sys_signal_sim2_3,
            R.drawable.stat_sys_signal_sim2_4,
            R.drawable.stat_sys_signal_sim2_5,
        )
    private val sim2Fully =
        intArrayOf(
            R.drawable.stat_sys_signal_sim2_0_fully,
            R.drawable.stat_sys_signal_sim2_1_fully,
            R.drawable.stat_sys_signal_sim2_2_fully,
            R.drawable.stat_sys_signal_sim2_3_fully,
            R.drawable.stat_sys_signal_sim2_4_fully,
            R.drawable.stat_sys_signal_sim2_5_fully,
        )
    private val generic =
        intArrayOf(
            R.drawable.stat_sys_signal_0,
            R.drawable.stat_sys_signal_1,
            R.drawable.stat_sys_signal_2,
            R.drawable.stat_sys_signal_3,
            R.drawable.stat_sys_signal_4,
            R.drawable.stat_sys_signal_5,
        )
    private val genericFully =
        intArrayOf(
            R.drawable.stat_sys_signal_0_fully,
            R.drawable.stat_sys_signal_1_fully,
            R.drawable.stat_sys_signal_2_fully,
            R.drawable.stat_sys_signal_3_fully,
            R.drawable.stat_sys_signal_4_fully,
            R.drawable.stat_sys_signal_5_fully,
        )
    private val coloredSim1 =
        intArrayOf(
            R.drawable.colored_stat_sys_signal_sim1_0,
            R.drawable.colored_stat_sys_signal_sim1_1,
            R.drawable.colored_stat_sys_signal_sim1_2,
            R.drawable.colored_stat_sys_signal_sim1_3,
            R.drawable.colored_stat_sys_signal_sim1_4,
            R.drawable.colored_stat_sys_signal_sim1_5,
        )
    private val coloredSim1Fully =
        intArrayOf(
            R.drawable.colored_stat_sys_signal_sim1_0_fully,
            R.drawable.colored_stat_sys_signal_sim1_1_fully,
            R.drawable.colored_stat_sys_signal_sim1_2_fully,
            R.drawable.colored_stat_sys_signal_sim1_3_fully,
            R.drawable.colored_stat_sys_signal_sim1_4_fully,
            R.drawable.colored_stat_sys_signal_sim1_5_fully,
        )
    private val coloredSim2 =
        intArrayOf(
            R.drawable.colored_stat_sys_signal_sim2_0,
            R.drawable.colored_stat_sys_signal_sim2_1,
            R.drawable.colored_stat_sys_signal_sim2_2,
            R.drawable.colored_stat_sys_signal_sim2_3,
            R.drawable.colored_stat_sys_signal_sim2_4,
            R.drawable.colored_stat_sys_signal_sim2_5,
        )
    private val coloredSim2Fully =
        intArrayOf(
            R.drawable.colored_stat_sys_signal_sim2_0_fully,
            R.drawable.colored_stat_sys_signal_sim2_1_fully,
            R.drawable.colored_stat_sys_signal_sim2_2_fully,
            R.drawable.colored_stat_sys_signal_sim2_3_fully,
            R.drawable.colored_stat_sys_signal_sim2_4_fully,
            R.drawable.colored_stat_sys_signal_sim2_5_fully,
        )
    private val coloredGeneric =
        intArrayOf(
            R.drawable.colored_stat_sys_signal_0,
            R.drawable.colored_stat_sys_signal_1,
            R.drawable.colored_stat_sys_signal_2,
            R.drawable.colored_stat_sys_signal_3,
            R.drawable.colored_stat_sys_signal_4,
            R.drawable.colored_stat_sys_signal_5,
        )
    private val coloredGenericFully =
        intArrayOf(
            R.drawable.colored_stat_sys_signal_0_fully,
            R.drawable.colored_stat_sys_signal_1_fully,
            R.drawable.colored_stat_sys_signal_2_fully,
            R.drawable.colored_stat_sys_signal_3_fully,
            R.drawable.colored_stat_sys_signal_4_fully,
            R.drawable.colored_stat_sys_signal_5_fully,
        )

    fun resolve(
        context: Context,
        subscriptionId: Int,
        level: Int,
        showExclamationMark: Boolean,
        carrierNetworkChange: Boolean,
        colorIcon: Boolean = false,
    ): Int =
        resolvePresentation(
                slot = activeDualSimSlot(context, subscriptionId),
                level = level,
                showExclamationMark = showExclamationMark,
                carrierNetworkChange = carrierNetworkChange,
                colorIcon = colorIcon,
            )
            .signalRes

    fun resolvePresentation(
        slot: Int?,
        level: Int,
        showExclamationMark: Boolean,
        carrierNetworkChange: Boolean,
        colorIcon: Boolean = false,
    ): MobileIconPresentation {
        if (carrierNetworkChange) {
            return MobileIconPresentation(
                signalRes = R.drawable.stat_sys_signal_carrier_network_change_animation
            )
        }

        val safeLevel = level.coerceIn(0, 5)
        val signalRes =
            when (slot) {
                0 ->
                    if (colorIcon) {
                        if (showExclamationMark) coloredSim1[safeLevel]
                        else coloredSim1Fully[safeLevel]
                    } else if (showExclamationMark) sim1[safeLevel] else sim1Fully[safeLevel]
                1 ->
                    if (colorIcon) {
                        if (showExclamationMark) coloredSim2[safeLevel]
                        else coloredSim2Fully[safeLevel]
                    } else if (showExclamationMark) sim2[safeLevel] else sim2Fully[safeLevel]
                else ->
                    if (colorIcon) {
                        if (showExclamationMark) coloredGeneric[safeLevel]
                        else coloredGenericFully[safeLevel]
                    } else if (showExclamationMark) generic[safeLevel]
                    else genericFully[safeLevel]
            }
        return MobileIconPresentation(signalRes = signalRes)
    }

    fun resolveNetworkType(icon: Icon.Resource): Icon.Resource {
        val r2Res =
            when (icon.resId) {
                TelephonyIcons.ICON_G -> R.drawable.stat_sys_data_fully_connected_g
                TelephonyIcons.ICON_E -> R.drawable.stat_sys_data_fully_connected_e
                TelephonyIcons.ICON_1X -> R.drawable.stat_sys_data_fully_connected_1x
                TelephonyIcons.ICON_3G -> R.drawable.stat_sys_data_fully_connected_3g
                TelephonyIcons.ICON_H -> R.drawable.stat_sys_data_fully_connected_h
                TelephonyIcons.ICON_H_PLUS -> R.drawable.stat_sys_data_fully_connected_hp
                TelephonyIcons.ICON_4G,
                TelephonyIcons.ICON_LTE,
                TelephonyIcons.ICON_4G_LTE -> R.drawable.stat_sys_data_fully_connected_4g
                TelephonyIcons.ICON_4G_PLUS,
                TelephonyIcons.ICON_LTE_PLUS,
                TelephonyIcons.ICON_4G_LTE_PLUS -> R.drawable.stat_sys_data_fully_connected_4gp
                TelephonyIcons.ICON_5G,
                TelephonyIcons.ICON_5G_PLUS,
                TelephonyIcons.ICON_5G_E -> R.drawable.stat_sys_data_fully_connected_5g
                else -> return icon
            }
        return Icon.Resource(r2Res, icon.contentDescription)
    }

    /**
     * R2 non-carrier branch: data activity is shown only while roaming and data is connected.
     * Idle roaming still occupies the activity layer with `inout_null`.
     */
    @DrawableRes
    fun resolveActivity(
        hasActivityIn: Boolean,
        hasActivityOut: Boolean,
        roaming: Boolean,
        dataConnected: Boolean,
    ): Int {
        if (!roaming || !dataConnected) return 0
        return when {
            hasActivityIn && hasActivityOut -> R.drawable.stat_sys_roaming_signal_inout
            hasActivityIn -> R.drawable.stat_sys_roaming_signal_in
            hasActivityOut -> R.drawable.stat_sys_roaming_signal_out
            else -> R.drawable.stat_sys_roaming_signal_inout_null
        }
    }

    private fun activeDualSimSlot(context: Context, subscriptionId: Int): Int? {
        val modemCount =
            try {
                context.getSystemService(TelephonyManager::class.java)?.activeModemCount ?: 1
            } catch (_: RuntimeException) {
                1
            }
        if (modemCount <= 1) return null

        return when (SubscriptionManager.getSlotIndex(subscriptionId)) {
            0 -> 0
            1 -> 1
            else -> null
        }
    }
}
