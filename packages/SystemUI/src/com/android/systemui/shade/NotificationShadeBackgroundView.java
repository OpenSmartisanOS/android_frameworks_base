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

package com.android.systemui.shade;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import java.util.function.Consumer;

/**
 * SurfaceFlinger-backed, physically cropped blur used by the SmartisanOS R2 notification shade.
 *
 * <p>The original blur amount becomes active at the first non-zero panel height. Only the crop
 * bottom follows the shade edge; the radius never follows Android's spring/depth animation.</p>
 */
public class NotificationShadeBackgroundView extends View {
    @Nullable private BackgroundBlurDrawable mBlurDrawable;
    @Nullable private WindowManager mWindowManager;
    private boolean mCrossWindowBlurEnabled;
    private float mExpandedHeight;
    private float mMaxPanelHeight;
    private boolean mShadeAllowed;
    private boolean mListenerRegistered;
    private final NotificationShadeBackgroundModel.State mState =
            new NotificationShadeBackgroundModel.State();
    private int mAppliedBlurRadius = -1;
    private boolean mAppliedVisibility;

    private final Consumer<Boolean> mBlurEnabledListener = enabled -> {
        mCrossWindowBlurEnabled = enabled;
        applyState();
    };

    public NotificationShadeBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    /** Mirrors the physical shade edge already calculated by NotificationPanelViewController. */
    public void setExpansion(float expandedHeight, float maxPanelHeight, boolean shadeAllowed) {
        if (mExpandedHeight == expandedHeight
                && mMaxPanelHeight == maxPanelHeight
                && mShadeAllowed == shadeAllowed) {
            return;
        }
        mExpandedHeight = Math.max(0f, expandedHeight);
        mMaxPanelHeight = Math.max(0f, maxPanelHeight);
        mShadeAllowed = shadeAllowed;
        applyState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setVisibility(VISIBLE);
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot != null) {
            mBlurDrawable = viewRoot.createBackgroundBlurDrawable();
            mAppliedBlurRadius = -1;
            mAppliedVisibility = false;
            mBlurDrawable.setCallback(this);
            mBlurDrawable.setColor(Color.TRANSPARENT);
            mBlurDrawable.setCornerRadius(0f);
            mBlurDrawable.setAlpha(255);
            mBlurDrawable.setVisible(false, false);
        }
        mWindowManager = getContext().getSystemService(WindowManager.class);
        if (mWindowManager != null) {
            mWindowManager.addCrossWindowBlurEnabledListener(mBlurEnabledListener);
            mListenerRegistered = true;
        }
        applyState();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mListenerRegistered && mWindowManager != null) {
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurEnabledListener);
        }
        mListenerRegistered = false;
        mWindowManager = null;
        if (mBlurDrawable != null) {
            mBlurDrawable.setVisible(false, false);
            mBlurDrawable.setBlurRadius(0);
            mBlurDrawable.setCallback(null);
            mBlurDrawable = null;
        }
        mAppliedBlurRadius = -1;
        mAppliedVisibility = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyState();
    }

    @Override
    protected void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyState();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mBlurDrawable != null && mBlurDrawable.isVisible()) {
            mBlurDrawable.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mBlurDrawable || super.verifyDrawable(who);
    }

    private void applyState() {
        final BackgroundBlurDrawable drawable = mBlurDrawable;
        if (drawable == null) {
            return;
        }
        NotificationShadeBackgroundModel.calculateInto(
                mState, getWidth(), getResources().getDisplayMetrics().density, mExpandedHeight,
                mMaxPanelHeight, mShadeAllowed, mCrossWindowBlurEnabled);
        final int blurRadius = mState.blurVisible ? mState.blurRadius : 0;
        boolean changed = false;
        if (mAppliedBlurRadius != blurRadius) {
            mAppliedBlurRadius = blurRadius;
            drawable.setBlurRadius(blurRadius);
            changed = true;
        }
        if (!drawable.getBounds().equals(mState.blurBounds)) {
            drawable.setBounds(mState.blurBounds);
            changed = true;
        }
        if (mAppliedVisibility != mState.blurVisible) {
            mAppliedVisibility = mState.blurVisible;
            drawable.setVisible(mState.blurVisible, false);
            changed = true;
        }
        if (changed) {
            invalidate();
        }
    }
}
