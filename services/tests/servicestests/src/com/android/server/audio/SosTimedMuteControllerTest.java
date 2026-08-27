/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.audio;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioManager;
import android.media.AudioSystem;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class SosTimedMuteControllerTest {
    @Test
    public void effectiveRingerMode_preservesStoredModeWhenInactive() {
        assertThat(SosTimedMuteController.effectiveRingerMode(false, true,
                AudioManager.RINGER_MODE_NORMAL)).isEqualTo(AudioManager.RINGER_MODE_NORMAL);
    }

    @Test
    public void effectiveRingerMode_reportsVibrateOrSilentWithoutChangingStoredMode() {
        assertThat(SosTimedMuteController.effectiveRingerMode(true, true,
                AudioManager.RINGER_MODE_NORMAL)).isEqualTo(AudioManager.RINGER_MODE_VIBRATE);
        assertThat(SosTimedMuteController.effectiveRingerMode(true, false,
                AudioManager.RINGER_MODE_NORMAL)).isEqualTo(AudioManager.RINGER_MODE_SILENT);
        assertThat(SosTimedMuteController.effectiveRingerMode(true, true,
                AudioManager.RINGER_MODE_SILENT)).isEqualTo(AudioManager.RINGER_MODE_SILENT);
    }

    @Test
    public void deadline_requiresEnabledAndFutureWallClock() {
        assertThat(SosTimedMuteController.isRequestedAndUnexpired(true, 10_001L, 10_000L))
                .isTrue();
        assertThat(SosTimedMuteController.isRequestedAndUnexpired(true, 10_000L, 10_000L))
                .isFalse();
        assertThat(SosTimedMuteController.isRequestedAndUnexpired(false, 20_000L, 10_000L))
                .isFalse();
    }

    @Test
    public void missingGlobalVibrationSetting_defaultsEnabled() {
        assertThat(SosTimedMuteController.DEFAULT_VIBRATION_ENABLED).isEqualTo(1);
    }

    @Test
    public void snapshot_carriesGenerationAndUserWithoutMutableControllerState() {
        SosTimedMuteController.Snapshot snapshot = new SosTimedMuteController.Snapshot(
                7L, 12, true, false, 42_000L);

        assertThat(snapshot.generation).isEqualTo(7L);
        assertThat(snapshot.userId).isEqualTo(12);
        assertThat(snapshot.active).isTrue();
        assertThat(snapshot.vibrateOnMute).isFalse();
        assertThat(snapshot.deadlineMillis).isEqualTo(42_000L);
    }

    @Test
    public void timedMuteStreamFilter_honorsAffectedMaskAndSafetyExclusions() {
        int allStreams = -1;

        assertThat(AudioService.isSosTimedMuteStream(AudioSystem.STREAM_RING, allStreams)).isTrue();
        assertThat(AudioService.isSosTimedMuteStream(AudioSystem.STREAM_MUSIC, allStreams)).isTrue();
        assertThat(AudioService.isSosTimedMuteStream(
                AudioSystem.STREAM_VOICE_CALL, allStreams)).isFalse();
        assertThat(AudioService.isSosTimedMuteStream(
                AudioSystem.STREAM_BLUETOOTH_SCO, allStreams)).isFalse();
        assertThat(AudioService.isSosTimedMuteStream(
                AudioSystem.STREAM_ACCESSIBILITY, allStreams)).isFalse();
        assertThat(AudioService.isSosTimedMuteStream(
                AudioSystem.STREAM_SYSTEM_ENFORCED, allStreams)).isFalse();
        assertThat(AudioService.isSosTimedMuteStream(AudioSystem.STREAM_RING, 0)).isFalse();
    }
}
