/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.widget;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.res.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationCountViewTest {
    @Test
    public void backgroundResource_usesSingleDoubleAndMaxMonochromeGlyphs() {
        assertThat(NotificationCountView.backgroundResourceForCount(1, false))
                .isEqualTo(R.drawable.smartisan_bg_notification_count_single);
        assertThat(NotificationCountView.backgroundResourceForCount(9, false))
                .isEqualTo(R.drawable.smartisan_bg_notification_count_single);
        assertThat(NotificationCountView.backgroundResourceForCount(10, false))
                .isEqualTo(R.drawable.smartisan_bg_notification_count_double);
        assertThat(NotificationCountView.backgroundResourceForCount(99, false))
                .isEqualTo(R.drawable.smartisan_bg_notification_count_double);
        assertThat(NotificationCountView.backgroundResourceForCount(100, false))
                .isEqualTo(R.drawable.smartisan_bg_notification_count_max);
    }

    @Test
    public void displayText_clampsAtNinetyNine() {
        assertThat(NotificationCountView.displayTextForCount(1)).isEqualTo("1");
        assertThat(NotificationCountView.displayTextForCount(99)).isEqualTo("99");
        assertThat(NotificationCountView.displayTextForCount(100)).isEqualTo("99");
    }
}
