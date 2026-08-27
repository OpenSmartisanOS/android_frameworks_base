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

package com.android.systemui.statusbar.pipeline.shared.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeStatusBarIconBlockListInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    private val Kosmos.underTest by Kosmos.Fixture { kosmos.homeStatusBarIconBlockListInteractor }

    @Test
    fun iconBlockList_containsResources() =
        kosmos.runTest {
            // GIVEN a list of blocked icons
            overrideResource(
                R.array.config_collapsed_statusbar_icon_blocklist,
                arrayOf("test1", "test2"),
            )

            val latest by collectLastValue(underTest.iconBlockList)

            // THEN the volume is not the blocklist
            assertThat(latest).containsExactly("test1", "test2")
        }

    @Test
    fun iconBlockList_neverBlocksFactoryVolumeSlot() =
        kosmos.runTest {
            // GIVEN a list of blocked icons
            overrideResource(
                R.array.config_collapsed_statusbar_icon_blocklist,
                arrayOf("test1", "volume", "test2"),
            )

            val latest by collectLastValue(underTest.iconBlockList)

            // R2 owns the shared silent/vibrate icon and never lets the A16 setting suppress it.
            assertThat(latest).containsExactly("test1", "test2")
        }
}
