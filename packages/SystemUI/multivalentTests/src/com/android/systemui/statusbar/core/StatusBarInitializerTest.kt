/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.core

import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.data.repository.fakeStatusBarModePerDisplayRepository
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory
import com.android.systemui.statusbar.policy.statusBarConfigurationController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarInitializerTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val windowController = mock(StatusBarWindowController::class.java)
    private val backgroundView = mock(ViewGroup::class.java)
    private val statusBarRootFactory = mock(StatusBarRootFactory::class.java)
    private val statusBarRoot = mock(androidx.compose.ui.platform.ComposeView::class.java)
    private val statusBarModePerDisplayRepository = kosmos.fakeStatusBarModePerDisplayRepository

    @Before
    fun setup() {
        whenever(windowController.backgroundView).thenReturn(backgroundView)
        whenever(statusBarRootFactory.create(any(), any())).thenReturn(statusBarRoot)
    }

    val underTest =
        StatusBarInitializerImpl(
            statusBarWindowController = windowController,
            statusBarRootFactory = statusBarRootFactory,
            componentFactory = mock(HomeStatusBarComponent.Factory::class.java),
            lifecycleListeners = setOf(),
            statusBarModePerDisplayRepository = statusBarModePerDisplayRepository,
            statusBarConfigurationController = kosmos.statusBarConfigurationController,
        )

    @Test
    fun startsCanonicalStatusBar() {
        underTest.start()
        assertThat(underTest.initialized).isTrue()
        verify(backgroundView).addView(statusBarRoot)
    }

    @Test
    fun repeatedInitializationIsIdempotent() {
        underTest.start()
        underTest.initializeStatusBar()

        verify(backgroundView).addView(statusBarRoot)
    }

    @Test
    fun stopRemovesCanonicalRoot() {
        underTest.start()
        whenever(statusBarRoot.parent).thenReturn(backgroundView)
        underTest.stop()

        verify(backgroundView).removeView(statusBarRoot)
        assertThat(underTest.initialized).isFalse()
    }
}
