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
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusIconDisplayable

/**
 * Smartisan system-icon merger: leftover width in front of the fixed network cluster, no overflow
 * DOT, and later-visible icons sit closer to the cluster.
 *
 * Subclasses [StatusIconContainer] so HOME / KEYGUARD / PANEL inflation sites that still require
 * that type do not crash, but measurement and layout fully replace DOT/underflow semantics.
 */
open class StatusIconMerger
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    StatusIconContainer(context, attrs) {
    private val lastVisibleAt = HashMap<View, Long>()
    private val wasLogicallyVisible = HashMap<View, Boolean>()
    private val intrinsicWidths = HashMap<View, Int>()
    private val accessibilityBeforeClip = HashMap<View, Int>()
    private val clipped = HashSet<View>()
    private var visibilitySequence = 0L
    private var iconMarginStart = 0
    private var iconMarginEnd = 0
    private var restoreGeneration = 0L
    private var restoreTarget: Set<View>? = null
    private var restoreRunnable: Runnable? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        setShouldRestrictIcons(false)
        reloadDimens()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        layoutDirection = LAYOUT_DIRECTION_LTR
        setShouldRestrictIcons(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        reloadDimens()
        cancelRestore()
        clipped.clear()
        intrinsicWidths.clear()
        requestLayout()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        cancelRestore()
        clipped.clear()
        requestLayout()
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        stampVisibility(child)
        requestLayout()
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        clipped.remove(child)
        lastVisibleAt.remove(child)
        wasLogicallyVisible.remove(child)
        intrinsicWidths.remove(child)
        accessibilityBeforeClip.remove(child)
    }

    override fun onDetachedFromWindow() {
        cancelRestore()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specWidth = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSpec =
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec),
                MeasureSpec.getMode(heightMeasureSpec),
            )
        // Always obtain the full width before selecting the retained set. A clipped child is
        // measured at zero below, so using its previous measuredWidth here causes the familiar
        // clip/restore/requestLayout oscillation.
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            stampVisibility(child)
            if (wantsToShow(child)) {
                measureChild(
                    child,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    heightSpec,
                )
                intrinsicWidths[child] = measuredIconWidth(child)
            }
        }

        val availableBeforeSpeed =
            if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (specWidth - paddingStart - paddingEnd).coerceAtLeast(0)
        val available =
            if (availableBeforeSpeed == Int.MAX_VALUE) availableBeforeSpeed
            else (availableBeforeSpeed - activeNetSpeedWidth()).coerceAtLeast(0)
        selectRetainedIcons(available)

        var used = paddingStart + paddingEnd
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (!wantsToShow(child) || clipped.contains(child)) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                    heightSpec,
                )
            } else {
                measureChild(
                    child,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    heightSpec,
                )
                used += intrinsicWidths[child] ?: measuredIconWidth(child)
            }
        }
        val measuredWidth =
            when (widthMode) {
                MeasureSpec.EXACTLY -> specWidth
                MeasureSpec.AT_MOST -> used.coerceAtMost(specWidth)
                else -> used
            }
        setMeasuredDimension(
            measuredWidth,
            resolveSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val midY = (b - t) / 2f
        var cursor = width - paddingEnd
        val ordered = layoutOrder()
        for (child in ordered) {
            val totalWidth =
                if (clipped.contains(child) || !wantsToShow(child)) 0
                else intrinsicWidths[child] ?: child.measuredWidth
            val childHeight = child.measuredHeight
            val top = (midY - childHeight / 2f).toInt()
            val childRight = if (totalWidth == 0) cursor else cursor - iconMarginEnd
            val childLeft = childRight - if (totalWidth == 0) 0 else child.measuredWidth
            child.layout(childLeft, top, childRight, top + childHeight)
            applyClipChrome(child)
            cursor -= totalWidth
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (!ordered.contains(child)) {
                child.layout(0, 0, 0, 0)
                applyClipChrome(child)
            }
        }
        syncNetSpeedPosition()
    }

    private fun selectRetainedIcons(available: Int) {
        val visible = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            stampVisibility(child)
            if (wantsToShow(child)) {
                visible.add(child)
            } else {
                clipped.remove(child)
            }
        }
        visible.sortWith { a, b ->
            val tb = lastVisibleAt[b] ?: 0L
            val ta = lastVisibleAt[a] ?: 0L
            tb.compareTo(ta)
        }
        var used = 0
        val keep = HashSet<View>()
        var overflow = false
        for (child in visible) {
            val width = intrinsicWidths[child] ?: measuredIconWidth(child)
            if (!overflow && used + width <= available) {
                keep.add(child)
                used += width
            } else {
                // Factory StatusIconMerger keeps one contiguous newest-visible prefix. It never
                // skips a wide icon to bring back an older, narrower one.
                overflow = true
            }
        }
        val nextClipped = HashSet<View>()
        for (child in visible) {
            if (!keep.contains(child)) nextClipped.add(child)
        }
        val restoring =
            clipped.isNotEmpty() && clipped.containsAll(nextClipped) && nextClipped.size < clipped.size
        if (restoring) {
            scheduleRestore(nextClipped)
        } else {
            cancelRestore()
            clipped.clear()
            clipped.addAll(nextClipped)
        }
    }

    private fun layoutOrder(): List<View> {
        val ordered = ArrayList<View>(childCount)
        for (i in 0 until childCount) {
            ordered.add(getChildAt(i))
        }
        ordered.sortWith { a, b ->
            val clipA = if (clipped.contains(a) || !wantsToShow(a)) 1 else 0
            val clipB = if (clipped.contains(b) || !wantsToShow(b)) 1 else 0
            if (clipA != clipB) return@sortWith clipA - clipB
            val tb = lastVisibleAt[b] ?: 0L
            val ta = lastVisibleAt[a] ?: 0L
            tb.compareTo(ta)
        }
        return ordered
    }

    private fun wantsToShow(child: View): Boolean {
        if (child is StatusIconDisplayable) {
            return child.isIconVisible && !child.isIconBlocked
        }
        return child.visibility != GONE
    }

    private fun stampVisibility(child: View) {
        val visible = wantsToShow(child)
        val previous = wasLogicallyVisible.put(child, visible)
        if (visible && previous != true) {
            lastVisibleAt[child] = ++visibilitySequence
        }
    }

    private fun measuredIconWidth(child: View): Int {
        val drawableWidth =
            (child as? ImageView)?.drawable?.let { drawable ->
                drawable.bounds.width().takeIf { it > 0 }
                    ?: drawable.intrinsicWidth.takeIf { it > 0 }
            }
        // A bindable icon may report a drawable narrower than its mandatory touch/layout box.
        // Never let the merger advance by less than the laid-out width or adjacent icons overlap.
        // Factory StatusIconMerger uses drawable bounds plus icon padding. Modern bindable views
        // have no drawable, so their measured width is the only compatible fallback.
        val drawableBox =
            drawableWidth?.let { it + child.paddingLeft + child.paddingRight } ?: 0
        val visualWidth = maxOf(child.measuredWidth, drawableBox)
        return visualWidth + iconMarginStart + iconMarginEnd
    }

    /** Width occupied by the retained R2 queue, excluding the separate network-speed view. */
    fun getRealWidth(): Int {
        var width = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (wantsToShow(child) && !clipped.contains(child)) {
                width += intrinsicWidths[child] ?: measuredIconWidth(child)
            }
        }
        return width
    }

    private fun activeNetSpeedWidth(): Int {
        val speed = (parent as? ViewGroup)?.findViewById<View>(R.id.net_speed_view) ?: return 0
        if (speed.visibility != VISIBLE) return 0
        return speed.measuredWidth.takeIf { it > 0 }
            ?: speed.layoutParams?.width?.takeIf { it > 0 }
            ?: 0
    }

    private fun syncNetSpeedPosition() {
        val speed = (parent as? ViewGroup)?.findViewById<View>(R.id.net_speed_view) ?: return
        speed.translationX = if (speed.visibility == VISIBLE) -getRealWidth().toFloat() else 0f
    }

    private fun scheduleRestore(target: Set<View>) {
        if (restoreTarget == target && restoreRunnable != null) return
        cancelRestore()
        val generation = restoreGeneration
        restoreTarget = HashSet(target)
        postRestoreStep(generation)
    }

    private fun postRestoreStep(generation: Long) {
        val runnable =
            Runnable {
                if (generation != restoreGeneration) return@Runnable
                restoreRunnable = null
                val target = restoreTarget ?: return@Runnable
                val next =
                    clipped
                        .asSequence()
                        .filterNot(target::contains)
                        .maxByOrNull { lastVisibleAt[it] ?: 0L }
                if (next == null) {
                    restoreTarget = null
                    return@Runnable
                }
                clipped.remove(next)
                requestLayout()
                if (clipped.any { !target.contains(it) }) {
                    postRestoreStep(generation)
                } else {
                    restoreTarget = null
                }
            }
        restoreRunnable = runnable
        postOnAnimation(runnable)
    }

    private fun cancelRestore() {
        restoreGeneration++
        restoreRunnable?.let(::removeCallbacks)
        restoreRunnable = null
        restoreTarget = null
    }

    private fun applyClipChrome(child: View) {
        val hide = clipped.contains(child) || !wantsToShow(child)
        if (child is StatusIconDisplayable) {
            child.setVisibleState(
                if (hide) StatusBarIconView.STATE_HIDDEN else StatusBarIconView.STATE_ICON,
                false,
            )
        }
        child.translationX = 0f
        child.translationY = 0f
        if (hide) {
            accessibilityBeforeClip.putIfAbsent(child, child.importantForAccessibility)
            child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            accessibilityBeforeClip.remove(child)?.let {
                child.importantForAccessibility = it
            }
        }
        child.alpha = if (hide) 0f else 1f
    }

    private fun reloadDimens() {
        val metrics = StatusBarGeometry.calculate(this)
        iconMarginStart = metrics.itemMarginStart
        iconMarginEnd = metrics.itemMarginEnd
    }
}
