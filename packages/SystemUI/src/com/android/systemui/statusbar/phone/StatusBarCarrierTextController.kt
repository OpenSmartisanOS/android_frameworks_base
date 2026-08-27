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
import android.widget.TextView
import com.android.keyguard.CarrierTextManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject

/** Shares the platform carrier state between every status-bar host. */
@SysUISingleton
class StatusBarCarrierTextController
@Inject
constructor(
    builder: CarrierTextManager.Builder,
    keyguardUpdateMonitor: KeyguardUpdateMonitor,
    @Application context: Context,
) {
    private val separator =
        context.getString(com.android.internal.R.string.kg_text_message_separator)
    private val manager =
        builder
            .setShowAirplaneMode(false)
            .setShowMissingSim(false)
            .setDebugLocationString("StatusBar")
            .build()
    private val hosts = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
    private var carrierText: CharSequence = ""
    private val callback =
        object : CarrierTextManager.CarrierTextCallback {
            override fun updateCarrierInfo(info: CarrierTextManager.CarrierTextCallbackInfo) {
                carrierText = carrierTextForInServiceSubscriptions(info, keyguardUpdateMonitor)
                hosts.toList().forEach { it.text = carrierText }
            }
        }

    private fun carrierTextForInServiceSubscriptions(
        info: CarrierTextManager.CarrierTextCallbackInfo,
        keyguardUpdateMonitor: KeyguardUpdateMonitor,
    ): CharSequence {
        if (info.airplaneMode || !info.anySimReady) return ""
        if (info.isInSatelliteMode) return info.carrierText ?: ""
        val subscriptionIds = info.subscriptionIds ?: return ""
        val carriers = info.listOfCarriers ?: return ""
        return subscriptionIds.indices
            .mapNotNull { index ->
                val subId = subscriptionIds[index]
                if (!Utils.isInService(keyguardUpdateMonitor.getServiceState(subId))) {
                    return@mapNotNull null
                }
                carriers.getOrNull(index)?.takeIf { it.isNotBlank() }
            }
            .joinToString(separator)
    }

    fun registerHost(view: TextView) {
        val startListening = hosts.isEmpty()
        hosts.add(view)
        view.text = carrierText
        if (startListening) manager.setListening(callback)
    }

    fun unregisterHost(view: TextView) {
        hosts.remove(view)
        if (hosts.isEmpty()) manager.setListening(null)
    }
}
