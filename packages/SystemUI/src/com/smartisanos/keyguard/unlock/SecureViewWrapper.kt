/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.smartisanos.keyguard.unlock

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.RelativeLayout

/**
 * Original SecureViewWrapper geometry without the obsolete private authentication state machine.
 */
class SecureViewWrapper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    private val originalInterpolator = PathInterpolator(0f, 0f, 0.21f, 1f)

    fun showOriginalAnimation() {
        animate().cancel()
        visibility = View.VISIBLE
        alpha = 0f
        translationY = height / 3f
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(originalInterpolator)
            .start()
    }

    fun hideOriginalAnimation(endAction: (() -> Unit)? = null) {
        animate().cancel()
        animate()
            .alpha(0f)
            .translationY(height / 3f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(originalInterpolator)
            .withEndAction {
                visibility = View.INVISIBLE
                endAction?.invoke()
            }
            .start()
    }

    companion object {
        const val SHOW_DURATION_MS = 250L
        const val HIDE_DURATION_MS = 150L
    }
}
