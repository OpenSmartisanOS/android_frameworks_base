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

import android.content.res.mainResources
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.res.R

val Kosmos.homeStatusBarIconBlockListInteractor by
    Kosmos.Fixture { HomeStatusBarIconBlockListInteractor(mainResources) }

/**
 * [icons] can be a list of icons that should appear on the blocklist. Note that this should be
 * called before instantiating your class dependent on this list, since it overrides resources and
 * is not reactive to resource changes.
 */
suspend fun Kosmos.setHomeStatusBarIconBlockList(icons: List<String>) {
    testCase.overrideResource(
        R.array.config_collapsed_statusbar_icon_blocklist,
        icons.toTypedArray(),
    )
}
