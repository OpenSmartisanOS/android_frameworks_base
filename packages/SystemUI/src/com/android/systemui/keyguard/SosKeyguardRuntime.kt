/* Copyright (C) 2026 OpenSmartisanOS. Licensed under the Apache License, Version 2.0. */
package com.android.systemui.keyguard

import android.app.ActivityManager
import android.content.Context
import android.os.UserHandle
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for the R2 keyguard runtime gate. */
object SosKeyguardRuntime {
    private const val NO_CREDENTIAL_GENERATION = 0L
    private const val NO_CREDENTIAL_USER = UserHandle.USER_NULL
    private val originalUnlockAnimationCompleted = AtomicBoolean(false)
    private val nextCredentialGeneration = AtomicLong(NO_CREDENTIAL_GENERATION)
    private val activeCredentialGeneration = AtomicLong(NO_CREDENTIAL_GENERATION)
    private val activeCredentialUser = AtomicInteger(NO_CREDENTIAL_USER)
    private val credentialReadyForKeyguardDone = AtomicBoolean(false)

    /**
     * Framework prepares keyguard_background before the drag. R2 must not start Android's visual
     * LOCKSCREEN -> GONE transition until its own curtain has reached the top, so keep that final
     * hand-off separate from the interactive presentation state.
     */
    enum class OriginalInteractiveTransitionPhase {
        IDLE,
        PREVIEW,
        CREDENTIAL_CURTAIN,
        AUTHENTICATED_PREPARING,
        CANCELLING,
        COMMITTING,
    }

    private val _originalInteractiveTransitionPhase =
        MutableStateFlow(OriginalInteractiveTransitionPhase.IDLE)
    val originalInteractiveTransitionPhase =
        _originalInteractiveTransitionPhase.asStateFlow()

    @JvmStatic
    fun getOriginalInteractiveTransitionPhaseValue(): OriginalInteractiveTransitionPhase =
        _originalInteractiveTransitionPhase.value

    /**
     * True only while the default-display R2 lockscreen is actually being presented and the
     * panel is awake.  The regular status bar consumes this instead of trying to infer R2
     * visibility from a transient KeyguardState/Scene value.  In particular, cold boot and AOD
     * exit can briefly report transitional states even though the lockscreen window is already
     * visible.
     */
    private val _awakeLockscreenPresented = MutableStateFlow(false)
    val awakeLockscreenPresented = _awakeLockscreenPresented.asStateFlow()

    @JvmStatic
    fun setAwakeLockscreenPresented(presented: Boolean) {
        _awakeLockscreenPresented.value = presented
    }

    @JvmStatic
    @Synchronized
    fun beginOriginalInteractiveTransition() {
        // A no-credential gesture must never replace an authenticated curtain that is still
        // waiting for its real WMS hand-off.
        if (activeCredentialGeneration.get() != NO_CREDENTIAL_GENERATION) return
        activeCredentialGeneration.set(NO_CREDENTIAL_GENERATION)
        activeCredentialUser.set(NO_CREDENTIAL_USER)
        credentialReadyForKeyguardDone.set(false)
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.PREVIEW
    }

    /** Starts the original 300 ms credential curtain and returns its unique session generation. */
    @JvmStatic
    fun beginOriginalCredentialTransition(): Long =
        beginOriginalCredentialTransition(ActivityManager.getCurrentUser())

    @JvmStatic
    @Synchronized
    fun beginOriginalCredentialTransition(userId: Int): Long {
        val generation = nextCredentialGeneration.incrementAndGet()
        activeCredentialGeneration.set(generation)
        activeCredentialUser.set(userId)
        credentialReadyForKeyguardDone.set(false)
        originalUnlockAnimationCompleted.set(false)
        _originalInteractiveTransitionPhase.value =
            OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN
        return generation
    }

    /** Advances the active credential curtain to its 150 ms surface preparation point. */
    @JvmStatic
    @Synchronized
    fun prepareOriginalCredentialTransition(generation: Long): Boolean {
        if (generation == NO_CREDENTIAL_GENERATION ||
            activeCredentialGeneration.get() != generation ||
            _originalInteractiveTransitionPhase.value !=
                OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN
        ) {
            return false
        }
        _originalInteractiveTransitionPhase.value =
            OriginalInteractiveTransitionPhase.AUTHENTICATED_PREPARING
        return true
    }

    /** Opens Android's final hand-off gate only after the original curtain reaches 300 ms. */
    @JvmStatic
    @Synchronized
    fun commitOriginalCredentialTransition(generation: Long): Boolean {
        if (generation == NO_CREDENTIAL_GENERATION ||
            activeCredentialGeneration.get() != generation
        ) {
            return false
        }
        val phase = _originalInteractiveTransitionPhase.value
        if (phase != OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN &&
            phase != OriginalInteractiveTransitionPhase.AUTHENTICATED_PREPARING
        ) {
            return false
        }
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.COMMITTING
        return true
    }

    @JvmStatic
    @Synchronized
    fun cancelOriginalCredentialTransition(generation: Long): Boolean {
        if (generation == NO_CREDENTIAL_GENERATION ||
            activeCredentialGeneration.get() != generation
        ) {
            return false
        }
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.CANCELLING
        credentialReadyForKeyguardDone.set(false)
        originalUnlockAnimationCompleted.set(false)
        return true
    }

    @JvmStatic
    fun getOriginalCredentialGeneration(): Long = activeCredentialGeneration.get()

