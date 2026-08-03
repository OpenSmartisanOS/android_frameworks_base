/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.settings.brightness;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;

import com.android.systemui.res.R;

/** Legacy Smartisan brightness surface backed by the current brightness controller. */
public class SosBrightnessSliderView extends BrightnessSliderView {
    private static final long ICON_SCALE_DURATION_MS = 100L;
    private static final int SEGMENT_COUNT = 40;
    private static final int[] ICONS = {
            R.drawable.brightness_icon_close,
            R.drawable.brightness_icon_small,
            R.drawable.brightness_icon,
    };

    private ImageView mIcon;
    private View mBrightnessMin;
    private View mBrightnessMax;
    private SegmentedBrightnessDrawable mSegmentedDrawable;
    private int mLastState = -1;

    public SosBrightnessSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void setBoundaryOffset() {
        // The SOS track is designed to fit its own 46dp bounds. The A16 focus expansion
        // introduces negative margins and clips the legacy 9-patch.
    }

    @Override
    protected void initBrightnessViewComponents() {
        super.initBrightnessViewComponents();
        mIcon = findViewById(R.id.brightness_icon);
        mBrightnessMin = findViewById(R.id.brightness_min);
        mBrightnessMax = findViewById(R.id.brightness_max);
        mSlider.setThumb(null);
        mSegmentedDrawable = new SegmentedBrightnessDrawable(getContext());
        mSlider.setProgressDrawable(mSegmentedDrawable);
        mBrightnessMin.setOnClickListener(view -> setValue(0));
        mBrightnessMax.setOnClickListener(view -> setValue(getMax()));
        refreshVisuals(getValue());
    }

    @Override
    public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener listener) {
        super.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refreshVisuals(progress);
                if (listener != null) listener.onProgressChanged(seekBar, progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSegmentedDrawable.setExpanded(true);
                if (listener != null) listener.onStartTrackingTouch(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mSegmentedDrawable.setExpanded(false);
                if (listener != null) listener.onStopTrackingTouch(seekBar);
            }
        });
    }

    @Override
    public void setValue(int value) {
        super.setValue(value);
        refreshVisuals(value);
    }

    private void refreshVisuals(int progress) {
        if (mIcon == null) return;
        final int state = progress <= 0 ? 0 : (progress * 2 >= getMax() ? 2 : 1);
        if (state == mLastState) return;
        final int previousState = mLastState;
        mLastState = state;
        setBackgroundResource(state == 0
                ? R.drawable.brightness_seekbar_normal_bg
                : R.drawable.brightness_seekbar_active_bg);
        animateIcon(previousState, state);
    }

    private void animateIcon(int previousState, int state) {
        mIcon.animate().setListener(null);
        mIcon.animate().cancel();
        if (state == 0 || previousState <= 0) {
            mIcon.setScaleX(1f);
            mIcon.setScaleY(1f);
            mIcon.setImageResource(ICONS[state]);
            return;
        }
        final float targetScale = state == 1 && previousState == 2 ? 0.7f : 1.4285715f;
        mIcon.setPivotX(mIcon.getWidth() / 2f);
        mIcon.setPivotY(mIcon.getHeight() / 2f);
        mIcon.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(ICON_SCALE_DURATION_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mIcon.setScaleX(1f);
                        mIcon.setScaleY(1f);
                        mIcon.setImageResource(ICONS[state]);
                        mIcon.animate().setListener(null);
                    }
                })
                .start();
    }

    private static final class SegmentedBrightnessDrawable extends Drawable {
        private final Drawable mNormalSegment;
        private final Drawable mActiveBackgroundSegment;
        private final Drawable mActiveProgressSegment;
        private final float mSegmentWidth;
        private final float mNormalSegmentHeight;
        private final float mExpandedSegmentHeight;
        private final DecelerateInterpolator mInterpolator = new DecelerateInterpolator();
        private ValueAnimator mHeightAnimator;
        private float mSegmentHeight;

        SegmentedBrightnessDrawable(Context context) {
            mSegmentWidth =
                    context.getResources().getDimension(R.dimen.sos_brightness_segment_width);
            mNormalSegmentHeight =
                    context.getResources().getDimension(R.dimen.sos_brightness_segment_height);
            mExpandedSegmentHeight =
                    context.getResources().getDimension(
                            R.dimen.sos_brightness_segment_expanded_height);
            mSegmentHeight = mNormalSegmentHeight;
            mNormalSegment =
                    context.getDrawable(R.drawable.sos_brightness_segment_normal).mutate();
            mActiveBackgroundSegment =
                    context.getDrawable(R.drawable.sos_brightness_segment_active_bg).mutate();
            mActiveProgressSegment =
                    context.getDrawable(R.drawable.sos_brightness_segment_active_progress).mutate();
        }

        void setExpanded(boolean expanded) {
            final float targetHeight =
                    expanded ? mExpandedSegmentHeight : mNormalSegmentHeight;
            if (mSegmentHeight == targetHeight) {
                return;
            }
            if (mHeightAnimator != null) {
                mHeightAnimator.cancel();
            }
            mHeightAnimator = ValueAnimator.ofFloat(mSegmentHeight, targetHeight);
            mHeightAnimator.setDuration(expanded ? 300L : 200L);
            mHeightAnimator.setInterpolator(mInterpolator);
            mHeightAnimator.addUpdateListener(animation -> {
                mSegmentHeight = (float) animation.getAnimatedValue();
                invalidateSelf();
            });
            mHeightAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mHeightAnimator = null;
                }
            });
            mHeightAnimator.start();
        }

        @Override
        protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect bounds = getBounds();
            final float usableWidth = bounds.width() - mSegmentWidth * SEGMENT_COUNT;
            final float gap = Math.max(0f, usableWidth / (SEGMENT_COUNT - 1));
            final float top = bounds.centerY() - mSegmentHeight / 2f;
            final int level = getLevel();
            final int activeSegments =
                    level == 0 ? 0 : Math.min(
                            SEGMENT_COUNT,
                            Math.max(1, (int) Math.ceil(level * SEGMENT_COUNT / 10000f)));
            for (int i = 0; i < SEGMENT_COUNT; i++) {
                final Drawable segment =
                        level == 0
                                ? mNormalSegment
                                : i < activeSegments
                                        ? mActiveProgressSegment
                                        : mActiveBackgroundSegment;
                final float left = bounds.left + i * (mSegmentWidth + gap);
                segment.setBounds(
                        Math.round(left),
                        Math.round(top),
                        Math.round(left + mSegmentWidth),
                        Math.round(top + mSegmentHeight));
                segment.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mNormalSegment.setAlpha(alpha);
            mActiveBackgroundSegment.setAlpha(alpha);
            mActiveProgressSegment.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mNormalSegment.setColorFilter(colorFilter);
            mActiveBackgroundSegment.setColorFilter(colorFilter);
            mActiveProgressSegment.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(mExpandedSegmentHeight);
        }
    }
}
