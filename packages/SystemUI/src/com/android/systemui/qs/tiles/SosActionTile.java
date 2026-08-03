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

package com.android.systemui.qs.tiles;

import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import org.opensmartisanos.fakecall.IFakeCallCallback;
import org.opensmartisanos.fakecall.IFakeCallService;

/** Smartisan action/state tiles backed by Android 16 platform services. */
public final class SosActionTile extends QSTileImpl<BooleanState> {

    public enum Action {
        SCREENSHOT,
        VIBRATE,
        MUTE,
        DND,
        LOCK,
        PROTECT_EYES,
        FAKE_CALL,
    }

    private static final long SCREENSHOT_DELAY_MS = 350;
    private static final String NIGHT_DISPLAY_ACTIVATED = "night_display_activated";
    private static final ComponentName FAKE_CALL_SERVICE =
            new ComponentName(
                    "org.opensmartisanos.fakecall",
                    "org.opensmartisanos.fakecall.FakeCallService");
    private static final String FAKE_CALL_SETTINGS_ACTION =
            "org.opensmartisanos.fakecall.action.SETTINGS";
    private static final int FAKE_CALL_STATE_IDLE = 0;
    private static final int FAKE_CALL_STATE_SCHEDULED = 1;
    private static final int FAKE_CALL_STATE_RINGING = 2;
    private static final int FAKE_CALL_STATE_OFFHOOK = 3;

    private final Action mAction;
    private final int mLabelRes;
    private final int mIconOnRes;
    private final int mIconOffRes;
    private final AudioManager mAudioManager;
    private final PowerManager mPowerManager;
    private final ColorDisplayManager mColorDisplayManager;
    private final ZenModeController mZenModeController;
    private final PanelInteractor mPanelInteractor;
    private final ScreenshotHelper mScreenshotHelper;
    private final AtomicBoolean mReceiverRegistered = new AtomicBoolean();
    private volatile IFakeCallService mFakeCallService;
    private volatile int mFakeCallState = FAKE_CALL_STATE_IDLE;
    private volatile long mFakeCallTriggerAtMillis;
    private volatile boolean mFakeCallBound;
    private volatile boolean mFakeCallTogglePending;

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    private final ContentObserver mNightDisplayObserver;

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(int zen) {
            refreshState();
        }

