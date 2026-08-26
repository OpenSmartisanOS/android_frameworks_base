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

import android.graphics.Rect;
import android.util.MathUtils;

import androidx.annotation.NonNull;

/** Pure geometry and alpha model for the SmartisanOS R2 notification-shade background. */
public final class NotificationShadeBackgroundModel {
    private static final float DESIGN_WIDTH_PX = 1080f;
    private static final float MAX_CONTENT_WIDTH_DP = 480f;
    private static final float ORIGINAL_BLUR_RADIUS_PX = (2f / 5.5f) * 200f;
    private static final float ORIGINAL_SCRIM_MAX_ALPHA = 0.62f;
    private static final double ORIGINAL_PI = 3.141590118408203d;

    private NotificationShadeBackgroundModel() {}

    /** Reusable result. Production callers keep one instance to avoid per-frame allocations. */
    public static final class State {
        @NonNull public final Rect blurBounds = new Rect();
        public int blurRadius;
        public float expansionFraction;
        public float scrimAlpha;
        public boolean blurVisible;
    }

    /** Calculates the exact crop and the width-responsive equivalent of the R2 blur radius. */
    @NonNull
    public static State calculate(int widthPx, float density, float expandedHeight,
            float maxPanelHeight, boolean shadeAllowed, boolean blurAvailable) {
        final State state = new State();
        calculateInto(state, widthPx, density, expandedHeight, maxPanelHeight, shadeAllowed,
                blurAvailable);
        return state;
    }

    /** Allocation-free variant used by the shade's frame hot path. */
    public static void calculateInto(@NonNull State outState, int widthPx, float density,
            float expandedHeight, float maxPanelHeight, boolean shadeAllowed,
            boolean blurAvailable) {
        final int safeWidth = Math.max(0, widthPx);
        final float safeDensity = Math.max(0.01f, density);
        final float contentWidth = Math.min(safeWidth, MAX_CONTENT_WIDTH_DP * safeDensity);
        final float scale = Math.max(0.01f, contentWidth / DESIGN_WIDTH_PX);
        final int blurRadius = Math.max(1, Math.round(ORIGINAL_BLUR_RADIUS_PX * scale));

        final float safeMaxHeight = Math.max(0f, maxPanelHeight);
        final float clampedHeight = safeMaxHeight > 0f
                ? MathUtils.constrain(expandedHeight, 0f, safeMaxHeight)
                : 0f;
        final float fraction = safeMaxHeight > 0f
                ? MathUtils.saturate(clampedHeight / safeMaxHeight)
                : 0f;
        outState.blurBounds.set(0, 0, safeWidth, Math.round(clampedHeight));
        final boolean visible = shadeAllowed && blurAvailable && safeWidth > 0
                && outState.blurBounds.bottom > 0;
        outState.blurRadius = blurRadius;
        outState.expansionFraction = fraction;
        outState.scrimAlpha = calculateScrimAlpha(fraction);
        outState.blurVisible = visible;
    }

    /** The original R2 cosine shade-darkening curve. */
    public static float calculateScrimAlpha(float expansionFraction) {
        final float fraction = MathUtils.saturate(expansionFraction);
        final float adjustedFraction = (1.2f * fraction) - 0.2f;
        if (adjustedFraction <= 0f) {
            return 0f;
        }
        final double cosine = Math.cos(
                Math.pow(1f - adjustedFraction, 2d) * ORIGINAL_PI);
        return ORIGINAL_SCRIM_MAX_ALPHA
                * (float) (1d - ((1d - cosine) * 0.5d));
    }
}
