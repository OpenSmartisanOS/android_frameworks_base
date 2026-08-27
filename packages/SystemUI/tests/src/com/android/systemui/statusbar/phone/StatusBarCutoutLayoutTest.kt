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

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.Clock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class StatusBarCutoutLayoutTest : SysuiTestCase() {
    @Test
    fun apply_none_keepsClockCenteredInContents() {
        val tree = tree()
        StatusBarCutoutLayout.apply(tree.contents, StatusBarCutoutMode.NONE, null)

        assertThat(tree.clock.parent).isEqualTo(tree.privacyHost)
        val lp = tree.privacyHost.layoutParams as RelativeLayout.LayoutParams
        assertThat(lp.rules[RelativeLayout.CENTER_IN_PARENT]).isEqualTo(RelativeLayout.TRUE)
        assertThat(tree.camera.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun apply_center_keepsStableClockHostAndBoundsLeftAreaBeforeRealCutout() {
        val tree = tree()
        tree.privacyHost.layoutParams = RelativeLayout.LayoutParams(83, 80)
        StatusBarCutoutLayout.apply(
            tree.contents,
            StatusBarCutoutMode.CENTER,
            Rect(500, 0, 580, 80),
        )
        layout(tree.contents, 1080, 80)

        assertThat(tree.clock.parent).isEqualTo(tree.privacyHost)
        assertThat(tree.camera.visibility).isEqualTo(View.VISIBLE)
        assertThat(tree.camera.layoutParams.width).isEqualTo(80)
        val leftLp = tree.left.layoutParams as RelativeLayout.LayoutParams
        assertThat(leftLp.width).isEqualTo(android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(leftLp.rules[RelativeLayout.END_OF]).isEqualTo(tree.privacyHost.id)
        assertThat(leftLp.rules[RelativeLayout.START_OF]).isEqualTo(tree.camera.id)
        assertThat(tree.left.left).isEqualTo(83)
        assertThat(tree.left.right).isEqualTo(500)
        assertThat(tree.left.width).isEqualTo(417)
        assertThat(tree.end.left).isEqualTo(580)
        assertThat(tree.end.right).isEqualTo(1080)
    }

    @Test
    fun apply_center_relayoutNeverPublishesNegativeOrZeroNotificationPartition() {
        val tree = tree()
        tree.privacyHost.layoutParams = RelativeLayout.LayoutParams(83, 80)

        StatusBarCutoutLayout.apply(
            tree.contents,
            StatusBarCutoutMode.CENTER,
            Rect(479, 0, 601, 80),
        )
        layout(tree.contents, 1080, 80)
        assertThat(tree.left.left).isEqualTo(83)
        assertThat(tree.left.right).isEqualTo(479)
        assertThat(tree.left.width).isGreaterThan(0)

        StatusBarCutoutLayout.apply(tree.contents, StatusBarCutoutMode.NONE, null)
        layout(tree.contents, 1080, 80)
        assertThat(tree.left.left).isEqualTo(0)
        assertThat(tree.left.right).isEqualTo((1080 - 83) / 2)
        assertThat(tree.left.width).isGreaterThan(0)

        StatusBarCutoutLayout.apply(
            tree.contents,
            StatusBarCutoutMode.CENTER,
            Rect(487, 0, 593, 80),
        )
        layout(tree.contents, 1080, 80)
        assertThat(tree.left.left).isEqualTo(83)
        assertThat(tree.left.right).isEqualTo(487)
        assertThat(tree.left.width).isGreaterThan(0)
    }

    @Test
    fun apply_leftCutout_partitionsNotificationsAndSystemIconsAtStableCenterBoundary() {
        val tree = tree()
        StatusBarCutoutLayout.apply(
            tree.contents,
            StatusBarCutoutMode.LEFT,
            Rect(0, 0, 100, 80),
        )

        val clockLp = tree.privacyHost.layoutParams as RelativeLayout.LayoutParams
        val leftLp = tree.left.layoutParams as RelativeLayout.LayoutParams
        val endLp = tree.end.layoutParams as RelativeLayout.LayoutParams
        assertThat(clockLp.rules[RelativeLayout.END_OF]).isEqualTo(tree.camera.id)
        assertThat(leftLp.width).isEqualTo(android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(leftLp.rules[RelativeLayout.END_OF]).isEqualTo(tree.privacyHost.id)
        assertThat(leftLp.rules[RelativeLayout.START_OF]).isEqualTo(tree.centerBoundary.id)
        assertThat(endLp.rules[RelativeLayout.END_OF]).isEqualTo(tree.centerBoundary.id)
    }

    @Test
    fun apply_rightCutout_keepsSystemIconsBeforePhysicalCutout() {
        val tree = tree()
        StatusBarCutoutLayout.apply(
            tree.contents,
            StatusBarCutoutMode.RIGHT,
            Rect(980, 0, 1080, 80),
        )

        val leftLp = tree.left.layoutParams as RelativeLayout.LayoutParams
        val endLp = tree.end.layoutParams as RelativeLayout.LayoutParams
        assertThat(leftLp.width).isEqualTo(android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(leftLp.rules[RelativeLayout.END_OF]).isEqualTo(tree.privacyHost.id)
        assertThat(leftLp.rules[RelativeLayout.START_OF]).isEqualTo(tree.centerBoundary.id)
        assertThat(endLp.rules[RelativeLayout.START_OF]).isEqualTo(tree.camera.id)
        assertThat(endLp.rules[RelativeLayout.END_OF]).isEqualTo(tree.centerBoundary.id)
    }

    @Test
    fun apply_rebuildsRulesWithoutDroppingAnyPhysicalOrRelativeMargin() {
        val tree = tree()
        tree.privacyHost.layoutParams =
            RelativeLayout.LayoutParams(120, 40).apply {
                leftMargin = 3
                topMargin = 5
                rightMargin = 7
                bottomMargin = 11
                marginStart = 13
                marginEnd = 17
            }

        StatusBarCutoutLayout.apply(tree.contents, StatusBarCutoutMode.NONE, null)

        val lp = tree.privacyHost.layoutParams as RelativeLayout.LayoutParams
        assertThat(lp.leftMargin).isEqualTo(3)
        assertThat(lp.topMargin).isEqualTo(5)
        assertThat(lp.rightMargin).isEqualTo(7)
        assertThat(lp.bottomMargin).isEqualTo(11)
        assertThat(lp.marginStart).isEqualTo(13)
        assertThat(lp.marginEnd).isEqualTo(17)
        assertThat(lp.height).isEqualTo(40)
    }

    @Test
    fun apply_panelUsesPhysicalCutoutRectInsteadOfSyntheticEdgeWidth() {
        val contents = RelativeLayout(context)
        val camera = View(context).apply { id = R.id.shade_panel_display_cutout_space }
        val left = LinearLayout(context).apply { id = R.id.status_bar_contents_left }
        val end = FrameLayout(context).apply { id = R.id.status_bar_end_side_content }
        contents.addView(camera)
        contents.addView(left)
        contents.addView(end)

        StatusBarCutoutLayout.apply(
            contents,
            StatusBarCutoutMode.CENTER,
            Rect(487, 0, 593, 80),
        )

        val cameraLp = camera.layoutParams as RelativeLayout.LayoutParams
        val leftLp = left.layoutParams as RelativeLayout.LayoutParams
        val endLp = end.layoutParams as RelativeLayout.LayoutParams
        assertThat(cameraLp.width).isEqualTo(106)
        assertThat(cameraLp.marginStart).isEqualTo(487)
        assertThat(leftLp.rules[RelativeLayout.START_OF]).isEqualTo(camera.id)
        assertThat(endLp.rules[RelativeLayout.END_OF]).isEqualTo(camera.id)
    }

    @Test
    fun apply_panelLeftCutout_placesBothRegionsAfterPhysicalCutout() {
        val contents = RelativeLayout(context)
        val camera = View(context).apply { id = R.id.shade_panel_display_cutout_space }
        val left = LinearLayout(context).apply { id = R.id.status_bar_contents_left }
        val end = FrameLayout(context).apply { id = R.id.status_bar_end_side_content }
        contents.addView(camera)
        contents.addView(left)
        contents.addView(end)

        StatusBarCutoutLayout.apply(
            contents,
            StatusBarCutoutMode.LEFT,
            Rect(0, 0, 100, 80),
        )

        val leftLp = left.layoutParams as RelativeLayout.LayoutParams
        val endLp = end.layoutParams as RelativeLayout.LayoutParams
        assertThat(leftLp.rules[RelativeLayout.END_OF]).isEqualTo(camera.id)
        assertThat(endLp.rules[RelativeLayout.END_OF]).isEqualTo(camera.id)
    }

    @Test
    fun apply_panelRightCutout_endsSystemRegionBeforePhysicalCutout() {
        val contents = RelativeLayout(context)
        val camera = View(context).apply { id = R.id.shade_panel_display_cutout_space }
        val left = LinearLayout(context).apply { id = R.id.status_bar_contents_left }
        val end = FrameLayout(context).apply { id = R.id.status_bar_end_side_content }
        contents.addView(camera)
        contents.addView(left)
        contents.addView(end)

        StatusBarCutoutLayout.apply(
            contents,
            StatusBarCutoutMode.RIGHT,
            Rect(980, 0, 1080, 80),
        )

        val leftLp = left.layoutParams as RelativeLayout.LayoutParams
        val endLp = end.layoutParams as RelativeLayout.LayoutParams
        assertThat(leftLp.rules[RelativeLayout.ALIGN_PARENT_START]).isEqualTo(RelativeLayout.TRUE)
        assertThat(endLp.rules[RelativeLayout.START_OF]).isEqualTo(camera.id)
        assertThat(endLp.rules[RelativeLayout.ALIGN_PARENT_START])
            .isEqualTo(RelativeLayout.TRUE)
    }

    private fun tree(): Tree {
        val contents = StatusBarContentsLayout(context)
        val camera =
            View(context).apply {
                id = R.id.camera_area
                visibility = View.GONE
            }
        val clock = Clock(context, null).apply { id = R.id.clock }
        val privacyHost =
            FrameLayout(context).apply {
                id = R.id.privacy_highlight
                addView(clock)
            }
        val left = LinearLayout(context).apply { id = R.id.status_bar_contents_left }
        val end = LinearLayout(context).apply { id = R.id.status_bar_end_side_content }
        val centerBoundary = View(context).apply { id = R.id.status_bar_center_boundary }
        contents.addView(camera)
        contents.addView(centerBoundary)
        contents.addView(privacyHost)
        contents.addView(left)
        contents.addView(end)
        return Tree(contents, camera, centerBoundary, privacyHost, clock, left, end)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private data class Tree(
        val contents: StatusBarContentsLayout,
        val camera: View,
        val centerBoundary: View,
        val privacyHost: FrameLayout,
        val clock: Clock,
        val left: LinearLayout,
        val end: LinearLayout,
    )
}
