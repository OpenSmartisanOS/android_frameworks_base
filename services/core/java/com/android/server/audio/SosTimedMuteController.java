package com.android.server.audio;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

/**
 * Process-local owner of Smartisan's timed global mute setting.
 *
 * <p>This class schedules settings and user lifecycle. AudioService remains the only component
 * allowed to change stream mute state, so no new binder API or public framework state is added.</p>
 */
final class SosTimedMuteController {
    interface Host {
        int getCurrentUserId();
        void applyTimedMute(boolean active);
        void broadcastEffectiveRingerMode();
    }

    private static final String TAG = "AS.SosTimedMute";
    private static final String SETTING_ENABLED = Settings.System.VOLUME_PANEL_MUTE_ENABLE;
    private static final String SETTING_TIMEOUT = Settings.System.MUTE_TIMEOUT;
    private static final String SETTING_VIBRATION = Settings.Global.TELEPHONY_VIBRATION_ENABLED;
    static final int DEFAULT_VIBRATION_ENABLED = 1;

    /** Immutable state read from Binder threads without touching AudioHandler-owned fields. */
    static final class Snapshot {
        final long generation;
        final int userId;
        final boolean active;
        final boolean vibrateOnMute;
        final long deadlineMillis;

        Snapshot(long generation, int userId, boolean active, boolean vibrateOnMute,
                long deadlineMillis) {
            this.generation = generation;
            this.userId = userId;
            this.active = active;
            this.vibrateOnMute = vibrateOnMute;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private final ContentResolver mResolver;
    private final AlarmManager mAlarmManager;
    private final Handler mHandler;
    private final Host mHost;
    private final AlarmManager.OnAlarmListener mExpiryAlarm = this::evaluate;
    private boolean mStarted;
    private long mGeneration;
    private volatile Snapshot mSnapshot = new Snapshot(0, UserHandle.USER_NULL,
            false, true, 0L);
    private int mUserId = UserHandle.USER_NULL;

    private final ContentObserver mObserver;

    SosTimedMuteController(Context context, Handler handler, Host host) {
        mResolver = context.getContentResolver();
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mHandler = handler;
        mHost = host;
        mObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                evaluate();
            }
        };
    }

    void start() {
        if (mStarted) return;
        mStarted = true;
        mResolver.registerContentObserver(Settings.System.getUriFor(SETTING_ENABLED), false,
                mObserver, UserHandle.USER_ALL);
        mResolver.registerContentObserver(Settings.System.getUriFor(SETTING_TIMEOUT), false,
                mObserver, UserHandle.USER_ALL);
        mResolver.registerContentObserver(Settings.Global.getUriFor(SETTING_VIBRATION), false,
                mObserver);
        mUserId = mHost.getCurrentUserId();
        evaluate();
    }

    void prepareForUserChange() {
        if (!mStarted) return;
        mAlarmManager.cancel(mExpiryAlarm);
        Snapshot previous = mSnapshot;
        if (previous.active) {
            mHost.applyTimedMute(false);
        }
        mSnapshot = new Snapshot(++mGeneration, UserHandle.USER_NULL, false, true, 0L);
        mUserId = UserHandle.USER_NULL;
    }

    void onUserChanged() {
        if (!mStarted) return;
        mUserId = mHost.getCurrentUserId();
        evaluate();
    }

    void onTimeChanged() {
        if (mStarted) evaluate();
    }

    /** Re-applies owned stream mutes after audioserver state has been reconstructed. */
    void reapply() {
        if (!mStarted || !mSnapshot.active) return;
        mHost.applyTimedMute(true);
        mHost.broadcastEffectiveRingerMode();
    }

    boolean isActive() {
        return mSnapshot.active;
    }

    Snapshot getSnapshot() {
        return mSnapshot;
    }

    int getEffectiveRingerMode(int storedMode) {
        Snapshot snapshot = mSnapshot;
        return effectiveRingerMode(snapshot.active, snapshot.vibrateOnMute, storedMode);
    }

    static int effectiveRingerMode(boolean active, boolean vibrate, int storedMode) {
        if (!active || storedMode != AudioManager.RINGER_MODE_NORMAL) return storedMode;
        return vibrate ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT;
    }

    static boolean isRequestedAndUnexpired(boolean requested, long deadline, long now) {
        return requested && deadline > now;
    }

    private void evaluate() {
        if (!mStarted) return;
        int currentUser = mHost.getCurrentUserId();
        if (currentUser != mUserId) {
            prepareForUserChange();
            onUserChanged();
            return;
        }
        mAlarmManager.cancel(mExpiryAlarm);
        boolean requested = Settings.System.getIntForUser(mResolver, SETTING_ENABLED, 0,
                mUserId) == 1;
        boolean vibrateOnMute = Settings.Global.getInt(mResolver, SETTING_VIBRATION,
                DEFAULT_VIBRATION_ENABLED) == 1;
        long deadline = Settings.System.getLongForUser(mResolver, SETTING_TIMEOUT, 0L, mUserId);
        long now = System.currentTimeMillis();
        if (requested && deadline <= now) {
            requested = false;
            Settings.System.putIntForUser(mResolver, SETTING_ENABLED, 0, mUserId);
            Settings.System.putLongForUser(mResolver, SETTING_TIMEOUT, 0L, mUserId);
        }
        if (isRequestedAndUnexpired(requested, deadline, now)) {
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, deadline, TAG, mExpiryAlarm,
                    mHandler);
        }
        Snapshot previous = mSnapshot;
        if (requested != previous.active) {
            Slog.i(TAG, (requested ? "enabling" : "disabling") + " for user " + mUserId);
            mHost.applyTimedMute(requested);
        }
        mSnapshot = new Snapshot(++mGeneration, mUserId, requested, vibrateOnMute,
                requested ? deadline : 0L);
        mHost.broadcastEffectiveRingerMode();
    }
}
