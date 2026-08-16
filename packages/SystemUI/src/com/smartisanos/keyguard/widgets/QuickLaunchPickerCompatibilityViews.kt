/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

package com.smartisanos.keyguard.widgets

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.keyguard.ui.view.layout.sections.SosQuickLaunchTarget
import com.android.systemui.keyguard.ui.view.layout.sections.SosResolvedQuickLaunchTarget
import com.android.systemui.res.R
import kotlin.math.ceil

/** Original dot renderer with safe bitmap replacement across theme/configuration changes. */
class QuickLaunchIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var pageCount = 0
    private var currentPage = 0
    private var darkTheme = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setDarkTheme(dark: Boolean) {
        if (darkTheme == dark) return
        darkTheme = dark
        invalidate()
    }

    fun setState(all: Int, current: Int = currentPage) {
        pageCount = all.coerceIn(0, MAX_DOTS)
        currentPage = current.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 1) return
        val selected =
            BitmapFactory.decodeResource(
                resources,
                if (darkTheme) R.drawable.focus else R.drawable.focus_light,
            )
        val normal =
            BitmapFactory.decodeResource(
                resources,
                if (darkTheme) R.drawable.unfocus else R.drawable.unfocus_light,
            )
        if (selected == null || normal == null) return
        val diameter = 36f * width / 1080f
        var left = (width - diameter * pageCount) / 2f + 11f * width / 1080f
        val top = (height - selected.height) / 2f
        repeat(pageCount) { index ->
            canvas.drawBitmap(if (index == currentPage) selected else normal, left, top, paint)
            left += diameter
        }
        selected.recycle()
        normal.recycle()
    }

    private companion object {
        const val MAX_DOTS = 25
    }
}

/**
 * Android 16-compatible implementation of the original in-keyguard picker.
 *
 * It deliberately has no Activity or provider dependency. Candidate resolution remains outside
 * this visual class; drag/drop only edits an in-memory three-slot snapshot until the host asks the
 * bouncer to authenticate the save.
 */
class QuickLaunchPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    interface Listener {
        fun onCancel()

