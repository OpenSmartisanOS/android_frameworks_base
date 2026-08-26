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

/** Keeps the notification/QS page selector attached to the physical shade edge. */
public class ShadePageSwitch extends FrameLayout {
    private View mHandle;
    private int mHandleHeight;
    private int mVisibleHandleHeight;
    private float mExpandedHeight;

    public ShadePageSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandle = requireViewById(R.id.shade_page_switch_container);
        mHandleHeight = mHandle.getLayoutParams().height;
        mVisibleHandleHeight =
                getResources().getDimensionPixelSize(R.dimen.shade_page_switch_container_height);
        updateHandlePosition();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateHandlePosition();
    }

    /** Returns the original handle height excluding its 15dp shadow underlap. */
    public int getSwitchHeight() {
        return mVisibleHandleHeight;
    }

    public void setExpandedHeight(float expandedHeight) {
        if (mExpandedHeight == expandedHeight) {
            return;
        }
        mExpandedHeight = expandedHeight;
        updateHandlePosition();
    }

    private void updateHandlePosition() {
        if (mHandle == null || mHandleHeight == 0 || mVisibleHandleHeight == 0) {
            return;
        }
        // Smartisan's 87dp status_bar_close surface includes a 15dp shadow underlap. Position its
        // 72dp visible portion at the physical shade edge; the shadow is allowed to extend beyond
        // that edge and is naturally clipped at the display bottom.
        final float expandedBottom = Math.max(0f, Math.min(getHeight(), mExpandedHeight));
        final float handleTop = expandedBottom - mVisibleHandleHeight;
        mHandle.setTranslationY(handleTop);
    }
}
