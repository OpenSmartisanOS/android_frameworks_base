/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.app.Notification
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.shared.NotifStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class NotificationPresentationClassifierTest : SysuiTestCase() {
    private val classifier by lazy { NotificationPresentationClassifier(context.resources) }

    @Test
    fun defaultDialerRole_isCall() {
        val notification =
            activeNotificationModel(key = "call", packageName = "example.dialer", userId = 10)
        val roles =
            NotificationRoleSnapshot(dialerHolders = mapOf(10 to setOf("example.dialer")))

        assertThat(classifier.classify(notification, roles))
            .isEqualTo(NotificationPresentationClassifier.Type.CALL)
    }

    @Test
    fun messagingStyle_isMessagingNotSocial() {
        val notification =
            activeNotificationModel(key = "message").copy(style = NotifStyle.Messaging())

        assertThat(classifier.classify(notification, NotificationRoleSnapshot()))
            .isEqualTo(NotificationPresentationClassifier.Type.MESSAGING)
    }

    @Test
    fun conversation_isMessagingNotSocial() {
        val notification = activeNotificationModel(key = "conversation").copy(isConversation = true)

        assertThat(classifier.classify(notification, NotificationRoleSnapshot()))
            .isEqualTo(NotificationPresentationClassifier.Type.MESSAGING)
    }

    @Test
    fun voicemailChannel_isNotEmail() {
        val notification =
            activeNotificationModel(key = "voicemail").copy(channelId = "visual_voicemail")

        assertThat(classifier.classify(notification, NotificationRoleSnapshot()))
            .isEqualTo(NotificationPresentationClassifier.Type.OTHER)
    }

    @Test
    fun boundedEmailChannelToken_isEmail() {
        val notification =
            activeNotificationModel(key = "email").copy(channelId = "priority_email_updates")

        assertThat(classifier.classify(notification, NotificationRoleSnapshot()))
            .isEqualTo(NotificationPresentationClassifier.Type.EMAIL)
    }

    @Test
    fun messageCategoryWinsOverSocialCompatibility() {
        val socialPackage = context.resources.getStringArray(
            com.android.systemui.res.R.array.sos_notification_social_packages
        ).firstOrNull() ?: return
        val notification =
            activeNotificationModel(key = "social-message", packageName = socialPackage)
                .copy(category = Notification.CATEGORY_MESSAGE)

        assertThat(classifier.classify(notification, NotificationRoleSnapshot()))
            .isEqualTo(NotificationPresentationClassifier.Type.MESSAGING)
    }
}
