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

internal object SosSignalIconResource {
    fun resolve(
        context: Context,
        subscriptionId: Int,
        level: Int,
        showExclamationMark: Boolean,
        carrierNetworkChange: Boolean,
    ): Int {
        if (carrierNetworkChange) {
            return drawableId(context, "stat_sys_signal_carrier_network_change_animation")
        }

        val safeLevel = level.coerceIn(0, 5)
        val connectionSuffix = if (showExclamationMark) "" else "_fully"
        val slotSuffix = activeDualSimSlotSuffix(context, subscriptionId)
        if (slotSuffix != null) {
            val slotResource =
                drawableId(
                    context,
                    "stat_sys_signal_${slotSuffix}_${safeLevel}${connectionSuffix}",
                )
            if (slotResource != 0) return slotResource
        }

        return drawableId(context, "stat_sys_signal_${safeLevel}${connectionSuffix}")
    }

    private fun activeDualSimSlotSuffix(context: Context, subscriptionId: Int): String? {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        if (subscriptionManager?.activeSubscriptionInfoCount?.let { it > 1 } != true) return null

        return when (SubscriptionManager.getSlotIndex(subscriptionId)) {
            0 -> "sim1"
            1 -> "sim2"
            else -> null
        }
    }

    private fun drawableId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)
}
