/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.shade

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.LauncherProxyService
import com.android.systemui.SysuiTestCase
import com.android.systemui.fragments.FragmentService
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationsQSContainerControllerTest : SysuiTestCase() {

    private val view = mock<NotificationsQuickSettingsContainer>()
    private val headerController = mock<ShadeHeaderController>()
    private lateinit var underTest: NotificationsQSContainerController

    @Before
    fun setUp() {
        whenever(view.context).thenReturn(mContext)
        whenever(view.resources).thenReturn(mContext.resources)
        underTest =
            NotificationsQSContainerController(
                view,
                mock<NavigationModeController>(),
                mock<LauncherProxyService>(),
                headerController,
                mock<ShadeInteractor>(),
                mock<FragmentService>(),
                mock<ActivityStarter>(),
                mock<DelayableExecutor>(),
                mock<NotificationStackScrollLayoutController>(),
            )
    }

    @Test
    fun customizerAnimationUsesCanonicalShadeHeader() {
        underTest.setCustomizerShowing(true, 300L)

        verify(headerController).startCustomizingAnimation(true, 300L)
    }

    @Test
    fun customizerAnimatingInvalidatesCanonicalContainer() {
        underTest.setCustomizerAnimating(true)

        verify(view).invalidate()
    }
}
