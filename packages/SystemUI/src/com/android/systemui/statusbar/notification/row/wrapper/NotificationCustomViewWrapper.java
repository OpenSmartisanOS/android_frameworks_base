/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.widget.CachingIconView;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.NotificationFadeAware;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationCustomViewContainer;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;

/**
 * Wraps a notification containing a custom view.
 */
public class NotificationCustomViewWrapper extends NotificationViewWrapper {

    private boolean mIsLegacy;
    private int mLegacyColor;
    @Nullable private final NotificationCustomViewContainer mCustomContainer;

    protected NotificationCustomViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(ctx, view, row);
        mLegacyColor = row.getContext().getColor(R.color.notification_legacy_background_color);
        mCustomContainer = view instanceof NotificationCustomViewContainer
                ? (NotificationCustomViewContainer) view : null;
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        mView.setAlpha(visible ? 1.0f : 0.0f);
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        super.onContentUpdated(row);

        if (mCustomContainer != null) {
            final Notification notification = NotificationBundleUi.isEnabled()
                    ? row.getEntryAdapter().getSbn().getNotification()
                    : row.getEntryLegacy().getSbn().getNotification();
            // The R2 container accepts only the application's explicit notification color. The
            // row/Monet surface tint must never be forwarded into custom RemoteViews.
            mCustomContainer.setContainerTint(notification.color);
            return;
        }

        // Let's invert the notification colors when we're in night mode and
        // the notification background isn't colorized.
        if (needsInversion(mBackgroundColor, mView)) {
            invertViewLuminosity(mView);

            // Also invert background color if necessary
            // (Otherwise we'd end-up with white on white.)
            float[] hsl = new float[] {0f, 0f, 0f};
            ColorUtils.colorToHSL(mBackgroundColor, hsl);
            if (mBackgroundColor != Color.TRANSPARENT && hsl[2] > 0.5) {
                hsl[2] = 1f - hsl[2];
                mBackgroundColor = ColorUtils.HSLToColor(hsl);
            }
        }
    }

    @Override
    protected boolean shouldClearBackgroundOnReapply() {
        return false;
    }

    @Override
    public int getCustomBackgroundColor() {
        int customBackgroundColor = super.getCustomBackgroundColor();
        if (customBackgroundColor == 0 && mIsLegacy) {
            return mLegacyColor;
        }
        return customBackgroundColor;
    }

    public void setLegacy(boolean legacy) {
        super.setLegacy(legacy);
        mIsLegacy = legacy;
    }

    @Override
    public void updateExpandability(boolean expandable, View.OnClickListener onClickListener,
            boolean requestLayout) {
        if (mCustomContainer == null) {
            super.updateExpandability(expandable, onClickListener, requestLayout);
            return;
        }
        // The canonical custom wrapper owns the R2 shell too. Its compatibility header is never
        // visible and must not be resurrected by the Android 16 expansion controller.
        mCustomContainer.getExpandButton().setVisibility(View.INVISIBLE);
        mCustomContainer.getExpandButton().setOnClickListener(null);
        mCustomContainer.getHeader().setVisibility(View.INVISIBLE);
        mCustomContainer.getHeader().setClickable(false);
        mCustomContainer.getHeader().setOnClickListener(null);
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mCustomContainer != null) {
            mCustomContainer.setExpanded(expanded);
            return;
        }
        super.setExpanded(expanded);
    }

    @Override
    public @Nullable View getExpandButton() {
        return mCustomContainer != null ? mCustomContainer.getExpandButton()
                : super.getExpandButton();
    }

    @Override
    public @Nullable CachingIconView getIcon() {
        return mCustomContainer != null ? mCustomContainer.getIcon() : super.getIcon();
    }

    @Override
    public boolean shouldClipToRounding(boolean topRounded, boolean bottomRounded) {
        return true;
    }

    /**
     * Apply the faded state as a layer type change to the custom view which needs to have
     * overlapping contents render precisely.
     */
    @Override
    public void setNotificationFaded(boolean faded) {
        super.setNotificationFaded(faded);
        NotificationFadeAware.setLayerTypeForFaded(mView, faded);
    }
}
