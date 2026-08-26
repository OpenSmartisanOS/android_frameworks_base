/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.notification.row;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.AlphaOptimizedImageView;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

import java.util.ArrayList;

/** The original single-button Smartisan notification block affordance. */
public final class NotificationBlockMenuRow implements NotificationMenuRowPlugin,
        View.OnClickListener, ExpandableNotificationRow.LayoutListener {
    private static final long REVEAL_DELAY_MS = 60L;
    private static final long ALPHA_SCALE_DURATION_MS = 200L;
    private static final float DISMISSIBLE_REVEAL_FRACTION = .4f;
    private static final float NON_DISMISSIBLE_REVEAL_FRACTION = .2f;
    private static final float SNAP_BACK_FRACTION = .6f;
    private static final float DISMISS_FRACTION = .4f;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<MenuItem> mItems = new ArrayList<>(1);
    private final Runnable mRevealCheck = this::revealIfNeeded;

    @Nullable private ExpandableNotificationRow mParent;
    @Nullable private FrameLayout mMenuContainer;
    @Nullable private BlockMenuItem mBlockItem;
    @Nullable private OnMenuEventListener mMenuListener;
    @Nullable private ValueAnimator mAnimator;

    private int mMenuWidth;
    private int mMinRowHeight;
    private float mTranslation;
    private float mAlpha;
    private boolean mFadedIn;
    private boolean mAnimating;
    private boolean mSnapped;
    private boolean mDismissing;

    public NotificationBlockMenuRow(Context context) {
        mContext = context;
        reloadDimensions();
    }

    /** Typed routing check; these items never enter NotificationGuts. */
    public static boolean isBlockItem(@Nullable MenuItem item) {
        return item instanceof BlockMenuItem;
    }

    @Override
    public ArrayList<MenuItem> getMenuItems(Context context) {
        return new ArrayList<>(mItems);
    }

    @Override
    public MenuItem getLongpressMenuItem(Context context) {
        return mBlockItem;
    }

    @Override
    public MenuItem getFeedbackMenuItem(Context context) {
        return null;
    }

    @Override
    public MenuItem getSnoozeMenuItem(Context context) {
        return null;
    }

    @Override
    public void setMenuItems(ArrayList<MenuItem> items) {
        // The R2 presentation owns exactly one typed block item.
    }

    @Override
    public void setMenuClickListener(OnMenuEventListener listener) {
        mMenuListener = listener;
    }

    @Override
    public void setAppName(String appName) {
        if (mBlockItem != null) {
            mBlockItem.setAppName(appName);
        }
    }

    @Override
    public void createMenu(ViewGroup parent) {
        mParent = (ExpandableNotificationRow) parent;
        buildMenu();
        mParent.setLayoutListener(this);
    }

    private void buildMenu() {
        cancelAnimationsAndChecks();
        mItems.clear();
        mBlockItem = mParent != null && NotificationBlockDialogController.canShow(mParent)
                ? new BlockMenuItem(mContext) : null;

        final FrameLayout container = new FrameLayout(mContext);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        container.setVisibility(View.INVISIBLE);
        mMenuContainer = container;
        if (mBlockItem != null) {
            mItems.add(mBlockItem);
            final View button = mBlockItem.getMenuView();
            button.setOnClickListener(this);
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    mMenuWidth, mMenuWidth, Gravity.END | Gravity.TOP);
            container.addView(button, lp);
        }
        positionButton();
        setMenuAlpha(0f);
        mSnapped = false;
        mDismissing = false;
    }

    private void reloadDimensions() {
        mMenuWidth = mContext.getResources().getDimensionPixelSize(
                R.dimen.sos_notification_menu_width);
        mMinRowHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.sos_notification_min_height);
    }

    private void positionButton() {
        if (mMenuContainer == null || mMenuContainer.getChildCount() == 0 || mParent == null) {
            return;
        }
        final View button = mMenuContainer.getChildAt(0);
        button.setX(Math.max(0, mParent.getWidth() - mMenuWidth));
        final int visibleHeight = Math.min(mParent.getActualHeight(), mMinRowHeight);
        button.setTranslationY((visibleHeight - mMenuWidth) / 2f);
    }

    @Override
    public void resetMenu() {
        cancelAnimationsAndChecks();
        mSnapped = false;
        mDismissing = false;
        setMenuAlpha(0f);
        if (mMenuListener != null && mParent != null) {
            mMenuListener.onMenuReset(mParent);
        }
    }

    @Override
    public View getMenuView() {
        return mMenuContainer;
    }

    @Override
    public int getMenuSnapTarget() {
        return mBlockItem == null ? 0 : -mMenuWidth;
    }

    @Override
    public boolean shouldShowMenu() {
        return mBlockItem != null;
    }

    @Override
    public boolean isMenuVisible() {
        return mAlpha > 0f;
    }

    @Override
    public boolean isTowardsMenu(float movement) {
        // The button is physically behind the right edge; a positive velocity covers it.
        return isMenuVisible() && movement >= 0f;
    }

    @Override
    public boolean shouldSnapBack() {
        return mTranslation > -mMenuWidth * SNAP_BACK_FRACTION;
    }

    @Override
    public boolean isSnappedAndOnSameSide() {
        return mSnapped && isMenuVisible();
    }

    @Override
    public boolean canBeDismissed() {
        return mParent != null && mParent.canViewBeDismissed();
    }

    @Override
    public boolean isWithinSnapMenuThreshold() {
        return mParent != null && mBlockItem != null
                && mTranslation < -mMenuWidth * SNAP_BACK_FRACTION
                && mTranslation > -mParent.getWidth() * DISMISS_FRACTION;
    }

    @Override
    public boolean isSwipedEnoughToShowMenu() {
        final float revealFraction = canBeDismissed()
                ? DISMISSIBLE_REVEAL_FRACTION : NON_DISMISSIBLE_REVEAL_FRACTION;
        return isMenuVisible() && mTranslation < -mMenuWidth * revealFraction;
    }

    @Override
    public void onParentTranslationUpdate(float translation) {
        mTranslation = translation;
        if (translation >= 0f && !mSnapped) {
            setMenuAlpha(0f);
            return;
        }
        if (!mAnimating && mFadedIn && mParent != null) {
            final float absTranslation = Math.abs(translation);
            final float fadeStart = mParent.getWidth() * .3f;
            final float alpha = absTranslation <= fadeStart ? 1f
                    : 1f - (absTranslation - fadeStart)
                            / Math.max(1f, mParent.getWidth() - fadeStart);
            setMenuAlpha(Math.max(0f, Math.min(1f, alpha)));
        }
    }

    @Override
    public void onParentHeightUpdate() {
        positionButton();
    }

    @Override
    public void onNotificationUpdated() {
        if (mMenuContainer != null) {
            buildMenu();
        }
    }

    @Override
    public void onTouchMove(float delta) {
        mSnapped = false;
        if (!shouldShowMenu() || mParent == null || mTranslation >= 0f
                || NotificationStackScrollLayout.isPinnedHeadsUp(mParent)
                || mParent.areGutsExposed() || mParent.showingPulsing()) {
            mHandler.removeCallbacks(mRevealCheck);
            return;
        }
        if (!mHandler.hasCallbacks(mRevealCheck)) {
            mHandler.postDelayed(mRevealCheck, REVEAL_DELAY_MS);
        }
    }

    private void revealIfNeeded() {
        if (mParent == null || mBlockItem == null || mTranslation >= 0f) {
            return;
        }
        final float absTranslation = Math.abs(mTranslation);
        final float revealFraction = canBeDismissed()
                ? DISMISSIBLE_REVEAL_FRACTION : NON_DISMISSIBLE_REVEAL_FRACTION;
        if (absTranslation >= mMenuWidth * revealFraction
                && absTranslation < mParent.getWidth() * DISMISS_FRACTION) {
            animateMenuAlpha(1f);
        }
    }

    @Override
    public void onTouchStart() {
        cancelAnimationsAndChecks();
        mDismissing = false;
    }

    @Override
    public void onTouchEnd() {}

    @Override
    public void onSnapClosed() {
        mSnapped = false;
        animateMenuAlpha(0f);
    }

    @Override
    public void onSnapOpen() {
        mSnapped = true;
        animateMenuAlpha(1f);
        if (mMenuListener != null && mParent != null) {
            mMenuListener.onMenuShown(mParent);
        }
    }

    @Override
    public void onDismiss() {
        mDismissing = true;
        mSnapped = false;
        cancelAnimationsAndChecks();
        setMenuAlpha(0f);
    }

    @Override
    public void onConfigurationChanged() {
        reloadDimensions();
        if (mParent != null) {
            mParent.setLayoutListener(this);
        }
    }

    @Override
    public void onLayout() {
        positionButton();
        if (mParent != null) {
            mParent.setLayoutListener(null);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(View view, MotionEvent event) {
        return false;
    }

    @Override
    public Point getRevealAnimationOrigin() {
        if (mBlockItem == null || mParent == null) {
            return new Point();
        }
        final View button = mBlockItem.getMenuView();
        return new Point(mParent.getWidth() - mMenuWidth / 2,
                Math.round(button.getY() + mMenuWidth / 2f));
    }

    @Override
    public void onClick(View view) {
        if (mMenuListener == null || mParent == null || mBlockItem == null) {
            return;
        }
        mMenuListener.onMenuClicked(mParent,
                mParent.getWidth() - mMenuWidth / 2,
                Math.round(view.getY() + mMenuWidth / 2f), mBlockItem);
    }

    private void animateMenuAlpha(float target) {
        if (mDismissing) {
            return;
        }
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = ValueAnimator.ofFloat(mAlpha, target);
        mAnimator.setDuration(ALPHA_SCALE_DURATION_MS);
        mAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mAnimator.addUpdateListener(animation -> setMenuAlpha((float) animation.getAnimatedValue()));
        mAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                mAnimating = true;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimating = false;
                if (!mCancelled) {
                    setMenuAlpha(target);
                }
            }
        });
        mAnimator.start();
    }

    private void setMenuAlpha(float alpha) {
        mAlpha = alpha;
        mFadedIn = alpha >= 1f;
        if (mMenuContainer == null) {
            return;
        }
        mMenuContainer.setVisibility(alpha > 0f ? View.VISIBLE : View.INVISIBLE);
        if (mMenuContainer.getChildCount() != 0) {
            final View button = mMenuContainer.getChildAt(0);
            button.setAlpha(alpha);
            button.setScaleX(alpha);
            button.setScaleY(alpha);
        }
    }

    private void cancelAnimationsAndChecks() {
        mHandler.removeCallbacks(mRevealCheck);
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        mAnimating = false;
    }

    private static final class BlockMenuItem implements MenuItem {
        private final AlphaOptimizedImageView mView;
        private final String mDescription;

        BlockMenuItem(Context context) {
            mDescription = context.getString(R.string.sos_notification_block);
            mView = new AlphaOptimizedImageView(context);
            mView.setImageResource(R.drawable.sos_ic_notification_block);
            mView.setScaleType(ImageView.ScaleType.CENTER);
            mView.setContentDescription(mDescription);
        }

        @Override
        public View getMenuView() {
            return mView;
        }

        @Override
        public Object getGutsContent() {
            return null;
        }

        @Override
        public String getContentDescription() {
            return mDescription;
        }

        @Override
        public void setAppName(String appName) {}
    }
}
