/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.notification.emptyshade.ui.view

import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
@RunWithLooper(setAsMainLooper = true)
class EmptyShadeViewInflationTest : SysuiTestCase() {
    @Test
    fun canonicalLayout_inflatesOnlyR2Presentation() {
        val view =
            LayoutInflater.from(context)
                .inflate(R.layout.status_bar_no_notifications, FrameLayout(context), false)

        assertThat(view).isInstanceOf(EmptyShadeView::class.java)
        assertThat(view.layoutParams.height).isEqualTo(FrameLayout.LayoutParams.MATCH_PARENT)
        assertThat(view.requireViewById<View>(R.id.empty_shade_content)).isNotNull()
        assertThat(view.requireViewById<View>(R.id.empty_shade_time)).isNotNull()
        assertThat(view.requireViewById<View>(R.id.empty_shade_weather)).isNotNull()
        assertThat(view.requireViewById<View>(R.id.empty_shade_logo)).isNotNull()
        assertThat(view.requireViewById<View>(R.id.no_notifications)).isNotNull()
    }
}
