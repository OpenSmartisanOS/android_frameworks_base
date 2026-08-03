/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensmartisanos.fakecall;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

/** Privileged fake-call scheduler and state owner. */
public final class FakeCallService extends Service {
    public static final String ACTION_RESTORE =
            "org.opensmartisanos.fakecall.action.RESTORE";
    public static final String ACTION_RING =
            "org.opensmartisanos.fakecall.action.RING";
    public static final String ACTION_CANCEL =
            "org.opensmartisanos.fakecall.action.CANCEL";
    public static final String ACTION_ANSWER =
            "org.opensmartisanos.fakecall.action.ANSWER";

    public static final int STATE_IDLE = 0;
    public static final int STATE_SCHEDULED = 1;
    public static final int STATE_RINGING = 2;
    public static final int STATE_OFFHOOK = 3;

    static final String PREFS = "fake_call_state";
    static final String PREF_STATE = "state";
    static final String PREF_TRIGGER_AT = "trigger_at";
    static final String PREF_CALLER_NAME = "caller_name";
    static final String PREF_CALLER_NUMBER = "caller_number";
    static final String PREF_DELAY_MILLIS = "delay_millis";
    static final String PREF_RINGTONE = "ringtone";
    static final long DEFAULT_DELAY_MILLIS = 5_000L;

    private static final String SCHEDULE_CHANNEL_ID = "sos_fake_call_schedule";
    private static final String CALL_CHANNEL_ID = "sos_fake_call";
    private static final int SCHEDULE_NOTIFICATION_ID = 4300;
    private static final int CALL_NOTIFICATION_ID = 4301;
    private static final int ALARM_REQUEST_CODE = 4301;
    private static final int ACTIVITY_REQUEST_CODE = 4302;
    private static final int CANCEL_REQUEST_CODE = 4303;
    private static final int ANSWER_REQUEST_CODE = 4304;
    private static final long MIN_DELAY_MILLIS = 1_000L;
    private static final long MAX_DELAY_MILLIS = 60L * 60L * 1000L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IFakeCallCallback> mCallbacks =
            new RemoteCallbackList<>();
    private final Object mStateLock = new Object();
    private final FakeCallBinder mBinder = new FakeCallBinder();
    private final RealCallCallback mRealCallCallback = new RealCallCallback();

    private AlarmManager mAlarmManager;
    private NotificationManager mNotificationManager;
    private TelephonyManager mTelephonyManager;
    private SharedPreferences mPreferences;
    private volatile int mState = STATE_IDLE;
    private volatile long mTriggerAtMillis;
    private String mCallerName;
    private String mCallerNumber;
    private Ringtone mRingtone;
    private boolean mTelephonyCallbackRegistered;

    private final Runnable mCountdownTicker = new Runnable() {
        @Override
        public void run() {
            if (mState != STATE_SCHEDULED) {
                return;
            }
            startForeground(SCHEDULE_NOTIFICATION_ID, buildScheduleNotification());
            notifyCallbacks();
            mMainHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mAlarmManager = getSystemService(AlarmManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
        mTelephonyManager = getSystemService(TelephonyManager.class);
        mPreferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannels();
        restoreState();
        registerRealCallListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : ACTION_RESTORE;
        if (ACTION_RING.equals(action)) {
            ringInternal();
        } else if (ACTION_CANCEL.equals(action)) {
            cancelInternal();
        } else if (ACTION_ANSWER.equals(action)) {
            answerInternal();
        } else if (mState == STATE_SCHEDULED) {
            startScheduleForeground();
        } else {
            stopSelf(startId);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        mMainHandler.removeCallbacksAndMessages(null);
        stopRinging();
        if (mTelephonyCallbackRegistered) {
            mTelephonyManager.unregisterTelephonyCallback(mRealCallCallback);
            mTelephonyCallbackRegistered = false;
        }
        mCallbacks.kill();
        super.onDestroy();
    }

    private void restoreState() {
        final int savedState = mPreferences.getInt(PREF_STATE, STATE_IDLE);
        final long savedTrigger = mPreferences.getLong(PREF_TRIGGER_AT, 0L);
        mCallerName = valueOrDefault(
                mPreferences.getString(PREF_CALLER_NAME, null),
                R.string.default_caller_name);
        mCallerNumber = valueOrDefault(
                mPreferences.getString(PREF_CALLER_NUMBER, null),
                R.string.default_caller_number);
        if (savedState == STATE_SCHEDULED && savedTrigger > System.currentTimeMillis()) {
            mState = STATE_SCHEDULED;
            mTriggerAtMillis = savedTrigger;
            scheduleAlarm(savedTrigger - System.currentTimeMillis());
        } else {
            persistState(STATE_IDLE, 0L);
        }
    }

    private void scheduleInternal(long delayMillis, String callerName, String callerNumber) {
        if (hasRealCall()) {
            return;
        }
        final long delay =
                Math.max(MIN_DELAY_MILLIS, Math.min(MAX_DELAY_MILLIS,
                        delayMillis > 0 ? delayMillis : getConfiguredDelay()));
        synchronized (mStateLock) {
            mCallerName = valueOrDefault(callerName,
                    mPreferences.getString(PREF_CALLER_NAME, null),
                    R.string.default_caller_name);
            mCallerNumber = valueOrDefault(callerNumber,
                    mPreferences.getString(PREF_CALLER_NUMBER, null),
                    R.string.default_caller_number);
            mTriggerAtMillis = System.currentTimeMillis() + delay;
            persistState(STATE_SCHEDULED, mTriggerAtMillis);
            scheduleAlarm(delay);
        }
        startForegroundService(
                new Intent(this, FakeCallService.class).setAction(ACTION_RESTORE));
        notifyCallbacks();
    }

    private void scheduleAlarm(long delayMillis) {
        final long triggerElapsed = SystemClock.elapsedRealtime() + Math.max(0L, delayMillis);
        if (mAlarmManager.canScheduleExactAlarms()) {
            mAlarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsed,
                    alarmPendingIntent());
        } else {
            mAlarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsed,
                    alarmPendingIntent());
        }
    }

    private void startScheduleForeground() {
        startForeground(SCHEDULE_NOTIFICATION_ID, buildScheduleNotification());
        mMainHandler.removeCallbacks(mCountdownTicker);
        mMainHandler.post(mCountdownTicker);
    }

    private Notification buildScheduleNotification() {
        final long seconds =
                Math.max(0L, (mTriggerAtMillis - System.currentTimeMillis() + 999L) / 1000L);
        return new Notification.Builder(this, SCHEDULE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.fake_call_scheduled_countdown, seconds))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(settingsPendingIntent())
                .addAction(
                        new Notification.Action.Builder(
                                null,
                                getString(R.string.cancel),
                                servicePendingIntent(ACTION_CANCEL, CANCEL_REQUEST_CODE))
                                .build())
                .build();
    }

