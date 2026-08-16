/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.util.Log
import com.android.systemui.classifier.Classifier
import com.android.systemui.keyguard.SosKeyguardRuntime
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import kotlin.math.abs

/** The outer shade-window contract used to give R2 first ownership of an upward unlock swipe. */
interface SosKeyguardUnlockGestureTarget {
    fun canStartUnlockGesture(x: Float, y: Float): Boolean
    fun prepareUnlockGestureCandidate()
    fun beginUnlockGesture(downY: Float)
    fun updateUnlockGesture(currentY: Float)
    fun endUnlockGesture(velocityY: Float, rejected: Boolean)
    fun cancelUnlockGesture()
}

/**
 * Observes DOWN without intercepting, then claims only an unambiguous single-finger upward swipe.
 * Horizontal gestures and downward shade/QS gestures remain in their existing owners.
 */
class SosKeyguardUnlockGestureRouter(
    private val root: View,
    private val falsingManager: FalsingManager,
) {
    private val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var target: SosKeyguardUnlockGestureTarget? = null
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var candidate = false
    private var intercepting = false
    private var beginPending = false

    fun shouldIntercept(event: MotionEvent): Boolean {
        if (!SosKeyguardRuntime.isEnabled(root.context)) {
            reset(recycleTracker = true)
            return false
        }
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset(recycleTracker = true)
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                target = findTarget()
                candidate = target?.canStartUnlockGesture(downX, downY) == true
                if (candidate) target?.prepareUnlockGestureCandidate()
                Log.d(TAG, "down candidate=$candidate target=${target != null} x=$downX y=$downY")
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (intercepting) return true
                candidate = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (intercepting) return true
                if (!candidate || event.pointerCount != 1) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    candidate = false
                    return false
                }
                if (dy > touchSlop) {
                    candidate = false
                    return false
                }
                if (dy < -touchSlop && abs(dy) > abs(dx)) {
                    intercepting = true
                    beginPending = true
                    Log.d(TAG, "claim dx=$dx dy=$dy slop=$touchSlop")
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> reset(recycleTracker = true)
        }
        return false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepting) return false
        tracker?.addMovement(event)
        val activeTarget = target ?: run {
            reset(recycleTracker = true)
            return false
        }
        if (beginPending) {
            beginPending = false
            Log.d(TAG, "begin downY=$downY")
            activeTarget.beginUnlockGesture(downY)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                activeTarget.cancelUnlockGesture()
                reset(recycleTracker = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) {
                    activeTarget.cancelUnlockGesture()
                    reset(recycleTracker = true)
                } else {
                    activeTarget.updateUnlockGesture(event.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.computeCurrentVelocity(1000)
                val rejected =
                    falsingManager.isUnlockingDisabled ||
                        falsingManager.isFalseTouch(Classifier.BOUNCER_UNLOCK)
                activeTarget.endUnlockGesture(tracker?.yVelocity ?: 0f, rejected)
                reset(recycleTracker = true)
            }
            MotionEvent.ACTION_CANCEL -> {
                activeTarget.cancelUnlockGesture()
                reset(recycleTracker = true)
            }
        }
        return true
    }

    fun cancel() {
        if (intercepting) target?.cancelUnlockGesture()
        reset(recycleTracker = true)
    }

    private fun findTarget(): SosKeyguardUnlockGestureTarget? =
        root.findViewById<View?>(R.id.sos_keyguard_host_view) as? SosKeyguardUnlockGestureTarget

    private fun reset(recycleTracker: Boolean) {
        if (recycleTracker) tracker?.recycle()
        tracker = null
        target = null
        candidate = false
        intercepting = false
        beginPending = false
    }

    private companion object {
        const val TAG = "SosUnlockRouter"
    }
}