        @Override
        public void onZenAvailableChanged(boolean available) {
            refreshState();
        }
    };

    private final Runnable mFakeCallCountdownRefresh = new Runnable() {
        @Override
        public void run() {
            if (mFakeCallState == FAKE_CALL_STATE_SCHEDULED) {
                refreshState();
                mHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final IFakeCallCallback mFakeCallCallback = new IFakeCallCallback.Stub() {
        @Override
        public void onStateChanged(int state, long triggerAtMillis) {
            mFakeCallState = state;
            mFakeCallTriggerAtMillis = triggerAtMillis;
            scheduleFakeCallCountdownRefresh();
            refreshState();
        }
    };

    private final ServiceConnection mFakeCallConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mFakeCallService = IFakeCallService.Stub.asInterface(service);
            try {
                mFakeCallService.registerCallback(mFakeCallCallback);
                mFakeCallState = mFakeCallService.getState();
                mFakeCallTriggerAtMillis = mFakeCallService.getTriggerAtMillis();
            } catch (RemoteException e) {
                mFakeCallService = null;
                mFakeCallState = FAKE_CALL_STATE_IDLE;
                mFakeCallTriggerAtMillis = 0L;
                mFakeCallTogglePending = false;
            }
            scheduleFakeCallCountdownRefresh();
            refreshState();
            if (mFakeCallService != null && mFakeCallTogglePending) {
                mFakeCallTogglePending = false;
                mHandler.post(SosActionTile.this::toggleFakeCall);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mFakeCallService = null;
            mFakeCallState = FAKE_CALL_STATE_IDLE;
            mFakeCallTriggerAtMillis = 0L;
            mHandler.removeCallbacks(mFakeCallCountdownRefresh);
            refreshState();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            try {
                mContext.unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // The framework already discarded the dead binding.
            }
            mFakeCallBound = false;
            onServiceDisconnected(name);
            if (mReceiverRegistered.get()) {
                bindFakeCallService();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            mContext.unbindService(this);
            mFakeCallBound = false;
            mFakeCallTogglePending = false;
            onServiceDisconnected(name);
        }
    };

    private SosActionTile(
            Factory factory,
            Action action,
            int labelRes,
            int iconOnRes,
            int iconOffRes) {
        super(
                factory.mHost,
                factory.mUiEventLogger,
                factory.mBackgroundLooper,
                factory.mMainHandler,
                factory.mFalsingManager,
                factory.mMetricsLogger,
                factory.mStatusBarStateController,
                factory.mActivityStarter,
                factory.mQsLogger);
        mAction = action;
        mLabelRes = labelRes;
        mIconOnRes = iconOnRes;
        mIconOffRes = iconOffRes;
        mZenModeController = factory.mZenModeController;
        mPanelInteractor = factory.mPanelInteractor;
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mColorDisplayManager = mContext.getSystemService(ColorDisplayManager.class);
        mScreenshotHelper = new ScreenshotHelper(mContext);
        mNightDisplayObserver = new ContentObserver(factory.mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(mLabelRes);
    }

    @Override
    public boolean isAvailable() {
        if (mAction == Action.PROTECT_EYES) {
            return ColorDisplayManager.isNightDisplayAvailable(mContext);
        }
        if (mAction == Action.DND) {
            return mZenModeController.isZenAvailable();
        }
        return true;
    }

    @Override
    public Intent getLongClickIntent() {
        switch (mAction) {
            case VIBRATE:
            case MUTE:
                return new Intent(Settings.ACTION_SOUND_SETTINGS);
            case DND:
                return new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);
            case PROTECT_EYES:
                return new Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS);
            case FAKE_CALL:
                return isFakeCallBackendAvailable()
                        ? new Intent(FAKE_CALL_SETTINGS_ACTION)
                                .setPackage(FAKE_CALL_SERVICE.getPackageName())
                        : null;
            default:
                return null;
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        switch (mAction) {
            case SCREENSHOT:
                mPanelInteractor.collapsePanels();
                mHandler.postDelayed(
                        () -> mScreenshotHelper.takeScreenshot(
                                TAKE_SCREENSHOT_FULLSCREEN,
                                SCREENSHOT_OTHER,
                                mHandler,
                                null),
                        SCREENSHOT_DELAY_MS);
                break;
            case VIBRATE:
                setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                break;
            case MUTE:
                setRingerMode(AudioManager.RINGER_MODE_SILENT);
                break;
            case DND:
                mZenModeController.setZen(
                        isActive() ? ZEN_MODE_OFF : ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        null,
                        TAG);
                break;
            case LOCK:
                mPanelInteractor.collapsePanels();
                mPowerManager.goToSleep(
                        SystemClock.uptimeMillis(),
                        PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON,
                        0);
                break;
            case PROTECT_EYES:
                if (mColorDisplayManager != null) {
                    mColorDisplayManager.setNightDisplayActivated(!isActive());
                }
                break;
            case FAKE_CALL:
                toggleFakeCall();
                break;
        }
    }

    @Override
    protected void handleSecondaryClick(@Nullable Expandable expandable) {
        handleClick(expandable);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean active = isActive();
        state.label = mContext.getString(mLabelRes);
        state.contentDescription = state.label;
        state.value = active;
        state.state = active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = ResourceIcon.get(active ? mIconOnRes : mIconOffRes);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.handlesLongClick = getLongClickIntent() != null;
        if (mAction == Action.FAKE_CALL) {
            if (!isFakeCallBackendAvailable()) {
                state.state = Tile.STATE_UNAVAILABLE;
            } else {
                state.label = getFakeCallLabel();
            }
            state.contentDescription = state.label;
            state.secondaryLabel = null;
        }
        if (mAction == Action.SCREENSHOT || mAction == Action.LOCK) {
            state.state = Tile.STATE_INACTIVE;
            state.value = false;
            state.expandedAccessibilityClassName = View.class.getName();
        }
    }

    @Override
    protected void handleSetListening(boolean listening) {
        if (listening && mReceiverRegistered.compareAndSet(false, true)) {
            if (mAction == Action.VIBRATE || mAction == Action.MUTE) {
                mContext.registerReceiverAsUser(
                        mRingerReceiver,
                        UserHandle.ALL,
                        new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
                        null,
                        mHandler,
                        Context.RECEIVER_EXPORTED);
            } else if (mAction == Action.DND) {
                mZenModeController.addCallback(mZenCallback);
            } else if (mAction == Action.PROTECT_EYES) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(NIGHT_DISPLAY_ACTIVATED),
                        false,
                        mNightDisplayObserver,
                        UserHandle.USER_ALL);
            } else if (mAction == Action.FAKE_CALL && isFakeCallBackendAvailable()) {
                bindFakeCallService();
            }
        } else if (!listening && mReceiverRegistered.compareAndSet(true, false)) {
            if (mAction == Action.VIBRATE || mAction == Action.MUTE) {
                mContext.unregisterReceiver(mRingerReceiver);
            } else if (mAction == Action.DND) {
                mZenModeController.removeCallback(mZenCallback);
            } else if (mAction == Action.PROTECT_EYES) {
                mContext.getContentResolver().unregisterContentObserver(mNightDisplayObserver);
            } else if (mAction == Action.FAKE_CALL) {
                unbindFakeCallService();
            }
        }
    }

    private void setRingerMode(int requestedMode) {
        if (mAudioManager == null) {
            return;
        }
        final int currentMode = mAudioManager.getRingerModeInternal();
        mAudioManager.setRingerModeInternal(
                currentMode == requestedMode ? AudioManager.RINGER_MODE_NORMAL : requestedMode);
        refreshState();
    }

    private boolean isActive() {
        switch (mAction) {
            case VIBRATE:
                return mAudioManager != null
                        && mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_VIBRATE;
            case MUTE:
                return mAudioManager != null
                        && mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT;
            case DND:
                return mZenModeController.getZen() != ZEN_MODE_OFF;
            case PROTECT_EYES:
                return mColorDisplayManager != null
                        && mColorDisplayManager.isNightDisplayActivated();
            case FAKE_CALL:
                return mFakeCallState != FAKE_CALL_STATE_IDLE;
            default:
                return false;
        }
    }

    private boolean isFakeCallBackendAvailable() {
        return mContext.getPackageManager().resolveService(
                new Intent().setComponent(FAKE_CALL_SERVICE),
                0) != null;
    }

    private void bindFakeCallService() {
        if (mFakeCallBound) {
            return;
        }
        try {
            mFakeCallBound = mContext.bindService(
                    new Intent().setComponent(FAKE_CALL_SERVICE),
                    mFakeCallConnection,
                    Context.BIND_AUTO_CREATE);
            if (!mFakeCallBound) {
                mFakeCallTogglePending = false;
            }
        } catch (SecurityException e) {
            mFakeCallBound = false;
            mFakeCallTogglePending = false;
            refreshState();
        }
    }

    private void unbindFakeCallService() {
        mHandler.removeCallbacks(mFakeCallCountdownRefresh);
        if (!mFakeCallBound) {
            return;
        }
        try {
            if (mFakeCallService != null) {
                mFakeCallService.unregisterCallback(mFakeCallCallback);
            }
        } catch (RemoteException ignored) {
            // The service is already gone.
        }
        mContext.unbindService(mFakeCallConnection);
        mFakeCallBound = false;
        mFakeCallService = null;
        mFakeCallTogglePending = false;
    }

    private void toggleFakeCall() {
        if (mFakeCallService == null) {
            mFakeCallTogglePending = true;
            bindFakeCallService();
            return;
        }
        try {
            if (mFakeCallState == FAKE_CALL_STATE_IDLE) {
                mFakeCallService.schedule(0L, null, null);
            } else {
                mFakeCallService.cancel();
            }
        } catch (RemoteException e) {
            mFakeCallService = null;
            mFakeCallState = FAKE_CALL_STATE_IDLE;
            mFakeCallTriggerAtMillis = 0L;
            refreshState();
        }
    }

    private CharSequence getFakeCallLabel() {
        switch (mFakeCallState) {
            case FAKE_CALL_STATE_SCHEDULED:
                final long remainingMillis =
                        Math.max(0L, mFakeCallTriggerAtMillis - System.currentTimeMillis());
                final long remainingSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
                return String.format(
                        Locale.getDefault(),
                        "%02d:%02d",
                        remainingSeconds / 60L,
                        remainingSeconds % 60L);
            case FAKE_CALL_STATE_RINGING:
                return mContext.getString(R.string.sos_qs_fake_call_ringing);
            case FAKE_CALL_STATE_OFFHOOK:
                return mContext.getString(R.string.sos_qs_fake_call_connected);
            default:
                return mContext.getString(mLabelRes);
        }
    }

    private void scheduleFakeCallCountdownRefresh() {
        mHandler.removeCallbacks(mFakeCallCountdownRefresh);
        if (mFakeCallState == FAKE_CALL_STATE_SCHEDULED && mReceiverRegistered.get()) {
            mHandler.post(mFakeCallCountdownRefresh);
        }
    }

    /** Creates configured variants while sharing the normal SystemUI dependencies. */
    public static final class Factory {
        private final QSHost mHost;
        private final QsEventLogger mUiEventLogger;
        private final Looper mBackgroundLooper;
        private final Handler mMainHandler;
        private final FalsingManager mFalsingManager;
        private final MetricsLogger mMetricsLogger;
        private final StatusBarStateController mStatusBarStateController;
        private final ActivityStarter mActivityStarter;
        private final QSLogger mQsLogger;
        private final ZenModeController mZenModeController;
        private final PanelInteractor mPanelInteractor;

        @Inject
        public Factory(
                QSHost host,
                QsEventLogger uiEventLogger,
                @Background Looper backgroundLooper,
                @Main Handler mainHandler,
                FalsingManager falsingManager,
                MetricsLogger metricsLogger,
                StatusBarStateController statusBarStateController,
                ActivityStarter activityStarter,
                QSLogger qsLogger,
                ZenModeController zenModeController,
                PanelInteractor panelInteractor) {
            mHost = host;
            mUiEventLogger = uiEventLogger;
            mBackgroundLooper = backgroundLooper;
            mMainHandler = mainHandler;
            mFalsingManager = falsingManager;
            mMetricsLogger = metricsLogger;
            mStatusBarStateController = statusBarStateController;
            mActivityStarter = activityStarter;
            mQsLogger = qsLogger;
            mZenModeController = zenModeController;
            mPanelInteractor = panelInteractor;
        }

        public SosActionTile create(
                Action action,
                int labelRes,
                int iconOnRes,
                int iconOffRes) {
            return new SosActionTile(this, action, labelRes, iconOnRes, iconOffRes);
        }
    }
}
