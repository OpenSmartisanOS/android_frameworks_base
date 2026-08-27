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

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/** Immutable input consumed by every Smartisan status-bar battery view. */
public final class BatteryState {
    public static final int STYLE_GRAPHIC = 0;
    public static final int STYLE_GRAPHIC_COMPAT = 1;
    public static final int STYLE_PERCENT = 3;

    @IntDef({STYLE_GRAPHIC, STYLE_GRAPHIC_COMPAT, STYLE_PERCENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Style {}

    private static final int[] GRAPHIC_MAX_LEVELS = {
        3, 10, 20, 24, 32, 40, 48, 56, 64, 72, 80, 99, 100
    };
    private static final int[] GRAPHIC_DRAWABLE_LEVELS = {
        0, 10, 20, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100
    };

    private final int mLevel;
    private final @Style int mStyle;
    private final boolean mPlugged;
    private final boolean mCharging;
    private final boolean mCharged;
    private final boolean mPowerSave;
    private final boolean mScreenOn;
    private final boolean mAnimationEnabled;
    private final boolean mIncompatibleCharging;
    private final boolean mUnknown;

    public BatteryState(
            int level,
            int style,
            boolean plugged,
            boolean charging,
            boolean charged,
            boolean powerSave,
            boolean screenOn,
            boolean animationEnabled,
            boolean incompatibleCharging,
            boolean unknown) {
        mLevel = Math.max(0, Math.min(100, level));
        mStyle = normalizeStyle(style);
        mPlugged = plugged;
        mCharging = charging;
        mCharged = charged;
        mPowerSave = powerSave;
        mScreenOn = screenOn;
        mAnimationEnabled = animationEnabled;
        mIncompatibleCharging = incompatibleCharging;
        mUnknown = unknown;
    }

    public int getLevel() {
        return mLevel;
    }

    public @Style int getStyle() {
        return mStyle;
    }

    public boolean isPercentStyle() {
        return mStyle == STYLE_PERCENT;
    }

    public boolean isPlugged() {
        return mPlugged;
    }

    public boolean isCharging() {
        return mCharging;
    }

    public boolean isCharged() {
        return mCharged;
    }

    public boolean isPowerSave() {
        return mPowerSave;
    }

    public boolean isScreenOn() {
        return mScreenOn;
    }

    public boolean isAnimationEnabled() {
        return mAnimationEnabled;
    }

    public boolean isIncompatibleCharging() {
        return mIncompatibleCharging;
    }

    public boolean isUnknown() {
        return mUnknown;
    }

    /** Full has priority over the animation path and requires a real power connection. */
    public boolean isFull() {
        return !mUnknown && mPlugged && mCharged;
    }

    /** Matches the original visible charging criteria using Android's screen lifecycle. */
    public boolean shouldAnimateCharging() {
        return !mUnknown
                && !isFull()
                && mPlugged
                && mCharging
                && !mIncompatibleCharging
                && mScreenOn
                && mAnimationEnabled;
    }

    /** Exact original bitmap name for the numeric layer. */
    public String getPercentDrawableName(boolean colored) {
        if (isFull()) {
            return colored && !mPowerSave
                    ? "colored_stat_sys_battery_full"
                    : "stat_sys_battery_full";
        }
        int displayLevel = Math.max(1, mLevel);
        String prefix;
        if (mPowerSave) {
            prefix = "smaritisan_stat_sys_powersave_battery_";
        } else if (colored) {
            prefix = "colored_smaritisan_stat_sys_battery_";
        } else {
            prefix = "smaritisan_stat_sys_battery_";
        }
        return prefix + displayLevel;
    }

    /** Returns the drawable threshold selected by the original graphical level-list. */
    public static int graphicalBucketForLevel(int level) {
        int safeLevel = Math.max(0, Math.min(100, level));
        for (int i = 0; i < GRAPHIC_MAX_LEVELS.length; i++) {
            if (safeLevel <= GRAPHIC_MAX_LEVELS[i]) {
                return GRAPHIC_DRAWABLE_LEVELS[i];
            }
        }
        return 100;
    }

    public static @Style int normalizeStyle(int style) {
        if (style == STYLE_PERCENT) {
            return STYLE_PERCENT;
        }
        return style == STYLE_GRAPHIC_COMPAT ? STYLE_GRAPHIC_COMPAT : STYLE_GRAPHIC;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BatteryState that)) {
            return false;
        }
        return mLevel == that.mLevel
                && mStyle == that.mStyle
                && mPlugged == that.mPlugged
                && mCharging == that.mCharging
                && mCharged == that.mCharged
                && mPowerSave == that.mPowerSave
                && mScreenOn == that.mScreenOn
                && mAnimationEnabled == that.mAnimationEnabled
                && mIncompatibleCharging == that.mIncompatibleCharging
                && mUnknown == that.mUnknown;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mLevel,
                mStyle,
                mPlugged,
                mCharging,
                mCharged,
                mPowerSave,
                mScreenOn,
                mAnimationEnabled,
                mIncompatibleCharging,
                mUnknown);
    }

    @Override
    public String toString() {
        return "BatteryState{"
                + "level=" + mLevel
                + ", style=" + mStyle
                + ", plugged=" + mPlugged
                + ", charging=" + mCharging
                + ", charged=" + mCharged
                + ", powerSave=" + mPowerSave
                + ", screenOn=" + mScreenOn
                + ", animationEnabled=" + mAnimationEnabled
                + ", incompatible=" + mIncompatibleCharging
                + ", unknown=" + mUnknown
                + '}';
    }
}
