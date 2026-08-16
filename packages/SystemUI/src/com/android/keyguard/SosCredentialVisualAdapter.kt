/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.keyguard

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.keyguard.SosKeyguardRuntime
import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardHostView
import com.android.systemui.res.R
import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardLayoutModel
import kotlin.math.max
import kotlin.math.min

/**
 * Resolves the non-secret PIN length needed by the original R2 auto-submit UI.
 *
 * The length is stored beside the active synthetic-password protector, so it changes atomically
 * with the credential. An old unversioned secure-setting bridge is deleted and never consulted.
 */
object SosPinLengthRepository {
    private const val SETTING_PIN_LENGTH = "sos_keyguard_pin_length"
    private const val MIN_PIN_LENGTH = LockPatternUtils.MIN_LOCK_PASSWORD_SIZE
    private const val MAX_REASONABLE_PIN_LENGTH = 64

    @JvmStatic
    fun resolve(context: Context, lockPatternUtils: LockPatternUtils, userId: Int): Int {
        val frameworkLength = lockPatternUtils.getPinLength(userId)
        // The synthetic-password protector is the only credential-versioned source of truth.
        // Remove the old unversioned bridge even when Framework is temporarily unavailable; using
        // it could cap input at a previous credential's length and lock the user out.
        clearLegacyMigrationLength(context, userId)
        if (isUsable(frameworkLength)) {
            return frameworkLength
        }
        return LockPatternUtils.PIN_LENGTH_UNAVAILABLE
    }

    @JvmStatic
    fun isUsable(length: Int): Boolean =
        length in MIN_PIN_LENGTH..MAX_REASONABLE_PIN_LENGTH

    @JvmStatic
    fun clearLegacyMigrationLength(context: Context, userId: Int) {
        Settings.Secure.putStringForUser(
            context.contentResolver,
            SETTING_PIN_LENGTH,
            null,
            userId,
        )
    }
}

/** Whole-page timing from the original SecureViewWrapper. */
object SosCredentialTransitionAnimator {
    private val interpolator = PathInterpolator(0f, 0f, 0.21f, 1f)

    @JvmStatic
    fun appear(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = transitionDistance(view)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(interpolator)
            .start()
    }

    @JvmStatic
    fun disappear(view: View, finishRunnable: Runnable?): Boolean {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(transitionDistance(view))
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                finishRunnable?.run()
                view.alpha = 1f
                view.translationY = 0f
            }
            .start()
        return true
    }

    private fun transitionDistance(view: View): Float {
        val sourceHeight = if (view.height > 0) view.height else view.rootView.height
        return sourceHeight / 3f
    }

    const val SHOW_DURATION_MS = 250L
    const val HIDE_DURATION_MS = 150L
}

/** Exact R2 lockout copy: seconds through 60, then ceiling-rounded minutes. */
object SosLockoutMessageFormatter {
    @JvmStatic
    fun format(resources: Resources, secondsRemaining: Int): CharSequence {
        val seconds = secondsRemaining.coerceAtLeast(1)
        if (seconds <= SECONDS_PER_MINUTE) {
            return resources.getQuantityString(R.plurals.kg_pattern_try_later, seconds, seconds)
        }
        val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return resources.getQuantityString(
            R.plurals.kg_pattern_try_later_minute,
            minutes,
            minutes,
        )
    }

    private const val SECONDS_PER_MINUTE = 60
}

/**
 * Visual-only bridge from Android 16 credential views to the original R2 presentation.
 * Credential bytes and verification never pass through this adapter.
 */
class SosCredentialVisualAdapter private constructor(private val root: View) {
    private val pinEntry =
        root.findViewById<SosPasswordTextView?>(R.id.pinEntry)
            ?: root.findViewById(R.id.simPinEntry)
            ?: root.findViewById(R.id.pukEntry)
    private val pinDots = root.findViewById<SosPinDotContainer?>(R.id.password_container)
    private val pinMessage = root.findViewById<SosPinMessageArea?>(R.id.easy_password_tip)
    private val exceptionalConfirm = root.findViewById<View?>(R.id.key_enter)
    private var expectedPinLengthKnown = true