    @JvmStatic
    fun getOriginalCredentialUserId(): Int = activeCredentialUser.get()

    @JvmStatic
    fun isOriginalCredentialSession(generation: Long, userId: Int): Boolean =
        generation != NO_CREDENTIAL_GENERATION &&
            activeCredentialGeneration.get() == generation &&
            activeCredentialUser.get() == userId &&
            isOriginalCredentialTransitionActive()

    @JvmStatic
    fun isOriginalCredentialTransitionActive(): Boolean =
        activeCredentialGeneration.get() != NO_CREDENTIAL_GENERATION &&
            when (_originalInteractiveTransitionPhase.value) {
                OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN,
                OriginalInteractiveTransitionPhase.AUTHENTICATED_PREPARING,
                OriginalInteractiveTransitionPhase.COMMITTING -> true
                OriginalInteractiveTransitionPhase.IDLE,
                OriginalInteractiveTransitionPhase.PREVIEW,
                OriginalInteractiveTransitionPhase.CANCELLING -> false
            }

    @JvmStatic
    fun isOriginalCredentialBeforeCommit(): Boolean =
        when (_originalInteractiveTransitionPhase.value) {
            OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN,
            OriginalInteractiveTransitionPhase.AUTHENTICATED_PREPARING -> true
            OriginalInteractiveTransitionPhase.IDLE,
            OriginalInteractiveTransitionPhase.PREVIEW,
            OriginalInteractiveTransitionPhase.CANCELLING,
            OriginalInteractiveTransitionPhase.COMMITTING -> false
        }

    /** Records an early Android ready callback without allowing it to finish Keyguard. */
    @JvmStatic
    fun deferReadyForKeyguardDone(): Boolean {
        if (!isOriginalCredentialBeforeCommit()) return false
        credentialReadyForKeyguardDone.set(true)
        return true
    }

    @JvmStatic
    fun consumeDeferredReadyForKeyguardDone(generation: Long): Boolean {
        if (activeCredentialGeneration.get() != generation) return false
        return credentialReadyForKeyguardDone.getAndSet(false)
    }

    @JvmStatic
    fun cancelOriginalInteractiveTransition() {
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.CANCELLING
    }

    @JvmStatic
    fun commitOriginalInteractiveTransition() {
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.COMMITTING
    }

    /** True while Android is only performing the final, non-visual Keyguard hand-off. */
    @JvmStatic
    fun isOriginalCommitInProgress(): Boolean =
        _originalInteractiveTransitionPhase.value == OriginalInteractiveTransitionPhase.COMMITTING

    @JvmStatic
    fun finishOriginalInteractiveTransition() {
        // This compatibility entry point belongs to no-credential gestures. Credential sessions
        // must be completed with their generation so a stale remote callback cannot erase a newer
        // authenticated curtain.
        if (activeCredentialGeneration.get() != NO_CREDENTIAL_GENERATION) return
        credentialReadyForKeyguardDone.set(false)
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.IDLE
    }

    /** Clears exactly one credential session and ignores stale WMS/transaction callbacks. */
    @JvmStatic
    @Synchronized
    fun finishOriginalCredentialTransition(generation: Long): Boolean {
        if (generation == NO_CREDENTIAL_GENERATION ||
            activeCredentialGeneration.get() != generation
        ) {
            return false
        }
        activeCredentialGeneration.set(NO_CREDENTIAL_GENERATION)
        activeCredentialUser.set(NO_CREDENTIAL_USER)
        credentialReadyForKeyguardDone.set(false)
        _originalInteractiveTransitionPhase.value = OriginalInteractiveTransitionPhase.IDLE
        return true
    }

    @JvmStatic
    fun blocksAndroidGoneTransition(): Boolean =
        when (_originalInteractiveTransitionPhase.value) {
            OriginalInteractiveTransitionPhase.PREVIEW,
            OriginalInteractiveTransitionPhase.CREDENTIAL_CURTAIN,
            OriginalInteractiveTransitionPhase.AUTHENTICATED_PREPARING,
            OriginalInteractiveTransitionPhase.CANCELLING -> true
            OriginalInteractiveTransitionPhase.IDLE,
            OriginalInteractiveTransitionPhase.COMMITTING -> false
        }

    @JvmStatic
    fun isEnabled(context: Context?): Boolean =
        context != null && context.displayId == Display.DEFAULT_DISPLAY

    @JvmStatic
    fun isEnabledForDisplay(displayId: Int): Boolean = displayId == Display.DEFAULT_DISPLAY

    /**
     * The R2 host has already animated the complete no-credential unlock before asking Android 16
     * to release keyguard.  Keep this as a short-lived, one-shot hand-off so the remote-animation
     * controller exposes the launcher surface without adding its canned scale/translation pass.
     */
    @JvmStatic
    fun markOriginalUnlockAnimationCompleted() {
        originalUnlockAnimationCompleted.set(true)
    }

    /** Clears an abandoned hand-off so a later unrelated unlock cannot consume its one-shot. */
    @JvmStatic
    fun clearOriginalUnlockAnimationCompletion() {
        originalUnlockAnimationCompleted.set(false)
    }

    @JvmStatic
    fun isOriginalUnlockAnimationCompletionPending(): Boolean =
        originalUnlockAnimationCompleted.get()

    @JvmStatic
    fun consumeOriginalUnlockAnimationCompletion(): Boolean =
        originalUnlockAnimationCompleted.getAndSet(false)
}
