/* Copyright (C) 2026 OpenSmartisanOS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.ComponentName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SosKeyguardSecurityBoundaryTest {
    @Test
    fun pinRoot_acceptsOnlyValidRealUnprotectedSnapshot() {
        assertThat(
                SosKeyguardPinnedTaskController.isSafeTaskSnapshotProperties(
                    isBufferValid = true,
                    isRealSnapshot = true,
                    hasProtectedContent = false,
                )
            )
            .isTrue()

        assertThat(
                SosKeyguardPinnedTaskController.isSafeTaskSnapshotProperties(
                    isBufferValid = false,
                    isRealSnapshot = true,
                    hasProtectedContent = false,
                )
            )
            .isFalse()
        assertThat(
                SosKeyguardPinnedTaskController.isSafeTaskSnapshotProperties(
                    isBufferValid = true,
                    isRealSnapshot = false,
                    hasProtectedContent = false,
                )
            )
            .isFalse()
        assertThat(
                SosKeyguardPinnedTaskController.isSafeTaskSnapshotProperties(
                    isBufferValid = true,
                    isRealSnapshot = true,
                    hasProtectedContent = true,
                )
            )
            .isFalse()
    }

    @Test
    fun pinRoot_requiresSnapshotComponentToMatchPinnedTask() {
        val expected = ComponentName("com.example.notes", "com.example.notes.MainActivity")
        val reusedTask = ComponentName("com.example.camera", "com.example.camera.MainActivity")

        assertThat(
                SosKeyguardPinnedTaskController.isSafeTaskSnapshotIdentity(expected, expected)
            )
            .isTrue()
        assertThat(
                SosKeyguardPinnedTaskController.isSafeTaskSnapshotIdentity(expected, reusedTask)
            )
            .isFalse()
        assertThat(SosKeyguardPinnedTaskController.isSafeTaskSnapshotIdentity(expected, null))
            .isFalse()
    }

    @Test
    fun quickAction_requiresExactGenerationUserAndAttachedHost() {
        assertThat(
                SosKeyguardHostView.isQuickActionSessionCurrent(
                    expectedGeneration = 4,
                    currentGeneration = 4,
                    expectedUserId = 10,
                    currentUserId = 10,
                    attached = true,
                )
            )
            .isTrue()
        assertThat(
                SosKeyguardHostView.isQuickActionSessionCurrent(4, 5, 10, 10, true)
            )
            .isFalse()
        assertThat(
                SosKeyguardHostView.isQuickActionSessionCurrent(4, 4, 10, 11, true)
            )
            .isFalse()
        assertThat(
                SosKeyguardHostView.isQuickActionSessionCurrent(4, 4, 10, 10, false)
            )
            .isFalse()
    }

    @Test
    fun wallpaperPendingGeneration_neverCountsAsReady() {
        assertThat(
                SosKeyguardHostView.isWallpaperFrameReady(
                    state = SosKeyguardHostView.WallpaperLoadState.PENDING,
                    stateGeneration = 7,
                    currentGeneration = 7,
                    usingOpaqueFallback = true,
                    hasBitmap = true,
                    installedGeneration = 7,
                )
            )
            .isFalse()
    }

    @Test
    fun wallpaperReady_requiresCurrentRealBitmap_terminalFailureMayRevealFallback() {
        assertThat(
                SosKeyguardHostView.isWallpaperFrameReady(
                    state = SosKeyguardHostView.WallpaperLoadState.READY,
                    stateGeneration = 7,
                    currentGeneration = 7,
                    usingOpaqueFallback = false,
                    hasBitmap = true,
                    installedGeneration = 7,
                )
            )
            .isTrue()
        assertThat(
                SosKeyguardHostView.isWallpaperFrameReady(
                    state = SosKeyguardHostView.WallpaperLoadState.READY,
                    stateGeneration = 6,
                    currentGeneration = 7,
                    usingOpaqueFallback = false,
                    hasBitmap = true,
                    installedGeneration = 6,
                )
            )
            .isFalse()
        assertThat(
                SosKeyguardHostView.isWallpaperFrameReady(
                    state = SosKeyguardHostView.WallpaperLoadState.FAILED,
                    stateGeneration = 7,
                    currentGeneration = 7,
                    usingOpaqueFallback = true,
                    hasBitmap = true,
                    installedGeneration = 7,
                )
            )
            .isTrue()
    }

    @Test
    fun wallpaperRetry_switchesFromFastBootRetriesToPersistentSlowRetry() {
        assertThat(SosKeyguardHostView.wallpaperRetryDelayForAttempt(1)).isEqualTo(150L)
        assertThat(SosKeyguardHostView.wallpaperRetryDelayForAttempt(2)).isEqualTo(500L)
        assertThat(SosKeyguardHostView.wallpaperRetryDelayForAttempt(3)).isEqualTo(1_500L)
        assertThat(SosKeyguardHostView.wallpaperRetryDelayForAttempt(4)).isEqualTo(3_000L)
        assertThat(SosKeyguardHostView.wallpaperRetryDelayForAttempt(5)).isEqualTo(2_000L)
        assertThat(SosKeyguardHostView.wallpaperRetryDelayForAttempt(100)).isEqualTo(2_000L)
    }
}
