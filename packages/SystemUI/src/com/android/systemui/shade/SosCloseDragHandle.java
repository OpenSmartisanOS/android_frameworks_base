/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.res.R;

/**
 * Smartisan's close handle follows the lower edge of the expanding shade.
 *
 * <p>The view itself fills the shade window so its child can be positioned at the current panel
 * height. Keeping the moving part in a child also leaves the rest of the full-screen overlay
 * transparent to touches.
 */
public class SosCloseDragHandle extends FrameLayout {
    private View mHandle;
    private int mHandleHeight;
    private float mExpandedHeight;

    public SosCloseDragHandle(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandle = requireViewById(R.id.sos_shade_close_handle);
        mHandleHeight =
                getResources().getDimensionPixelSize(R.dimen.sos_shade_close_handle_height);
        updateHandlePosition();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateHandlePosition();
    }

    public int getHandleHeight() {
        return mHandleHeight;
    }

    public void setExpandedHeight(float expandedHeight) {
        if (mExpandedHeight == expandedHeight) {
            return;
        }
        mExpandedHeight = expandedHeight;
        updateHandlePosition();
    }

    private void updateHandlePosition() {
        if (mHandle == null || mHandleHeight == 0) {
            return;
        }
        // The shade window is edge-to-edge. Keep the panel edge at the physical display bottom;
        // interactive controls apply the navigation inset separately.
        final float expandedBottom = Math.max(0f, mExpandedHeight);
        final float maximumTop = Math.max(-mHandleHeight, getHeight() - mHandleHeight);
        final float handleTop =
                Math.max(-mHandleHeight, Math.min(maximumTop, expandedBottom - mHandleHeight));
        mHandle.setTranslationY(handleTop);
    }
}
