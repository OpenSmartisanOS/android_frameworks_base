/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.Nullable;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.view.Display;
import android.view.View;

import com.android.systemui.res.R;
import com.android.systemui.shared.statusbar.phone.BarTransitions;

public final class PhoneStatusBarTransitions extends BarTransitions {
    private static final float ICON_ALPHA_WHEN_NOT_OPAQUE = 1;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK = 0.5f;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK = 0;
    private static final long STATUS_BAR_LIGHTS_OUT_DURATION = 1500L;

    private final float mIconAlphaWhenOpaque;
    private final boolean mControlsPhoneTicker;
    @Nullable private final StatusBarTickerController mTickerController;

    private boolean mIsHeadsUp;

    private View mStartSide, mClockHost, mStatusIcons, mNetworkCluster, mNetSpeed, mOtg, mBattery;
    private Animator mCurrentAnimation;

    /**
     * @param backgroundView view to apply the background drawable
     */
    public PhoneStatusBarTransitions(PhoneStatusBarView statusBarView, View backgroundView) {
        this(statusBarView, backgroundView, null);
    }

    public PhoneStatusBarTransitions(PhoneStatusBarView statusBarView, View backgroundView,
            @Nullable StatusBarTickerController tickerController) {
        super(backgroundView, R.drawable.status_background);
        mTickerController = tickerController;
        final Resources res = statusBarView.getContext().getResources();
        mIconAlphaWhenOpaque = res.getFraction(R.dimen.status_bar_icon_drawing_alpha, 1, 1);
        mControlsPhoneTicker =
                statusBarView.getContext().getDisplayId() == Display.DEFAULT_DISPLAY;
        mStartSide = statusBarView.findViewById(R.id.status_bar_contents_left);
        mStatusIcons = statusBarView.findViewById(R.id.statusIcons);
        mClockHost = statusBarView.findViewById(R.id.privacy_highlight);
        if (mClockHost == null) {
            mClockHost = statusBarView.findViewById(R.id.clock);
        }
        mNetworkCluster = statusBarView.findViewById(R.id.network_signal_cluster);
        mNetSpeed = statusBarView.findViewById(R.id.net_speed_view);
        mOtg = statusBarView.findViewById(R.id.otg);
        mBattery = statusBarView.findViewById(R.id.battery);
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyMode(getMode(), false /*animate*/);
    }

    public ObjectAnimator animateTransitionTo(View v, float toAlpha) {
        return ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), toAlpha);
    }

    private float getStatusIconsAlphaFor(int mode) {
        return getDefaultAlphaFor(mode);
    }

    private float getStartSideAlphaFor(int mode) {
        // When there's a heads up notification, we need the start side icons to show regardless of
        // lights out mode.
        if (mIsHeadsUp) {
            return getIconAlphaBasedOnOpacity(mode);
        }
        return getDefaultAlphaFor(mode);
    }

    private float getBatteryClockAlpha(int mode) {
        return isLightsOut(mode) ? ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK
                : getIconAlphaBasedOnOpacity(mode);
    }

    private float getDefaultAlphaFor(int mode) {
        return isLightsOut(mode) ? ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK
                : getIconAlphaBasedOnOpacity(mode);
    }

    private float getIconAlphaBasedOnOpacity(int mode) {
        return !isOpaque(mode) ? ICON_ALPHA_WHEN_NOT_OPAQUE
                : mIconAlphaWhenOpaque;
    }

    private boolean isOpaque(int mode) {
        return !(mode == MODE_SEMI_TRANSPARENT || mode == MODE_TRANSLUCENT
                || mode == MODE_TRANSPARENT || mode == MODE_LIGHTS_OUT_TRANSPARENT);
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyMode(newMode, animate);
    }

    /** Informs this controller that the heads up notification state has changed. */
    public void onHeadsUpStateChanged(boolean isHeadsUp) {
        mIsHeadsUp = isHeadsUp;
        // We want the icon to be fully visible when the HUN appears, so just immediately change the
        // icon visibility and don't animate.
        applyMode(getMode(), /* animate= */ false);
    }

    private void applyMode(int mode, boolean animate) {
        if (mControlsPhoneTicker && mTickerController != null) {
            mTickerController.setLightsOut(isLightsOut(mode));
        }
        if (mStartSide == null) return; // pre-init
        float newStartSideAlpha = getStartSideAlphaFor(mode);
        float newStatusIconsAlpha = getStatusIconsAlphaFor(mode);
        float newBatteryAlpha = getBatteryClockAlpha(mode);
        if (mCurrentAnimation != null) {
            mCurrentAnimation.cancel();
        }
        if (animate) {
            AnimatorSet anims = new AnimatorSet();
            java.util.ArrayList<Animator> items = new java.util.ArrayList<>();
            items.add(animateTransitionTo(mStartSide, newStartSideAlpha));
            if (mClockHost != null) {
                items.add(animateTransitionTo(mClockHost, newBatteryAlpha));
            }
            if (mStatusIcons != null) {
                items.add(animateTransitionTo(mStatusIcons, newStatusIconsAlpha));
            }
            if (mNetworkCluster != null) {
                items.add(animateTransitionTo(mNetworkCluster, newStatusIconsAlpha));
            }
            if (mNetSpeed != null) {
                items.add(animateTransitionTo(mNetSpeed, newStatusIconsAlpha));
            }
            if (mOtg != null) {
                items.add(animateTransitionTo(mOtg, newStatusIconsAlpha));
            }
            if (mBattery != null) {
                items.add(animateTransitionTo(mBattery, newBatteryAlpha));
            }
            anims.playTogether(items);
            if (isLightsOut(mode)) {
                anims.setDuration(STATUS_BAR_LIGHTS_OUT_DURATION);
            }
            anims.start();
            mCurrentAnimation = anims;
        } else {
            mStartSide.setAlpha(newStartSideAlpha);
            if (mClockHost != null) mClockHost.setAlpha(newBatteryAlpha);
            if (mStatusIcons != null) mStatusIcons.setAlpha(newStatusIconsAlpha);
            if (mNetworkCluster != null) mNetworkCluster.setAlpha(newStatusIconsAlpha);
            if (mNetSpeed != null) mNetSpeed.setAlpha(newStatusIconsAlpha);
            if (mOtg != null) mOtg.setAlpha(newStatusIconsAlpha);
            if (mBattery != null) mBattery.setAlpha(newBatteryAlpha);
        }
    }
}