    private void ringInternal() {
        if (mState != STATE_SCHEDULED || hasRealCall()) {
            cancelInternal();
            return;
        }
        mMainHandler.removeCallbacks(mCountdownTicker);
        mAlarmManager.cancel(alarmPendingIntent());
        persistState(STATE_RINGING, 0L);
        startRinging();
        final Notification notification = buildIncomingCallNotification();
        startForeground(CALL_NOTIFICATION_ID, notification);
        mNotificationManager.cancel(SCHEDULE_NOTIFICATION_ID);
        notifyCallbacks();
    }

    private Notification buildIncomingCallNotification() {
        final Person caller =
                new Person.Builder().setName(mCallerName).setImportant(true).build();
        final PendingIntent activityIntent = callActivityPendingIntent();
        final PendingIntent declineIntent =
                servicePendingIntent(ACTION_CANCEL, CANCEL_REQUEST_CODE);
        final PendingIntent answerIntent =
                servicePendingIntent(ACTION_ANSWER, ANSWER_REQUEST_CODE);
        return new Notification.Builder(this, CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(mCallerName)
                .setContentText(mCallerNumber)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(activityIntent)
                .setFullScreenIntent(activityIntent, true)
                .setStyle(Notification.CallStyle.forIncomingCall(
                        caller,
                        declineIntent,
                        answerIntent))
                .build();
    }

    private Notification buildOngoingCallNotification() {
        final Person caller =
                new Person.Builder().setName(mCallerName).setImportant(true).build();
        return new Notification.Builder(this, CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(mCallerName)
                .setContentText(getString(R.string.connected))
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(callActivityPendingIntent())
                .setStyle(Notification.CallStyle.forOngoingCall(
                        caller,
                        servicePendingIntent(ACTION_CANCEL, CANCEL_REQUEST_CODE)))
                .build();
    }

    private void answerInternal() {
        if (mState != STATE_RINGING) {
            return;
        }
        stopRinging();
        persistState(STATE_OFFHOOK, 0L);
        startForeground(CALL_NOTIFICATION_ID, buildOngoingCallNotification());
        startActivity(
                callActivityIntent()
                        .putExtra(FakeCallActivity.EXTRA_ANSWERED, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        notifyCallbacks();
    }

    private void cancelInternal() {
        mMainHandler.removeCallbacks(mCountdownTicker);
        mAlarmManager.cancel(alarmPendingIntent());
        stopRinging();
        persistState(STATE_IDLE, 0L);
        mNotificationManager.cancel(SCHEDULE_NOTIFICATION_ID);
        mNotificationManager.cancel(CALL_NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyCallbacks();
        stopSelf();
    }

    private void persistState(int state, long triggerAtMillis) {
        mState = state;
        mTriggerAtMillis = triggerAtMillis;
        mPreferences.edit()
                .putInt(PREF_STATE, state)
                .putLong(PREF_TRIGGER_AT, triggerAtMillis)
                .putString(PREF_CALLER_NAME, mCallerName)
                .putString(PREF_CALLER_NUMBER, mCallerNumber)
                .apply();
    }

    private void startRinging() {
        stopRinging();
        final String configured = mPreferences.getString(PREF_RINGTONE, null);
        final Uri uri = TextUtils.isEmpty(configured)
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                : Uri.parse(configured);
        mRingtone = RingtoneManager.getRingtone(this, uri);
        if (mRingtone != null) {
            mRingtone.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            mRingtone.setLooping(true);
            mRingtone.play();
        }
    }

    private void stopRinging() {
        if (mRingtone != null) {
            mRingtone.stop();
            mRingtone = null;
        }
    }

    private void createNotificationChannels() {
        final NotificationChannel schedule =
                new NotificationChannel(
                        SCHEDULE_CHANNEL_ID,
                        getString(R.string.schedule_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        schedule.setSound(null, null);
        mNotificationManager.createNotificationChannel(schedule);

        final NotificationChannel call =
                new NotificationChannel(
                        CALL_CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
        call.setSound(null, null);
        call.enableVibration(true);
        call.setVibrationPattern(new long[] {0, 700, 400, 700, 400});
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        mNotificationManager.createNotificationChannel(call);
    }

    private void registerRealCallListener() {
        if (mTelephonyManager == null
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mTelephonyManager.registerTelephonyCallback(getMainExecutor(), mRealCallCallback);
        mTelephonyCallbackRegistered = true;
    }

    private boolean hasRealCall() {
        return mTelephonyManager != null
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED
                && mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    private void notifyCallbacks() {
        final int count = mCallbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onStateChanged(mState, mTriggerAtMillis);
            } catch (RemoteException ignored) {
                // RemoteCallbackList removes dead binders.
            }
        }
        mCallbacks.finishBroadcast();
    }

    private PendingIntent alarmPendingIntent() {
        return PendingIntent.getBroadcast(
                this,
                ALARM_REQUEST_CODE,
                new Intent(this, FakeCallAlarmReceiver.class).setAction(ACTION_RING),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent callActivityPendingIntent() {
        return PendingIntent.getActivity(
                this,
                ACTIVITY_REQUEST_CODE,
                callActivityIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Intent callActivityIntent() {
        return new Intent(this, FakeCallActivity.class)
                .putExtra(FakeCallActivity.EXTRA_CALLER_NAME, mCallerName)
                .putExtra(FakeCallActivity.EXTRA_CALLER_NUMBER, mCallerNumber)
                .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private PendingIntent settingsPendingIntent() {
        return PendingIntent.getActivity(
                this,
                0,
                new Intent(this, FakeCallSettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        return PendingIntent.getService(
                this,
                requestCode,
                new Intent(this, FakeCallService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private long getConfiguredDelay() {
        return mPreferences.getLong(PREF_DELAY_MILLIS, DEFAULT_DELAY_MILLIS);
    }

    private String valueOrDefault(String value, int fallbackRes) {
        return TextUtils.isEmpty(value) ? getString(fallbackRes) : value;
    }

    private String valueOrDefault(String value, String configured, int fallbackRes) {
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        return valueOrDefault(configured, fallbackRes);
    }

    private final class FakeCallBinder extends IFakeCallService.Stub {
        @Override
        public int getState() {
            return mState;
        }

        @Override
        public long getTriggerAtMillis() {
            return mTriggerAtMillis;
        }

        @Override
        public void schedule(long delayMillis, String callerName, String callerNumber) {
            enforceCallingOrSelfPermission(
                    "org.opensmartisanos.permission.CONTROL_FAKE_CALL",
                    "Fake-call control permission required");
            mMainHandler.post(() -> scheduleInternal(delayMillis, callerName, callerNumber));
        }

        @Override
        public void cancel() {
            enforceCallingOrSelfPermission(
                    "org.opensmartisanos.permission.CONTROL_FAKE_CALL",
                    "Fake-call control permission required");
            mMainHandler.post(FakeCallService.this::cancelInternal);
        }

        @Override
        public void answer() {
            enforceCallingOrSelfPermission(
                    "org.opensmartisanos.permission.CONTROL_FAKE_CALL",
                    "Fake-call control permission required");
            mMainHandler.post(FakeCallService.this::answerInternal);
        }

        @Override
        public void registerCallback(IFakeCallCallback callback) {
            if (callback != null) {
                mCallbacks.register(callback);
                try {
                    callback.onStateChanged(mState, mTriggerAtMillis);
                } catch (RemoteException ignored) {
                }
            }
        }

        @Override
        public void unregisterCallback(IFakeCallCallback callback) {
            if (callback != null) {
                mCallbacks.unregister(callback);
            }
        }
    }

    private final class RealCallCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            if (state != TelephonyManager.CALL_STATE_IDLE && mState != STATE_IDLE) {
                cancelInternal();
            }
        }
    }
}
