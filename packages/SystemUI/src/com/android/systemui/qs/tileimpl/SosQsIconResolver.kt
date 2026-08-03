/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tileimpl

import android.content.Context
import android.service.quicksettings.Tile
import android.telephony.SubscriptionManager
import androidx.annotation.DrawableRes
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.res.R

/** Maps the current QS pipeline state onto the original SmartisanOS stateful artwork. */
object SosQsIconResolver {
    fun apply(context: Context, state: QSTile.State) {
        val icon =
            when (state.spec) {
                "wifi", "internet" -> wifiIcon(state)
                "cell" -> mobileDataIcon(context, state)
                // Bluetooth supplies its battery bucket directly from BluetoothTile.
                "bt" -> null
                else -> iconFor(state.spec, state.state == Tile.STATE_ACTIVE)
            }
        icon?.let { state.icon = QSTileImpl.ResourceIcon.get(it) }
        if (
            (state.spec == "wifi" || state.spec == "internet") &&
                state.state == Tile.STATE_ACTIVE &&
                !state.isTransient &&
                !state.secondaryLabel.isNullOrBlank()
        ) {
            state.label = state.secondaryLabel
            state.secondaryLabel = null
        } else {
            labelFor(state.spec)?.let { state.label = context.getString(it) }
        }
    }

    @androidx.annotation.StringRes
    private fun labelFor(spec: String?): Int? =
        when (spec) {
            "airplane" -> R.string.sos_qs_label_airplane
            "wifi", "internet" -> R.string.sos_qs_label_wifi
            "cell" -> R.string.sos_qs_label_cell
            "hotspot" -> R.string.sos_qs_label_hotspot
            "dnd", "modes_dnd", "sos_disable_buttons" -> R.string.sos_qs_label_dnd
            "location" -> R.string.sos_qs_label_location
            "flashlight" -> R.string.sos_qs_label_flashlight
            "rotation" -> R.string.sos_qs_label_rotation
            "screenrecord" -> R.string.sos_qs_label_screenrecord
            "battery" -> R.string.sos_qs_label_battery
            "sos_screenshot" -> R.string.sos_qs_label_screenshot
            "sos_vibrate" -> R.string.sos_qs_label_vibrate
            "sos_mute" -> R.string.sos_qs_label_mute
            "nfc" -> R.string.sos_qs_label_nfc
            "caffeine" -> R.string.sos_qs_label_caffeine
            "sos_lock_screen" -> R.string.sos_qs_label_lock
            "sos_protect_eyes" -> R.string.sos_qs_label_protect_eyes
            "sos_fake_call" -> R.string.sos_qs_label_fake_call
            else -> null
        }

    @DrawableRes
    private fun wifiIcon(state: QSTile.State): Int =
        when {
            state.state == Tile.STATE_UNAVAILABLE -> R.drawable.smartisan_qs_wifi_disable
            state.isTransient -> R.drawable.smartisan_qs_wifi_button_anim
            state.state == Tile.STATE_ACTIVE -> R.drawable.smartisan_qs_wifi_on
            else -> R.drawable.smartisan_qs_wifi_off
        }

    @DrawableRes
    private fun mobileDataIcon(context: Context, state: QSTile.State): Int {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscriptions = subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        val defaultDataSlot =
            SubscriptionManager.getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId())
        val useSim2 = subscriptions.size > 1 && defaultDataSlot == 1
        return if (useSim2) {
            if (state.state == Tile.STATE_ACTIVE) {
                R.drawable.smartisan_qs_signal_sim2_on
            } else {
                R.drawable.smartisan_qs_signal_sim2_off
            }
        } else {
            if (state.state == Tile.STATE_ACTIVE) {
                R.drawable.smartisan_qs_signal_sim1_on
            } else {
                R.drawable.smartisan_qs_signal_sim1_off
            }
        }
    }

    @DrawableRes
    private fun iconFor(spec: String?, active: Boolean): Int? =
        when (spec) {
            "flashlight" ->
                if (active) R.drawable.smartisan_qs_flashlight_on else R.drawable.smartisan_qs_flashlight_off
            "dnd", "modes_dnd" ->
                if (active) R.drawable.smartisan_qs_undisturb_on else R.drawable.smartisan_qs_undisturb_off
            "airplane" ->
                if (active) R.drawable.smartisan_qs_airplane_on else R.drawable.smartisan_qs_airplane_off
            "rotation" ->
                if (active) R.drawable.smartisan_qs_auto_rotation_on else R.drawable.smartisan_qs_auto_rotation_off
            "battery" ->
                if (active) R.drawable.smartisan_qs_battery_save_on else R.drawable.smartisan_qs_battery_save_off
            "screenrecord" ->
                if (active) R.drawable.smartisan_qs_screen_recording_on else R.drawable.smartisan_qs_screen_recording_off
            "nfc" ->
                if (active) R.drawable.smartisan_qs_nfc_on else R.drawable.smartisan_qs_nfc_off
            "location" ->
                if (active) R.drawable.smartisan_qs_location_on else R.drawable.smartisan_qs_location_off
            "hotspot" ->
                if (active) R.drawable.smartisan_qs_hotspot_on else R.drawable.smartisan_qs_hotspot_off
            "vpn" ->
                if (active) R.drawable.smartisan_qs_vpn_on else R.drawable.smartisan_qs_vpn_off
            "reading_mode", "night" ->
                if (active) R.drawable.smartisan_qs_reading_mode_on else R.drawable.smartisan_qs_reading_mode_off
            "caffeine" ->
                if (active) R.drawable.smartisan_qs_screen_neveroff_on else R.drawable.smartisan_qs_screen_neveroff_off
            "sos_screenshot" -> R.drawable.smartisan_qs_screenshot_off
            "sos_vibrate" ->
                if (active) R.drawable.smartisan_qs_vibrate_on else R.drawable.smartisan_qs_vibrate_off
            "sos_mute" ->
                if (active) R.drawable.smartisan_qs_mute_on else R.drawable.smartisan_qs_mute_off
            "sos_lock_screen" -> R.drawable.smartisan_qs_lock_screen_off
            "sos_protect_eyes" ->
                if (active) R.drawable.smartisan_qs_night_shift_on else R.drawable.smartisan_qs_night_shift_off
            "sos_fake_call" ->
                if (active) R.drawable.smartisan_qs_fake_call_on else R.drawable.smartisan_qs_fake_call_off
            "sos_disable_buttons" ->
                if (active) R.drawable.smartisan_qs_undisturb_on
                else R.drawable.smartisan_qs_undisturb_off
            else -> null
        }
}