    init {
        pinEntry?.setLengthObserver { length ->
            pinDots?.setEnteredLength(length)
            exceptionalConfirm?.isEnabled =
                !expectedPinLengthKnown && length >= LockPatternUtils.MIN_LOCK_PASSWORD_SIZE
            exceptionalConfirm?.alpha = if (exceptionalConfirm?.isEnabled == true) 1f else 0.4f
        }
    }

    fun setExpectedPinLength(length: Long) {
        expectedPinLengthKnown =
            length <= Int.MAX_VALUE && SosPinLengthRepository.isUsable(length.toInt())
        pinEntry?.setExpectedLength(length.toInt())
        pinDots?.setExpectedLength(length.toInt())
        exceptionalConfirm?.visibility = if (expectedPinLengthKnown) View.GONE else View.VISIBLE
        exceptionalConfirm?.isEnabled =
            !expectedPinLengthKnown &&
                (pinEntry?.text?.length ?: 0) >= LockPatternUtils.MIN_LOCK_PASSWORD_SIZE
        exceptionalConfirm?.alpha = if (exceptionalConfirm?.isEnabled == true) 1f else 0.4f
    }

    fun showCredentialError() {
        pinDots?.showError()
        pinDots?.let(::shake)
        pinMessage?.showTransientError()
        pinMessage?.let(::shake)
    }

    companion object {
        @JvmStatic fun attach(root: View): SosCredentialVisualAdapter =
            SosCredentialVisualAdapter(root)

        @JvmStatic fun shake(view: View) {
            ObjectAnimator.ofFloat(
                    view,
                    View.TRANSLATION_X,
                    0f,
                    -25f,
                    25f,
                    -25f,
                    25f,
                    -25f,
                    25f,
                    0f,
                )
                .apply {
                    duration = ERROR_DURATION_MS
                    start()
                }
        }

        const val ERROR_DURATION_MS = 400L
    }
}

/** Centers one immutable 1080 x 2242 R2 design canvas on any phone/foldable display. */
object SosCredentialLayoutScaler {
    @JvmStatic
    fun apply(root: ViewGroup, designCanvas: View, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val metrics =
            SosKeyguardLayoutModel.calculate(
                SosKeyguardLayoutModel.Input(
                    widthPx = width.toFloat(),
                    heightPx = height.toFloat(),
                    density = root.resources.displayMetrics.density,
                    fontScale = root.resources.configuration.fontScale,
                    scene = SosKeyguardLayoutModel.Scene.SECURITY,
                )
            )
        designCanvas.layoutParams =
            LinearLayout.LayoutParams(DESIGN_WIDTH.toInt(), DESIGN_HEIGHT.toInt())
        designCanvas.pivotX = 0f
        designCanvas.pivotY = 0f
        designCanvas.scaleX = metrics.scale
        designCanvas.scaleY = metrics.scale
        designCanvas.translationX = metrics.credentialCanvas.left
        designCanvas.translationY = metrics.credentialCanvas.top
    }

    private const val DESIGN_WIDTH = 1080f
    private const val DESIGN_HEIGHT = 2242f
}

/** KeyguardPINView contract with the original 400 ms error response. */
class SosKeyguardPINView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KeyguardPINView(context, attrs) {
    private lateinit var visualAdapter: SosCredentialVisualAdapter

    override fun onFinishInflate() {
        super.onFinishInflate()
        visualAdapter = SosCredentialVisualAdapter.attach(this)
    }

    override fun setIsLockScreenLandscapeEnabled(isLockScreenLandscapeEnabled: Boolean) {
        // SOS layouts are responsive portrait-derived layouts and never enter AOSP MotionLayout.
        super.setIsLockScreenLandscapeEnabled(false)
    }

    override fun startErrorAnimation() {
        visualAdapter.showCredentialError()
    }

