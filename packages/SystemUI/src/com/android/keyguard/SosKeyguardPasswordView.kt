/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.android.keyguard

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import com.android.systemui.keyguard.SosKeyguardRuntime
import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardHostView
import com.android.systemui.res.R

/** Original password presentation while retaining Android 16 IME and credential handling. */
class SosKeyguardPasswordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KeyguardPasswordView(context, attrs) {
    private var stableWidth = 0
    private var stableHeight = 0
    override fun setIsLockScreenLandscapeEnabled() {
        // This layout uses the shared R2 responsive geometry, not AOSP MotionLayout.
    }

    override fun setPasswordEntryEnabled(enabled: Boolean) {
        // Keep the original 9-patch byte-for-byte; AOSP's implementation tints its background.
        findViewById<EditText?>(R.id.passwordEntry)?.let { entry ->
            entry.isEnabled = enabled
            entry.isCursorVisible = enabled
        }
    }

    override fun startAppearAnimation() {
        // Ordinary R2 unlock is already driven frame-for-frame by the host swipe. The original
        // 250 ms SecureViewWrapper animation belongs to UnlockToStart's internal mode switch and
        // must not run again on the password child.
        alpha = 1f
        translationY = 0f
    }

    override fun startDisappearAnimation(finishRunnable: Runnable?): Boolean {
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

    override fun getWrongPasswordStringId(): Int = R.string.easy_password_wrong

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stableWidth = maxOf(stableWidth, w)
        stableHeight = maxOf(stableHeight, h)
        getChildAt(0)?.let {
            SosCredentialLayoutScaler.apply(this, it, stableWidth, stableHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val entry = findViewById<EditText?>(R.id.passwordEntry) ?: return
        val imeSwitch = findViewById<View?>(R.id.switch_ime_button)
        // The immutable original has symmetric 60px padding. Reserve the extra trailing area only
        // when Android 16 actually exposes an IME chooser, so the one-IME golden remains exact.
        val desiredEndPadding =
            if (imeSwitch?.visibility == View.VISIBLE) IME_SWITCH_END_PADDING_PX else ORIGINAL_PADDING_PX
        if (entry.paddingStart != ORIGINAL_PADDING_PX || entry.paddingEnd != desiredEndPadding) {
            entry.setPaddingRelative(
                ORIGINAL_PADDING_PX,
                entry.paddingTop,
                desiredEndPadding,
                entry.paddingBottom,
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        stableWidth = 0
        stableHeight = 0
        super.onConfigurationChanged(newConfig)
    }

    private companion object {
        const val ORIGINAL_PADDING_PX = 60
        const val IME_SWITCH_END_PADDING_PX = 108
    }
}
