/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.internal.widget;

import android.annotation.DimenRes;
import android.content.Context;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.RemoteViews;

import java.util.ArrayList;

/** Original equal-width Smartisan notification action strip. @hide */
@RemoteViews.RemoteView
public final class SmartisanosNotificationActionListLayout
        extends NotificationActionListLayout {
    private static final String DIVIDER_TAG = "smartisan_notification_action_divider";

    private final ArrayList<View> mVisibleActions = new ArrayList<>();
    private final ArrayList<View> mDividers = new ArrayList<>();
    private boolean mAddingDivider;
    private boolean mMeasureLinearly;

    public SmartisanosNotificationActionListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private boolean isDivider(View child) {
        return DIVIDER_TAG.equals(child.getTag());
    }

    private View createDivider() {
        final View divider = new View(getContext());
        divider.setTag(DIVIDER_TAG);
        divider.setBackgroundColor(0x14000000);
        divider.setVisibility(GONE);
        divider.setLayoutParams(new LayoutParams(2, LayoutParams.MATCH_PARENT));
        return divider;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (mAddingDivider || isDivider(child)) {
            return;
        }
        mAddingDivider = true;
        addView(createDivider());
        mAddingDivider = false;
    }

    private void collectVisibleChildren() {
        mVisibleActions.clear();
        mDividers.clear();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (isDivider(child)) {
                mDividers.add(child);
                child.setVisibility(GONE);
            } else if (child.getVisibility() != GONE) {
                mVisibleActions.add(child);
            }
        }
        boolean hasVisibleActionAfter = false;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final View child = getChildAt(i);
            if (isDivider(child)) {
                final View action = i > 0 ? getChildAt(i - 1) : null;
                child.setVisibility(action != null && action.getVisibility() != GONE
                        && hasVisibleActionAfter ? VISIBLE : GONE);
            } else if (child.getVisibility() != GONE) {
                hasVisibleActionAfter = true;
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMeasureLinearly) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        collectVisibleChildren();
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int actionCount = mVisibleActions.size();
        final int dividerCount = Math.max(0, actionCount - 1);
        final int available = Math.max(0,
                width - getPaddingLeft() - getPaddingRight() - dividerCount * 2);
        final int baseCellWidth = actionCount == 0 ? 0 : available / actionCount;
        int remainder = actionCount == 0 ? 0 : available % actionCount;
        int measuredHeight = 0;

        for (View action : mVisibleActions) {
            final MarginLayoutParams lp = (MarginLayoutParams) action.getLayoutParams();
            final int cellWidth = baseCellWidth + (remainder-- > 0 ? 1 : 0);
            action.measure(MeasureSpec.makeMeasureSpec(
                            Math.max(0, cellWidth - lp.leftMargin - lp.rightMargin),
                            MeasureSpec.EXACTLY),
                    getChildMeasureSpec(heightMeasureSpec,
                            getPaddingTop() + getPaddingBottom() + lp.topMargin + lp.bottomMargin,
                            lp.height));
            measuredHeight = Math.max(measuredHeight,
                    action.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        }
        for (View divider : mDividers) {
            if (divider.getVisibility() != GONE) {
                divider.measure(MeasureSpec.makeMeasureSpec(2, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY));
            }
        }
        setMeasuredDimension(resolveSize(available + dividerCount * 2
                        + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(measuredHeight + getPaddingTop() + getPaddingBottom(),
                        heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mMeasureLinearly) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int x = rtl ? right - left - getPaddingRight() : getPaddingLeft();
        final int contentHeight = bottom - top - getPaddingTop() - getPaddingBottom();
        for (int index = 0; index < getChildCount(); index++) {
            final View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            final int childTop = getPaddingTop()
                    + (contentHeight - childHeight) / 2 + lp.topMargin - lp.bottomMargin;
            if (rtl) {
                x -= lp.rightMargin;
                child.layout(x - childWidth, childTop, x, childTop + childHeight);
                x -= childWidth + lp.leftMargin;
            } else {
                x += lp.leftMargin;
                child.layout(x, childTop, x + childWidth, childTop + childHeight);
                x += childWidth + lp.rightMargin;
            }
        }
    }

    @RemotableViewMethod
    public void setCollapsibleIndentDimen(@DimenRes int ignored) {}

    @RemotableViewMethod
    public void setEvenlyDividedMode(boolean ignored) {}

    @RemotableViewMethod
    public void setEmphasizedMode(boolean emphasized) {
        if (mMeasureLinearly != emphasized) {
            mMeasureLinearly = emphasized;
            requestLayout();
        }
    }
}
