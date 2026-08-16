/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.keyguard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockPatternView.Cell
import com.android.internal.widget.LockPatternView.DisplayMode
import com.android.internal.widget.LockPatternView.InputMode
import com.android.systemui.keyguard.SosKeyguardRuntime
import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardHostView
import com.android.systemui.res.R

/** Original R2 bitmap renderer on top of the Android 16 LockPatternView input contract. */
class SosLockPatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LockPatternView(context, attrs) {
    private val pathPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ORIGINAL_PATH_COLOR
            alpha = ORIGINAL_PATH_ALPHA
            isDither = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val currentPath = Path()
    private val correctNode: Drawable =
        context.getDrawable(R.drawable.keyguard_lock_pattern_view_circle_right)!!
    private val wrongNode: Drawable =
        context.getDrawable(R.drawable.keyguard_lock_pattern_view_circle_wrong)!!
    private val trackedPattern = ArrayList<Cell>()
    private var trackedDisplayMode = DisplayMode.Correct
    private var pointerX = -1f
    private var pointerY = -1f

    init {
        setBackgroundResource(R.drawable.keyguard_lock_pattern_view_background)
        // Android's renderer is suppressed below, but its input contract still uses this value.
        setPathWidth(ORIGINAL_PATH_WIDTH_PX.toInt())
    }

    override fun setOnPatternListener(listener: OnPatternListener?) {
        if (listener == null) {
            super.setOnPatternListener(null)
            return
        }
        super.setOnPatternListener(
            object : OnPatternListener {
                override fun onPatternStart(inputMode: InputMode) {
                    trackedPattern.clear()
                    listener.onPatternStart(inputMode)
                    invalidate()
                }

                override fun onPatternCleared() {
                    trackedPattern.clear()
                    listener.onPatternCleared()
                    invalidate()
                }

                override fun onPatternCellAdded(
                    pattern: MutableList<Cell>,
                    inputMode: InputMode,
                ) {
                    trackedPattern.replaceWith(pattern)
                    listener.onPatternCellAdded(pattern, inputMode)
                    invalidate()
                }

                override fun onPatternDetected(
                    pattern: MutableList<Cell>,
                    inputMode: InputMode,
                    patternSize: Byte,
                ) {
                    trackedPattern.replaceWith(pattern)
                    listener.onPatternDetected(pattern, inputMode, patternSize)
                    invalidate()
                }
            }
        )
    }

    override fun setPattern(displayMode: DisplayMode, pattern: MutableList<Cell>) {
        trackedPattern.replaceWith(pattern)
        trackedDisplayMode = displayMode
        super.setPattern(displayMode, pattern)
    }

    override fun setDisplayMode(displayMode: DisplayMode) {
        trackedDisplayMode = displayMode
        super.setDisplayMode(displayMode)
    }

    override fun clearPattern() {
        trackedPattern.clear()
        pointerX = -1f
        pointerY = -1f
        super.clearPattern()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                pointerX = event.x
                pointerY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerX = -1f
                pointerY = -1f
            }
        }
        invalidate()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        if (trackedPattern.isEmpty()) return
        // R2 hides an in-progress pattern in stealth mode, but always exposes the red error path.
        if (isInStealthMode && trackedDisplayMode != DisplayMode.Wrong) return
        val scale = width / 1080f
        pathPaint.strokeWidth = ORIGINAL_PATH_WIDTH_PX * scale
        pathPaint.color =
            if (trackedDisplayMode == DisplayMode.Wrong) ORIGINAL_WRONG_PATH_COLOR
            else ORIGINAL_PATH_COLOR
        pathPaint.alpha = ORIGINAL_PATH_ALPHA

        currentPath.rewind()
        trackedPattern.forEachIndexed { index, cell ->
            val x = pathCenter(cell.column, scale)
            val y = pathCenter(cell.row, scale)
            if (index == 0) currentPath.moveTo(x, y) else currentPath.lineTo(x, y)
        }
        if (pointerX >= 0f && pointerY >= 0f) {
            currentPath.lineTo(pointerX, pointerY)
        }
        canvas.drawPath(currentPath, pathPaint)

        val node = if (trackedDisplayMode == DisplayMode.Wrong) wrongNode else correctNode
        val nodeWidth = (node.intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val nodeHeight = (node.intrinsicHeight * scale).toInt().coerceAtLeast(1)
        trackedPattern.forEach { cell ->
            // This is the exact top-left formula in R2 KeyguardLockPatternView.drawCircle():
            // offset + grid * index + gridRadius - circleRadius.
            val left = nodeLeft(cell.column, scale)
            val top = nodeLeft(cell.row, scale)
            node.setBounds(
                left,
                top,
                left + nodeWidth,
                top + nodeHeight,
            )
            node.draw(canvas)
        }
    }

    private fun pathCenter(index: Int, scale: Float): Float =
        (ORIGINAL_NODE_OFFSET_PX +
            ORIGINAL_GRID_SIZE_PX * index +
            ORIGINAL_GRID_SIZE_PX / 2f +
            ORIGINAL_PATH_OFFSET_PX) * scale

    private fun nodeLeft(index: Int, scale: Float): Int =
        ((ORIGINAL_NODE_OFFSET_PX +
            ORIGINAL_GRID_SIZE_PX * index +
            ORIGINAL_GRID_SIZE_PX / 2f -
            ORIGINAL_CIRCLE_SIZE_PX / 2f) * scale).toInt()

    private fun ArrayList<Cell>.replaceWith(pattern: List<Cell>?) {
        clear()
        if (pattern != null) addAll(pattern)
    }

    private companion object {
        const val ORIGINAL_GRID_SIZE_PX = 292f
        const val ORIGINAL_CIRCLE_SIZE_PX = 60f
        const val ORIGINAL_NODE_OFFSET_PX = 15f
        const val ORIGINAL_PATH_OFFSET_PX = 88.5f
        const val ORIGINAL_PATH_WIDTH_PX = 23f
        const val ORIGINAL_PATH_ALPHA = 128
        val ORIGINAL_PATH_COLOR = 0x99FFFFFF.toInt()
        val ORIGINAL_WRONG_PATH_COLOR = 0xFFAE0400.toInt()
    }
}

class SosKeyguardPatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KeyguardPatternView(context, attrs) {
    override fun setIsLockScreenLandscapeEnabled(isLockScreenLandscapeEnabled: Boolean) {
        super.setIsLockScreenLandscapeEnabled(false)
    }

    override fun startAppearAnimation() {
        // The host swipe already drives the ordinary R2 security-page entrance.
        alpha = 1f
        translationY = 0f
    }

    override fun startDisappearAnimation(
        needsSlowUnlockTransition: Boolean,
        finishRunnable: Runnable?,
    ): Boolean {
        if (SosKeyguardRuntime.isEnabled(context)) {
            val host =
                rootView.findViewById<View?>(R.id.sos_keyguard_host_view)
                    as? SosKeyguardHostView
            if (host?.startCredentialDismissAnimation(this, finishRunnable) == true) {
                return true
            }
        }
        finishRunnable?.run()
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // The shared R2 scaler owns orientation/posture geometry.
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        getChildAt(0)?.let { SosCredentialLayoutScaler.apply(this, it, w, h) }
    }
}
