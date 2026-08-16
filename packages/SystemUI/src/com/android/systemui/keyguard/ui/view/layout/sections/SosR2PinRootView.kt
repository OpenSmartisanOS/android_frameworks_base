/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Android 16 compatibility implementation of the original R2 PinRootView indicator/selector.
 *
 * The legacy implementation depended on smartisanos.view.ActivityStackView.  Task embedding is
 * owned by [SosKeyguardPinnedTaskController], while this view preserves the original three-state
 * presentation and touch model: compact bottom indicators, the radial drag selector, and the
 * pinned-stack state.  Every coordinate comes from the shared 1080 x 2242 layout transform.
 */
internal class SosR2PinRootView(
    context: Context,
    private val callback: Callback,
) : FrameLayout(context) {
    interface Callback {
        fun onTaskSelected(task: SosKeyguardPinnedTaskController.PinnedTask, colorIndex: Int)
    }

    private enum class State { INDICATOR, SELECTOR, STACK }

    private var state = State.INDICATOR
    private var tasks = emptyList<SosKeyguardPinnedTaskController.PinnedTask>()
    private var metrics: SosKeyguardLayoutModel.Metrics? = null
    private var indicatorViews = emptyList<View>()
    private var iconViews = emptyList<ImageView>()
    private var selectedIndex = -1
    private var downInIndicator = false
    private var downX = 0f
    private var downY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val expandRunnable = Runnable { if (downInIndicator) showSelector() }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setTasks(value: List<SosKeyguardPinnedTaskController.PinnedTask>) {
        val snapshot = value.take(MAX_PINNED_TASKS)
        if (snapshot.map { it.taskId } == tasks.map { it.taskId }) return
        tasks = snapshot
        state = State.INDICATOR
        rebuildChildren()
    }

    fun updateLayout(value: SosKeyguardLayoutModel.Metrics) {
        metrics = value
        layoutChildren(animated = false)
    }

    fun showStack(colorIndex: Int) {
        handler.removeCallbacks(expandRunnable)
        state = State.STACK
        selectedIndex = colorIndex.coerceIn(0, max(0, tasks.lastIndex))
        animate().cancel()
        animate().alpha(0f).setDuration(STACK_FADE_MS).withEndAction {
            visibility = View.INVISIBLE
        }.start()
    }

    fun resetToIndicator() {
        handler.removeCallbacks(expandRunnable)
        state = State.INDICATOR
        selectedIndex = -1
        visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        alpha = 1f
        setBackgroundColor(Color.TRANSPARENT)
        layoutChildren(animated = true)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(expandRunnable)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tasks.isEmpty() || state == State.STACK) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downInIndicator = state == State.SELECTOR || indicatorTouchBounds().contains(downX, downY)
                if (!downInIndicator) return false
                if (state == State.INDICATOR) handler.postDelayed(expandRunnable, SELECTOR_HOLD_MS)
                if (state == State.SELECTOR) updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!downInIndicator) return false
                if (state == State.INDICATOR &&
                    abs(event.x - downX) + abs(event.y - downY) > selectorSlop()
                ) {
                    handler.removeCallbacks(expandRunnable)
                    showSelector()
                }
                if (state == State.SELECTOR) updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(expandRunnable)
                if (!downInIndicator) return false
                if (state == State.INDICATOR) {
                    showSelector()
                } else if (state == State.SELECTOR) {
                    updateSelection(event.x, event.y)
                    val index = selectedIndex
                    if (index in tasks.indices) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        callback.onTaskSelected(tasks[index], index % PIN_COLOR_COUNT)
                    } else {
                        resetToIndicator()
                    }
                }
                downInIndicator = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(expandRunnable)
                downInIndicator = false
                if (state == State.SELECTOR) resetToIndicator()
                return true
            }
        }
        return false
    }

    private fun rebuildChildren() {
        removeAllViews()
        if (tasks.isEmpty()) {
            visibility = View.GONE
            indicatorViews = emptyList()
            iconViews = emptyList()
            return
        }
        visibility = View.VISIBLE
        alpha = 1f
        indicatorViews =
            tasks.mapIndexed { index, _ ->
                View(context).also { point ->
                    point.setBackgroundResource(INDICATOR_DRAWABLES[index % PIN_COLOR_COUNT])
                    addView(point)
                }
            }
        iconViews =
            tasks.map { task ->
                ImageView(context).also { icon ->
                    icon.setImageDrawable(task.icon)
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.contentDescription = task.label
                    icon.alpha = 0f
                    icon.scaleX = 0f
                    icon.scaleY = 0f
                    addView(icon)
                }
            }
        layoutChildren(animated = false)
    }

    private fun showSelector() {
        if (tasks.isEmpty() || state != State.INDICATOR) return
        handler.removeCallbacks(expandRunnable)
        state = State.SELECTOR
        setBackgroundColor(PIN_VIEW_BACKGROUND)
        layoutChildren(animated = true)
    }

    private fun updateSelection(x: Float, y: Float) {
        if (state != State.SELECTOR) return
        var closest = -1
        var closestSquared = Float.MAX_VALUE
        iconViews.forEachIndexed { index, icon ->
            val dx = x - (icon.x + icon.width / 2f)
            val dy = y - (icon.y + icon.height / 2f)
            val distance = dx * dx + dy * dy
            if (distance < closestSquared) {
                closest = index
                closestSquared = distance
            }
        }
        val maximum = selectorRadius() * selectorRadius()
        val next = if (closestSquared <= maximum) closest else -1
        if (next == selectedIndex) return
        selectedIndex = next
        if (next >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        iconViews.forEachIndexed { index, icon ->
            val scale = if (index == selectedIndex) SELECTED_ICON_SCALE else 1f
            icon.animate().scaleX(scale).scaleY(scale).setDuration(SELECTION_ANIM_MS).start()
        }
    }

    private fun layoutChildren(animated: Boolean) {
        val layout = metrics ?: return
        if (tasks.isEmpty()) return
        val scale = layout.scale
        val pointSize = DESIGN_INDICATOR_SIZE * scale
        val pointGap = DESIGN_INDICATOR_MARGIN * 2f * scale
        val totalWidth = pointSize * tasks.size + pointGap * (tasks.size - 1)
        val indicatorY =
            layout.safeBounds.bottom -
                (DESIGN_INDICATOR_BOTTOM + DESIGN_INDICATOR_OTHER_PADDING + pointSize / scale) * scale
        val indicatorStart = layout.contentBounds.centerX - totalWidth / 2f
        val arcAngles = selectorAngles(tasks.size)
        val circleCenterY =
            indicatorY + pointSize / 2f +
                cos(Math.toRadians(MAX_SELECTOR_SPAN_DEGREES / 2.0)).toFloat() *
                    DESIGN_INNER_RADIUS * scale
        val iconSize = DESIGN_ICON_SIZE * scale
        val iconRadius = (DESIGN_INNER_RADIUS + DESIGN_ICON_DISTANCE) * scale

        indicatorViews.forEachIndexed { index, point ->
            val indicatorX = indicatorStart + index * (pointSize + pointGap)
            val targetCenterX: Float
            val targetCenterY: Float
            if (state == State.SELECTOR) {
                val radians = Math.toRadians(arcAngles[index].toDouble())
                targetCenterX =
                    layout.contentBounds.centerX +
                        sin(radians).toFloat() * DESIGN_INNER_RADIUS * scale
                targetCenterY = circleCenterY - cos(radians).toFloat() * DESIGN_INNER_RADIUS * scale
            } else {
                targetCenterX = indicatorX + pointSize / 2f
                targetCenterY = indicatorY + pointSize / 2f
            }
            point.layoutParams = LayoutParams(pointSize.toInt().coerceAtLeast(1), pointSize.toInt().coerceAtLeast(1))
            move(point, targetCenterX - pointSize / 2f, targetCenterY - pointSize / 2f, animated)

            val icon = iconViews[index]
            val radians = Math.toRadians(arcAngles[index].toDouble())
            val iconCenterX = layout.contentBounds.centerX + sin(radians).toFloat() * iconRadius
            val iconCenterY = circleCenterY - cos(radians).toFloat() * iconRadius
            icon.layoutParams = LayoutParams(iconSize.toInt().coerceAtLeast(1), iconSize.toInt().coerceAtLeast(1))
            val expanded = state == State.SELECTOR
            move(
                icon,
                if (expanded) iconCenterX - iconSize / 2f else targetCenterX - iconSize / 2f,
                if (expanded) iconCenterY - iconSize / 2f else targetCenterY - iconSize / 2f,
                animated,
            )
            if (animated) {
                icon.animate()
                    .alpha(if (expanded) 1f else 0f)
                    .scaleX(if (expanded) 1f else 0f)
                    .scaleY(if (expanded) 1f else 0f)
                    .setDuration(SELECTOR_ANIM_MS)
                    .start()
            } else {
                icon.alpha = if (expanded) 1f else 0f
                icon.scaleX = if (expanded) 1f else 0f
                icon.scaleY = if (expanded) 1f else 0f
            }
        }
    }

    private fun move(view: View, x: Float, y: Float, animated: Boolean) {
        if (animated) {
            view.animate().x(x).y(y).setDuration(SELECTOR_ANIM_MS).start()
        } else {
            view.x = x
            view.y = y
        }
    }

    private fun indicatorTouchBounds(): RectF {
        if (indicatorViews.isEmpty()) return RectF()
        val first = indicatorViews.first()
        val last = indicatorViews.last()
        val padding = (metrics?.minimumTouchTarget ?: 0f) / 2f
        return RectF(
            first.x - padding,
            first.y - padding,
            last.x + last.width + padding,
            last.y + last.height + padding,
        )
    }

    private fun selectorSlop(): Float = (metrics?.minimumTouchTarget ?: 48f) / 4f

    private fun selectorRadius(): Float = max(metrics?.minimumTouchTarget ?: 48f, (metrics?.scale ?: 1f) * 150f)

    private fun selectorAngles(count: Int): FloatArray {
        val span = SELECTOR_SPANS[(count - 1).coerceIn(0, SELECTOR_SPANS.lastIndex)]
        if (count == 1) return floatArrayOf(0f)
        return FloatArray(count) { index -> -span / 2f + span * index / (count - 1) }
    }

    private companion object {
        const val MAX_PINNED_TASKS = 5
        const val PIN_COLOR_COUNT = 7
        const val DESIGN_INDICATOR_SIZE = 18f
        const val DESIGN_INDICATOR_MARGIN = 9f
        const val DESIGN_INDICATOR_BOTTOM = 101f
        const val DESIGN_INDICATOR_OTHER_PADDING = 114f
        const val DESIGN_ICON_SIZE = 90f
        const val DESIGN_ICON_DISTANCE = 78f
        const val DESIGN_INNER_RADIUS = 666f
        const val MAX_SELECTOR_SPAN_DEGREES = 60.0
        const val SELECTED_ICON_SCALE = 1.5f
        const val SELECTOR_HOLD_MS = 200L
        const val SELECTOR_ANIM_MS = 300L
        const val SELECTION_ANIM_MS = 120L
        const val STACK_FADE_MS = 180L
        const val PIN_VIEW_BACKGROUND = 0x99000000.toInt()
        val SELECTOR_SPANS = floatArrayOf(0f, 15f, 30f, 45f, 60f)
        val INDICATOR_DRAWABLES =
            intArrayOf(
                R.drawable.pinned_indicator_0,
                R.drawable.pinned_indicator_1,
                R.drawable.pinned_indicator_2,
                R.drawable.pinned_indicator_3,
                R.drawable.pinned_indicator_4,
                R.drawable.pinned_indicator_5,
                R.drawable.pinned_indicator_6,
            )
    }
}
