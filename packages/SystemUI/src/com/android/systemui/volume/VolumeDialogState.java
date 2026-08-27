package com.android.systemui.volume;

import android.media.AudioManager;
import android.media.AudioSystem;
import android.provider.Settings;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.android.systemui.plugins.VolumeDialogController;

/** Immutable Android-16-to-R2 presentation snapshot consumed by the volume panel. */
public final class VolumeDialogState {
    public enum Column { BRIGHTNESS, RINGER, CALL, ALARM, MEDIA, ACCESSIBILITY }
    public enum Route { SPEAKER, WIRED_HEADSET, BLUETOOTH_HEADSET }

    public static final class TimedMuteState {
        public final boolean active;
        public final long deadlineMillis;

        TimedMuteState(boolean active, long deadlineMillis) {
            this.active = active;
            this.deadlineMillis = active ? deadlineMillis : 0L;
        }

        public long remainingMillis(long now) {
            return active ? Math.max(0L, deadlineMillis - now) : 0L;
        }
    }

    public final int userId;
    public final int activeStream;
    /** Dynamic stream ids are Android 16's stable identity for the current remote session. */
    public final int activeSessionId;
    public final Column compactColumn;
    public final SparseArray<VolumeDialogController.StreamState> streams;
    public final int ringerMode;
    public final int zenMode;
    public final boolean disallowAlarms;
    public final boolean disallowMedia;
    public final boolean disallowSystem;
    public final boolean disallowRinger;
    public final boolean expanded;
    public final TimedMuteState timedMute;
    public final Route route;

    private VolumeDialogState(
            int userId,
            int activeStream,
            int activeSessionId,
            Column compactColumn,
            SparseArray<VolumeDialogController.StreamState> streams,
            int ringerMode,
            int zenMode,
            boolean disallowAlarms,
            boolean disallowMedia,
            boolean disallowSystem,
            boolean disallowRinger,
            boolean expanded,
            TimedMuteState timedMute,
            Route route) {
        this.userId = userId;
        this.activeStream = activeStream;
        this.activeSessionId = activeSessionId;
        this.compactColumn = compactColumn;
        this.streams = streams;
        this.ringerMode = ringerMode;
        this.zenMode = zenMode;
        this.disallowAlarms = disallowAlarms;
        this.disallowMedia = disallowMedia;
        this.disallowSystem = disallowSystem;
        this.disallowRinger = disallowRinger;
        this.expanded = expanded;
        this.timedMute = timedMute;
        this.route = route;
    }

    public static VolumeDialogState from(
            @Nullable VolumeDialogController.State platform,
            int userId,
            boolean expanded,
            boolean timedMute,
            long muteDeadlineMillis,
            Route route) {
        SparseArray<VolumeDialogController.StreamState> copy = new SparseArray<>();
        int active = AudioManager.STREAM_MUSIC;
        int activeSession = VolumeDialogController.State.NO_ACTIVE_STREAM;
        int ringer = AudioManager.RINGER_MODE_NORMAL;
        int zenMode = Settings.Global.ZEN_MODE_OFF;
        boolean disallowAlarms = false;
        boolean disallowMedia = false;
        boolean disallowSystem = false;
        boolean disallowRinger = false;
        Route resolvedRoute = route == null ? Route.SPEAKER : route;
        if (platform != null) {
            active = platform.activeStream == VolumeDialogController.State.NO_ACTIVE_STREAM
                    ? AudioManager.STREAM_MUSIC : platform.activeStream;
            ringer = platform.ringerModeExternal;
            zenMode = platform.zenMode;
            disallowAlarms = platform.disallowAlarms;
            disallowMedia = platform.disallowMedia;
            disallowSystem = platform.disallowSystem;
            disallowRinger = platform.disallowRinger;
            for (int i = 0; i < platform.states.size(); i++) {
                int key = platform.states.keyAt(i);
                VolumeDialogController.StreamState value = platform.states.valueAt(i).copy();
                copy.put(key, value);
                if (key == active && value.dynamic) activeSession = key;
                if ((key == AudioManager.STREAM_MUSIC || key == active || value.dynamic)
                        && value.routedToBluetooth) {
                    resolvedRoute = Route.BLUETOOTH_HEADSET;
                }
            }
        }
        return new VolumeDialogState(userId, active, activeSession, columnForStream(active, copy),
                copy, ringer, zenMode, disallowAlarms, disallowMedia, disallowSystem,
                disallowRinger, expanded, new TimedMuteState(timedMute, muteDeadlineMillis),
                resolvedRoute);
    }

    public @Nullable VolumeDialogController.StreamState stream(int stream) {
        return streams.get(stream);
    }

    /** Mirrors Android 16's DND stream policy while keeping R2 visuals. */
    public boolean isStreamRestricted(int stream) {
        final int family = streamFamily(stream);
        final boolean ring = family == AudioManager.STREAM_RING;
        final boolean system = family == AudioManager.STREAM_SYSTEM;
        final boolean alarm = family == AudioManager.STREAM_ALARM;
        final boolean media = family == AudioManager.STREAM_MUSIC;
        if (zenMode == Settings.Global.ZEN_MODE_ALARMS) return ring || system;
        if (zenMode == Settings.Global.ZEN_MODE_NO_INTERRUPTIONS) {
            return ring || system || alarm || media;
        }
        if (zenMode != Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) return false;
        return (alarm && disallowAlarms)
                || (media && disallowMedia)
                || (ring && disallowRinger)
                || (system && disallowSystem);
    }

    private static int streamFamily(int stream) {
        switch (stream) {
            case AudioManager.STREAM_NOTIFICATION:
            case AudioManager.STREAM_DTMF:
                return AudioManager.STREAM_RING;
            default:
                return stream;
        }
    }

    public static Column columnForStream(
            int stream, SparseArray<VolumeDialogController.StreamState> states) {
        VolumeDialogController.StreamState state = states.get(stream);
        if (state != null && state.dynamic) return Column.MEDIA;
        switch (stream) {
            case AudioManager.STREAM_VOICE_CALL:
            case AudioManager.STREAM_BLUETOOTH_SCO:
                return Column.CALL;
            case AudioManager.STREAM_ALARM:
                return Column.ALARM;
            case AudioManager.STREAM_ACCESSIBILITY:
            case AudioSystem.STREAM_ASSISTANT:
            case AudioSystem.STREAM_TTS:
                return Column.ACCESSIBILITY;
            case AudioManager.STREAM_RING:
            case AudioManager.STREAM_NOTIFICATION:
            case AudioManager.STREAM_SYSTEM:
            case AudioManager.STREAM_DTMF:
                return Column.RINGER;
            case AudioManager.STREAM_MUSIC:
            default:
                return Column.MEDIA;
        }
    }
}
