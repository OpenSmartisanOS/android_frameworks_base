/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.volume

import android.media.AudioManager
import android.media.AudioSystem
import android.util.SparseArray
import androidx.test.filters.SmallTest
import com.android.systemui.plugins.VolumeDialogController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class VolumeDialogStateTest {
    @Test
    fun streams_mapToOriginalThreeAudioFamilies() {
        val states = SparseArray<VolumeDialogController.StreamState>()

        assertThat(VolumeDialogState.columnForStream(AudioManager.STREAM_RING, states))
            .isEqualTo(VolumeDialogState.Column.RINGER)
        assertThat(VolumeDialogState.columnForStream(AudioManager.STREAM_NOTIFICATION, states))
            .isEqualTo(VolumeDialogState.Column.RINGER)
        assertThat(VolumeDialogState.columnForStream(AudioManager.STREAM_VOICE_CALL, states))
            .isEqualTo(VolumeDialogState.Column.CALL)
        assertThat(VolumeDialogState.columnForStream(AudioManager.STREAM_ALARM, states))
            .isEqualTo(VolumeDialogState.Column.ALARM)
        assertThat(VolumeDialogState.columnForStream(AudioSystem.STREAM_ASSISTANT, states))
            .isEqualTo(VolumeDialogState.Column.ACCESSIBILITY)
        assertThat(VolumeDialogState.columnForStream(AudioManager.STREAM_MUSIC, states))
            .isEqualTo(VolumeDialogState.Column.MEDIA)
    }

    @Test
    fun dynamicAndBluetoothStreams_useMediaColumnAndBluetoothRoute() {
        val platform = VolumeDialogController.State()
        val remote = VolumeDialogController.StreamState()
        remote.dynamic = true
        remote.routedToBluetooth = true
        remote.level = 5
        remote.levelMax = 15
        platform.activeStream = 100
        platform.states.put(100, remote)

        val result =
            VolumeDialogState.from(
                platform,
                10,
                false,
                false,
                0L,
                VolumeDialogState.Route.SPEAKER,
            )

        assertThat(result.userId).isEqualTo(10)
        assertThat(result.activeSessionId).isEqualTo(100)
        assertThat(result.compactColumn).isEqualTo(VolumeDialogState.Column.MEDIA)
        assertThat(result.route).isEqualTo(VolumeDialogState.Route.BLUETOOTH_HEADSET)
        assertThat(result.stream(100)).isNotSameInstanceAs(remote)
    }

    @Test
    fun wiredRoute_isUsedWhenNoBluetoothMediaRouteExists() {
        val result =
            VolumeDialogState.from(
                null,
                0,
                false,
                false,
                0L,
                VolumeDialogState.Route.WIRED_HEADSET,
            )

        assertThat(result.activeStream).isEqualTo(AudioManager.STREAM_MUSIC)
        assertThat(result.route).isEqualTo(VolumeDialogState.Route.WIRED_HEADSET)
    }

    @Test
    fun dndRestrictions_followAndroid16StreamPolicy() {
        val platform = VolumeDialogController.State()
        platform.zenMode = android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
        platform.disallowAlarms = true
        platform.disallowMedia = true
        platform.disallowSystem = false
        platform.disallowRinger = true

        val result =
            VolumeDialogState.from(
                platform,
                0,
                true,
                false,
                0L,
                VolumeDialogState.Route.SPEAKER,
            )

        assertThat(result.isStreamRestricted(AudioManager.STREAM_ALARM)).isTrue()
        assertThat(result.isStreamRestricted(AudioManager.STREAM_MUSIC)).isTrue()
        assertThat(result.isStreamRestricted(AudioManager.STREAM_RING)).isTrue()
        assertThat(result.isStreamRestricted(AudioManager.STREAM_NOTIFICATION)).isTrue()
        assertThat(result.isStreamRestricted(AudioManager.STREAM_SYSTEM)).isFalse()
        assertThat(result.isStreamRestricted(AudioManager.STREAM_VOICE_CALL)).isFalse()
        assertThat(result.isStreamRestricted(AudioManager.STREAM_ACCESSIBILITY)).isFalse()
    }

    @Test
    fun timedMuteSnapshot_isImmutableAndClampsRemainingTime() {
        val result =
            VolumeDialogState.from(
                null,
                11,
                false,
                true,
                12_000L,
                VolumeDialogState.Route.SPEAKER,
            )

        assertThat(result.timedMute.active).isTrue()
        assertThat(result.timedMute.deadlineMillis).isEqualTo(12_000L)
        assertThat(result.timedMute.remainingMillis(10_000L)).isEqualTo(2_000L)
        assertThat(result.timedMute.remainingMillis(13_000L)).isEqualTo(0L)
    }
}
