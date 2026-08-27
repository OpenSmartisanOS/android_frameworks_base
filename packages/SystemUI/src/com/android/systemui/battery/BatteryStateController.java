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
package com.android.systemui.battery;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;

import java.util.ArrayList;

import javax.inject.Inject;

/** One Smartisan battery state source shared by HOME, KEYGUARD and PANEL view instances. */
@SysUISingleton
public final class BatteryStateController {
    public static final String SETTING_STATUS_BAR_BATTERY = "status_bar_battery";
    private static final String PROPERTY_BATTERY_ANIMATION = "persist.sys.battery.animation";

    /** Per-view observer. Animations remain owned by the individual view. */
    public interface Listener {
        void onBatteryStateChanged(BatteryState state);
    }

    private final ContentResolver mContentResolver;
    private final BatteryController mBatteryController;
    private final ScreenLifecycle mScreenLifecycle;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final UserTracker mUserTracker;
    private final Handler mMainHandler;
    private final boolean mAnimationEnabled;
    private final ArraySet<Listener> mListeners = new ArraySet<>();

    private int mLevel;
    private int mStyle = BatteryState.STYLE_GRAPHIC;
    private boolean mPlugged;
    private boolean mCharging;
    private boolean mCharged;
    private boolean mPowerSave;
    private boolean mScreenOn;
    private boolean mDozing;
    private boolean mIncompatibleCharging;
    private boolean mUnknown = true;
    private boolean mListening;

    private final ContentObserver mStyleObserver;

    private final BatteryController.BatteryStateChangeCallback mBatteryCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(
                        int level, boolean pluggedIn, boolean charging) {
                    mLevel = level;
                    mPlugged = pluggedIn;
                    mCharging = charging;
                    mCharged = mBatteryController.isCharged();
                    dispatchState();
                }

                @Override
                public void onPowerSaveChanged(boolean isPowerSave) {
                    mPowerSave = isPowerSave;
                    dispatchState();
                }

                @Override
                public void onBatteryUnknownStateChanged(boolean isUnknown) {
                    mUnknown = isUnknown;
                    dispatchState();
                }

                @Override
                public void onIsIncompatibleChargingChanged(boolean isIncompatibleCharging) {
                    mIncompatibleCharging = isIncompatibleCharging;
                    dispatchState();
                }
            };

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            setScreenOn(true);
        }

        @Override
        public void onScreenTurningOff() {
            setScreenOn(false);
        }

        @Override
        public void onScreenTurnedOff() {
            setScreenOn(false);
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    if (mDozing == isDozing) {
                        return;
                    }
                    mDozing = isDozing;
                    dispatchState();
                }
            };

    private final UserTracker.Callback mUserChangedCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            if (!mListening) {
                return;
            }
            registerStyleObserver(newUser);
            readStyle(newUser);
        }
    };

    @Inject
    public BatteryStateController(
            ContentResolver contentResolver,
            BatteryController batteryController,
            ScreenLifecycle screenLifecycle,
            SysuiStatusBarStateController statusBarStateController,
            UserTracker userTracker,
            @Main Handler mainHandler) {
        mContentResolver = contentResolver;
        mBatteryController = batteryController;
        mScreenLifecycle = screenLifecycle;
        mStatusBarStateController = statusBarStateController;
        mUserTracker = userTracker;
        mMainHandler = mainHandler;
        mAnimationEnabled = SystemProperties.getBoolean(PROPERTY_BATTERY_ANIMATION, true);
        mScreenOn = screenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mDozing = statusBarStateController.isDozing();
        mStyleObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                readStyle(mUserTracker.getUserId());
            }
        };
    }

    public void addListener(Listener listener) {
        if (!mListeners.add(listener)) {
            listener.onBatteryStateChanged(buildState());
            return;
        }
        if (!mListening) {
            startListening();
        } else {
            listener.onBatteryStateChanged(buildState());
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            stopListening();
        }
    }

    private void startListening() {
        mListening = true;
        mScreenOn = mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mDozing = mStatusBarStateController.isDozing();
        mPowerSave = mBatteryController.isPowerSave();
        mCharged = mBatteryController.isCharged();
        registerStyleObserver(mUserTracker.getUserId());
        readStyle(mUserTracker.getUserId());
        mScreenLifecycle.addObserver(mScreenObserver);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mUserTracker.addCallback(mUserChangedCallback, new HandlerExecutor(mMainHandler));
        mBatteryController.addCallback(mBatteryCallback);
        dispatchState();
    }

    private void stopListening() {
        if (!mListening) {
            return;
        }
        mListening = false;
        mBatteryController.removeCallback(mBatteryCallback);
        mScreenLifecycle.removeObserver(mScreenObserver);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mUserTracker.removeCallback(mUserChangedCallback);
        mContentResolver.unregisterContentObserver(mStyleObserver);
    }

    private void registerStyleObserver(int userId) {
        mContentResolver.unregisterContentObserver(mStyleObserver);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(SETTING_STATUS_BAR_BATTERY),
                false,
                mStyleObserver,
                userId);
    }

    private void readStyle(int userId) {
        int style = Settings.System.getIntForUser(
                mContentResolver,
                SETTING_STATUS_BAR_BATTERY,
                BatteryState.STYLE_GRAPHIC,
                userId);
        int normalized = BatteryState.normalizeStyle(style);
        if (mStyle != normalized) {
            mStyle = normalized;
            dispatchState();
        }
    }

    private void setScreenOn(boolean screenOn) {
        if (mScreenOn == screenOn) {
            return;
        }
        mScreenOn = screenOn;
        dispatchState();
    }

    private BatteryState buildState() {
        return new BatteryState(
                mLevel,
                mStyle,
                mPlugged,
                mCharging,
                mCharged,
                mPowerSave,
                mScreenOn && !mDozing,
                mAnimationEnabled,
                mIncompatibleCharging,
                mUnknown);
    }

    private void dispatchState() {
        if (!mListening) {
            return;
        }
        BatteryState state = buildState();
        for (Listener listener : new ArrayList<>(mListeners)) {
            listener.onBatteryStateChanged(state);
        }
    }

    @VisibleForTesting
    BatteryState getCurrentState() {
        return buildState();
    }
}
