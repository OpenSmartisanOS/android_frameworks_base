/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.keyguard

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.hardware.biometrics.BiometricSourceType
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.android.systemui.Dependency
import com.android.systemui.res.R

/** Maps Android 16 biometric state to the unmodified R2 FaceID frame animations. */
class SosBiometricAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private enum class FaceState { IDLE, DETECTING, FAILED, RETRY, SUCCESS }

    private var monitor: KeyguardUpdateMonitor? = null
    private var faceState = FaceState.IDLE
    private val hideAnimation = Runnable { showIdle() }
    private val callback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricRunningStateChanged(
                running: Boolean,
                biometricSourceType: BiometricSourceType,
            ) {
                if (biometricSourceType != BiometricSourceType.FACE) return
                if (running) {
                    if (faceState == FaceState.FAILED) showFaceState(FaceState.RETRY)
                    else showFaceState(FaceState.DETECTING)
                } else if (faceState == FaceState.DETECTING) {
                    showIdle()
                }
            }

            override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType) {
                when (biometricSourceType) {
                    BiometricSourceType.FACE -> showFaceState(FaceState.FAILED)
                    BiometricSourceType.FINGERPRINT -> shakeCredentialMessage()
                    else -> Unit
                }
            }

            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType,
                isStrongBiometric: Boolean,
            ) {
                if (biometricSourceType == BiometricSourceType.FACE) {
                    showFaceState(FaceState.SUCCESS)
                }
            }

            override fun onBiometricError(
                msgId: Int,
                errString: String?,
                biometricSourceType: BiometricSourceType,
            ) {
                if (biometricSourceType == BiometricSourceType.FACE) {
                    showFaceState(FaceState.FAILED)
                } else if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                    shakeCredentialMessage()
                }
            }

            override fun onStartedGoingToSleep(why: Int) {
                showIdle()
            }

            override fun onUserSwitching(userId: Int) {
                showIdle()
            }

            override fun onKeyguardVisibilityChanged(visible: Boolean) {
                if (!visible) showIdle()
            }

            override fun onStrongAuthStateChanged(userId: Int) {
                showIdle()
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        monitor = Dependency.get(KeyguardUpdateMonitor::class.java)
        monitor?.registerCallback(callback)
        showIdle()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideAnimation)
        (drawable as? AnimationDrawable)?.stop()
        monitor?.removeCallback(callback)
        monitor = null
        super.onDetachedFromWindow()
    }

    private fun showFaceState(state: FaceState) {
        removeCallbacks(hideAnimation)
        (drawable as? AnimationDrawable)?.stop()
        faceState = state
        val resource =
            when (state) {
                FaceState.DETECTING -> R.drawable.animation_faceid_detecting
                FaceState.FAILED -> R.drawable.animation_faceid_failed
                FaceState.RETRY -> R.drawable.animation_faceid_refresh
                FaceState.SUCCESS -> R.drawable.animation_faceid_success
                FaceState.IDLE -> return showIdle()
            }
        visibility = View.VISIBLE
        setImageResource(resource)
        (drawable as? AnimationDrawable)?.start()
        when (state) {
            FaceState.FAILED -> postDelayed(hideAnimation, FAILED_DURATION_MS)
            FaceState.RETRY -> postDelayed(hideAnimation, RETRY_DURATION_MS)
            FaceState.SUCCESS -> postDelayed(hideAnimation, SUCCESS_DURATION_MS)
            else -> Unit
        }
    }

    private fun showIdle() {
        removeCallbacks(hideAnimation)
        (drawable as? AnimationDrawable)?.stop()
        setImageDrawable(null)
        visibility = View.INVISIBLE
        faceState = FaceState.IDLE
    }

    private fun shakeCredentialMessage() {
        rootView.findViewById<View?>(R.id.bouncer_message_area)?.let {
            SosCredentialVisualAdapter.shake(it)
        }
    }

    companion object {
        private const val FAILED_DURATION_MS = 46L * 16L
        private const val RETRY_DURATION_MS = 91L * 16L
        private const val SUCCESS_DURATION_MS = 41L * 16L
    }
}
