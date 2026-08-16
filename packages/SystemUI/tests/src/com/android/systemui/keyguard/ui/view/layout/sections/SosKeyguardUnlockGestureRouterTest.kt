/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SosKeyguardUnlockGestureRouterTest : SysuiTestCase() {
    private lateinit var root: FrameLayout
    private lateinit var target: RecordingTarget
    private lateinit var falsingManager: FalsingManager
    private lateinit var router: SosKeyguardUnlockGestureRouter

    @Before
    fun setUp() {
        root = FrameLayout(context)
        target = RecordingTarget(context).apply { id = R.id.sos_keyguard_host_view }
        root.addView(target)
        falsingManager = mock()
        router = SosKeyguardUnlockGestureRouter(root, falsingManager)
    }

    @Test
    fun downDoesNotIntercept_upwardMoveClaimsAndEndsTarget() {
        assertThat(router.shouldIntercept(event(MotionEvent.ACTION_DOWN, 500f, 200f))).isFalse()
        val move = event(MotionEvent.ACTION_MOVE, 430f, 202f)
        assertThat(router.shouldIntercept(move)).isTrue()
        assertThat(router.onTouchEvent(move)).isTrue()
        router.onTouchEvent(event(MotionEvent.ACTION_UP, 260f, 202f))

        assertThat(target.beginCount).isEqualTo(1)
        assertThat(target.lastY).isEqualTo(430f)
        assertThat(target.endCount).isEqualTo(1)
        assertThat(target.lastRejected).isFalse()
    }

    @Test
    fun horizontalMoveRemainsWithTPageOwner() {
        router.shouldIntercept(event(MotionEvent.ACTION_DOWN, 500f, 200f))
        assertThat(router.shouldIntercept(event(MotionEvent.ACTION_MOVE, 495f, 280f))).isFalse()
        assertThat(target.beginCount).isEqualTo(0)
    }

    @Test
    fun falsingRejectsClaimedUnlock() {
        whenever(falsingManager.isFalseTouch(com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK))
            .thenReturn(true)
        router.shouldIntercept(event(MotionEvent.ACTION_DOWN, 500f, 200f))
        val move = event(MotionEvent.ACTION_MOVE, 400f, 200f)
        router.shouldIntercept(move)
        router.onTouchEvent(move)
        router.onTouchEvent(event(MotionEvent.ACTION_UP, 300f, 200f))

        assertThat(target.lastRejected).isTrue()
    }

    @Test
    fun secondPointerAfterClaimCancelsWithoutCompletingUnlock() {
        router.shouldIntercept(event(MotionEvent.ACTION_DOWN, 500f, 200f))
        val move = event(MotionEvent.ACTION_MOVE, 400f, 200f)
        assertThat(router.shouldIntercept(move)).isTrue()
        router.onTouchEvent(move)

        router.onTouchEvent(twoPointerDownEvent())
        router.onTouchEvent(event(MotionEvent.ACTION_UP, 300f, 200f))

        assertThat(target.cancelCount).isEqualTo(1)
        assertThat(target.endCount).isEqualTo(0)
    }

    private fun event(action: Int, y: Float, x: Float): MotionEvent =
        MotionEvent.obtain(0L, if (action == MotionEvent.ACTION_DOWN) 0L else 16L, action, x, y, 0)

    private fun twoPointerDownEvent(): MotionEvent {
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0 },
                MotionEvent.PointerProperties().apply { id = 1 },
            )
        val coordinates =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 200f
                    y = 400f
                },
                MotionEvent.PointerCoords().apply {
                    x = 260f
                    y = 420f
                },
            )
        return MotionEvent.obtain(
            0L,
            24L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
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
    }

    private class RecordingTarget(context: Context) : View(context), SosKeyguardUnlockGestureTarget {
        var beginCount = 0
        var endCount = 0
        var lastY = 0f
        var lastRejected = false
        var cancelCount = 0

        override fun canStartUnlockGesture(x: Float, y: Float) = true

        override fun prepareUnlockGestureCandidate() = Unit

        override fun beginUnlockGesture(downY: Float) {
            beginCount++
        }

        override fun updateUnlockGesture(currentY: Float) {
            lastY = currentY
        }

        override fun endUnlockGesture(velocityY: Float, rejected: Boolean) {
            endCount++
            lastRejected = rejected
        }

        override fun cancelUnlockGesture() {
            cancelCount++
        }
    }
}
