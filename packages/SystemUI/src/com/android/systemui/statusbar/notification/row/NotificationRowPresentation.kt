/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.notification.row

import android.view.View
import java.util.concurrent.atomic.AtomicLong

/**
 * One immutable classification plus one generation's safely inflated notification views.
 *
 * The four view references are populated by the existing RemoteViews safety pipeline. They are
 * deliberately kept together so the private contracted/expanded pair can only be committed after
 * the complete generation succeeds.
 */
class NotificationRowPresentation private constructor(
    val generationId: Long,
    val contentType: NotificationContentType,
    val contractedCustom: Boolean,
    val expandedCustom: Boolean,
    val expandedSource: ExpandedSource,
    val targetSdk: Int,
) {
    enum class ExpandedSource {
        NONE,
        CONTENT,
        BIG,
    }

    val isPrivateCustom: Boolean
        get() = contentType == NotificationContentType.CUSTOM

    var safeContractedView: View? = null
    var safeExpandedView: View? = null
    var safeHeadsUpView: View? = null
    var safePublicView: View? = null

    companion object {
        private val nextGeneration = AtomicLong()

        fun create(
            contractedCustom: Boolean,
            expandedCustom: Boolean,
            expandedSource: ExpandedSource,
            targetSdk: Int,
        ): NotificationRowPresentation {
            return NotificationRowPresentation(
                generationId = nextGeneration.incrementAndGet(),
                contentType =
                    if (contractedCustom || expandedCustom) {
                        NotificationContentType.CUSTOM
                    } else {
                        NotificationContentType.STANDARD
                    },
                contractedCustom = contractedCustom,
                expandedCustom = expandedCustom,
                expandedSource = expandedSource,
                targetSdk = targetSdk,
            )
        }

        fun standard(targetSdk: Int = 0): NotificationRowPresentation {
            return create(false, false, ExpandedSource.NONE, targetSdk)
        }
    }
}
