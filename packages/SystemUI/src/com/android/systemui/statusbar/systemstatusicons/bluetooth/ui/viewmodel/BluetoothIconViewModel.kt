/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.systemstatusicons.bluetooth.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose

/**
 * View model for the bluetooth connected system status icon. Emits a bluetooth connected icon when
 * a bluetooth device is connected. Null icon otherwise.
 */
class BluetoothIconViewModel
@AssistedInject
constructor(
    @Assisted context: Context,
    controller: BluetoothController,
) :
    SystemStatusIconViewModel.Default, ExclusiveActivatable() {
    init {
        SystemStatusIconsInCompose.expectInNewMode()
    }

    private val hydrator = Hydrator("BluetoothIconViewModel.hydrator")

    private val bluetoothState =
        conflatedCallbackFlow {
            fun current() =
                BluetoothStatus(
                    connected = controller.isBluetoothConnected(),
                    batteryLevel = controller.getBatteryLevel(),
                )
            val callback =
                object : BluetoothController.Callback {
                    override fun onBluetoothStateChange(enabled: Boolean) { trySend(current()) }
                    override fun onBluetoothDevicesChanged() { trySend(current()) }
                }
            controller.addCallback(callback)
            trySend(current())
            awaitClose { controller.removeCallback(callback) }
        }

    private val status: BluetoothStatus by
        hydrator.hydratedStateOf(
            traceName = null,
            initialValue = BluetoothStatus(false, -1),
            source = bluetoothState,
        )

    override val slotName = context.getString(com.android.internal.R.string.status_bar_bluetooth)

    override val visible: Boolean
        get() = status.connected

    override val icon: Icon?
        get() = status.toUiState()

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun BluetoothStatus.toUiState(): Icon? =
        if (connected) {
            val batteryRes =
                if (batteryLevel in 0..100) {
                    when ((batteryLevel / 10).coerceAtMost(9)) {
                        0 -> R.drawable.stat_sys_data_bluetooth_connected_battery_0
                        1 -> R.drawable.stat_sys_data_bluetooth_connected_battery_1
                        2 -> R.drawable.stat_sys_data_bluetooth_connected_battery_2
                        3 -> R.drawable.stat_sys_data_bluetooth_connected_battery_3
                        4 -> R.drawable.stat_sys_data_bluetooth_connected_battery_4
                        5 -> R.drawable.stat_sys_data_bluetooth_connected_battery_5
                        6 -> R.drawable.stat_sys_data_bluetooth_connected_battery_6
                        7 -> R.drawable.stat_sys_data_bluetooth_connected_battery_7
                        8 -> R.drawable.stat_sys_data_bluetooth_connected_battery_8
                        else -> R.drawable.stat_sys_data_bluetooth_connected_battery_9
                    }
                } else {
                    R.drawable.ic_bluetooth_connected
                }
            Icon.Resource(
                resId = batteryRes,
                contentDescription =
                    ContentDescription.Resource(R.string.accessibility_bluetooth_connected),
            )
        } else {
            null
        }

    @AssistedFactory
    interface Factory {
        fun create(context: Context): BluetoothIconViewModel
    }

    private data class BluetoothStatus(val connected: Boolean, val batteryLevel: Int)
}