    override fun getWrongPasswordStringId(): Int = R.string.easy_password_wrong

    override fun startAppearAnimation() {
        SosCredentialTransitionAnimator.appear(this)
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
        // The R2 Host is the sole credential-dismiss animator. If it is no longer attached (for
        // example during a display teardown), complete without exposing the old child-page
        // animation; never substitute an AOSP/legacy visual path.
        finishRunnable?.run()
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // The base implementation injects AOSP phone/fold posture margins into the PIN grid.
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        getChildAt(0)?.let { SosCredentialLayoutScaler.apply(this, it, w, h) }
    }
}

/** Invisible Android 16 credential buffer. The original R2 dot row is a separate view. */
class SosPasswordTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PasswordTextView(context, attrs, defStyleAttr) {
    private var expectedLength = Int.MAX_VALUE
    private var lengthObserver: ((Int) -> Unit)? = null

    fun setExpectedLength(length: Int) {
        expectedLength = if (length >= MIN_PIN_LENGTH) length else Int.MAX_VALUE
        lengthObserver?.invoke(text.length)
    }

    fun setLengthObserver(observer: (Int) -> Unit) {
        lengthObserver = observer
        observer(text.length)
    }

    override fun append(c: Char) {
        if (text.length >= expectedLength) return
        super.append(c)
    }

    override fun onAppend(c: Char, newLength: Int) = lengthObserver?.invoke(newLength) ?: Unit

    override fun onDelete(index: Int) = lengthObserver?.invoke(index) ?: Unit

    override fun onReset(animated: Boolean) = lengthObserver?.invoke(0) ?: Unit

    override fun setUsePinShapes(usePinShapes: Boolean) = Unit

    override fun setIsPinHinting(isPinHinting: Boolean) = Unit

    override fun onDraw(canvas: Canvas) = Unit

    override fun onDetachedFromWindow() {
        lengthObserver = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MIN_PIN_LENGTH = 4
    }
}

/** Original R2 empty/full/error ImageView row. It is the only visible PIN representation. */
class SosPinDotContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var expectedLength = DEFAULT_PIN_LENGTH
    private var expectedLengthKnown = true
    private var enteredLength = 0
    private var error = false

    init {
        gravity = android.view.Gravity.CENTER
        orientation = HORIZONTAL
    }

    fun setExpectedLength(length: Int) {
        expectedLengthKnown = length >= MIN_PIN_LENGTH
        val resolved = if (expectedLengthKnown) length else DEFAULT_PIN_LENGTH
        if (expectedLength == resolved && childCount == resolved) return
        expectedLength = resolved
        rebuildDots()
    }

    fun setEnteredLength(length: Int) {
        if (!expectedLengthKnown) {
            val dynamicLength = max(DEFAULT_PIN_LENGTH, length)
            if (dynamicLength != expectedLength) {
                expectedLength = dynamicLength
                rebuildDots()
            }
        }
        enteredLength = length.coerceIn(0, expectedLength)
        renderDots()
    }

    fun showError() {
        error = true
        renderDots()
        removeCallbacks(clearError)
        postDelayed(clearError, SosCredentialVisualAdapter.ERROR_DURATION_MS)
    }

    private fun rebuildDots() {
        removeAllViews()
        repeat(expectedLength) {
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(DOT_SIZE_PX, DOT_SIZE_PX),
            )
        }
        updateDotMargins(width.takeIf { it > 0 } ?: DESIGN_WIDTH_PX)
        renderDots()
    }

    private fun updateDotMargins(availableWidth: Int) {
        if (childCount == 0) return
        val maxMargin = ORIGINAL_SIDE_MARGIN_PX
        val usableWidth = max(0, availableWidth - DOT_SIZE_PX * childCount)
        val margin =
            if (childCount == 1) 0
            else min(maxMargin, usableWidth / (childCount * 2))
        repeat(childCount) { index ->
            val child = getChildAt(index)
            child.layoutParams =
                (child.layoutParams as LayoutParams).apply {
                    marginStart = margin
                    marginEnd = margin
                }
        }
    }

    private fun renderDots() {
        repeat(childCount) { index ->
            val child = getChildAt(index)
            val drawable =
                when {
                    error -> R.drawable.easy_password_dot_error
                    index < enteredLength -> R.drawable.easy_password_dot_full
                    else -> R.drawable.easy_password_dot_empty
                }
            (child as ImageView).setImageResource(drawable)
        }
    }

    private val clearError = Runnable {
        error = false
        enteredLength = 0
        renderDots()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDotMargins(w)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearError)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DEFAULT_PIN_LENGTH = 4
        private const val MIN_PIN_LENGTH = 4
        private const val DOT_SIZE_PX = 45
        private const val ORIGINAL_SIDE_MARGIN_PX = 36
        private const val DESIGN_WIDTH_PX = 1080
    }
}

