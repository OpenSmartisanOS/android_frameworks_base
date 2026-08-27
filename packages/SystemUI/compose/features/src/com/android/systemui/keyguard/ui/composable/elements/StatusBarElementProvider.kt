/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.composable.elements

import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import javax.inject.Inject

/**
 * Keyguard does not own a second status-bar element.
 *
 * The canonical phone status-bar window remains visible through lockscreen and AOD transitions,
 * so SceneContainer must not inflate a duplicate lockscreen status bar.
 */
class StatusBarElementProvider @Inject constructor() : LockscreenElementProvider {
    override val elements: List<LockscreenElement> = emptyList()
}
