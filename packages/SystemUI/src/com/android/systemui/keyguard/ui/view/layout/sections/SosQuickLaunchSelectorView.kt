/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.res.R

/** Transient three-item surface shown while the user keeps the Delta camera shortcut pressed. */
class SosQuickLaunchSelectorView(context: Context) : FrameLayout(context) {
    private val icons = List(SLOT_COUNT) { ImageView(context) }
    private val label =
        TextView(context).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    private var slots = List<SosResolvedQuickLaunchTarget?>(SLOT_COUNT) { null }
    private var slotBounds = List(SLOT_COUNT) { RectF() }
    private var labelBounds = RectF()
    private var activeSlot = NO_SLOT
    private var lightAssets = true

    init {
        visibility = View.GONE
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(label)
        icons.forEach { icon ->
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(icon)
        }
    }

    fun setGeometry(metrics: SosKeyguardLayoutModel.Metrics) {
        slotBounds = metrics.quickSelectorSlots.map { it.toRectF() }
        labelBounds = metrics.quickSelectorLabel.toRectF()
        requestLayout()
    }

    fun show(
        targets: List<SosResolvedQuickLaunchTarget?>,
        useLightAssets: Boolean,
    ) {
        require(targets.size == SLOT_COUNT)
        slots = targets
        lightAssets = useLightAssets
        activeSlot = NO_SLOT
        slots.forEachIndexed { index, resolved -> bindIcon(index, resolved) }
        label.text = ""
        label.setTextColor(if (useLightAssets) 0xE6FFFFFF.toInt() else 0xFF454545.toInt())
        alpha = 0f
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(SHOW_DURATION_MS).start()
    }

    fun updatePointer(x: Float, y: Float): SosResolvedQuickLaunchTarget? {
        val hit =
            slotBounds.indexOfFirst { bounds ->
                RectF(bounds).apply { inset(-HIT_SLOP * resources.displayMetrics.density, -HIT_SLOP * resources.displayMetrics.density) }
                    .contains(x, y)
            }
        setActiveSlot(hit)
        return slots.getOrNull(hit)?.takeIf { it.available }
    }

    fun selectedTarget(): SosResolvedQuickLaunchTarget? =
        slots.getOrNull(activeSlot)?.takeIf { it.available }

    fun showLaunching(target: SosResolvedQuickLaunchTarget) {
        val index = slots.indexOfFirst { it?.target == target.target }
        setActiveSlot(index)
        label.text = target.label
        icons.getOrNull(index)?.animate()?.scaleX(1.28f)?.scaleY(1.28f)?.setDuration(120)?.start()
    }

    fun hide(immediate: Boolean = false) {
        animate().cancel()
        if (immediate) {
            resetAndHide()
        } else {
            animate().alpha(0f).setDuration(HIDE_DURATION_MS).withEndAction(::resetAndHide).start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        label.layout(
            labelBounds.left.toInt(),
            labelBounds.top.toInt(),
            labelBounds.right.toInt(),
            labelBounds.bottom.toInt(),
        )
        icons.forEachIndexed { index, icon ->
            val bounds = slotBounds[index]
            icon.layout(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt(),
            )
        }
    }

    private fun bindIcon(index: Int, resolved: SosResolvedQuickLaunchTarget?) {
        val icon = icons[index]
        icon.animate().cancel()
        icon.scaleX = 1f
        icon.scaleY = 1f
        icon.alpha = if (resolved?.available == true) 1f else DISABLED_ALPHA
        icon.isEnabled = resolved?.available == true
        icon.contentDescription = resolved?.label
        if (resolved == null) {
            icon.setPadding(0, 0, 0, 0)
            icon.setImageDrawable(null)
            icon.setBackgroundResource(R.drawable.buttom_small_disabled)
        } else if (resolved.selectorResource != 0) {
            icon.setPadding(0, 0, 0, 0)
            icon.background = null
            icon.setImageResource(resolved.selectorResource)
        } else {
            icon.setBackgroundResource(
                if (resolved.available) R.drawable.buttom_small else R.drawable.buttom_small_disabled
            )
            icon.setImageDrawable(resolved.icon)
            val padding = (18f * (slotBounds[index].width() / 153f)).toInt().coerceAtLeast(0)
            icon.setPadding(padding, padding, padding, padding)
        }
    }

    private fun setActiveSlot(index: Int) {
        val constrained = index.takeIf { it in 0 until SLOT_COUNT } ?: NO_SLOT
        if (activeSlot == constrained) return
        activeSlot = constrained
        icons.forEachIndexed { slot, icon ->
            icon.isPressed = slot == activeSlot && slots[slot]?.available == true
            val scale = if (slot == activeSlot) ACTIVE_SCALE else 1f
            icon.animate().scaleX(scale).scaleY(scale).setDuration(ACTIVE_DURATION_MS).start()
        }
        label.text = slots.getOrNull(activeSlot)?.label ?: ""
        activeSlot
            .takeIf { it >= 0 }
            ?.let { icons[it].announceForAccessibility(label.text) }
    }

    private fun resetAndHide() {
        visibility = View.GONE
        alpha = 1f
        label.text = ""
        activeSlot = NO_SLOT
        icons.forEach {
            it.animate().cancel()
            it.isPressed = false
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }

    private fun SosKeyguardLayoutModel.Bounds.toRectF() = RectF(left, top, right, bottom)

    private companion object {
        const val SLOT_COUNT = 3
        const val NO_SLOT = -1
        const val ACTIVE_SCALE = 1.2f
        const val DISABLED_ALPHA = 0.45f
        const val HIT_SLOP = 12f
        const val SHOW_DURATION_MS = 160L
        const val HIDE_DURATION_MS = 120L
        const val ACTIVE_DURATION_MS = 120L
    }
}
