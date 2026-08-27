/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.view.layout.sections

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import javax.inject.Inject

/** A section for the status bar displayed at the top of the lockscreen. */
class DefaultStatusBarSection
@Inject
constructor() : KeyguardSection() {

    // The lockscreen reuses the canonical phone status-bar window and owns no duplicate view.
    override fun addViews(constraintLayout: ConstraintLayout) = Unit

    override fun bindData(constraintLayout: ConstraintLayout) = Unit

    override fun applyConstraints(constraintSet: ConstraintSet) = Unit

    override fun removeViews(constraintLayout: ConstraintLayout) = Unit
}