        fun onSave(slots: List<SosQuickLaunchTarget?>)
    }

    private data class DragPayload(
        val target: SosQuickLaunchTarget,
        val sourceSlot: Int = NO_SLOT,
    )

    private val backgroundPanel = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val actionBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val cancelButton = Button(context)
    private val finishButton = Button(context)
    private val title = TextView(context).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        text = context.getString(R.string.sos_quick_launch_picker_alert)
    }
    private val selectedRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val candidateGrid = GridLayout(context).apply {
        columnCount = COLUMNS
        rowCount = ROWS
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
    }
    private val indicator = QuickLaunchIndicatorView(context)
    private val shadow = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }

    private var listener: Listener? = null
    private var candidates = emptyList<SosResolvedQuickLaunchTarget>()
    private var selected = MutableList<SosQuickLaunchTarget?>(SLOT_COUNT) { null }
    private var page = 0
    private var pageCount = 0
    private var darkTheme = true
    private var scale = 1f

    init {
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false

        addView(backgroundPanel)
        addView(actionBar)
        addView(selectedRow)
        addView(candidateGrid)
        addView(indicator)
        addView(shadow)

        cancelButton.text = context.getString(R.string.sos_quick_launch_cancel)
        finishButton.text = context.getString(R.string.sos_quick_launch_finish)
        actionBar.addView(cancelButton)
        actionBar.addView(
            title,
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        actionBar.addView(finishButton)
        cancelButton.setOnClickListener { listener?.onCancel() }
        finishButton.setOnClickListener { listener?.onSave(selected.toList()) }

        setOnClickListener { /* absorb taps outside picker children */ }
        setOnTouchListener(
            object : OnTouchListener {
                private var downX = 0f

                override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> downX = event.x
                        android.view.MotionEvent.ACTION_UP -> {
                            val delta = event.x - downX
                            if (kotlin.math.abs(delta) > 90f * scale) {
                                showPage(page + if (delta < 0f) 1 else -1)
                                return true
                            }
                        }
                    }
                    return false
                }
            }
        )
    }

    fun show(
        candidates: List<SosResolvedQuickLaunchTarget>,
        slots: List<SosQuickLaunchTarget?>,
        darkTheme: Boolean,
        listener: Listener,
    ) {
        require(slots.size == SLOT_COUNT)
        this.listener = listener
        this.candidates = candidates
        this.selected = slots.toMutableList()
        this.darkTheme = darkTheme
        page = 0
        pageCount = ceil(candidates.size / ITEMS_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
        applyTheme()
        rebuildSelectedRow()
        showPage(0)
        visibility = View.VISIBLE
        translationY = height.takeIf { it > 0 }?.toFloat() ?: 805f * scale
        animate()
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(8f))
            .start()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun hide(after: (() -> Unit)? = null) {
        if (visibility != View.VISIBLE) {
            after?.invoke()
            return
        }
        animate()
            .translationY(height.toFloat())
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                translationY = 0f
                listener = null
                after?.invoke()
            }
            .start()
    }

    fun cancelTransientState() {
        animate().cancel()
        visibility = View.GONE
        translationY = 0f
        listener = null
        candidates = emptyList()
        selected.fill(null)
        candidateGrid.removeAllViews()
        selectedRow.removeAllViews()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        scale = width / 1080f
        val wantedHeight = (PICKER_HEIGHT * scale).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(
                wantedHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec)),
                MeasureSpec.EXACTLY,
            ),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val actionHeight = (ACTION_HEIGHT * scale).toInt()
        val selectedTop = actionHeight + (SELECTED_TOP * scale).toInt()
        val selectedHeight = (SELECTED_HEIGHT * scale).toInt()
        val gridTop = selectedTop + selectedHeight + (GRID_GAP * scale).toInt()
        val indicatorHeight = (INDICATOR_HEIGHT * scale).toInt()
        backgroundPanel.layout(0, 0, width, height)
        actionBar.layout(0, 0, width, actionHeight)
        selectedRow.layout(0, selectedTop, width, selectedTop + selectedHeight)
        candidateGrid.layout(0, gridTop, width, height - indicatorHeight)
        indicator.layout(0, height - indicatorHeight, width, height)
        shadow.layout(0, height - (5f * scale).toInt(), width, height)

        val buttonWidth = (160f * scale).toInt()
        cancelButton.layout((20f * scale).toInt(), 0, (20f * scale).toInt() + buttonWidth, actionHeight)
        finishButton.layout(
            width - (20f * scale).toInt() - buttonWidth,
            0,
            width - (20f * scale).toInt(),
            actionHeight,
        )
        title.layout(
            cancelButton.right + (20f * scale).toInt(),
            0,
            finishButton.left - (20f * scale).toInt(),
            actionHeight,
        )
    }

    private fun applyTheme() {
        backgroundPanel.setImageResource(if (darkTheme) R.drawable.popup_bg else R.drawable.popup_bg_light)
        actionBar.setBackgroundResource(if (darkTheme) R.drawable.popup_top else R.drawable.popup_top_light)
        shadow.setImageResource(
            if (darkTheme) R.drawable.quicklunch_slide_bg_shadow
            else R.drawable.quicklunch_slide_bg_light_shadow
        )
        val color = if (darkTheme) 0x9EFFFFFF.toInt() else 0x9E000000.toInt()
        title.setTextColor(color)
        cancelButton.setTextColor(color)
        finishButton.setTextColor(color)
        cancelButton.setBackgroundResource(
            if (darkTheme) R.drawable.secletor_quicklaunch_pick_btn
            else R.drawable.secletor_quicklaunch_pick_btn_light
        )
        finishButton.setBackgroundResource(
            if (darkTheme) R.drawable.secletor_quicklaunch_pick_btn
            else R.drawable.secletor_quicklaunch_pick_btn_light
        )
        title.textSize = 15f
        cancelButton.textSize = 14f
        finishButton.textSize = 14f
        indicator.setDarkTheme(darkTheme)
    }

    private fun rebuildSelectedRow() {
        selectedRow.removeAllViews()
        repeat(SLOT_COUNT) { slot ->
            val resolved = selected[slot]?.let(::findResolved)
            selectedRow.addView(
                createTargetCell(resolved, showLabel = true).apply {
                    contentDescription =
                        resolved?.label
                            ?: context.getString(R.string.sos_quick_launch_empty_slot, slot + 1)
                    setOnClickListener {
                        selected[slot] = null
                        rebuildSelectedRow()
                    }
                    setOnLongClickListener {
                        selected[slot]?.let { target -> startTargetDrag(this, target, slot) } != null
                    }
                    setOnDragListener { _, event -> handleSlotDrag(slot, event) }
                },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }

    private fun showPage(newPage: Int) {
        page = newPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        candidateGrid.removeAllViews()
        val pageItems = candidates.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)
        pageItems.forEach { resolved ->
            val cell =
                createTargetCell(resolved, showLabel = true).apply {
                    isEnabled = resolved.available
                    alpha = if (resolved.available) 1f else 0.45f
                    setOnClickListener { addToFirstAvailableSlot(resolved.target) }
                    setOnLongClickListener {
                        if (!resolved.available) false
                        else startTargetDrag(this, resolved.target, NO_SLOT)
                    }
                }
            candidateGrid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                },
            )
        }
        indicator.setState(pageCount, page)
    }

    private fun createTargetCell(
        resolved: SosResolvedQuickLaunchTarget?,
        showLabel: Boolean,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val icon =
                ImageView(context).apply {
                    setBackgroundResource(
                        if (resolved == null) R.drawable.buttom_small_disabled
                        else R.drawable.buttom_small
                    )
                    setImageDrawable(resolved?.icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val padding = (18f * scale).toInt()
                    setPadding(padding, padding, padding, padding)
                }
            addView(
                icon,
                LinearLayout.LayoutParams((153f * scale).toInt(), (153f * scale).toInt()),
            )
            if (showLabel) {
                addView(
                    TextView(context).apply {
                        gravity = Gravity.CENTER
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        text = resolved?.label ?: ""
                        setTextColor(if (darkTheme) 0x9EFFFFFF.toInt() else 0x9E000000.toInt())
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                    },
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            }
        }

    private fun addToFirstAvailableSlot(target: SosQuickLaunchTarget) {
        if (target in selected) return
        val slot = selected.indexOfFirst { it == null }.takeIf { it >= 0 } ?: (SLOT_COUNT - 1)
        selected[slot] = target
        rebuildSelectedRow()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun startTargetDrag(view: View, target: SosQuickLaunchTarget, sourceSlot: Int): Boolean {
        val data = ClipData.newPlainText("sos-quick-launch", target.kind.name)
        return view.startDragAndDrop(data, DragShadowBuilder(view), DragPayload(target, sourceSlot), 0)
    }

    private fun handleSlotDrag(slot: Int, event: DragEvent): Boolean {
        val payload = event.localState as? DragPayload ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> {
                selectedRow.getChildAt(slot)?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(120)?.start()
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                selectedRow.getChildAt(slot)?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            }
            DragEvent.ACTION_DROP -> {
                if (payload.sourceSlot == slot) return true
                val displaced = selected[slot]
                selected[slot] = payload.target
                if (payload.sourceSlot in 0 until SLOT_COUNT) {
                    selected[payload.sourceSlot] = displaced
                } else {
                    val duplicate =
                        selected.indices.firstOrNull { index ->
                            selected[index] == payload.target && index != slot
                        } ?: NO_SLOT
                    if (duplicate >= 0) selected[duplicate] = displaced
                }
                rebuildSelectedRow()
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                return true
            }
        }
        return true
    }

    private fun findResolved(target: SosQuickLaunchTarget): SosResolvedQuickLaunchTarget? =
        candidates.firstOrNull { it.target == target }

    private companion object {
        const val SLOT_COUNT = 3
        const val COLUMNS = 5
        const val ROWS = 2
        const val ITEMS_PER_PAGE = COLUMNS * ROWS
        const val NO_SLOT = -1
        const val PICKER_HEIGHT = 805f
        const val ACTION_HEIGHT = 153f
        const val SELECTED_TOP = 18f
        const val SELECTED_HEIGHT = 190f
        const val GRID_GAP = 12f
        const val INDICATOR_HEIGHT = 36f
        const val SHOW_DURATION_MS = 300L
        const val HIDE_DURATION_MS = 200L
    }
}
