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
package com.android.systemui.statusbar.phone

import android.content.Context
import com.android.systemui.res.R
import com.android.internal.R.string as InternalString

/**
 * The factory R2 status bar does not put every framework slot in its dynamic icon merger.
 * Connectivity owns a fixed cluster, privacy owns the clock-side highlight, and only the
 * explicitly supported system slots enter the recency queue.
 */
object DynamicIconPolicy {
    const val SLOT_BLUETOOTH_HEADSET = "bluetooth_headset"
    const val SLOT_NORMAL_HEADSET = "normal_headset"
    const val SLOT_VOLTE = "volte"

    enum class Placement {
        FIXED_NETWORK,
        DYNAMIC,
        SAFETY_EXTENSION,
        PRIVACY_HIGHLIGHT,
        HIDDEN,
    }

    @JvmStatic
    fun classify(context: Context, slot: String): Placement {
        return when (slot) {
            context.getString(InternalString.status_bar_wifi),
            context.getString(InternalString.status_bar_mobile),
            context.getString(InternalString.status_bar_stacked_mobile),
            context.getString(InternalString.status_bar_airplane),
            context.getString(InternalString.status_bar_no_calling),
            context.getString(InternalString.status_bar_call_strength) -> Placement.FIXED_NETWORK

            context.getString(InternalString.status_bar_alarm_clock),
            context.getString(InternalString.status_bar_rotate),
            context.getString(InternalString.status_bar_data_saver),
            context.getString(InternalString.status_bar_sync_failing),
            context.getString(InternalString.status_bar_sync_active),
            context.getString(InternalString.status_bar_tty),
            context.getString(InternalString.status_bar_cdma_eri),
            context.getString(InternalString.status_bar_managed_profile),
            context.getString(InternalString.status_bar_vpn),
            context.getString(InternalString.status_bar_hotspot),
            context.getString(InternalString.status_bar_bluetooth),
            context.getString(InternalString.status_bar_volume),
            context.getString(InternalString.status_bar_zen),
            context.getString(InternalString.status_bar_cast),
            SLOT_BLUETOOTH_HEADSET,
            SLOT_NORMAL_HEADSET,
            SLOT_VOLTE -> Placement.DYNAMIC

            context.getString(InternalString.status_bar_sensors_off),
            context.getString(InternalString.status_bar_screen_record),
            context.getString(R.string.status_bar_firewall_slot) -> Placement.SAFETY_EXTENSION

            context.getString(InternalString.status_bar_camera),
            context.getString(InternalString.status_bar_microphone),
            context.getString(InternalString.status_bar_location) -> Placement.PRIVACY_HIGHLIGHT

            else -> Placement.HIDDEN
        }
    }

    /**
     * Factory R2 keeps these framework slots in the logical icon model, but hides them from the
     * built-in phone status bar unless an OEM overlay explicitly replaces the policy. Keep this
     * decision local to the canonical [IconManager], so every display uses the same factory rule.
     */
    @JvmStatic
    fun isFactoryHiddenByDefault(context: Context, slot: String): Boolean =
        slot == context.getString(InternalString.status_bar_rotate) ||
            slot == context.getString(InternalString.status_bar_headset)

    /** An explicitly persisted tuner value, including the empty string, overrides the factory. */
    @JvmStatic
    fun shouldApplyFactoryDefault(
        context: Context,
        slot: String,
        configuredHideList: String?,
    ): Boolean = configuredHideList == null && isFactoryHiddenByDefault(context, slot)

    /** A zero result means that the holder's resource must be used unchanged. */
    @JvmStatic
    fun resourceForSlot(context: Context, slot: String): Int =
        when (slot) {
            context.getString(InternalString.status_bar_alarm_clock) -> R.drawable.stat_sys_alarm
            context.getString(InternalString.status_bar_tty) -> R.drawable.stat_sys_tty_mode
            context.getString(InternalString.status_bar_managed_profile) ->
                R.drawable.stat_sys_managed_profile_status
            context.getString(InternalString.status_bar_sensors_off) ->
                R.drawable.sos_stat_sys_sensors_off
            context.getString(InternalString.status_bar_screen_record) ->
                R.drawable.sos_stat_sys_screen_record
            context.getString(R.string.status_bar_firewall_slot) ->
                R.drawable.sos_stat_sys_firewall
            else -> 0
        }

    @JvmStatic
    fun resourceForHost(context: Context, slot: String, sourceResource: Int): Int {
        return resourceForSlot(context, slot).takeIf { it != 0 } ?: sourceResource
    }

    /** Privacy is rendered by the R2 highlight host; unknown slots have no R2 presentation. */
    @JvmStatic
    fun shouldCreateView(context: Context, slot: String): Boolean =
        when (classify(context, slot)) {
            Placement.PRIVACY_HIGHLIGHT, Placement.HIDDEN -> false
            else -> true
        }

    @JvmStatic
    fun shouldAttachToFixedCluster(context: Context, slot: String): Boolean =
        classify(context, slot) == Placement.FIXED_NETWORK
}
