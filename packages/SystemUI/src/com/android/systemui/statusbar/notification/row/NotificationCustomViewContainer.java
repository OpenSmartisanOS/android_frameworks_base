/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.statusbar.notification.row;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Notification;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.internal.widget.CachingIconView;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Smartisan R2 custom RemoteViews shell.
 *
 * <p>The contracted shell intentionally contains no application view and renders as an empty
 * 60dp placeholder.  Only the expanded shell owns the already safety-validated application view.
 * The original implementation's {@code isGroupSummary()} always returned false, therefore the
 * header and divider are never visible and the application container has equal 14.4dp margins.</p>
 */
public final class NotificationCustomViewContainer extends FrameLayout {
    private static final float CONTAINER_MARGIN_DP = 14.4f;
    private static final float CONTAINER_RADIUS_DP = 3.6f;
    private static final int FLAG_SMT_PUSH = 0x20000000;

    private final int mMinHeight;
    private final int mLegacySmallHeight;
    private final int mModernSmallHeight;
    private final int mContainerMargin;
    private final float mRadius;

    private final FrameLayout mHiddenHeader;
    private final CachingIconView mHiddenIcon;
    private final ImageView mHiddenExpandButton;

    @Nullable private FrameLayout mNotificationContainer;
    @Nullable private View mNotificationView;
    private int mTargetSdk;
    private boolean mBusinessPush;
    private boolean mBigContent;
    @Nullable private Drawable mContainerBackground;

    private NotificationCustomViewContainer(Context context) {
        super(context);
        setClipChildren(false);
        mMinHeight = dp(60f);
        mLegacySmallHeight = dp(64f);
        mModernSmallHeight = dp(92f);
        mContainerMargin = dp(CONTAINER_MARGIN_DP);
        mRadius = dp(CONTAINER_RADIUS_DP);

        mHiddenHeader = new FrameLayout(context);
        mHiddenHeader.setVisibility(INVISIBLE);
        addView(mHiddenHeader, new LayoutParams(MATCH_PARENT, mMinHeight, Gravity.TOP));

        mHiddenIcon = new CachingIconView(context);
        mHiddenIcon.setVisibility(INVISIBLE);
        mHiddenHeader.addView(mHiddenIcon, new LayoutParams(0, 0));

        mHiddenExpandButton = new ImageView(context);
        mHiddenExpandButton.setVisibility(INVISIBLE);
        mHiddenHeader.addView(mHiddenExpandButton, new LayoutParams(0, 0));
    }

    /** Creates the contracted blank shell or the expanded application-content shell. */
    public static View wrap(Context context, NotificationEntry entry, @Nullable View appView,
            boolean expanded, boolean bigContent) {
        final NotificationCustomViewContainer shell =
                new NotificationCustomViewContainer(context);
        shell.bind(entry, expanded ? appView : null, bigContent);
        return shell;
    }

    private void bind(NotificationEntry entry, @Nullable View appView, boolean bigContent) {
        mTargetSdk = entry.targetSdk;
        final StatusBarNotification sbn = entry.getSbn();
        final Notification notification = sbn.getNotification();
        mBusinessPush = (notification.flags & FLAG_SMT_PUSH) != 0;
        mBigContent = bigContent;

        // DecoratedCustomViewStyle is CUSTOM in R2. Hide its nested framework header (modeSmall
        // in A11) before placing the validated view in the vendor shell.
        if (appView != null) {
            final View nestedHeader = appView.findViewById(
                    com.android.internal.R.id.notification_header);
            if (nestedHeader != null) {
                nestedHeader.setVisibility(GONE);
            }
            addApplicationView(appView);
        } else {
            setMinimumHeight(mMinHeight);
        }
    }

    private void addApplicationView(View appView) {
        mNotificationView = appView;
        final FrameLayout container = new FrameLayout(getContext());
        mNotificationContainer = container;

        mContainerBackground = getContext().getDrawable(
                R.drawable.sos_custom_notification_container_bg).mutate();
        container.setBackground(mContainerBackground);
        container.setClipToOutline(true);
        container.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mRadius);
            }
        });

        final int margin = mBusinessPush ? 0 : mContainerMargin;
        final LayoutParams containerLp = new LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP);
        containerLp.setMargins(margin, margin, margin, margin);
        container.addView(appView, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(container, containerLp);
    }

    /** Applies only an explicit app notification tint; the default row/Monet tint never enters. */
    public void setContainerTint(int color) {
        if (mContainerBackground == null) {
            return;
        }
        if (color != 0) {
            mContainerBackground.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        } else {
            mContainerBackground.clearColorFilter();
        }
        if (mNotificationContainer != null) {
            mNotificationContainer.setBackground(mContainerBackground);
        }
    }

    public ImageView getExpandButton() {
        return mHiddenExpandButton;
    }

    public CachingIconView getIcon() {
        return mHiddenIcon;
    }

    public View getHeader() {
        return mHiddenHeader;
    }

    public void setExpanded(boolean expanded) {
        // The header is deliberately hidden for both states in the original implementation.
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mNotificationView == null || mNotificationContainer == null) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(mMinHeight, MeasureSpec.EXACTLY));
            return;
        }

        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int parentLimit = heightMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(heightMeasureSpec);
        final int margins = mBusinessPush ? 0 : mContainerMargin * 2;
        final int contentLimit;
        final int contentMode;
        if (mBigContent) {
            contentLimit = parentLimit == Integer.MAX_VALUE
                    ? 0 : Math.max(0, parentLimit - margins);
            contentMode = parentLimit == Integer.MAX_VALUE
                    ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST;
        } else {
            contentLimit = Math.min(
                    parentLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE
                            : Math.max(0, parentLimit - margins),
                    mTargetSdk < 24 ? mLegacySmallHeight : mModernSmallHeight);
            contentMode = mTargetSdk < 24 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
        }

        final int childWidth = Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - margins);
        final int childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth,
                MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                        ? MeasureSpec.UNSPECIFIED : MeasureSpec.EXACTLY);
        final int childHeightSpec = MeasureSpec.makeMeasureSpec(
                contentLimit == Integer.MAX_VALUE ? 0 : contentLimit, contentMode);
        mNotificationView.measure(childWidthSpec, childHeightSpec);
        int measuredContentHeight = mNotificationView.getMeasuredHeight();
        if (!mBigContent && mTargetSdk >= 24 && measuredContentHeight < mMinHeight) {
            measuredContentHeight = mMinHeight;
        }

        final ViewGroup.LayoutParams appLp = mNotificationView.getLayoutParams();
        appLp.height = measuredContentHeight;
        mNotificationView.setLayoutParams(appLp);
        final ViewGroup.LayoutParams containerLp = mNotificationContainer.getLayoutParams();
        containerLp.height = measuredContentHeight;
        mNotificationContainer.setLayoutParams(containerLp);

        final int shellHeight = measuredContentHeight + margins;
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(shellHeight, MeasureSpec.EXACTLY));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
