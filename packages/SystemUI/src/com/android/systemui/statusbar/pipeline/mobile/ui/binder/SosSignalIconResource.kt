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

import android.content.Context
import android.telephony.SubscriptionManager
import com.android.systemui.res.R

internal object SosSignalIconResource {
    private val sim1 = intArrayOf(
        R.drawable.stat_sys_signal_sim1_0,
        R.drawable.stat_sys_signal_sim1_1,
        R.drawable.stat_sys_signal_sim1_2,
        R.drawable.stat_sys_signal_sim1_3,
        R.drawable.stat_sys_signal_sim1_4,
        R.drawable.stat_sys_signal_sim1_5,
    )
    private val sim1Fully = intArrayOf(
        R.drawable.stat_sys_signal_sim1_0_fully,
        R.drawable.stat_sys_signal_sim1_1_fully,
        R.drawable.stat_sys_signal_sim1_2_fully,
        R.drawable.stat_sys_signal_sim1_3_fully,
        R.drawable.stat_sys_signal_sim1_4_fully,
        R.drawable.stat_sys_signal_sim1_5_fully,
    )
    private val sim2 = intArrayOf(
        R.drawable.stat_sys_signal_sim2_0,
        R.drawable.stat_sys_signal_sim2_1,
        R.drawable.stat_sys_signal_sim2_2,
        R.drawable.stat_sys_signal_sim2_3,
        R.drawable.stat_sys_signal_sim2_4,
        R.drawable.stat_sys_signal_sim2_5,
    )
    private val sim2Fully = intArrayOf(
        R.drawable.stat_sys_signal_sim2_0_fully,
        R.drawable.stat_sys_signal_sim2_1_fully,
        R.drawable.stat_sys_signal_sim2_2_fully,
        R.drawable.stat_sys_signal_sim2_3_fully,
        R.drawable.stat_sys_signal_sim2_4_fully,
        R.drawable.stat_sys_signal_sim2_5_fully,
    )
    private val generic = intArrayOf(
        R.drawable.stat_sys_signal_0,
        R.drawable.stat_sys_signal_1,
        R.drawable.stat_sys_signal_2,
        R.drawable.stat_sys_signal_3,
        R.drawable.stat_sys_signal_4,
    )
    private val genericFully = intArrayOf(
        R.drawable.stat_sys_signal_0_fully,
        R.drawable.stat_sys_signal_1_fully,
        R.drawable.stat_sys_signal_2_fully,
        R.drawable.stat_sys_signal_3_fully,
        R.drawable.stat_sys_signal_4_fully,
    )

    fun resolve(
        context: Context,
        subscriptionId: Int,
        level: Int,
        showExclamationMark: Boolean,
        carrierNetworkChange: Boolean,
    ): Int {
        if (carrierNetworkChange) {
            return R.drawable.stat_sys_signal_carrier_network_change_animation
        }

        val safeLevel = level.coerceIn(0, 5)
        val slot = activeDualSimSlot(context, subscriptionId)
        if (slot >= 0) {
            return when {
                slot == 0 && showExclamationMark -> sim1[safeLevel]
                slot == 0 -> sim1Fully[safeLevel]
                showExclamationMark -> sim2[safeLevel]
                else -> sim2Fully[safeLevel]
            }
        }

        val genericLevel = safeLevel.coerceAtMost(generic.lastIndex)
        return if (showExclamationMark) generic[genericLevel] else genericFully[genericLevel]
    }

    private fun activeDualSimSlot(context: Context, subscriptionId: Int): Int {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        if (subscriptionManager?.activeSubscriptionInfoCount?.let { it > 1 } != true) return -1

        return when (SubscriptionManager.getSlotIndex(subscriptionId)) {
            0 -> 0
            1 -> 1
            else -> -1
        }
    }
}
