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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class DynamicIconPolicyTest : SysuiTestCase() {
    @Test
    fun factoryDynamicSlots_areAcceptedByMerger() {
        val slots =
            listOf(
                internalSlot(com.android.internal.R.string.status_bar_alarm_clock),
                internalSlot(com.android.internal.R.string.status_bar_rotate),
                internalSlot(com.android.internal.R.string.status_bar_data_saver),
                internalSlot(com.android.internal.R.string.status_bar_sync_active),
                internalSlot(com.android.internal.R.string.status_bar_sync_failing),
                internalSlot(com.android.internal.R.string.status_bar_tty),
                internalSlot(com.android.internal.R.string.status_bar_cdma_eri),
                internalSlot(com.android.internal.R.string.status_bar_managed_profile),
                internalSlot(com.android.internal.R.string.status_bar_vpn),
                internalSlot(com.android.internal.R.string.status_bar_hotspot),
                internalSlot(com.android.internal.R.string.status_bar_bluetooth),
                DynamicIconPolicy.SLOT_BLUETOOTH_HEADSET,
                DynamicIconPolicy.SLOT_NORMAL_HEADSET,
                internalSlot(com.android.internal.R.string.status_bar_volume),
                internalSlot(com.android.internal.R.string.status_bar_zen),
                internalSlot(com.android.internal.R.string.status_bar_cast),
                DynamicIconPolicy.SLOT_VOLTE,
            )

        slots.forEach { slot ->
            assertThat(DynamicIconPolicy.classify(context, slot))
                .isEqualTo(DynamicIconPolicy.Placement.DYNAMIC)
            assertThat(DynamicIconPolicy.shouldAttachToFixedCluster(context, slot)).isFalse()
        }
    }

    @Test
    fun factoryBlacklist_hidesOnlyLegacyRotateAndHeadsetSlots() {
        val rotate = internalSlot(com.android.internal.R.string.status_bar_rotate)
        val legacyHeadset = internalSlot(com.android.internal.R.string.status_bar_headset)

        assertThat(DynamicIconPolicy.isFactoryHiddenByDefault(context, rotate)).isTrue()
        assertThat(DynamicIconPolicy.isFactoryHiddenByDefault(context, legacyHeadset)).isTrue()
        assertThat(DynamicIconPolicy.shouldCreateView(context, legacyHeadset)).isFalse()
        assertThat(
                DynamicIconPolicy.isFactoryHiddenByDefault(
                    context,
                    DynamicIconPolicy.SLOT_NORMAL_HEADSET,
                )
            )
            .isFalse()
        assertThat(
                DynamicIconPolicy.isFactoryHiddenByDefault(
                    context,
                    DynamicIconPolicy.SLOT_BLUETOOTH_HEADSET,
                )
            )
            .isFalse()
        assertThat(
                DynamicIconPolicy.shouldApplyFactoryDefault(
                    context,
                    rotate,
                    configuredHideList = null,
                )
            )
            .isTrue()
        assertThat(
                DynamicIconPolicy.shouldApplyFactoryDefault(
                    context,
                    rotate,
                    configuredHideList = "",
                )
            )
            .isFalse()
    }

    @Test
    fun supersededPlatformSlots_areNotCreatedInCanonicalHost() {
        val sharedPlatformSlots =
            listOf(
                com.android.internal.R.string.status_bar_mute,
                com.android.internal.R.string.status_bar_connected_display,
                com.android.internal.R.string.status_bar_headset,
            )

        sharedPlatformSlots.forEach { resource ->
            val slot = internalSlot(resource)
            assertThat(DynamicIconPolicy.classify(context, slot))
                .isEqualTo(DynamicIconPolicy.Placement.HIDDEN)
            assertThat(DynamicIconPolicy.shouldCreateView(context, slot)).isFalse()
        }
    }

    @Test
    fun canonicalStandardSlots_andFactoryExtensions_areRecognized() {
        listOf(
                internalSlot(com.android.internal.R.string.status_bar_bluetooth),
                internalSlot(com.android.internal.R.string.status_bar_volume),
                internalSlot(com.android.internal.R.string.status_bar_zen),
                internalSlot(com.android.internal.R.string.status_bar_cast),
                DynamicIconPolicy.SLOT_BLUETOOTH_HEADSET,
                DynamicIconPolicy.SLOT_NORMAL_HEADSET,
                DynamicIconPolicy.SLOT_VOLTE,
            )
            .forEach {
                assertThat(DynamicIconPolicy.classify(context, it))
                    .isEqualTo(DynamicIconPolicy.Placement.DYNAMIC)
            }
    }

    @Test
    fun hostResourceMapping_usesCanonicalArtwork() {
        val alarmSlot = internalSlot(com.android.internal.R.string.status_bar_alarm_clock)
        assertThat(
                DynamicIconPolicy.resourceForSlot(
                    context,
                    alarmSlot,
                )
            )
            .isEqualTo(R.drawable.stat_sys_alarm)
        assertThat(
                DynamicIconPolicy.resourceForSlot(
                    context,
                    internalSlot(com.android.internal.R.string.status_bar_tty),
                )
            )
            .isEqualTo(R.drawable.stat_sys_tty_mode)
        assertThat(
                DynamicIconPolicy.resourceForSlot(
                    context,
                    internalSlot(com.android.internal.R.string.status_bar_bluetooth),
                )
            )
            .isEqualTo(0)
        assertThat(DynamicIconPolicy.resourceForSlot(context, "unknown")).isEqualTo(0)

        val platformAlarm = R.drawable.platform_stat_sys_alarm
        assertThat(
                DynamicIconPolicy.resourceForHost(context, alarmSlot, platformAlarm)
            )
            .isEqualTo(R.drawable.stat_sys_alarm)
    }

    @Test
    fun networkAndPrivacySlots_neverEnterDynamicMerger() {
        val fixed =
            listOf(
                internalSlot(com.android.internal.R.string.status_bar_wifi),
                internalSlot(com.android.internal.R.string.status_bar_mobile),
                internalSlot(com.android.internal.R.string.status_bar_stacked_mobile),
                internalSlot(com.android.internal.R.string.status_bar_airplane),
                internalSlot(com.android.internal.R.string.status_bar_no_calling),
                internalSlot(com.android.internal.R.string.status_bar_call_strength),
            )
        val privacy =
            listOf(
                internalSlot(com.android.internal.R.string.status_bar_camera),
                internalSlot(com.android.internal.R.string.status_bar_microphone),
                internalSlot(com.android.internal.R.string.status_bar_location),
            )

        fixed.forEach {
            assertThat(DynamicIconPolicy.classify(context, it))
                .isEqualTo(DynamicIconPolicy.Placement.FIXED_NETWORK)
        }
        privacy.forEach {
            assertThat(DynamicIconPolicy.classify(context, it))
                .isEqualTo(DynamicIconPolicy.Placement.PRIVACY_HIGHLIGHT)
        }
        fixed.forEach {
            assertThat(DynamicIconPolicy.shouldAttachToFixedCluster(context, it)).isTrue()
        }
        privacy.forEach {
            assertThat(DynamicIconPolicy.shouldAttachToFixedCluster(context, it)).isFalse()
            assertThat(DynamicIconPolicy.shouldCreateView(context, it)).isFalse()
        }
    }

    @Test
    fun onlyApprovedSafetyExtensionsEnterDynamicMerger() {
        val safety =
            listOf(
                internalSlot(com.android.internal.R.string.status_bar_sensors_off),
                internalSlot(com.android.internal.R.string.status_bar_screen_record),
                context.getString(R.string.status_bar_firewall_slot),
            )

        safety.forEach {
            assertThat(DynamicIconPolicy.classify(context, it))
                .isEqualTo(DynamicIconPolicy.Placement.SAFETY_EXTENSION)
            assertThat(DynamicIconPolicy.shouldAttachToFixedCluster(context, it)).isFalse()
        }
        assertThat(DynamicIconPolicy.classify(context, "ethernet"))
            .isEqualTo(DynamicIconPolicy.Placement.HIDDEN)
        assertThat(DynamicIconPolicy.classify(context, "satellite"))
            .isEqualTo(DynamicIconPolicy.Placement.HIDDEN)
        listOf(
                com.android.internal.R.string.status_bar_ime,
                com.android.internal.R.string.status_bar_nfc,
                com.android.internal.R.string.status_bar_speakerphone,
                com.android.internal.R.string.status_bar_secure,
            )
            .forEach {
                assertThat(DynamicIconPolicy.classify(context, internalSlot(it)))
                    .isEqualTo(DynamicIconPolicy.Placement.HIDDEN)
            }
        assertThat(DynamicIconPolicy.shouldAttachToFixedCluster(context, "ethernet")).isFalse()
        assertThat(DynamicIconPolicy.shouldCreateView(context, "ethernet")).isFalse()
    }

    private fun internalSlot(resId: Int): String = context.getString(resId)
}
