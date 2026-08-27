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

import android.content.Context
import android.content.ContextWrapper
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class StatusBarModeCoordinatorTest : SysuiTestCase() {
    private val coordinator = StatusBarModeCoordinator()

    @Test
    fun panelExit_restoresOnlyWrapper_preservesAndroidOwnedSystemInfoVisibility() {
        val tree = tree()
        coordinator.registerHomeHost(tree.root)
        tree.clockChild.visibility = View.GONE
        tree.systemInfo.visibility = View.GONE

        coordinator.setPanelExpanded(true, animate = false)
        assertThat(tree.clockHost.visibility).isEqualTo(View.INVISIBLE)
        assertThat(tree.notificationHost.visibility).isEqualTo(View.INVISIBLE)
        assertThat(tree.endSideHost.visibility).isEqualTo(View.INVISIBLE)
        assertThat(tree.normalContents.visibility).isEqualTo(View.INVISIBLE)

        coordinator.setPanelExpanded(false, animate = false)

        assertThat(tree.clockHost.visibility).isEqualTo(View.VISIBLE)
        assertThat(tree.notificationHost.visibility).isEqualTo(View.VISIBLE)
        assertThat(tree.endSideHost.visibility).isEqualTo(View.VISIBLE)
        assertThat(tree.systemInfo.visibility).isEqualTo(View.GONE)
        assertThat(tree.normalContents.visibility).isEqualTo(View.VISIBLE)
        // The coordinator never invents a clock/privacy child state on PANEL exit.
        assertThat(tree.clockChild.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun tickerAndPanel_overlap_doesNotRestoreHomeContentsEarly() {
        val tree = tree()
        coordinator.registerHomeHost(tree.root)

        coordinator.setTickerVisible(true)
        coordinator.setPanelExpanded(true, animate = false)
        coordinator.setPanelExpanded(false, animate = false)

        assertThat(tree.normalContents.visibility).isEqualTo(View.INVISIBLE)

        coordinator.setTickerVisible(false)
        assertThat(tree.normalContents.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun privacyState_isPublishedWithoutChangingClockPrivacyChildren() {
        val tree = tree()
        coordinator.registerHomeHost(tree.root)
        tree.clockChild.visibility = View.INVISIBLE
        val states = mutableListOf<StatusBarModeCoordinator.State>()
        coordinator.addCallback { states += it }

        coordinator.setPrivacyVisible(true)
        coordinator.setPanelExpanded(true, animate = false)
        coordinator.setPanelExpanded(false, animate = false)

        assertThat(states.last().privacyVisible).isTrue()
        assertThat(tree.clockChild.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun privacyDuringCommittedPanel_keepsOnlyPrivacyHostVisible() {
        val tree = tree()
        coordinator.registerHomeHost(tree.root)
        coordinator.setPanelExpanded(true, animate = false)

        coordinator.setPrivacyVisible(true)

        assertThat(tree.normalContents.visibility).isEqualTo(View.VISIBLE)
        assertThat(tree.clockHost.visibility).isEqualTo(View.VISIBLE)
        assertThat(tree.notificationHost.visibility).isEqualTo(View.INVISIBLE)
        assertThat(tree.endSideHost.visibility).isEqualTo(View.INVISIBLE)

        coordinator.setPrivacyVisible(false)

        assertThat(tree.normalContents.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun panelRoundTrip_preservesLightsOutBaseAlpha() {
        val tree = tree()
        coordinator.registerHomeHost(tree.root)
        tree.clockHost.alpha = 0.5f
        tree.notificationHost.alpha = 0f

        coordinator.setPanelExpanded(true, animate = false)
        coordinator.setPanelExpanded(false, animate = false)

        assertThat(tree.clockHost.alpha).isEqualTo(0.5f)
        assertThat(tree.notificationHost.alpha).isEqualTo(0f)
    }

    @Test
    fun lightsOut_isPublishedWithoutOverwritingPlatformOwnedAlpha() {
        val tree = tree()
        coordinator.registerHomeHost(tree.root)
        tree.clockHost.alpha = 0.5f

        coordinator.setLightsOut(true)

        assertThat(coordinator.state.lightsOut).isTrue()
        assertThat(tree.clockHost.alpha).isEqualTo(0.5f)
    }

    @Test
    fun phoneShadeModes_doNotMutateExternalStatusBarHost() {
        val tree = tree(displayId = Display.DEFAULT_DISPLAY + 1)
        tree.normalContents.visibility = View.GONE
        tree.clockHost.translationX = 17f
        coordinator.registerHomeHost(tree.root)

        coordinator.setTickerVisible(true)
        coordinator.setPanelExpanded(true, animate = false)
        coordinator.setPrivacyVisible(true)
        coordinator.setPanelExpanded(false, animate = false)
        coordinator.setTickerVisible(false)

        assertThat(tree.normalContents.visibility).isEqualTo(View.GONE)
        assertThat(tree.clockHost.translationX).isEqualTo(17f)
    }

    private fun tree(displayId: Int = Display.DEFAULT_DISPLAY): Tree {
        val viewContext: Context =
            if (displayId == context.displayId) {
                context
            } else {
                object : ContextWrapper(context) {
                    override fun getDisplayId(): Int = displayId
                }
            }
        val root = FrameLayout(viewContext)
        val normalContents = FrameLayout(viewContext).apply { id = R.id.status_bar_contents }
        val clockHost = FrameLayout(viewContext).apply { id = R.id.privacy_highlight }
        val clockChild = View(viewContext).apply { id = R.id.clock }
        val notificationHost =
            FrameLayout(viewContext).apply { id = R.id.status_bar_contents_left }
        val endSideHost =
            FrameLayout(viewContext).apply { id = R.id.status_bar_end_side_content }
        val systemInfo = View(viewContext).apply { id = R.id.system_icons }
        clockHost.addView(clockChild)
        normalContents.addView(clockHost)
        normalContents.addView(notificationHost)
        endSideHost.addView(systemInfo)
        normalContents.addView(endSideHost)
        root.addView(normalContents)
        return Tree(
            root,
            normalContents,
            clockHost,
            clockChild,
            notificationHost,
            endSideHost,
            systemInfo,
        )
    }

    private data class Tree(
        val root: FrameLayout,
        val normalContents: FrameLayout,
        val clockHost: FrameLayout,
        val clockChild: View,
        val notificationHost: FrameLayout,
        val endSideHost: FrameLayout,
        val systemInfo: View,
    )
}
