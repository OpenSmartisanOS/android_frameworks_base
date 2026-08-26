/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.statusbar.notification.stack;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationRowGeometryTest {
    @Test
    public void portrait1080x2340_usesR2LiteralPixelBaseline() {
        NotificationRowGeometry.Result result = NotificationRowGeometry.calculate(
                new Rect(0, 0, 1080, 2340), 2.5f, 2.5f, 400,
                Surface.ROTATION_0, Insets.NONE, Insets.NONE, false, false);

        assertThat(result.literalPixelScale).isEqualTo(1f);
        assertThat(result.cardWidth).isEqualTo(1020);
        assertThat(result.normalHeadsUpWidth).isEqualTo(1020);
        assertThat(result.inCallHeadsUpWidth).isEqualTo(900);
        assertThat(result.headsUpTop).isEqualTo(128);
        assertThat(result.bitmapCornerRadius).isEqualTo(9);
        assertThat(result.dividerHeight).isEqualTo(2);
        assertThat(result.outlineHorizontalInset).isEqualTo(6);
        assertThat(result.outlineVerticalInset).isEqualTo(18);
    }

    @Test
    public void portrait1080x2460At420dpi_scaleIsStillOne() {
        NotificationRowGeometry.Result result = NotificationRowGeometry.calculate(
                new Rect(0, 0, 1080, 2460), 2.625f, 5.25f, 420,
                Surface.ROTATION_0, Insets.NONE, Insets.NONE, false, false);

        assertThat(result.literalPixelScale).isEqualTo(1f);
        assertThat(result.fontScaleMultiplier).isEqualTo(2f);
        assertThat(result.headsUpTop).isEqualTo(128);
        assertThat(result.standardHeadsUpMaxHeight).isEqualTo(777);
    }

    @Test
    public void landscape_clampsHunToCutoutAndWaterfallSafeBounds() {
        NotificationRowGeometry.Result result = NotificationRowGeometry.calculate(
                new Rect(0, 0, 2340, 1080), 2.5f, 2.5f, 400,
                Surface.ROTATION_90, Insets.of(90, 0, 40, 0),
                Insets.of(12, 8, 20, 10), true, false);

        assertThat(result.safeBounds).isEqualTo(new Rect(90, 8, 2300, 1070));
        assertThat(result.normalHeadsUpWidth).isEqualTo(1200);
        assertThat(result.inCallHeadsUpWidth).isEqualTo(900);
        assertThat(result.headsUpTop).isEqualTo(23);
        assertThat(result.rtl).isTrue();
    }

    @Test
    public void largeDisplay_capsNormalCardsAt416dp() {
        NotificationRowGeometry.Result result = NotificationRowGeometry.calculate(
                new Rect(0, 0, 2560, 1600), 2f, 2f, 320,
                Surface.ROTATION_90, Insets.NONE, Insets.NONE, false, true);

        assertThat(result.cardWidth).isEqualTo(832);
    }

    @Test
    public void nonZeroHostOrigin_offsetsLiteralHunTopFromHost() {
        NotificationRowGeometry.Result result = NotificationRowGeometry.calculate(
                new Rect(40, 700, 1120, 3040), 2.5f, 2.5f, 400,
                Surface.ROTATION_0, Insets.NONE, Insets.NONE, false, false);

        assertThat(result.safeBounds).isEqualTo(new Rect(40, 700, 1120, 3040));
        assertThat(result.headsUpTop).isEqualTo(828);
    }
}