/**
 * Original R2 PIN prompt.
 *
 * This is intentionally a plain TextView, not a KeyguardMessageArea. Android 16 keeps a separate
 * invisible bouncer_message_area bridge for controller compatibility; credential errors and the
 * GateKeeper deadline are rendered only here, exactly like the original mTipText field.
 */
class SosPinMessageArea @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {
    private val normalColor = Color.WHITE
    private val errorColor = Color.rgb(0xD4, 0x4D, 0x44)
    private var lockoutActive = false

    fun showTransientError() {
        if (lockoutActive) return
        setTextColor(errorColor)
        text = context.getText(R.string.easy_password_wrong)
        removeCallbacks(resetTransientError)
        postDelayed(resetTransientError, ERROR_RESET_DELAY_MS)
    }

    fun showLockoutMessage(message: CharSequence) {
        removeCallbacks(resetTransientError)
        lockoutActive = true
        setTextColor(errorColor)
        text = message
        visibility = View.VISIBLE
        alpha = 1f
    }

    fun showPrompt() {
        removeCallbacks(resetTransientError)
        lockoutActive = false
        setTextColor(normalColor)
        text = originalPrompt()
        visibility = View.VISIBLE
        alpha = 1f
    }

    private val resetTransientError = Runnable { if (!lockoutActive) showPrompt() }

    private fun originalPrompt(): CharSequence = context.getText(R.string.please_enter_easy_password)

    override fun onDetachedFromWindow() {
        removeCallbacks(resetTransientError)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ERROR_RESET_DELAY_MS = 750L
    }
}

/** Original R2 pattern prompt: fixed colors, no AOSP text cross-fade, and a 400 ms error state. */
class SosPatternMessageArea @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : BouncerKeyguardMessageArea(context, attrs) {
    private var errorActive = false
    private var lockoutActive = false
    private val restorePrompt = Runnable { showPrompt() }

    override fun setMessage(msg: CharSequence?, animate: Boolean) {
        if (lockoutActive) return
        val prompt = context.getText(R.string.please_enter_pattern)
        val resolved = msg.takeUnless { it.isNullOrEmpty() } ?: prompt
        if (resolved == prompt) errorActive = false
        applyOriginalColor()
        // The original TextView changes content immediately; only its X translation shakes.
        super.setMessage(resolved, false)
    }

    fun showPatternError(resetAfter: Boolean) {
        if (lockoutActive) return
        removeCallbacks(restorePrompt)
        errorActive = true
        applyOriginalColor()
        super.setMessage(context.getText(R.string.sos_original_wrong_pattern), false)
        if (resetAfter) postDelayed(restorePrompt, SosCredentialVisualAdapter.ERROR_DURATION_MS)
    }

    fun showLockoutMessage(message: CharSequence) {
        removeCallbacks(restorePrompt)
        lockoutActive = true
        errorActive = true
        applyOriginalColor()
        super.setMessage(message, false)
    }

    fun showPrompt() {
        removeCallbacks(restorePrompt)
        lockoutActive = false
        errorActive = false
        applyOriginalColor()
        super.setMessage(context.getText(R.string.please_enter_pattern), false)
    }

