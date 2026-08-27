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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.LevelListDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.StatusBarGeometry;
import com.android.systemui.statusbar.phone.StatusBarMetrics;

import java.util.ArrayList;

/**
 * Smartisan's two-presentation battery.
 *
 * <p>The superclass is retained only as the stable SystemUI/Dagger type. Its AOSP child tree is
 * removed after construction and is never used by this view.
 */
public final class BatteryView extends BatteryMeterView
        implements BatteryStateController.Listener {
    private static final int LOW_BATTERY_MIN = 4;
    private static final int LOW_BATTERY_MAX = 10;
    private static final int LOW_BATTERY_COLOR = Color.rgb(0xE1, 0x3E, 0x35);
    private static final int POWER_SAVE_COLOR = Color.rgb(0xFF, 0xCC, 0x00);

    private static final long NUMERIC_FADE_IN_MS = 500L;
    private static final long NUMERIC_FADE_OUT_MS = 1000L;
    private static final long NUMERIC_LEVEL_HOLD_MS = 1000L;

    private static final Interpolator EASE_IN_OUT = input -> {
        float doubled = input * 2f;
        if (doubled < 1f) {
            return 0.5f * doubled * doubled * doubled;
        }
        float shifted = doubled - 2f;
        return 0.5f * (shifted * shifted * shifted + 2f);
    };

    private FrameLayout mOriginalContent;
    private ImageView mGraphicBattery;
    private FrameLayout mNumericBattery;
    private ImageView mNumericLevel;
    private ImageView mNumericBackground;

    private BatteryState mState = new BatteryState(
            0,
            BatteryState.STYLE_GRAPHIC,
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            true);
    private int mHostTint = DarkIconDispatcher.DEFAULT_ICON_TINT;
    private boolean mColorIcon;
    private boolean mColorIconRequested;
    private boolean mStaticColor;
    private @DrawableRes int mGraphicResource;
    private @DrawableRes int mNumericLevelResource;
    private @DrawableRes int mNumericBackgroundResource;
    private AnimatorSet mNumericAnimator;
    private boolean mNumericAnimationRunning;

    public BatteryView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        buildOriginalViewTree(context);
        renderState();
    }

    private void buildOriginalViewTree(Context context) {
        setLayoutTransition(null);
        removeAllViews();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        mOriginalContent = new FrameLayout(context);
        mGraphicBattery = new ImageView(context);
        mGraphicBattery.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mGraphicBattery.setId(R.id.battery_graphic);

        mNumericBattery = new FrameLayout(context);
        mNumericBattery.setId(R.id.battery_numeric);
        mNumericLevel = new ImageView(context);
        mNumericLevel.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mNumericLevel.setId(R.id.battery_charge_level);
        mNumericBackground = new ImageView(context);
        mNumericBackground.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mNumericBackground.setId(R.id.battery_charge_background);

        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        mNumericBattery.addView(mNumericLevel, new FrameLayout.LayoutParams(match));
        mNumericBattery.addView(mNumericBackground, new FrameLayout.LayoutParams(match));
        mOriginalContent.addView(mGraphicBattery, new FrameLayout.LayoutParams(match));
        mOriginalContent.addView(mNumericBattery, new FrameLayout.LayoutParams(match));
        addView(mOriginalContent);
        scaleBatteryMeterViews();

        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    public void onBatteryStateChanged(BatteryState state) {
        if (state.equals(mState)) {
            updateContentDescription(state);
            return;
        }
        // State transitions invalidate the entire local animation generation. In particular,
        // cancel before replacing drawables so a late Animator callback cannot restart a cycle
        // for the previous style, charge state, or screen lifecycle.
        stopGraphicAnimation();
        stopNumericAnimation();
        mState = state;
        renderState();
    }

    /** Original full-color artwork switch. Normal light/dark tint does not call this method. */
    public void setColorIcon(boolean colorIcon) {
        mColorIconRequested = colorIcon;
        boolean resolved = colorIcon && mHostTint == DarkIconDispatcher.DEFAULT_ICON_TINT;
        if (mColorIcon == resolved) {
            return;
        }
        mColorIcon = resolved;
        renderState();
    }

    @Override
    public void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mHostTint = singleToneColor;
        resolveColorIconForHostTint();
        renderState();
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mStaticColor) {
            return;
        }
        mHostTint = DarkIconDispatcher.getTint(areas, this, tint);
        resolveColorIconForHostTint();
        renderState();
    }

    private void resolveColorIconForHostTint() {
        mColorIcon = mColorIconRequested
                && mHostTint == DarkIconDispatcher.DEFAULT_ICON_TINT;
    }

    @Override
    public void setStaticColor(boolean isStaticColor) {
        mStaticColor = isStaticColor;
    }

    @Override
    public void setForceShowPercent(boolean show) {
        // Smartisan owns percentage selection through Settings.System.status_bar_battery.
    }

    @Override
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        // Smartisan owns percentage selection through Settings.System.status_bar_battery.
    }

    @Override
    void updateShowPercent() {
        // The AOSP percent TextView must never be attached to the Smartisan battery.
    }

    @Override
    void updatePercentText() {
        updateContentDescription(mState);
    }

    @Override
    void scaleBatteryMeterViews() {
        if (mOriginalContent == null) {
            return;
        }
        StatusBarMetrics metrics = StatusBarGeometry.calculate(this);
        int width = metrics.getBatteryWidth();
        int height = metrics.getIconHeight();
        LayoutParams params = new LayoutParams(width, height);
        params.gravity = Gravity.CENTER_VERTICAL;
        mOriginalContent.setLayoutParams(params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        renderState();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopGraphicAnimation();
        stopNumericAnimation();
        super.onDetachedFromWindow();
    }

    private void renderState() {
        if (mOriginalContent == null) {
            return;
        }
        updateContentDescription(mState);
        if (mState.isUnknown()) {
            renderUnknownState();
        } else if (mState.isPercentStyle()) {
            renderPercentState();
        } else {
            renderGraphicState();
        }
    }

    private void renderUnknownState() {
        stopGraphicAnimation();
        stopNumericAnimation();
        mGraphicBattery.setVisibility(VISIBLE);
        mNumericBattery.setVisibility(GONE);
        setGraphicResource(R.drawable.stat_sys_battery);
        mGraphicBattery.setImageLevel(0);
        mGraphicBattery.setColorFilter(mHostTint, PorterDuff.Mode.SRC_IN);
    }

    private void renderGraphicState() {
        stopNumericAnimation();
        mGraphicBattery.setVisibility(VISIBLE);
        mNumericBattery.setVisibility(GONE);

        @DrawableRes int resource;
        boolean useColoredFamily = mColorIcon && !mState.isPowerSave();
        if (mState.shouldAnimateCharging()) {
            resource = useColoredFamily
                    ? R.drawable.colored_stat_sys_battery_charge
                    : R.drawable.stat_sys_battery_charge;
        } else if (mState.isFull()) {
            resource = useColoredFamily
                    ? R.drawable.colored_stat_sys_battery_full
                    : R.drawable.stat_sys_battery_full;
        } else {
            resource = useColoredFamily
                    ? R.drawable.colored_stat_sys_battery
                    : R.drawable.stat_sys_battery;
        }

        setGraphicResource(resource);
        mGraphicBattery.setImageLevel(mState.getLevel());
        applyGraphicColors(useColoredFamily);
        if (mState.shouldAnimateCharging()) {
            AnimationDrawable animation = findGraphicAnimation();
            if (animation != null && !animation.isRunning()) {
                animation.start();
            }
        }
    }

    private void applyGraphicColors(boolean useColoredFamily) {
        mGraphicBattery.clearColorFilter();
        LayerDrawable layers = getCurrentGraphicLayers();
        if (layers != null) {
            Drawable frame = layers.findDrawableByLayerId(R.id.frame);
            Drawable content = layers.findDrawableByLayerId(R.id.content);
            if (frame != null) {
                frame.clearColorFilter();
            }
            if (content != null) {
                content.clearColorFilter();
            }
        }
        if (useColoredFamily) {
            return;
        }

        if (mState.isPowerSave() && mState.isFull()) {
            mGraphicBattery.setColorFilter(POWER_SAVE_COLOR, PorterDuff.Mode.SRC_IN);
            return;
        }

        boolean low = mState.getLevel() >= LOW_BATTERY_MIN
                && mState.getLevel() <= LOW_BATTERY_MAX
                && !mState.isCharging()
                && !mState.isFull();
        if (layers != null && (mState.isPowerSave() || low)) {
            Drawable frame = layers.findDrawableByLayerId(R.id.frame);
            Drawable content = layers.findDrawableByLayerId(R.id.content);
            if (frame != null) {
                frame.setColorFilter(mHostTint, PorterDuff.Mode.SRC_IN);
            }
            if (content != null) {
                content.setColorFilter(
                        mState.isPowerSave() ? POWER_SAVE_COLOR : LOW_BATTERY_COLOR,
                        PorterDuff.Mode.SRC_IN);
            }
            return;
        }
        mGraphicBattery.setColorFilter(mHostTint, PorterDuff.Mode.SRC_IN);
    }

    private void renderPercentState() {
        stopGraphicAnimation();
        mGraphicBattery.setVisibility(GONE);
        mNumericBattery.setVisibility(VISIBLE);

        boolean useColoredFamily = mColorIcon && !mState.isPowerSave();
        setNumericLevelResource(resolveDrawable(mState.getPercentDrawableName(useColoredFamily)));
        setNumericBackgroundResource(resolveDrawable(getPercentBackgroundName(useColoredFamily)));
        applyPercentColors(useColoredFamily);

        if (mState.shouldAnimateCharging()) {
            startNumericAnimation();
        } else {
            stopNumericAnimation();
        }
    }

    private String getPercentBackgroundName(boolean useColoredFamily) {
        if (mState.isPowerSave()) {
            return "smaritisan_stat_sys_powersave_battery_backgroud";
        }
        if (mState.getLevel() <= LOW_BATTERY_MAX) {
            return useColoredFamily
                    ? "colored_smaritisan_stat_sys_battery_low_backgroud"
                    : "smaritisan_stat_sys_battery_low_backgroud";
        }
        return useColoredFamily
                ? "colored_smaritisan_stat_sys_battery_background"
                : "smaritisan_stat_sys_battery_background";
    }

    private void applyPercentColors(boolean useColoredFamily) {
        mNumericLevel.clearColorFilter();
        mNumericBackground.clearColorFilter();
        if (useColoredFamily) {
            return;
        }
        if (mState.isPowerSave()) {
            if (mState.isFull()) {
                mNumericLevel.setColorFilter(POWER_SAVE_COLOR, PorterDuff.Mode.SRC_IN);
                mNumericBackground.setColorFilter(POWER_SAVE_COLOR, PorterDuff.Mode.SRC_ATOP);
            }
            return;
        }
        if (mState.getLevel() > LOW_BATTERY_MAX) {
            mNumericLevel.setColorFilter(mHostTint, PorterDuff.Mode.SRC_IN);
            mNumericBackground.setColorFilter(mHostTint, PorterDuff.Mode.SRC_ATOP);
        }
    }

    private void startNumericAnimation() {
        if (mNumericAnimationRunning || !isAttachedToWindow()) {
            return;
        }
        mNumericAnimationRunning = true;
        startNumericAnimationCycle();
    }

    private void startNumericAnimationCycle() {
        if (!mNumericAnimationRunning || !isAttachedToWindow()) {
            return;
        }
        mNumericBackground.setAlpha(0f);

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(mNumericBackground, View.ALPHA, 0f, 1f);
        fadeIn.setDuration(NUMERIC_FADE_IN_MS);
        fadeIn.setInterpolator(EASE_IN_OUT);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(mNumericBackground, View.ALPHA, 1f, 0f);
        fadeOut.setDuration(NUMERIC_FADE_OUT_MS);
        fadeOut.setInterpolator(EASE_IN_OUT);
        ObjectAnimator hold = ObjectAnimator.ofFloat(mNumericBackground, View.ALPHA, 0f, 0f);
        hold.setDuration(NUMERIC_LEVEL_HOLD_MS);
        hold.setInterpolator(new LinearInterpolator());

        AnimatorSet cycle = new AnimatorSet();
        cycle.playSequentially(fadeIn, fadeOut, hold);
        mNumericAnimator = cycle;
        cycle.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mNumericAnimator != animation) {
                    return;
                }
                mNumericAnimator = null;
                if (mNumericAnimationRunning) {
                    startNumericAnimationCycle();
                }
            }
        });
        cycle.start();
    }

    private void stopNumericAnimation() {
        mNumericAnimationRunning = false;
        AnimatorSet animator = mNumericAnimator;
        mNumericAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        if (mNumericBackground != null) {
            mNumericBackground.setAlpha(0f);
        }
        if (mNumericLevel != null) {
            mNumericLevel.setAlpha(1f);
        }
    }

    private void stopGraphicAnimation() {
        AnimationDrawable animation = findGraphicAnimation();
        if (animation != null) {
            animation.stop();
        }
    }

    private @Nullable AnimationDrawable findGraphicAnimation() {
        LayerDrawable layers = getCurrentGraphicLayers();
        if (layers == null) {
            return null;
        }
        Drawable content = layers.findDrawableByLayerId(R.id.content);
        return content instanceof AnimationDrawable ? (AnimationDrawable) content : null;
    }

    private @Nullable LayerDrawable getCurrentGraphicLayers() {
        if (mGraphicBattery == null) {
            return null;
        }
        Drawable drawable = mGraphicBattery.getDrawable();
        if (drawable instanceof LevelListDrawable) {
            drawable = ((LevelListDrawable) drawable).getCurrent();
        }
        return drawable instanceof LayerDrawable ? (LayerDrawable) drawable : null;
    }

    private void setImageDrawable(ImageView view, @DrawableRes int resource) {
        Drawable drawable = getContext().getDrawable(resource);
        view.setImageDrawable(drawable == null ? null : drawable.mutate());
    }

    private void setGraphicResource(@DrawableRes int resource) {
        if (mGraphicResource == resource) {
            return;
        }
        stopGraphicAnimation();
        mGraphicResource = resource;
        setImageDrawable(mGraphicBattery, resource);
    }

    private void setNumericLevelResource(@DrawableRes int resource) {
        if (mNumericLevelResource == resource) {
            return;
        }
        mNumericLevelResource = resource;
        setImageDrawable(mNumericLevel, resource);
    }

    private void setNumericBackgroundResource(@DrawableRes int resource) {
        if (mNumericBackgroundResource == resource) {
            return;
        }
        mNumericBackgroundResource = resource;
        setImageDrawable(mNumericBackground, resource);
    }

    private @DrawableRes int resolveDrawable(String resourceName) {
        String resourcePackage = getResources().getResourcePackageName(R.drawable.stat_sys_battery);
        int resource = getResources().getIdentifier(
                resourceName, "drawable", resourcePackage);
        return resource != 0 ? resource : R.drawable.stat_sys_battery;
    }

    private void updateContentDescription(BatteryState state) {
        if (state.isUnknown()) {
            setContentDescription(getContext().getString(R.string.accessibility_battery_unknown));
        } else if (state.isPlugged() && state.isCharging() && !state.isIncompatibleCharging()) {
            setContentDescription(getContext().getString(
                    R.string.accessibility_battery_level_charging, state.getLevel()));
        } else {
            setContentDescription(getContext().getString(
                    R.string.accessibility_battery_level, state.getLevel()));
        }
    }

    @VisibleForTesting
    ImageView getGraphicBatteryView() {
        return mGraphicBattery;
    }

    @VisibleForTesting
    ImageView getNumericLevelView() {
        return mNumericLevel;
    }

    @VisibleForTesting
    ImageView getNumericBackgroundView() {
        return mNumericBackground;
    }

    @VisibleForTesting
    boolean isNumericAnimationRunning() {
        return mNumericAnimationRunning;
    }
}
