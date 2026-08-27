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
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.Clock

/**
 * Deterministic host for the canonical status-bar composition.
 *
 * RelativeLayout normally resolves the start section from two runtime rules (after the clock and
 * before the cutout). Replacing those rules after attach can leave one edge unresolved for a
 * frame, producing a negative child rectangle and a permanently zero-width notification host.
 * This class keeps the rules for dependency ordering, but owns the final physical partitions and
 * measures them with exact non-negative bounds.
 */
class StatusBarContentsLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : RelativeLayout(context, attrs) {
    private var cutoutMode = StatusBarCutoutMode.NONE

    internal fun setCutoutMode(mode: StatusBarCutoutMode) {
        if (cutoutMode == mode) return
        cutoutMode = mode
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        partitionBounds()?.let { bounds ->
            measurePartition(bounds.left, bounds.leftStart, bounds.leftEnd)
            measurePartition(bounds.end, bounds.endStart, bounds.endEnd)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        partitionBounds()?.let { bounds ->
            layoutPartition(bounds.left, bounds.leftStart, bounds.leftEnd)
            layoutPartition(bounds.end, bounds.endStart, bounds.endEnd)
        }
    }

    private fun measurePartition(child: View, start: Int, end: Int) {
        val width = (end - start).coerceAtLeast(0)
        val height = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        child.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    private fun layoutPartition(child: View, start: Int, end: Int) {
        val safeStart = start.coerceIn(paddingLeft, measuredWidth - paddingRight)
        val safeEnd = end.coerceIn(safeStart, measuredWidth - paddingRight)
        child.layout(safeStart, paddingTop, safeEnd, measuredHeight - paddingBottom)
    }

    private fun partitionBounds(): PartitionBounds? {
        val clock = findViewById<View>(R.id.privacy_highlight) ?: findViewById(R.id.clock)
            ?: return null
        val left = findViewById<View>(R.id.status_bar_contents_left) ?: return null
        val end = findViewById<View>(R.id.status_bar_end_side_content) ?: return null
        val camera = findViewById<View>(R.id.camera_area)
        val contentStart = paddingLeft
        val contentEnd = (measuredWidth - paddingRight).coerceAtLeast(contentStart)
        val center = (contentStart + contentEnd) / 2
        val clockWidth = clock.measuredWidth.coerceAtLeast(0)
        val cameraStart = camera?.physicalStart(contentStart) ?: center
        val cameraEnd =
            (cameraStart + (camera?.measuredWidth ?: 0)).coerceIn(cameraStart, contentEnd)

        return when (cutoutMode) {
            StatusBarCutoutMode.NONE -> {
                val clockStart = ((contentStart + contentEnd - clockWidth) / 2)
                    .coerceIn(contentStart, contentEnd)
                val clockEnd = (clockStart + clockWidth).coerceAtMost(contentEnd)
                PartitionBounds(left, contentStart, clockStart, end, clockEnd, contentEnd)
            }
            StatusBarCutoutMode.CENTER -> {
                val clockEnd = (contentStart + clockWidth).coerceAtMost(cameraStart)
                PartitionBounds(left, clockEnd, cameraStart, end, cameraEnd, contentEnd)
            }
            StatusBarCutoutMode.LEFT -> {
                val clockEnd = (cameraEnd + clockWidth).coerceAtMost(center)
                PartitionBounds(left, clockEnd, center, end, center, contentEnd)
            }
            StatusBarCutoutMode.RIGHT -> {
                val clockEnd = (contentStart + clockWidth).coerceAtMost(center)
                PartitionBounds(left, clockEnd, center, end, center, cameraStart)
            }
        }
    }

    private fun View.physicalStart(fallback: Int): Int {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams
        val requested = lp?.leftMargin ?: left
        return requested.coerceIn(
            fallback,
            this@StatusBarContentsLayout.measuredWidth.coerceAtLeast(fallback),
        )
    }

    private data class PartitionBounds(
        val left: View,
        val leftStart: Int,
        val leftEnd: Int,
        val end: View,
        val endStart: Int,
        val endEnd: Int,
    )
}

/** Applies the four R2 status-bar compositions without reparenting the clock. */
object StatusBarCutoutLayout {
    @JvmStatic
    fun apply(
        contents: ViewGroup?,
        mode: StatusBarCutoutMode,
        cutoutBounds: Rect?,
    ) {
        if (contents !is RelativeLayout) return
        val clock = contents.findViewById<Clock>(R.id.clock)
        if (clock == null) {
            applyPanel(contents, mode, cutoutBounds)
            return
        }
        (contents as? StatusBarContentsLayout)?.setCutoutMode(mode)
        val clockHost =
            contents.findViewById<View>(R.id.privacy_highlight)?.takeIf {
                clock.parent === it
            } ?: clock
        val left = contents.findViewById<ViewGroup>(R.id.status_bar_contents_left) ?: return
        val endSide = contents.findViewById<View>(R.id.status_bar_end_side_content) ?: return
        val camera =
            contents.findViewById<View>(R.id.camera_area)
                ?: contents.findViewById(R.id.shade_panel_display_cutout_space)
        val centerBoundary = contents.findViewById<View>(R.id.status_bar_center_boundary)

        // R2 composes the bar in physical screen coordinates; locale direction must not swap a
        // left/right cutout or reverse the fixed network cluster.
        contents.layoutDirection = View.LAYOUT_DIRECTION_LTR
        contents.suppressLayout(true)
        try {
            if (mode == StatusBarCutoutMode.NONE || camera == null || cutoutBounds == null) {
                camera?.visibility = View.GONE
                applyNormal(clockHost, left, endSide)
            } else {
                placeCutout(contents, camera, cutoutBounds)
                when (mode) {
                    StatusBarCutoutMode.CENTER ->
                        applyCenter(clockHost, left, endSide, camera)
                    StatusBarCutoutMode.LEFT ->
                        applyLeft(clockHost, left, endSide, camera, centerBoundary)
                    StatusBarCutoutMode.RIGHT ->
                        applyRight(clockHost, left, endSide, camera, centerBoundary)
                    StatusBarCutoutMode.NONE -> applyNormal(clockHost, left, endSide)
                }
            }
        } finally {
            contents.suppressLayout(false)
            contents.requestLayout()
        }
    }

    /** R2's cutout PANEL contains only carrier text, the physical camera gap and system icons. */
    private fun applyPanel(
        contents: RelativeLayout,
        mode: StatusBarCutoutMode,
        cutoutBounds: Rect?,
    ) {
        val left = contents.findViewById<View>(R.id.status_bar_contents_left) ?: return
        val endSide = contents.findViewById<View>(R.id.status_bar_end_side_content) ?: return
        val camera = contents.findViewById<View>(R.id.shade_panel_display_cutout_space) ?: return
        contents.layoutDirection = View.LAYOUT_DIRECTION_LTR
        contents.suppressLayout(true)
        try {
            if (mode == StatusBarCutoutMode.NONE || cutoutBounds == null) {
                camera.visibility = View.GONE
                left.layoutParams =
                    relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                        addRule(RelativeLayout.ALIGN_PARENT_START)
                    }
                endSide.layoutParams =
                    relative(endSide, ViewGroup.LayoutParams.WRAP_CONTENT) {
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                    }
            } else {
                placeCutout(contents, camera, cutoutBounds)
                when (mode) {
                    StatusBarCutoutMode.CENTER -> {
                        left.layoutParams =
                            relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                                addRule(RelativeLayout.ALIGN_PARENT_START)
                                addRule(RelativeLayout.START_OF, camera.id)
                            }
                        endSide.layoutParams =
                            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                                addRule(RelativeLayout.ALIGN_PARENT_END)
                                addRule(RelativeLayout.END_OF, camera.id)
                            }
                    }
                    StatusBarCutoutMode.LEFT -> {
                        // A left punch-hole owns the physical leading edge. Keep the carrier and
                        // the right-aligned fixed icon cluster in the safe region after it.
                        left.layoutParams =
                            relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                                addRule(RelativeLayout.END_OF, camera.id)
                            }
                        endSide.layoutParams =
                            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                                addRule(RelativeLayout.ALIGN_PARENT_END)
                                addRule(RelativeLayout.END_OF, camera.id)
                            }
                    }
                    StatusBarCutoutMode.RIGHT -> {
                        left.layoutParams =
                            relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                                addRule(RelativeLayout.ALIGN_PARENT_START)
                            }
                        endSide.layoutParams =
                            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                                addRule(RelativeLayout.ALIGN_PARENT_START)
                                addRule(RelativeLayout.START_OF, camera.id)
                            }
                    }
                    StatusBarCutoutMode.NONE -> Unit
                }
            }
        } finally {
            contents.suppressLayout(false)
            contents.requestLayout()
        }
    }

    private fun applyNormal(clockHost: View, left: View, endSide: View) {
        clockHost.layoutParams =
            relative(clockHost) { addRule(RelativeLayout.CENTER_IN_PARENT) }
        left.layoutParams =
            relative(left, ViewGroup.LayoutParams.MATCH_PARENT) {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.START_OF, clockHost.id)
            }
        endSide.layoutParams =
            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.END_OF, clockHost.id)
            }
    }

    private fun applyCenter(clockHost: View, left: View, endSide: View, camera: View) {
        clockHost.layoutParams =
            relative(clockHost) { addRule(RelativeLayout.ALIGN_PARENT_START) }
        left.layoutParams =
            relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                addRule(RelativeLayout.END_OF, clockHost.id)
                addRule(RelativeLayout.START_OF, camera.id)
            }
        endSide.layoutParams =
            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.END_OF, camera.id)
            }
    }

    private fun applyLeft(
        clockHost: View,
        left: View,
        endSide: View,
        camera: View,
        centerBoundary: View?,
    ) {
        clockHost.layoutParams =
            relative(clockHost) { addRule(RelativeLayout.END_OF, camera.id) }
        left.layoutParams =
            relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                addRule(RelativeLayout.END_OF, clockHost.id)
                centerBoundary?.let { addRule(RelativeLayout.START_OF, it.id) }
            }
        endSide.layoutParams =
            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(
                    RelativeLayout.END_OF,
                    centerBoundary?.id ?: left.id,
                )
            }
    }

    private fun applyRight(
        clockHost: View,
        left: View,
        endSide: View,
        camera: View,
        centerBoundary: View?,
    ) {
        clockHost.layoutParams =
            relative(clockHost) { addRule(RelativeLayout.ALIGN_PARENT_START) }
        left.layoutParams =
            relative(left, ViewGroup.LayoutParams.WRAP_CONTENT) {
                addRule(RelativeLayout.END_OF, clockHost.id)
                centerBoundary?.let { addRule(RelativeLayout.START_OF, it.id) }
            }
        endSide.layoutParams =
            relative(endSide, ViewGroup.LayoutParams.MATCH_PARENT) {
                addRule(RelativeLayout.START_OF, camera.id)
                addRule(
                    RelativeLayout.END_OF,
                    centerBoundary?.id ?: left.id,
                )
            }
    }

    private fun placeCutout(contents: RelativeLayout, camera: View, bounds: Rect) {
        val location = IntArray(2)
        contents.getLocationOnScreen(location)
        val originX =
            if (contents.isLaidOut) {
                location[0]
            } else {
                val parent = contents.parent as? View
                val margin =
                    (contents.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
                (parent?.paddingStart ?: 0) + margin
            }
        val left = (bounds.left - originX).coerceAtLeast(0)
        camera.visibility = View.VISIBLE
        camera.layoutParams =
            relative(camera, bounds.width().coerceAtLeast(1)) {
                leftMargin = left
                marginStart = left
                addRule(RelativeLayout.ALIGN_PARENT_START)
            }
    }

    private fun relative(
        view: View,
        width: Int = view.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        rules: RelativeLayout.LayoutParams.() -> Unit,
    ): RelativeLayout.LayoutParams =
        RelativeLayout.LayoutParams(
                width,
                view.layoutParams?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
            )
            .also {
                val old = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (old != null) {
                    it.leftMargin = old.leftMargin
                    it.topMargin = old.topMargin
                    it.rightMargin = old.rightMargin
                    it.bottomMargin = old.bottomMargin
                    it.marginStart = old.marginStart
                    it.marginEnd = old.marginEnd
                }
                it.rules()
            }
}