    override fun onThemeChanged() {
        super.onThemeChanged()
        applyOriginalColor()
    }

    private fun applyOriginalColor() {
        val color = if (errorActive) ORIGINAL_ERROR_COLOR else Color.WHITE
        setNextMessageColor(ColorStateList.valueOf(color))
        setTextColor(color)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(restorePrompt)
        super.onDetachedFromWindow()
    }
}

/** Original R2 complex-password prompt with its 750 ms transient error state. */
class SosPasswordMessageArea @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : BouncerKeyguardMessageArea(context, attrs) {
    private var errorActive = false
    private var lockoutActive = false
    private val restorePrompt = Runnable { showPrompt() }

    override fun setMessage(msg: CharSequence?, animate: Boolean) {
        if (lockoutActive) return
        val prompt = context.getText(R.string.please_enter_easy_password)
        val resolved = msg.takeUnless { it.isNullOrEmpty() } ?: prompt
        if (resolved == prompt) errorActive = false
        applyOriginalColor()
        super.setMessage(resolved, false)
    }

    fun showCredentialError(resetAfter: Boolean) {
        if (lockoutActive) return
        removeCallbacks(restorePrompt)
        errorActive = true
        applyOriginalColor()
        super.setMessage(context.getText(R.string.easy_password_wrong), false)
        if (resetAfter) postDelayed(restorePrompt, ERROR_RESET_DELAY_MS)
    }

    fun showLockoutMessage(message: CharSequence) {
        removeCallbacks(restorePrompt)
        lockoutActive = true
        errorActive = true
        applyOriginalColor()
        super.setMessage(message, false)
    }

    fun showPrompt() {
        removeCallbacks(restorePrompt)
        lockoutActive = false
        errorActive = false
        applyOriginalColor()
        super.setMessage(context.getText(R.string.please_enter_easy_password), false)
    }

    override fun onThemeChanged() {
        super.onThemeChanged()
        applyOriginalColor()
    }

    private fun applyOriginalColor() {
        val color = if (errorActive) ORIGINAL_ERROR_COLOR else Color.WHITE
        setNextMessageColor(ColorStateList.valueOf(color))
        setTextColor(color)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(restorePrompt)
        super.onDetachedFromWindow()
    }

    companion object {
        const val ERROR_RESET_DELAY_MS = 750L
    }
}

private val ORIGINAL_ERROR_COLOR = 0xFFD44D44.toInt()

/** NumPadKey input semantics with the original selector doing all visual drawing. */
class SosNumPadKey @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.numPadKeyStyle,
) : NumPadKey(context, attrs, defStyleAttr) {
    override fun dispatchDraw(canvas: Canvas) {
        // Numeric glyph and pressed treatment are already baked into the original selector.
    }

    override fun reloadColors() = Unit
}

/** Delete/confirm button contract without AOSP vector tint replacement. */
class SosNumPadButton(
    context: Context,
    attrs: AttributeSet?,
) : NumPadButton(context, attrs) {
    private val cancelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 42f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    private var cancelMode = false

    fun setCancelMode(cancel: Boolean) {
        if (cancelMode == cancel) return
        cancelMode = cancel
        setBackgroundResource(
            if (cancel) R.drawable.secletor_easy_password_keyboard_emergency
            else R.drawable.secletor_easy_password_keyboard_delete
        )
        contentDescription =
            context.getString(
                if (cancel) R.string.forbid_cancel else R.string.keyboardview_keycode_delete
            )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cancelMode) {
            val baseline = height / 2f - (cancelPaint.ascent() + cancelPaint.descent()) / 2f
            canvas.drawText(context.getString(R.string.forbid_cancel), width / 2f, baseline, cancelPaint)
        }
    }

    override fun reloadColors() = Unit

    override fun setTransparentMode(isTransparentMode: Boolean) = Unit

    override fun setImageResource(resId: Int) = Unit
}
