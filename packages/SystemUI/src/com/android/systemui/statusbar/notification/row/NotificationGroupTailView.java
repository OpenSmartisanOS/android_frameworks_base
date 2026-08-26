/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.res.R;

/** The independent lower cap used by an expanded R2 notification group. */
public final class NotificationGroupTailView extends View {
    private int mAnimationGeneration;

    public NotificationGroupTailView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundResource(R.drawable.sos_notification_child_tail_material_bg);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setExpanded(boolean expanded) {
        animate().cancel();
        mAnimationGeneration++;
        setVisibility(expanded ? VISIBLE : INVISIBLE);
        setAlpha(expanded ? 1.0f : 0.0f);
    }

    /** Original tail follows a child removal with a short 100ms correction. */
    public void correctTranslation(float translationY) {
        final int generation = ++mAnimationGeneration;
        animate().cancel();
        animate().translationY(translationY).setDuration(100L).withEndAction(() -> {
            if (generation == mAnimationGeneration) {
                setTranslationY(translationY);
            }
        }).start();
    }
}
