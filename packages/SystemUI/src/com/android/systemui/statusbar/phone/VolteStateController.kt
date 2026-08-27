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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.feature.MmTelFeature
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import java.util.WeakHashMap
import javax.inject.Inject

/** Standard Android IMS-backed replacement for Smartisan's private VoLTE status source. */
@SysUISingleton
class VolteStateController
@Inject
constructor(
    @Application context: Context,
    private val broadcastDispatcher: BroadcastDispatcher,
) {
    enum class State {
        HIDDEN,
        DISABLED,
        READY,
        CALLING,
    }

    fun interface Callback {
        fun onVolteStateChanged(state: State)
    }

    internal data class SubscriptionSnapshot(
        val carrierAvailable: Boolean? = null,
        val supportKnown: Boolean = false,
        val supported: Boolean = false,
        val userEnabled: Boolean = true,
        val inService: Boolean = false,
        val registeredOnCellular: Boolean = false,
        val voiceCapable: Boolean = false,
        val callState: Int = TelephonyManager.CALL_STATE_IDLE,
    )

    private class SubscriptionTelephonyCallback(
        private val serviceStateChanged: (ServiceState) -> Unit,
        private val callStateChanged: (Int) -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.ServiceStateListener,
        TelephonyCallback.CallStateListener {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            serviceStateChanged(serviceState)
        }

        override fun onCallStateChanged(state: Int) {
            callStateChanged(state)
        }
    }

    private data class SubscriptionState(
        val manager: ImsMmTelManager,
        val telephonyManager: TelephonyManager,
        val registrationCallback: ImsMmTelManager.RegistrationCallback,
        val capabilityCallback: ImsMmTelManager.CapabilityCallback,
        val telephonyCallback: SubscriptionTelephonyCallback,
        var supportKnown: Boolean = false,
        var carrierAvailable: Boolean? = null,
        var supported: Boolean = false,
        var userEnabled: Boolean = true,
        var inService: Boolean = false,
        var registeredOnCellular: Boolean = false,
        var voiceCapable: Boolean = false,
        var callState: Int = TelephonyManager.CALL_STATE_IDLE,
    ) {
        fun snapshot() =
            SubscriptionSnapshot(
                carrierAvailable = carrierAvailable,
                supportKnown = supportKnown,
                supported = supported,
                userEnabled = userEnabled,
                inService = inService,
                registeredOnCellular = registeredOnCellular,
                voiceCapable = voiceCapable,
                callState = callState,
            )
    }

    private val appContext = context.applicationContext
    private val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
    private val carrierConfigManager =
        appContext.getSystemService(CarrierConfigManager::class.java)
    private val callbacks = WeakHashMap<Callback, Unit>()
    private val subscriptions = LinkedHashMap<Int, SubscriptionState>()
    private var listening = false
    private var lastState = State.HIDDEN

    private val subscriptionListener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                rebuildSubscriptions()
            }
        }
    private val carrierConfigReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val changedSubId =
                    intent?.getIntExtra(
                        CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                    ) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                if (SubscriptionManager.isValidSubscriptionId(changedSubId)) {
                    subscriptions[changedSubId]?.let { state ->
                        state.carrierAvailable = readCarrierAvailable(changedSubId)
                    }
                } else {
                    subscriptions.forEach { (subId, state) ->
                        state.carrierAvailable = readCarrierAvailable(subId)
                    }
                }
                updateVisible()
            }
        }

    fun addCallback(callback: Callback) {
        callbacks[callback] = Unit
        if (!listening) {
            listening = true
            startListening()
        }
        callback.onVolteStateChanged(lastState)
    }

    fun removeCallback(callback: Callback) {
        callbacks.remove(callback)
        if (listening && callbacks.isEmpty()) {
            listening = false
            stopListening()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        broadcastDispatcher.registerReceiver(
            carrierConfigReceiver,
            IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
            appContext.mainExecutor,
            UserHandle.ALL,
        )
        try {
            subscriptionManager?.addOnSubscriptionsChangedListener(
                appContext.mainExecutor,
                subscriptionListener,
            )
        } catch (_: RuntimeException) {
            // SystemUI may start before telephony has published all services. Rebuilding below is
            // still safe and the next process start/subscription event will retry registration.
        }
        rebuildSubscriptions()
    }

    private fun stopListening() {
        broadcastDispatcher.unregisterReceiver(carrierConfigReceiver)
        try {
            subscriptionManager?.removeOnSubscriptionsChangedListener(subscriptionListener)
        } catch (_: RuntimeException) {
            // Listener was already removed with the telephony process.
        }
        subscriptions.values.forEach(::unregister)
        subscriptions.clear()
        updateVisible()
    }

    @SuppressLint("MissingPermission")
    private fun rebuildSubscriptions() {
        val activeIds =
            try {
                subscriptionManager?.activeSubscriptionInfoList
                    ?.map { it.subscriptionId }
                    ?.toSet()
                    .orEmpty()
            } catch (_: RuntimeException) {
                emptySet()
            }

        val removed = subscriptions.keys.filterNot(activeIds::contains)
        for (subId in removed) {
            subscriptions.remove(subId)?.let(::unregister)
        }
        for (subId in activeIds) {
            if (!subscriptions.containsKey(subId)) register(subId)
        }
        updateVisible()
    }

    private fun register(subId: Int) {
        val manager =
            try {
                ImsMmTelManager.createForSubscriptionId(subId)
            } catch (_: RuntimeException) {
                return
            }
        val subscriptionTelephonyManager =
            try {
                telephonyManager?.createForSubscriptionId(subId)
            } catch (_: RuntimeException) {
                null
            } ?: return
        lateinit var state: SubscriptionState
        val registration =
            object : ImsMmTelManager.RegistrationCallback() {
                override fun onRegistered(imsTransportType: Int) {
                    state.registeredOnCellular =
                        imsTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
                    refreshUserEnabled(state)
                    updateVisible()
                }

                override fun onRegistering(imsTransportType: Int) {
                    state.registeredOnCellular = false
                    refreshUserEnabled(state)
                    updateVisible()
                }

                override fun onUnregistered(info: ImsReasonInfo) {
                    state.registeredOnCellular = false
                    refreshUserEnabled(state)
                    updateVisible()
                }
            }
        val capability =
            object : ImsMmTelManager.CapabilityCallback() {
                override fun onCapabilitiesStatusChanged(
                    capabilities: MmTelFeature.MmTelCapabilities
                ) {
                    state.voiceCapable =
                        capabilities.isCapable(
                            MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE
                        )
                    refreshUserEnabled(state)
                    updateVisible()
                }
            }
        val telephonyCallback =
            SubscriptionTelephonyCallback(
                serviceStateChanged = { serviceState ->
                    state.inService =
                        serviceState.state == ServiceState.STATE_IN_SERVICE ||
                            serviceState.dataRegistrationState == ServiceState.STATE_IN_SERVICE
                    updateVisible()
                },
                callStateChanged = { callState ->
                    state.callState = callState
                    updateVisible()
                },
            )
        state =
            SubscriptionState(
                manager = manager,
                telephonyManager = subscriptionTelephonyManager,
                registrationCallback = registration,
                capabilityCallback = capability,
                telephonyCallback = telephonyCallback,
                carrierAvailable = readCarrierAvailable(subId),
            )
        subscriptions[subId] = state
        try {
            manager.registerImsRegistrationCallback(appContext.mainExecutor, registration)
            manager.registerMmTelCapabilityCallback(appContext.mainExecutor, capability)
            subscriptionTelephonyManager.registerTelephonyCallback(
                appContext.mainExecutor,
                telephonyCallback,
            )
            refreshUserEnabled(state)
            manager.isSupported(
                MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                appContext.mainExecutor,
            ) { supported ->
                if (subscriptions[subId] === state) {
                    state.supportKnown = true
                    state.supported = supported
                    updateVisible()
                }
            }
        } catch (_: ImsException) {
            subscriptions.remove(subId)
            unregister(state)
        } catch (_: RuntimeException) {
            subscriptions.remove(subId)
            unregister(state)
        }
    }

    private fun unregister(state: SubscriptionState) {
        try {
            state.manager.unregisterImsRegistrationCallback(state.registrationCallback)
        } catch (_: RuntimeException) {
            // Telephony process is gone or registration never completed.
        }
        try {
            state.manager.unregisterMmTelCapabilityCallback(state.capabilityCallback)
        } catch (_: RuntimeException) {
            // Telephony process is gone or registration never completed.
        }
        try {
            state.telephonyManager.unregisterTelephonyCallback(state.telephonyCallback)
        } catch (_: RuntimeException) {
            // Telephony process is gone or registration never completed.
        }
    }

    private fun refreshUserEnabled(state: SubscriptionState) {
        state.userEnabled =
            try {
                state.manager.isAdvancedCallingSettingEnabled
            } catch (_: ImsException) {
                true
            } catch (_: RuntimeException) {
                true
            }
    }

    /**
     * Carrier config is an additional eligibility boundary, not a replacement for the live IMS
     * capability query. A temporarily unavailable config remains unknown so early SystemUI boot
     * does not incorrectly suppress a registration that the telephony service has already proven.
     */
    private fun readCarrierAvailable(subId: Int): Boolean? =
        try {
            carrierConfigManager
                ?.getConfigForSubId(subId)
                ?.takeIf { it.containsKey(CARRIER_VOLTE_AVAILABLE_KEY) }
                ?.getBoolean(CARRIER_VOLTE_AVAILABLE_KEY)
        } catch (_: RuntimeException) {
            null
        }

    private fun updateVisible() {
        val deviceVoiceCapable =
            try {
                telephonyManager?.isVoiceCapable == true
            } catch (_: RuntimeException) {
                false
            }
        val state =
            resolveState(
                deviceVoiceCapable = deviceVoiceCapable,
                snapshots = subscriptions.values.map(SubscriptionState::snapshot),
            )
        if (state == lastState) return
        lastState = state
        callbacks.keys.toList().forEach { it.onVolteStateChanged(state) }
    }

    internal companion object {
        fun resolveState(
            deviceVoiceCapable: Boolean,
            snapshots: Collection<SubscriptionSnapshot>,
        ): State {
            if (!deviceVoiceCapable || snapshots.isEmpty()) return State.HIDDEN
            val eligible =
                snapshots.filter {
                    it.carrierAvailable != false && it.supportKnown && it.supported
                }
            if (eligible.isEmpty()) return State.HIDDEN
            val ready =
                eligible.filter {
                    it.userEnabled &&
                        it.inService &&
                        it.registeredOnCellular &&
                        it.voiceCapable
                }
            if (ready.any { it.callState == TelephonyManager.CALL_STATE_OFFHOOK }) {
                return State.CALLING
            }
            if (ready.isNotEmpty()) return State.READY
            return if (eligible.any { it.inService }) State.DISABLED else State.HIDDEN
        }

        private const val CARRIER_VOLTE_AVAILABLE_KEY = "carrier_volte_available_bool"
    }
}
