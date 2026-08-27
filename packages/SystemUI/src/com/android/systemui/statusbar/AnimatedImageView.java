/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews.RemoteView;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.res.R;

@RemoteView
public class AnimatedImageView extends ImageView {
    private final boolean mHasOverlappingRendering;
    AnimationDrawable mAnim;
    boolean mAttached;
    private boolean mAllowAnimation = true;
    private boolean mUseSosHostTint;
    private int mHostTint;

    // Tracks the last image that was set, so that we don't refresh the image if it is exactly
    // the same as the previous one. If this is a resid, we track that. If it's a drawable, we
    // track the hashcode of the drawable.
    int mDrawableId;

    public AnimatedImageView(Context context) {
        this(context, null);
    }

    public AnimatedImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
                R.styleable.AnimatedImageView, 0, 0);

        try {
            // Default to true, which is what View.java defaults toA
            mHasOverlappingRendering = a.getBoolean(
                    R.styleable.AnimatedImageView_hasOverlappingRendering, true);
        } finally {
            a.recycle();
        }
    }

    public void setAllowAnimation(boolean allowAnimation) {
        if (mAllowAnimation != allowAnimation) {
            mAllowAnimation = allowAnimation;
            updateAnim();
            if (!mAllowAnimation && mAnim != null) {
                // Reset drawable such that we show the first frame whenever we're not animating.
                mAnim.setVisible(getVisibility() == VISIBLE, true /* restart */);
            }
        }
    }

    /**
     * Applies the R2 ticker host color only to monochrome notification icons.
     *
     * <p>The factory ticker preserves application-provided colored artwork while adapting white
     * status icons to the current light/dark host. Keeping this on the icon View also means a
     * switcher can replace its drawable without the appearance controller racing the next frame.
     */
    public void setHostTint(int tint) {
        mUseSosHostTint = true;
        mHostTint = tint;
        updateSosHostTint();
    }

    private void updateSosHostTint() {
        if (!mUseSosHostTint) {
            return;
        }
        final Drawable drawable = getDrawable();
        final boolean monochrome = drawable != null
                && ContrastColorUtil.getInstance(getContext()).isGrayscaleIcon(drawable);
        clearColorFilter();
        setImageTintList(monochrome ? ColorStateList.valueOf(mHostTint) : null);
    }

    private void updateAnim() {
        Drawable drawable = getDrawable();
        if (mAttached && mAnim != null) {
            mAnim.stop();
        }
        if (drawable instanceof AnimationDrawable) {
            mAnim = (AnimationDrawable) drawable;
            if (isShown() && mAllowAnimation) {
                mAnim.start();
            }
        } else {
            mAnim = null;
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        if (drawable != null) {
            if (mDrawableId == drawable.hashCode()) return;

            mDrawableId = drawable.hashCode();
        } else {
            mDrawableId = 0;
        }
        super.setImageDrawable(drawable);
        updateAnim();
        updateSosHostTint();
    }

    @Override
    @android.view.RemotableViewMethod
    public void setImageResource(int resid) {
        if (mDrawableId == resid) return;

        mDrawableId = resid;
        super.setImageResource(resid);
        updateAnim();
        updateSosHostTint();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        updateAnim();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnim != null) {
            mAnim.stop();
        }
        mAttached = false;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int vis) {
        super.onVisibilityChanged(changedView, vis);
        if (mAnim != null) {
            if (isShown() && mAllowAnimation) {
                mAnim.start();
            } else {
                mAnim.stop();
            }
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return mHasOverlappingRendering;
    }
}
