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
package com.android.systemui.statusbar.phone

import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.testing.TestableLooper.RunWithLooper
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryView
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.Clock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
@RunWithLooper(setAsMainLooper = true)
class StatusBarLayoutInflationTest : SysuiTestCase() {
    @Test
    fun statusBar_inflatesCenteredClockAndFixedRightCluster() {
        val bar =
            LayoutInflater.from(context)
                .inflate(R.layout.status_bar, FrameLayout(context), false) as PhoneStatusBarView

        val contents = bar.requireViewById<RelativeLayout>(R.id.status_bar_contents)
        val clock = contents.requireViewById<Clock>(R.id.clock)
        val left = contents.requireViewById<View>(R.id.status_bar_contents_left)
        val endSide = contents.requireViewById<View>(R.id.status_bar_end_side_content)
        val cluster = bar.requireViewById<NetworkSignalCluster>(R.id.network_signal_cluster)
        val merger = bar.requireViewById<View>(R.id.statusIcons)
        val battery = bar.requireViewById<BatteryView>(R.id.battery)
        val privacy = contents.requireViewById<View>(R.id.privacy_highlight)

        assertThat(clock.parent).isEqualTo(privacy)
        val privacyLp = privacy.layoutParams as RelativeLayout.LayoutParams
        assertThat(privacyLp.rules[RelativeLayout.CENTER_IN_PARENT])
            .isEqualTo(RelativeLayout.TRUE)

        val leftLp = left.layoutParams as RelativeLayout.LayoutParams
        assertThat(leftLp.rules[RelativeLayout.ALIGN_PARENT_START]).isEqualTo(RelativeLayout.TRUE)
        assertThat(leftLp.rules[RelativeLayout.START_OF])
            .isEqualTo(R.id.privacy_highlight)

        val endLp = endSide.layoutParams as RelativeLayout.LayoutParams
        assertThat(endLp.rules[RelativeLayout.ALIGN_PARENT_END]).isEqualTo(RelativeLayout.TRUE)
        assertThat(endLp.rules[RelativeLayout.END_OF])
            .isEqualTo(R.id.privacy_highlight)

        assertThat(merger).isInstanceOf(StatusIconMerger::class.java)
        assertThat(merger).isInstanceOf(StatusIconContainer::class.java)
        assertThat(cluster).isNotNull()
        assertThat(battery).isNotNull()
        assertThat(privacy.parent).isEqualTo(contents)
        assertThat(privacy.findViewById<View>(R.id.clock)).isSameInstanceAs(clock)

        assertThat(bar.requireViewById<View>(R.id.net_speed_view).visibility).isEqualTo(View.GONE)
        assertThat(bar.requireViewById<View>(R.id.otg).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun panelStatusBar_usesOriginalCarrierCutoutAndSystemIconComposition() {
        val panel =
            LayoutInflater.from(context)
                .inflate(R.layout.shade_panel_status_bar, FrameLayout(context), false)

        // Cutout fill is owned by the device/Lineage overlay. R2 must not add a black compact bar.
        assertThat(panel.background).isNull()
        assertThat(panel.findViewById<Clock>(R.id.clock)).isNull()
        assertThat(panel.findViewById<View>(R.id.notification_icon_area)).isNull()
        assertThat(panel.requireViewById<View>(R.id.network_label)).isNotNull()
        assertThat(panel.requireViewById<View>(R.id.shade_panel_display_cutout_space)).isNotNull()
        assertThat(panel.requireViewById<View>(R.id.status_bar_contents_left)).isNotNull()
        assertThat(panel.requireViewById<View>(R.id.status_bar_end_side_content)).isNotNull()
        assertThat(panel.requireViewById<StatusIconMerger>(R.id.statusIcons)).isNotNull()
        assertThat(panel.requireViewById<NetworkSignalCluster>(R.id.network_signal_cluster))
            .isNotNull()
        assertThat(panel.requireViewById<BatteryView>(R.id.battery)).isNotNull()
        assertThat(panel.requireViewById<View>(R.id.net_speed_view).visibility)
            .isEqualTo(View.GONE)
        assertThat(panel.requireViewById<View>(R.id.otg).visibility).isEqualTo(View.GONE)
        assertThat(panel.findViewById<View>(R.id.sidebar_drag)).isNull()
    }

    @Test
    fun homeAndPanel_accessoriesBelongToIndependentConcreteSubtrees() {
        val window = FrameLayout(context)
        val home =
            LayoutInflater.from(context)
                .inflate(R.layout.status_bar, window, false) as PhoneStatusBarView
        val panel =
            LayoutInflater.from(context)
                .inflate(R.layout.shade_panel_status_bar, window, false)
        window.addView(home)
        window.addView(panel)

        assertThat(home.requireViewById<View>(R.id.net_speed_view))
            .isNotSameInstanceAs(panel.requireViewById<View>(R.id.net_speed_view))
        assertThat(home.requireViewById<View>(R.id.otg))
            .isNotSameInstanceAs(panel.requireViewById<View>(R.id.otg))
        assertThat(home.requireViewById<View>(R.id.sidebar_drag)).isNotNull()
        assertThat(panel.findViewById<View>(R.id.sidebar_drag)).isNull()
    }

    @Test
    fun networkCluster_configurationChangePreservesConcreteParentLayoutParams() {
        val bar =
            LayoutInflater.from(context)
                .inflate(R.layout.status_bar, FrameLayout(context), false) as PhoneStatusBarView
        val cluster = bar.requireViewById<NetworkSignalCluster>(R.id.network_signal_cluster)
        val wifiSlot = cluster.requireViewById<FrameLayout>(R.id.wifi_slot)
        val mobileSlot = cluster.requireViewById<LinearLayout>(R.id.mobile_slot_container)
        val wifiChild = View(context)
        val mobileChild = View(context)
        wifiSlot.addView(wifiChild, FrameLayout.LayoutParams(20, 20))
        mobileSlot.addView(mobileChild, LinearLayout.LayoutParams(20, 20))
        wifiSlot.visibility = View.VISIBLE
        mobileSlot.visibility = View.VISIBLE

        cluster.onConfigurationChanged(Configuration(context.resources.configuration))

        assertThat(wifiChild.layoutParams).isInstanceOf(FrameLayout.LayoutParams::class.java)
        assertThat(mobileChild.layoutParams).isInstanceOf(LinearLayout.LayoutParams::class.java)
        cluster.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(76, View.MeasureSpec.EXACTLY),
        )
    }

    @Test
    fun statusBar_appliedInsetsOverrideUnavailableRootCutoutAfterRecreation() {
        val bar =
            LayoutInflater.from(context)
                .inflate(R.layout.status_bar, FrameLayout(context), false) as PhoneStatusBarView
        bar.layout(0, 0, 1080, 76)
        val cutout =
            DisplayCutout(
                Insets.of(0, 76, 0, 0),
                null,
                Rect(479, 0, 601, 76),
                null,
                null,
            )

        bar.onApplyWindowInsets(WindowInsets.Builder().setDisplayCutout(cutout).build())

        val privacyHost = bar.requireViewById<View>(R.id.privacy_highlight)
        val camera = bar.requireViewById<View>(R.id.camera_area)
        val privacyLp = privacyHost.layoutParams as RelativeLayout.LayoutParams
        assertThat(privacyLp.rules[RelativeLayout.ALIGN_PARENT_START])
            .isEqualTo(RelativeLayout.TRUE)
        assertThat(privacyLp.rules[RelativeLayout.CENTER_IN_PARENT]).isEqualTo(0)
        assertThat(camera.visibility).isEqualTo(View.VISIBLE)
        assertThat(camera.layoutParams.width).isEqualTo(122)
    }
}
