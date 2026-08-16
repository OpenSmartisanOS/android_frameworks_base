/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.keyguard

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.graphics.drawable.AnimationDrawable
import com.android.internal.widget.LockPatternUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class SosBouncerVisualCoordinatorTest : SysuiTestCase() {
    @Test
    fun hiddenOffset_matchesOriginalSecureWrapper() {
        val state = SosBouncerVisualCoordinator.calculate(0f, 2400f)

        assertThat(state.blurProgress).isEqualTo(0f)
        assertThat(state.securityAlpha).isEqualTo(0f)
        assertThat(state.securityTranslationY).isEqualTo(2400f)
    }

    @Test
    fun oneThirdHeightOffset_isFullyVisible() {
        val state = SosBouncerVisualCoordinator.calculate(800f, 2400f)

        assertThat(state.blurProgress).isEqualTo(1f)
        assertThat(state.securityAlpha).isEqualTo(1f)
        assertThat(state.securityTranslationY).isEqualTo(0f)
    }

    @Test
    fun negativeOffset_keepsVisibleAndTracksFinger() {
        val state = SosBouncerVisualCoordinator.calculate(-120f, 2400f)

        assertThat(state.securityAlpha).isEqualTo(1f)
        assertThat(state.securityTranslationY).isEqualTo(-120f)
    }

    @Test
    fun firstTenPercent_matchesOriginalMainPageAnimHelper() {
        val state = SosBouncerVisualCoordinator.calculate(80f, 2400f)

        assertThat(state.blurProgress).isEqualTo(0.1f)
        assertThat(state.timeAlpha).isEqualTo(0f)
        assertThat(state.shortcutTranslation).isEqualTo(150f)
        assertThat(state.pinTranslationY).isEqualTo(-90f)
    }

    @Test
    fun actionUpThresholds_matchOriginalHost() {
        assertThat(SosBouncerVisualCoordinator.settleTarget(101f, -701f, 2400f))
            .isEqualTo(SosBouncerVisualCoordinator.SettleTarget.BOUNCER)
        assertThat(SosBouncerVisualCoordinator.settleTarget(99f, -701f, 2400f))
            .isEqualTo(SosBouncerVisualCoordinator.SettleTarget.MAIN)
        assertThat(SosBouncerVisualCoordinator.settleTarget(600f, 0f, 2400f))
            .isEqualTo(SosBouncerVisualCoordinator.SettleTarget.BOUNCER)
        assertThat(SosBouncerVisualCoordinator.settleTarget(900f, 701f, 2400f))
            .isEqualTo(SosBouncerVisualCoordinator.SettleTarget.MAIN)
    }

    @Test
    fun settleDurations_matchOriginalBounds() {
        assertThat(SosBouncerVisualCoordinator.showDurationMillis(800f, -10_000f, 2400f))
            .isEqualTo(250L)
        assertThat(SosBouncerVisualCoordinator.showDurationMillis(800f, -1_000f, 2400f))
            .isEqualTo(600L)
        assertThat(SosBouncerVisualCoordinator.hideDurationMillis(400f, 0f))
            .isEqualTo(300L)
    }

    @Test
    fun noSecurityRevealAlpha_matchesOriginalArrayAndOvershootClamp() {
        assertThat(SosBouncerVisualCoordinator.noSecurityRevealAlpha(0f, 2400)).isEqualTo(255)
        assertThat(SosBouncerVisualCoordinator.noSecurityRevealAlpha(1200f, 2400)).isEqualTo(128)
        assertThat(SosBouncerVisualCoordinator.noSecurityRevealAlpha(2399f, 2400)).isEqualTo(1)
        assertThat(SosBouncerVisualCoordinator.noSecurityRevealAlpha(2600f, 2400)).isEqualTo(1)
    }

    @Test
    fun allCredentialModes_inflateAndRetainAndroid16ControllerContracts() {
        val themedContext = ContextThemeWrapper(context, R.style.SosKeyguardBouncerTheme)
        val inflater = LayoutInflater.from(themedContext)
        val layouts =
            listOf(
                R.layout.sos_keyguard_pin_view to R.id.pinEntry,
                R.layout.sos_keyguard_pattern_view to R.id.lockPatternView,
                R.layout.sos_keyguard_password_view to R.id.passwordEntry,
                R.layout.sos_keyguard_sim_pin_view to R.id.simPinEntry,
                R.layout.sos_keyguard_sim_puk_view to R.id.pukEntry,
            )

        layouts.forEach { (layout, credentialEntry) ->
            val root = inflater.inflate(layout, null, false)
            assertThat(root.findViewById<View>(credentialEntry)).isNotNull()
            assertThat(root.findViewById<View>(R.id.bouncer_message_area)).isNotNull()
            assertThat(root.findViewById<View>(R.id.bouncer_message_view)).isNotNull()
            assertThat(root.findViewById<View>(R.id.keyguard_selector_fade_container)).isNotNull()
            if (layout == R.layout.sos_keyguard_sim_pin_view ||
                layout == R.layout.sos_keyguard_sim_puk_view) {
                assertThat(root.findViewById<View>(R.id.sos_sim_status)).isNotNull()
            }

            // Both portrait and landscape-sized hosts must use the same responsive R2 canvas.
            measureAndLayout(root, 1080, 2242)
            measureAndLayout(root, 2242, 1080)
        }
    }

    @Test
    fun landscapeFeatureFlag_doesNotSwitchSosViewsToAospMotionLayout() {
        val themedContext = ContextThemeWrapper(context, R.style.SosKeyguardBouncerTheme)
        val inflater = LayoutInflater.from(themedContext)
        val pin = inflater.inflate(R.layout.sos_keyguard_pin_view, null) as SosKeyguardPINView
        val pattern =
            inflater.inflate(R.layout.sos_keyguard_pattern_view, null) as SosKeyguardPatternView

        pin.setIsLockScreenLandscapeEnabled(false)
        pin.setIsLockScreenLandscapeEnabled(true)
        pattern.setIsLockScreenLandscapeEnabled(false)
        pattern.setIsLockScreenLandscapeEnabled(true)

        assertThat(pin.findViewById<View>(R.id.pin_container)).isNotNull()
        assertThat(pattern.findViewById<View>(R.id.pattern_container)).isNotNull()
    }

    @Test
    fun pinPage_usesOnlyOriginalDotRowAndOriginalBottomKeys() {
        val themedContext = ContextThemeWrapper(context, R.style.SosKeyguardBouncerTheme)
        val pin =
            LayoutInflater.from(themedContext)
                .inflate(R.layout.sos_keyguard_pin_view, null) as SosKeyguardPINView
        val entry = pin.findViewById<SosPasswordTextView>(R.id.pinEntry)
        val dots = pin.findViewById<SosPinDotContainer>(R.id.password_container)
        val enter = pin.findViewById<View>(R.id.key_enter)
        val emergency = pin.findViewById<View>(R.id.emergency_call_button)

        SosCredentialVisualAdapter.attach(pin).setExpectedPinLength(6)
        entry.append('1')
        entry.append('2')

        assertThat(enter.visibility).isEqualTo(View.GONE)
        assertThat(emergency.visibility).isEqualTo(View.VISIBLE)
        assertThat(dots.childCount).isEqualTo(6)
        assertThat((entry as ViewGroup).childCount).isEqualTo(0)
        assertThat(entry.text).isEqualTo("12")
    }

    @Test
    fun pinPage_missingLengthStaysR2AndOffersExceptionalConfirmation() {
        val themedContext = ContextThemeWrapper(context, R.style.SosKeyguardBouncerTheme)
        val pin =
            LayoutInflater.from(themedContext)
                .inflate(R.layout.sos_keyguard_pin_view, null) as SosKeyguardPINView
        val entry = pin.findViewById<SosPasswordTextView>(R.id.pinEntry)
        val dots = pin.findViewById<SosPinDotContainer>(R.id.password_container)
        val enter = pin.findViewById<View>(R.id.key_enter)

        SosCredentialVisualAdapter.attach(pin)
            .setExpectedPinLength(LockPatternUtils.PIN_LENGTH_UNAVAILABLE.toLong())

        assertThat(enter.visibility).isEqualTo(View.VISIBLE)
        assertThat(enter.isEnabled).isFalse()
        repeat(4) { entry.append(('1'.code + it).toChar()) }
        assertThat(enter.isEnabled).isTrue()
        assertThat(dots.childCount).isEqualTo(4)
        entry.append('5')
        assertThat(dots.childCount).isEqualTo(5)
    }

    @Test
    fun pinLengthValidation_acceptsAndroidPinsButRejectsMissingMetadata() {
        assertThat(SosPinLengthRepository.isUsable(4)).isTrue()
        assertThat(SosPinLengthRepository.isUsable(6)).isTrue()
        assertThat(SosPinLengthRepository.isUsable(16)).isTrue()
        assertThat(SosPinLengthRepository.isUsable(-1)).isFalse()
        assertThat(SosPinLengthRepository.isUsable(3)).isFalse()
        assertThat(SosPinLengthRepository.isUsable(65)).isFalse()
    }

    @Test
    fun faceAnimations_matchOriginalFrameCountsAndTiming() {
        val cases =
            listOf(
                Triple(R.drawable.animation_faceid_detecting, 91, false),
                Triple(R.drawable.animation_faceid_failed, 46, true),
                Triple(R.drawable.animation_faceid_refresh, 91, true),
                Triple(R.drawable.animation_faceid_success, 41, true),
            )

        cases.forEach { (resource, frames, oneShot) ->
            val animation = context.getDrawable(resource) as AnimationDrawable
            assertThat(animation.numberOfFrames).isEqualTo(frames)
            assertThat(animation.isOneShot).isEqualTo(oneShot)
            repeat(frames) { assertThat(animation.getDuration(it)).isEqualTo(16) }
        }
    }

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }
}
