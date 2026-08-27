/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiWarningDelayTest {
    @Test
    fun warningEntryDelayed500ms_exitImmediate() = runTest {
        val source = MutableStateFlow(false)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.delayR2WifiWarning().toList(values)
        }

        assertThat(values.last()).isFalse()

        source.value = true
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertThat(values.last()).isFalse()

        advanceTimeBy(1)
        runCurrent()
        assertThat(values.last()).isTrue()

        source.value = false
        runCurrent()
        assertThat(values.last()).isFalse()
    }

    @Test
    fun duplicateEligibleState_doesNotRestartDelay() = runTest {
        val source = MutableSharedFlow<Boolean>(replay = 1)
        source.emit(false)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.delayR2WifiWarning().toList(values)
        }

        source.emit(true)
        runCurrent()
        advanceTimeBy(300)
        source.emit(true)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertThat(values.last()).isTrue()
    }
}
