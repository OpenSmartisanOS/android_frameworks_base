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
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.WeakHashMap

/** Process-wide subscription state used by every Smartisan network cluster host. */
class NetworkClusterStateController private constructor(context: Context) {
    fun interface Callback {
        fun onSubscriptionStateChanged()
    }

    private val appContext = context.applicationContext
    private val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
    private val callbacks = WeakHashMap<Callback, Unit>()
    private var listening = false
    private var demoNoSim: Boolean? = null
    private var demoAirplane: Boolean? = null

    private val subscriptionListener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                notifyCallbacks()
            }
        }

    fun addCallback(callback: Callback) {
        callbacks[callback] = Unit
        if (!listening) {
            listening = true
            startListeners()
        }
        callback.onSubscriptionStateChanged()
    }

    fun removeCallback(callback: Callback) {
        callbacks.remove(callback)
        if (listening && callbacks.isEmpty()) {
            listening = false
            stopListeners()
        }
    }

    fun activeModemCount(): Int =
        try {
            telephonyManager?.activeModemCount ?: 0
        } catch (_: RuntimeException) {
            0
        }

    fun shouldShowNoSim(): Boolean {
        demoNoSim?.let { return it }
        if (telephonyManager?.isVoiceCapable != true) return false
        return try {
            subscriptionManager?.activeSubscriptionInfoCount == 0
        } catch (_: SecurityException) {
            false
        }
    }

    fun setDemoNoSim(show: Boolean?) {
        if (demoNoSim == show) return
        demoNoSim = show
        notifyCallbacks()
    }

    fun demoAirplane(): Boolean? = demoAirplane

    fun setDemoAirplane(show: Boolean?) {
        if (demoAirplane == show) return
        demoAirplane = show
        notifyCallbacks()
    }

    fun clearDemoOverrides() {
        if (demoNoSim == null && demoAirplane == null) return
        demoNoSim = null
        demoAirplane = null
        notifyCallbacks()
    }

    private fun startListeners() {
        try {
            subscriptionManager?.addOnSubscriptionsChangedListener(
                appContext.mainExecutor,
                subscriptionListener,
            )
        } catch (_: RuntimeException) {
            // Telephony may still be publishing its service during early SystemUI startup.
        }
    }

    private fun stopListeners() {
        try {
            subscriptionManager?.removeOnSubscriptionsChangedListener(subscriptionListener)
        } catch (_: RuntimeException) {
            // The telephony process may already have removed the listener.
        }
    }

    private fun notifyCallbacks() {
        callbacks.keys.toList().forEach { it.onSubscriptionStateChanged() }
    }

    companion object {
        @Volatile private var instance: NetworkClusterStateController? = null

        @JvmStatic
        fun get(context: Context): NetworkClusterStateController =
            instance
                ?: synchronized(this) {
                    instance
                        ?: NetworkClusterStateController(context).also { instance = it }
                }

        @JvmStatic
        fun slotIndex(subscriptionId: Int): Int {
            val slot = SubscriptionManager.getSlotIndex(subscriptionId)
            return if (slot == SubscriptionManager.INVALID_SIM_SLOT_INDEX) Int.MAX_VALUE else slot
        }
    }
}
