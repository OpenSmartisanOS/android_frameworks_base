/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.android.keyguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R

class SosKeyguardSimPinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KeyguardSimPinView(context, attrs) {
    override fun startAppearAnimation() {
        SosCredentialTransitionAnimator.appear(this)
    }

    override fun startDisappearAnimation(finishRunnable: Runnable?): Boolean =
        SosCredentialTransitionAnimator.disappear(this, finishRunnable)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        getChildAt(0)?.let { SosCredentialLayoutScaler.apply(this, it, w, h) }
    }
}

class SosKeyguardSimPukView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KeyguardSimPukView(context, attrs) {
    override fun startAppearAnimation() {
        SosCredentialTransitionAnimator.appear(this)
    }

    override fun startDisappearAnimation(finishRunnable: Runnable?): Boolean =
        SosCredentialTransitionAnimator.disappear(this, finishRunnable)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        getChildAt(0)?.let { SosCredentialLayoutScaler.apply(this, it, w, h) }
    }
}

/** One original bitmap key that targets whichever Android 16 SIM entry is active. */
class SosSimNumPadKey @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.numPadKeyStyle,
) : NumPadKey(context, attrs, defStyleAttr) {
    private val digit: Int =
        context.obtainStyledAttributes(attrs, R.styleable.NumPadKey, defStyleAttr, 0).let { array ->
            try {
                array.getInt(R.styleable.NumPadKey_digit, -1)
            } finally {
                array.recycle()
            }
        }

    init {
        setOnClickListener {
            val entry =
                rootView.findViewById<PasswordTextView?>(R.id.simPinEntry)
                    ?: rootView.findViewById(R.id.pukEntry)
            if (entry?.isEnabled == true && digit in 0..9) {
                entry.append(Character.forDigit(digit, 10))
            }
            userActivity()
        }
    }

    override fun dispatchDraw(canvas: Canvas) = Unit

    override fun reloadColors() = Unit
}

/** Original two-row SIM layer; Telephony remains the sole source of slot and lock state. */
class SosSimStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private data class Row(
        val root: LinearLayout,
        val simIcon: ImageView,
        val label: TextView,
        val stateIcon: ImageView,
    )

    private val rows = List(2, ::createRow)
    private var dualSession = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        rows.forEach { addView(it.root) }
    }

    fun update(monitor: KeyguardUpdateMonitor, currentSubId: Int) {
        val subscriptions = monitor.getSubscriptionInfo(false).sortedBy { it.simSlotIndex }
        val lockedCount =
            subscriptions.count {
                KeyguardUpdateMonitor.isSimPinSecure(
                    monitor.getSimStateForSlotId(it.simSlotIndex)
                )
            }
        if (lockedCount >= 2) dualSession = true
        visibility = if (dualSession && subscriptions.size >= 2) View.VISIBLE else View.GONE
        if (visibility != View.VISIBLE) return

        rows.forEachIndexed { index, row ->
            val info = subscriptions.getOrNull(index)
            if (info == null) {
                row.root.visibility = View.GONE
                return@forEachIndexed
            }
            row.root.visibility = View.VISIBLE
            row.simIcon.setImageResource(if (index == 0) R.drawable.sim1_icon else R.drawable.sim2_icon)
            row.label.text = info.displayName
            val state = monitor.getSimStateForSlotId(info.simSlotIndex)
            val complete = state == TelephonyManager.SIM_STATE_READY
            row.stateIcon.setImageResource(
                if (complete) R.drawable.multi_pin_unlock_checked
                else R.drawable.keyguard_sim_pass_confirm_button
            )
            row.stateIcon.visibility =
                if (complete || info.subscriptionId == currentSubId) View.VISIBLE else View.INVISIBLE
            row.root.alpha = if (info.subscriptionId == currentSubId || complete) 1f else 0.65f
        }
    }

    private fun createRow(index: Int): Row {
        val icon = ImageView(context)
        val label =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
            }
        val state = ImageView(context)
        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.password_input_bg)
                val horizontal = 34
                setPadding(horizontal, 0, horizontal, 0)
                addView(icon, LayoutParams(72, 72))
                addView(label, LayoutParams(0, 132, 1f).apply { marginStart = 30 })
                addView(state, LayoutParams(120, 132))
                layoutParams =
                    LayoutParams(798, 132).apply {
                        if (index > 0) topMargin = 48
                    }
            }
        return Row(row, icon, label, state)
    }
}
