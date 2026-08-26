/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.notification.stack;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.Surface;

import androidx.annotation.NonNull;

/** Pure, per-host geometry for the Smartisan notification presentation. */
public final class NotificationRowGeometry {
    public static final class Result {
        public final Rect safeBounds;
        public final float literalPixelScale;
        public final float fontScaleMultiplier;
        public final boolean portrait;
        public final boolean rtl;
        public final int cardWidth;
        public final int normalHeadsUpWidth;
        public final int inCallHeadsUpWidth;
        public final int headsUpTop;
        public final int standardHeadsUpMaxHeight;
        public final int legacyCustomHeadsUpMaxHeight;
        public final int bitmapCornerRadius;
        public final int dividerHeight;
        public final int outlineHorizontalInset;
        public final int outlineVerticalInset;

        private Result(Rect safeBounds, float literalPixelScale, float fontScaleMultiplier,
                boolean portrait, boolean rtl, int cardWidth, int normalHeadsUpWidth,
                int inCallHeadsUpWidth, int headsUpTop, int standardHeadsUpMaxHeight,
                int legacyCustomHeadsUpMaxHeight, int bitmapCornerRadius, int dividerHeight,
                int outlineHorizontalInset, int outlineVerticalInset) {
            this.safeBounds = safeBounds;
            this.literalPixelScale = literalPixelScale;
            this.fontScaleMultiplier = fontScaleMultiplier;
            this.portrait = portrait;
            this.rtl = rtl;
            this.cardWidth = cardWidth;
            this.normalHeadsUpWidth = normalHeadsUpWidth;
            this.inCallHeadsUpWidth = inCallHeadsUpWidth;
            this.headsUpTop = headsUpTop;
            this.standardHeadsUpMaxHeight = standardHeadsUpMaxHeight;
            this.legacyCustomHeadsUpMaxHeight = legacyCustomHeadsUpMaxHeight;
            this.bitmapCornerRadius = bitmapCornerRadius;
            this.dividerHeight = dividerHeight;
            this.outlineHorizontalInset = outlineHorizontalInset;
            this.outlineVerticalInset = outlineVerticalInset;
        }
    }

    private NotificationRowGeometry() {}

    /** Scale for constants which were literal pixels in the 1080px/400dpi source binary. */
    public static float literalPixelScale(int hostWidth, int hostHeight, int densityDpi) {
        final int shortSide = Math.min(Math.max(0, hostWidth), Math.max(0, hostHeight));
        return Math.max(0f, Math.min(shortSide / 1080f, densityDpi / 400f));
    }

    @NonNull
    public static Result calculate(@NonNull Rect hostBounds, float density, float scaledDensity,
            int densityDpi, int rotation, @NonNull Insets cutoutSafeInsets,
            @NonNull Insets waterfallInsets, boolean rtl, boolean capCardWidth) {
        final int hostWidth = Math.max(0, hostBounds.width());
        final int hostHeight = Math.max(0, hostBounds.height());
        final int leftInset = Math.max(cutoutSafeInsets.left, waterfallInsets.left);
        final int topInset = Math.max(cutoutSafeInsets.top, waterfallInsets.top);
        final int rightInset = Math.max(cutoutSafeInsets.right, waterfallInsets.right);
        final int bottomInset = Math.max(cutoutSafeInsets.bottom, waterfallInsets.bottom);
        final Rect safe = new Rect(hostBounds.left + Math.min(leftInset, hostWidth),
                hostBounds.top + Math.min(topInset, hostHeight),
                hostBounds.right - Math.min(rightInset, hostWidth),
                hostBounds.bottom - Math.min(bottomInset, hostHeight));
        if (safe.right < safe.left) safe.right = safe.left;
        if (safe.bottom < safe.top) safe.bottom = safe.top;

        final float s = literalPixelScale(hostWidth, hostHeight, densityDpi);
        final float fontMultiplier = Math.max(1f,
                density > 0f ? scaledDensity / density : 1f);
        final boolean portrait = hostHeight >= hostWidth
                && (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180);
        final int horizontalMargin = Math.round(24f * density);
        final int uncappedCardWidth = Math.max(0, safe.width() - horizontalMargin);
        final int cardWidth = capCardWidth
                ? Math.min(uncappedCardWidth, Math.round(416f * density))
                : uncappedCardWidth;
        final int normalHunWidth = portrait ? cardWidth
                : Math.min(safe.width(), Math.round(1200f * s));
        final int inCallHunWidth = Math.min(safe.width(), Math.round(900f * s));
        final int top = portrait
                ? Math.max(safe.top, hostBounds.top + Math.round(128f * s))
                : Math.max(safe.top, hostBounds.top + Math.round(9f * density));

        return new Result(safe, s, fontMultiplier, portrait, rtl, cardWidth,
                normalHunWidth, inCallHunWidth, top,
                Math.round(148f * density * fontMultiplier),
                Math.round(128f * density * fontMultiplier),
                Math.round(9f * s), Math.max(1, Math.round(2f * s)),
                Math.max(1, Math.round(6f * s)), Math.max(1, Math.round(18f * s)));
    }
}
