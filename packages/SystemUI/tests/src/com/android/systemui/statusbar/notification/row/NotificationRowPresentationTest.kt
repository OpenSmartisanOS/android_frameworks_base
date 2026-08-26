/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.statusbar.notification.row

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationRowPresentationTest {
    @Test
    fun customPair_isOneGenerationWithExactExpandedSource() {
        val presentation =
            NotificationRowPresentation.create(
                contractedCustom = true,
                expandedCustom = true,
                expandedSource = NotificationRowPresentation.ExpandedSource.BIG,
                targetSdk = 23,
            )

        assertThat(presentation.contentType).isEqualTo(NotificationContentType.CUSTOM)
        assertThat(presentation.isPrivateCustom).isTrue()
        assertThat(presentation.expandedSource)
            .isEqualTo(NotificationRowPresentation.ExpandedSource.BIG)
        assertThat(presentation.targetSdk).isEqualTo(23)
    }

    @Test
    fun generations_areStrictlyIncreasing() {
        val first = NotificationRowPresentation.standard(35)
        val second = NotificationRowPresentation.standard(35)

        assertThat(second.generationId).isGreaterThan(first.generationId)
        assertThat(second.contentType).isEqualTo(NotificationContentType.STANDARD)
    }
}
