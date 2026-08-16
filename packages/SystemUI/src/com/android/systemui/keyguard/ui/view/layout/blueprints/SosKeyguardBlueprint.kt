/*
 * Copyright (C) 2026 OpenSmartisanOS
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.blueprints

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.sections.AccessibilityActionsSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusBarSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultUdfpsAccessibilityOverlaySection
import com.android.systemui.keyguard.ui.view.layout.sections.SosKeyguardHostSection
import com.android.systemui.keyguard.SosKeyguardRuntime
import javax.inject.Inject

/**
 * SmartisanOS R2 Delta keyguard blueprint.
 *
 * <p>The lockscreen surface is owned by [SosKeyguardHostSection]. Lineage security and biometric
 * state machines remain outside this visual blueprint.
 */
@SysUISingleton
@JvmSuppressWildcards
class SosKeyguardBlueprint
@Inject
constructor(
    accessibilityActionsSection: AccessibilityActionsSection,
    defaultIndicationAreaSection: DefaultIndicationAreaSection,
    defaultStatusBarSection: DefaultStatusBarSection,
    sosKeyguardHostSection: SosKeyguardHostSection,
    aodBurnInSection: AodBurnInSection,
    udfpsAccessibilityOverlaySection: DefaultUdfpsAccessibilityOverlaySection,
) : KeyguardBlueprint {
    override val id: String = ID

    override val sections =
        listOfNotNull(
            accessibilityActionsSection,
            defaultIndicationAreaSection,
            sosKeyguardHostSection,
            // The legacy shade's real keyguard status bar is reparented here. Keep it after the
            // opaque R2 host so Android draws the icons above the restored original layout.
            defaultStatusBarSection,
            aodBurnInSection,
            udfpsAccessibilityOverlaySection,
        )

    companion object {
        const val ID = "sos"
        /** Must be usable before CoreStartable.start() so the first lockscreen frame is SOS. */
        @JvmStatic
        fun isEnabled(context: Context): Boolean = SosKeyguardRuntime.isEnabled(context)
    }
}
