/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.annotation.WorkerThread
import android.app.Notification
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.UserInfo
import android.content.res.Resources
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.notification.shared.NotifStyle
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable role-holder cache used by the R2 notification presentation path. */
data class NotificationRoleSnapshot(
    val dialerHolders: Map<Int, Set<String>> = emptyMap(),
    val smsHolders: Map<Int, Set<String>> = emptyMap(),
) {
    fun isDialer(userId: Int, packageName: String): Boolean =
        packageName in dialerHolders[userId].orEmpty()

    fun isSms(userId: Int, packageName: String): Boolean =
        packageName in smsHolders[userId].orEmpty()
}

/** Keeps RoleManager Binder calls off the notification-rendering hot path. */
@SysUISingleton
class NotificationRoleRepository
@Inject
constructor(
    private val roleManager: RoleManager,
    private val userTracker: UserTracker,
    @Background private val backgroundExecutor: Executor,
) {
    private val refreshGeneration = AtomicInteger()
    private val _roles = MutableStateFlow(NotificationRoleSnapshot())
    val roles: StateFlow<NotificationRoleSnapshot> = _roles.asStateFlow()

    private val roleListener =
        OnRoleHoldersChangedListener { roleName, _ ->
            if (roleName == RoleManager.ROLE_DIALER || roleName == RoleManager.ROLE_SMS) {
                requestRefresh()
            }
        }
    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) = requestRefresh()

            override fun onProfilesChanged(profiles: List<UserInfo>) = requestRefresh()
        }

    init {
        roleManager.addOnRoleHoldersChangedListenerAsUser(
            backgroundExecutor,
            roleListener,
            UserHandle.ALL,
        )
        userTracker.addCallback(userCallback, backgroundExecutor)
        requestRefresh()
    }

    private fun requestRefresh() {
        val generation = refreshGeneration.incrementAndGet()
        backgroundExecutor.execute { refreshRoles(generation) }
    }

    @WorkerThread
    private fun refreshRoles(generation: Int) {
        val users =
            buildSet {
                add(userTracker.userId)
                userTracker.userProfiles.forEach { add(it.id) }
            }
        val dialers = mutableMapOf<Int, Set<String>>()
        val smsApps = mutableMapOf<Int, Set<String>>()
        users.forEach { userId ->
            val user = UserHandle.of(userId)
            dialers[userId] = roleHolders(RoleManager.ROLE_DIALER, user)
            smsApps[userId] = roleHolders(RoleManager.ROLE_SMS, user)
        }
        if (generation == refreshGeneration.get()) {
            _roles.value =
                NotificationRoleSnapshot(
                    dialerHolders = dialers.toMap(),
                    smsHolders = smsApps.toMap(),
                )
        }
    }

    @WorkerThread
    private fun roleHolders(role: String, user: UserHandle): Set<String> =
        try {
            roleManager.getRoleHoldersAsUser(role, user).toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
}

/** Package-independent notification classification used by the R2 status bar. */
internal class NotificationPresentationClassifier(resources: Resources) {
    enum class Type {
        SOCIAL,
        CALL,
        MESSAGING,
        EMAIL,
        ANDROID,
        SYSTEM_UI,
        HIDDEN,
        OTHER,
    }

    private val callFallback = resources.getStringArray(R.array.sos_notification_call_packages).toSet()
    private val messageFallback =
        resources.getStringArray(R.array.sos_notification_messaging_packages).toSet()
    private val emailFallback =
        resources.getStringArray(R.array.sos_notification_email_packages).toSet()
    private val socialFallback =
        resources.getStringArray(R.array.sos_notification_social_packages).toSet()
    private val androidFallback =
        resources.getStringArray(R.array.sos_notification_android_packages).toSet()
    private val systemUiFallback =
        resources.getStringArray(R.array.sos_notification_systemui_packages).toSet()
    private val hiddenFallback =
        resources.getStringArray(R.array.sos_notification_hidden_packages).toSet()

    fun classify(
        notification: ActiveNotificationModel,
        roles: NotificationRoleSnapshot,
    ): Type {
        val pkg = notification.packageName
        if (pkg in hiddenFallback) return Type.HIDDEN
        if (pkg in androidFallback) return Type.ANDROID
        if (pkg in systemUiFallback) return Type.SYSTEM_UI
        if (
            notification.callType != CallType.None ||
                notification.category == Notification.CATEGORY_CALL ||
                roles.isDialer(notification.userId, pkg) ||
                pkg in callFallback
        ) {
            return Type.CALL
        }
        if (
            roles.isSms(notification.userId, pkg) ||
                notification.style is NotifStyle.Messaging ||
                notification.isConversation ||
                notification.category == Notification.CATEGORY_MESSAGE ||
                pkg in messageFallback
        ) {
            return Type.MESSAGING
        }
        if (
            notification.category == CATEGORY_EMAIL ||
                isEmailChannel(notification.channelId) ||
                pkg in emailFallback
        ) {
            return Type.EMAIL
        }
        if (pkg in socialFallback) return Type.SOCIAL
        return Type.OTHER
    }

    private fun isEmailChannel(channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        return EMAIL_TOKEN.containsMatchIn(channelId.lowercase())
    }

    private companion object {
        const val CATEGORY_EMAIL = "email"
        val EMAIL_TOKEN = Regex("(^|[^a-z0-9])(e-?mail|mail)([^a-z0-9]|$)")
    }
}
