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

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.widget.NotificationCountView
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class LeftCarrierNotificationControllerTest : SysuiTestCase() {
    @Test
    fun refresh_notificationCountHidesCarrier() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        val icons = left.requireViewById<View>(R.id.notification_icon_area)
        val count = NotificationCountView(context, null)
        count.id = R.id.notification_count
        left.requireViewById<FrameLayout>(R.id.notification_icon_area).addView(count, 0)
        label.text = "Carrier"
        label.visibility = View.VISIBLE
        val controller = LeftCarrierNotificationController(left)

        count.setCount(5)

        assertThat(icons.visibility).isEqualTo(View.VISIBLE)
        assertThat(label.visibility).isEqualTo(View.GONE)
        controller.destroy()
    }

    @Test
    fun platformPolicy_hidesWholeNotificationPresentationAndRestoresCarrier() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        val area = left.requireViewById<FrameLayout>(R.id.notification_icon_area)
        val count = NotificationCountView(context, null).apply { id = R.id.notification_count }
        area.addView(count, 0)
        label.text = "Carrier"
        val controller = LeftCarrierNotificationController(left)
        count.setCount(5)

        controller.setPlatformNotificationsAllowed(false)

        assertThat(area.visibility).isEqualTo(View.INVISIBLE)
        assertThat(label.visibility).isEqualTo(View.VISIBLE)

        controller.setPlatformNotificationsAllowed(true)

        assertThat(area.visibility).isEqualTo(View.VISIBLE)
        assertThat(label.visibility).isEqualTo(View.GONE)
        controller.destroy()
    }

    @Test
    fun platformPolicy_hidesNotificationPresentationWhenCarrierIsNotAllowed() {
        val left = leftCluster()
        val area = left.requireViewById<View>(R.id.notification_icon_area)
        val controller = LeftCarrierNotificationController(left, allowCarrier = false)

        controller.setPlatformNotificationsAllowed(false)

        assertThat(area.visibility).isEqualTo(View.INVISIBLE)
        controller.destroy()
    }

    @Test
    fun refresh_notificationsHideCarrier() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        val icons = left.requireViewById<View>(R.id.notification_icon_area)
        val iconChild = View(context).apply { visibility = View.VISIBLE }
        left.requireViewById<LinearLayout>(R.id.notificationIcons).addView(iconChild)
        label.text = "Carrier"
        label.visibility = View.VISIBLE

        LeftCarrierNotificationController(left).refresh()

        assertThat(icons.visibility).isEqualTo(View.VISIBLE)
        assertThat(label.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun homeHost_neverShowsCarrierText() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        label.text = "Carrier"
        label.visibility = View.VISIBLE

        LeftCarrierNotificationController(left, allowCarrier = false).refresh()

        assertThat(label.visibility).isEqualTo(View.GONE)
        assertThat(left.requireViewById<View>(R.id.notification_icon_area).visibility)
            .isEqualTo(View.VISIBLE)
    }

    @Test
    fun refresh_carrierShownWhenNoNotifications() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        val icons = left.requireViewById<View>(R.id.notification_icon_area)
        label.text = "Carrier"
        label.visibility = View.VISIBLE

        LeftCarrierNotificationController(left).refresh()

        assertThat(icons.visibility).isEqualTo(View.INVISIBLE)
        assertThat(label.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun refresh_defersWhileAnimationPending() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        label.text = "Carrier"
        val controller = LeftCarrierNotificationController(left)
        controller.animationPending = true
        left.requireViewById<LinearLayout>(R.id.notificationIcons)
            .addView(View(context).apply { visibility = View.VISIBLE })

        controller.refresh()
        assertThat(label.visibility).isNotEqualTo(View.GONE)

        controller.animationPending = false
        assertThat(label.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun keyguardPresented_neverShowsCarrierText() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        label.text = "Carrier"
        val controller = LeftCarrierNotificationController(left)

        controller.setKeyguardPresented(true)

        assertThat(label.visibility).isEqualTo(View.GONE)
        assertThat(left.requireViewById<View>(R.id.notification_icon_area).visibility)
            .isEqualTo(View.INVISIBLE)
    }

    @Test
    fun sidebarIndicator_hidesCarrierLikeFactoryController() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        val sidebar = left.requireViewById<View>(R.id.sidebar_drag)
        label.text = "Carrier"
        sidebar.visibility = View.VISIBLE
        val controller = LeftCarrierNotificationController(left)

        controller.refresh()

        assertThat(label.visibility).isEqualTo(View.GONE)
        assertThat(left.requireViewById<View>(R.id.notification_icon_area).visibility)
            .isEqualTo(View.INVISIBLE)
        controller.destroy()
    }

    @Test
    fun destroy_removesRootLayoutListener() {
        val left = leftCluster()
        val label = left.requireViewById<TextView>(R.id.network_label)
        val sidebar = left.requireViewById<View>(R.id.sidebar_drag)
        label.text = "Carrier"
        val controller = LeftCarrierNotificationController(left)
        assertThat(label.visibility).isEqualTo(View.VISIBLE)

        controller.destroy()
        sidebar.visibility = View.VISIBLE
        left.layout(0, 0, 300, 60)

        // A leaked root listener would refresh here and hide the label after destroy().
        assertThat(label.visibility).isEqualTo(View.VISIBLE)
    }

    private fun leftCluster(): LinearLayout {
        val left = LinearLayout(context)
        val label =
            TextView(context).apply {
                id = R.id.network_label
                visibility = View.GONE
            }
        val notificationArea =
            FrameLayout(context).apply {
                id = R.id.notification_icon_area
                addView(LinearLayout(context).apply { id = R.id.notificationIcons })
            }
        val sidebar =
            View(context).apply {
                id = R.id.sidebar_drag
                visibility = View.GONE
            }
        left.addView(label)
        left.addView(sidebar)
        left.addView(notificationArea)
        return left
    }
}
