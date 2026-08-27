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

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.DefaultTransport
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel

/** Immutable Smartisan R2 presentation state shared by every status-bar Wi-Fi view. */
data class WifiState(
    val visible: Boolean = false,
    val connected: Boolean = false,
    val level: Int = MIN_CONNECTED_LEVEL,
    val inetCondition: Int = INET_CONDITION_UNVALIDATED,
    val warning: Boolean = false,
    val wifi6: Boolean = false,
    val activity: Activity = Activity.NONE,
    val contentDescription: String = "",
    /**
     * The single default-route identity published by ConnectivityRepository. The fixed mobile
     * cluster consumes this same value so Wi-Fi warning and RAT visibility can never disagree
     * during a handover.
     */
    val defaultTransport: DefaultTransport = DefaultTransport.NONE,
) {
    enum class Activity {
        NONE,
        IN,
        OUT,
        INOUT,
    }

    companion object {
        const val INET_CONDITION_UNVALIDATED = 0
        const val INET_CONDITION_VALIDATED = 1
        const val MIN_CONNECTED_LEVEL = 1
        const val MAX_CONNECTED_LEVEL = 4
        private const val ORIGINAL_SIGNAL_BUCKETS = 4

        fun fromInputs(
            enabled: Boolean,
            forceHidden: Boolean,
            hasDataCapabilities: Boolean,
            network: WifiNetworkModel,
            defaultConnections: DefaultConnectionModel,
            warning: Boolean,
            activity: DataActivityModel,
            connectedDescription: (Int) -> String,
            disconnectedDescription: String,
        ): WifiState {
            if (!enabled || forceHidden || network is WifiNetworkModel.CarrierMerged) {
                return WifiState(defaultTransport = defaultConnections.defaultTransport)
            }

            if (network !is WifiNetworkModel.Active) {
                return if (hasDataCapabilities) {
                    WifiState(defaultTransport = defaultConnections.defaultTransport)
                } else {
                    WifiState(
                        visible = true,
                        contentDescription = disconnectedDescription,
                        defaultTransport = defaultConnections.defaultTransport,
                    )
                }
            }

            val originalLevel = originalLevel(network.rssi, network.level)
            return WifiState(
                visible = true,
                connected = true,
                level = originalLevel,
                inetCondition =
                    if (defaultConnections.isValidated) {
                        INET_CONDITION_VALIDATED
                    } else {
                        INET_CONDITION_UNVALIDATED
                    },
                warning = warning,
                wifi6 = network.wifiStandard == ScanResult.WIFI_STANDARD_11AX,
                activity = activity.toSosActivity(),
                contentDescription = connectedDescription(originalLevel),
                defaultTransport = defaultConnections.defaultTransport,
            )
        }

        /** Reproduces WifiManager.calculateSignalLevel(rssi, 4) + 1 from R2. */
        fun originalLevel(rssi: Int, fallbackLevel: Int): Int {
            if (rssi != WifiInfo.INVALID_RSSI) {
                return (WifiManager.calculateSignalLevel(rssi, ORIGINAL_SIGNAL_BUCKETS) + 1)
                    .coerceIn(MIN_CONNECTED_LEVEL, MAX_CONNECTED_LEVEL)
            }
            return (fallbackLevel + 1).coerceIn(MIN_CONNECTED_LEVEL, MAX_CONNECTED_LEVEL)
        }

        /** Original warning only applies when cellular, not Ethernet or VPN, owns the default. */
        fun shouldWarn(defaultConnections: DefaultConnectionModel, avoidBadWifi: Boolean): Boolean =
            avoidBadWifi &&
                defaultConnections.defaultTransport == DefaultTransport.MOBILE &&
                !defaultConnections.carrierMerged.isDefault &&
                !defaultConnections.isVpn

        private fun DataActivityModel.toSosActivity(): Activity =
            when {
                hasActivityIn && hasActivityOut -> Activity.INOUT
                hasActivityIn -> Activity.IN
                hasActivityOut -> Activity.OUT
                else -> Activity.NONE
            }
    }
}
