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

import android.content.res.Resources
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A place to define the blocklist/allowlist for home status bar icons */
@SysUISingleton
class HomeStatusBarIconBlockListInteractor
@Inject
constructor(@Main res: Resources) {
    private val defaultBlockedIcons =
        res.getStringArray(R.array.config_collapsed_statusbar_icon_blocklist)

    private val volumeSlot = res.getString(com.android.internal.R.string.status_bar_volume)

    /** The factory status bar always owns and displays its shared silent/vibrate slot. */
    val iconBlockList: Flow<List<String>> =
        flowOf(defaultBlockedIcons.filterNot { it == volumeSlot })
}
