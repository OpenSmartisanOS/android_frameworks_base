/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSFragmentLegacy
import com.android.systemui.res.R
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationsQuickSettingsContainerTest : SysuiTestCase() {

    @Mock private lateinit var qsFrame: View
    @Mock private lateinit var stackScroller: View
    @Mock private lateinit var keyguardStatusBar: View
    @Mock private lateinit var qsFragment: QSFragmentLegacy

    private lateinit var qsView: ViewGroup
    private lateinit var qsContainer: View

    private lateinit var underTest: NotificationsQuickSettingsContainer

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = NotificationsQuickSettingsContainer(context, null)

        setUpViews()
        underTest.onFinishInflate()
        underTest.onFragmentViewCreated("QS", qsFragment)
    }

    @Test
    fun qsContainerPaddingSetAgainAfterQsRecreated() {
        val padding = 100
        underTest.setQSContainerPaddingBottom(padding)

        assertThat(qsContainer.paddingBottom).isEqualTo(padding)

        // We reset the padding before "creating" a new QSFragment
        qsContainer.setPadding(0, 0, 0, 0)
        underTest.onFragmentViewCreated("QS", qsFragment)

        assertThat(qsContainer.paddingBottom).isEqualTo(padding)
    }

    @Test
    fun canonicalShade_qsFrameUsesPhysicalPanelTranslationY() {

        underTest.setPanelExpansion(
            /* expandedHeight= */ 800f,
            /* maxPanelHeight= */ 1000f,
            /* shadeContentAllowed= */ true,
        )

        verify(qsFrame).setTranslationY(-200f)
    }

    @Test
    fun pageSwitch_restoresOriginalCloseHandleAndGripSurface() {
        val pageSwitch =
            LayoutInflater.from(context)
                .inflate(R.layout.shade_page_switch, FrameLayout(context), false)
                as ShadePageSwitch

        assertThat(pageSwitch.switchHeight)
            .isEqualTo(context.resources.getDimensionPixelSize(
                R.dimen.shade_page_switch_container_height))
        val handle = pageSwitch.requireViewById<View>(R.id.shade_page_switch_container)
        assertThat(handle.background).isNotNull()
        assertThat(handle.layoutParams.height - handle.paddingBottom)
            .isEqualTo(pageSwitch.switchHeight)
    }

    @Test
    fun shadeMotion_originalThresholdsAndPhysicalVerticalDrag() {
        val target = FakeMotionTarget()
        val controller = ShadeMotionController(target, 2.5f)
        assertThat(controller.directionLockThreshold).isEqualTo(70f)
        assertThat(controller.pageSwitchThreshold).isEqualTo(150f)

        sendMotion(controller, MotionEvent.ACTION_DOWN, 200f, 0f, 0L)
        sendMotion(controller, MotionEvent.ACTION_MOVE, 200f, 69f, 100L)
        assertThat(target.trackingStarts).isEqualTo(0)
        sendMotion(controller, MotionEvent.ACTION_MOVE, 200f, 100f, 200L)
        assertThat(target.trackingStarts).isEqualTo(1)
        assertThat(target.height).isEqualTo(100f)
        assertThat(controller.state).isEqualTo(ShadeMotionController.State.VERTICAL_TRACKING)
        controller.destroy()
    }

    @Test
    fun shadeMotion_fullyExpandedUpwardDragSettlesCollapsed() {
        val target = FakeMotionTarget(height = 2000f)
        val controller = ShadeMotionController(target, 2.5f)

        sendMotion(controller, MotionEvent.ACTION_DOWN, 540f, 1900f, 0L, mayExpand = false)
        sendMotion(controller, MotionEvent.ACTION_MOVE, 540f, 1500f, 100L, mayExpand = false)

        assertThat(controller.state).isEqualTo(ShadeMotionController.State.VERTICAL_TRACKING)
        assertThat(target.height).isEqualTo(1600f)

        sendMotion(controller, MotionEvent.ACTION_UP, 540f, 1100f, 200L, mayExpand = false)

        assertThat(target.lastFalsingExpanding).isFalse()
        assertThat(target.lastSettleExpanded).isFalse()
        controller.destroy()
    }

    @Test
    fun shadeMotion_rejectedUpwardCollapseSafelyRestoresExpandedShade() {
        val target = FakeMotionTarget(height = 2000f).apply { rejectTouch = true }
        val controller = ShadeMotionController(target, 2.5f)

        sendMotion(controller, MotionEvent.ACTION_DOWN, 540f, 1900f, 0L, mayExpand = false)
        sendMotion(controller, MotionEvent.ACTION_MOVE, 540f, 1500f, 100L, mayExpand = false)
        sendMotion(controller, MotionEvent.ACTION_UP, 540f, 1100f, 200L, mayExpand = false)

        assertThat(target.lastFalsingExpanding).isFalse()
        assertThat(target.lastSettleExpanded).isTrue()
        controller.destroy()
    }

    @Test
    fun shadeMotion_horizontalSwitchRunsOnlyOnRelease() {
        val target = FakeMotionTarget(height = 2000f, quickSettings = true)
        val controller = ShadeMotionController(target, 2.5f)
        sendMotion(controller, MotionEvent.ACTION_DOWN, 300f, 400f, 0L)
        sendMotion(controller, MotionEvent.ACTION_MOVE, 500f, 410f, 100L)
        assertThat(target.pageChanges).isEqualTo(0)
        sendMotion(controller, MotionEvent.ACTION_UP, 500f, 410f, 200L)
        assertThat(target.quickSettings).isFalse()
        assertThat(target.pageChanges).isEqualTo(1)
        controller.destroy()
    }

    @Test
    fun shadeMotion_horizontalSwitchDoesNotStealNotificationRowSwipe() {
        val target =
            FakeMotionTarget(height = 2000f, quickSettings = false).apply {
                allowHorizontalPageSwitch = false
            }
        val controller = ShadeMotionController(target, 2.5f)

        sendMotion(controller, MotionEvent.ACTION_DOWN, 300f, 400f, 0L)
        sendMotion(controller, MotionEvent.ACTION_MOVE, 600f, 410f, 100L)
        sendMotion(controller, MotionEvent.ACTION_UP, 600f, 410f, 200L)

        assertThat(controller.state).isEqualTo(ShadeMotionController.State.IDLE)
        assertThat(target.pageChanges).isEqualTo(0)
        controller.destroy()
    }

    @Test
    fun shadeMotion_cancelRestoresOriginAndNeverSwitchesPage() {
        val verticalTarget = FakeMotionTarget()
        val verticalController = ShadeMotionController(verticalTarget, 2.5f)
        sendMotion(verticalController, MotionEvent.ACTION_DOWN, 200f, 0f, 0L)
        sendMotion(verticalController, MotionEvent.ACTION_MOVE, 200f, 900f, 100L)
        sendMotion(verticalController, MotionEvent.ACTION_CANCEL, 200f, 900f, 200L)
        assertThat(verticalTarget.lastSettleExpanded).isFalse()

        val horizontalTarget = FakeMotionTarget(height = 2000f, quickSettings = true)
        val horizontalController = ShadeMotionController(horizontalTarget, 2.5f)
        sendMotion(horizontalController, MotionEvent.ACTION_DOWN, 300f, 400f, 0L)
        sendMotion(horizontalController, MotionEvent.ACTION_MOVE, 500f, 410f, 100L)
        sendMotion(horizontalController, MotionEvent.ACTION_CANCEL, 500f, 410f, 200L)
        assertThat(horizontalTarget.pageChanges).isEqualTo(0)
        assertThat(horizontalTarget.quickSettings).isTrue()
        verticalController.destroy()
        horizontalController.destroy()
    }

    @Test
    fun shadeMotion_secondPointerBeforeDirectionLockStillStartsVerticalTracking() {
        val target = FakeMotionTarget()
        val controller = ShadeMotionController(target, 2.5f)

        sendInterceptMotion(controller, MotionEvent.ACTION_DOWN, floatArrayOf(200f), 0f, 0L)
        sendInterceptMotion(
            controller,
            MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            floatArrayOf(200f, 400f),
            0f,
            20L,
        )
        sendInterceptMotion(
            controller,
            MotionEvent.ACTION_MOVE,
            floatArrayOf(200f, 400f),
            100f,
            100L,
        )

        assertThat(controller.state).isEqualTo(ShadeMotionController.State.VERTICAL_TRACKING)
        assertThat(target.trackingStarts).isEqualTo(1)
        assertThat(target.lastTrackingPointerCount).isEqualTo(2)
        controller.destroy()
    }

    private fun sendMotion(
        controller: ShadeMotionController,
        action: Int,
        x: Float,
        y: Float,
        time: Long,
        mayExpand: Boolean = true,
        mayCollapse: Boolean = true,
    ) {
        val event = MotionEvent.obtain(0L, time, action, x, y, 0)
        try {
            controller.onTouchEvent(event, mayExpand, mayCollapse)
        } finally {
            event.recycle()
        }
    }

    private fun sendInterceptMotion(
        controller: ShadeMotionController,
        action: Int,
        xs: FloatArray,
        y: Float,
        time: Long,
    ) {
        val properties =
            Array(xs.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coordinates =
            Array(xs.size) { index ->
                MotionEvent.PointerCoords().apply {
                    this.x = xs[index]
                    this.y = y
                    pressure = 1f
                    size = 1f
                }
            }
        val event =
            MotionEvent.obtain(
                0L,
                time,
                action,
                xs.size,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                0,
                0,
            )
        try {
            controller.onInterceptTouchEvent(event, true, true)
        } finally {
            event.recycle()
        }
    }

    private class FakeMotionTarget(
        var height: Float = 0f,
        var quickSettings: Boolean = false,
    ) : ShadeMotionController.Target {
        var trackingStarts = 0
        var lastTrackingPointerCount = 0
        var pageChanges = 0
        var lastSettleExpanded: Boolean? = null
        var lastFalsingExpanding: Boolean? = null
        var rejectTouch = false
        var allowHorizontalPageSwitch = true

        override fun getExpandedHeight() = height
        override fun getMaxExpandedHeight() = 2000f
        override fun getPanelWidth() = 1080f
        override fun getMinFlingVelocity() = 1000f
        override fun isFullyCollapsed() = height == 0f
        override fun isFullyExpanded() = height == 2000f
        override fun isQuickSettingsPage() = quickSettings
        override fun canStartHorizontalPageSwitch(initialX: Float, initialY: Float) =
            allowHorizontalPageSwitch
        override fun isFalseTouch(x: Float, y: Float, expanding: Boolean): Boolean {
            lastFalsingExpanding = expanding
            return rejectTouch
        }
        override fun onVerticalTrackingStarted(pointerCount: Int) {
            trackingStarts++
            lastTrackingPointerCount = pointerCount
        }
        override fun setExpandedHeight(height: Float) { this.height = height }
        override fun settleExpandedHeight(expand: Boolean, velocity: Float) {
            lastSettleExpanded = expand
        }
        override fun setQuickSettingsPageFromGesture(
            quickSettings: Boolean,
            animate: Boolean,
        ) {
            this.quickSettings = quickSettings
            pageChanges++
        }
        override fun cancelHeightAnimation() = Unit
    }

    private fun setUpViews() {
        qsView = FrameLayout(context)
        qsContainer = View(context)
        qsContainer.id = R.id.quick_settings_container
        qsView.addView(qsContainer)

        whenever(qsFrame.findViewById<View>(R.id.qs_frame)).thenReturn(qsFrame)
        whenever(stackScroller.findViewById<View>(R.id.notification_stack_scroller))
            .thenReturn(stackScroller)
        whenever(keyguardStatusBar.findViewById<View>(R.id.keyguard_header))
            .thenReturn(keyguardStatusBar)
        whenever(qsFragment.view).thenReturn(qsView)

        val layoutParams = ConstraintLayout.LayoutParams(0, 0)
        whenever(qsFrame.layoutParams).thenReturn(layoutParams)
        whenever(stackScroller.layoutParams).thenReturn(layoutParams)
        whenever(keyguardStatusBar.layoutParams).thenReturn(layoutParams)

        underTest.addView(qsFrame)
        underTest.addView(stackScroller)
        underTest.addView(keyguardStatusBar)
    }
}
