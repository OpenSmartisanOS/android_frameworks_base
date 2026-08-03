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
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Icon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.headsup.shared.StatusBarNoHunBehavior
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.ActiveNotificationIconModel
import com.android.systemui.statusbar.notification.icon.domain.interactor.StatusBarNotificationIconsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** View-model for the row of notification icons displayed in the status bar, */
class NotificationIconContainerStatusBarViewModel
@Inject
constructor(
    @Background private val bgContext: CoroutineContext,
    private val darkIconInteractor: DarkIconInteractor,
    dumpManager: DumpManager,
    iconsInteractor: StatusBarNotificationIconsInteractor,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    headsUpIconInteractor: HeadsUpNotificationIconInteractor,
    keyguardInteractor: KeyguardInteractor,
    @Main resources: Resources,
    shadeInteractor: ShadeInteractor,
) : FlowDumperImpl(dumpManager) {

    private val maxIcons = resources.getInteger(R.integer.max_notif_static_icons)
    private val useSosNotificationPresentation =
        resources.getBoolean(R.bool.config_sos_legacy_shade)
    private val callPackages = resources.getStringArray(R.array.sos_notification_call_packages).toSet()
    private val messagingPackages =
        resources.getStringArray(R.array.sos_notification_messaging_packages).toSet()
    private val emailPackages =
        resources.getStringArray(R.array.sos_notification_email_packages).toSet()
    private val androidPackages =
        resources.getStringArray(R.array.sos_notification_android_packages).toSet()
    private val systemUiPackages =
        resources.getStringArray(R.array.sos_notification_systemui_packages).toSet()
    private val hiddenPackages =
        resources.getStringArray(R.array.sos_notification_hidden_packages).toSet()
    private val socialPackages =
        resources.getStringArray(R.array.sos_notification_social_packages).toSet()
    private val socialStatusBarIcon =
        Icon.createWithResource("com.android.systemui", R.drawable.smartisan_social_notification)

    private val notificationPresentation: Flow<SosNotificationPresentation> =
        if (useSosNotificationPresentation) {
            combine(
                    iconsInteractor.statusBarNotifs,
                    activeNotificationsInteractor.allRepresentativeNotifications,
                    ::buildSosNotificationPresentation,
                )
                .flowOn(bgContext)
                .conflate()
                .distinctUntilChanged()
        } else {
            iconsInteractor.statusBarNotifs.map {
                SosNotificationPresentation(it.toList(), hiddenCount = 0)
            }
        }

    /** Are changes to the icon container animated? */
    val animationsEnabled: Flow<Boolean> =
        combine(shadeInteractor.isShadeTouchable, keyguardInteractor.isKeyguardShowing) {
                panelTouchesEnabled,
                isKeyguardShowing ->
                panelTouchesEnabled && !isKeyguardShowing
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()

    /** The colors with which to display the notification icons. */
    fun iconColors(displayId: Int): Flow<NotificationIconColors> {
        return darkIconInteractor
            .darkState(displayId)
            .map { (areas: Collection<Rect>, tint: Int) -> IconColorsImpl(tint, areas) }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()
    }

    /** [NotificationIconsViewData] indicating which icons to display in the view. */
    val icons: Flow<NotificationIconsViewData> =
        notificationPresentation
            .map { presentation ->
                NotificationIconsViewData(
                    visibleIcons =
                        presentation.visibleIcons.mapNotNull { it.toIconInfo(it.statusBarIcon) },
                    iconLimit = maxIcons,
                )
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()
            .dumpWhileCollecting("icons")

    /** Number represented by the SOS count glyph next to the app notification icons. */
    val notificationCount: Flow<Int> =
        notificationPresentation
            .map { it.hiddenCount }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()

    /** An Icon to show "isolated" in the IconContainer. */
    val isolatedIcon: Flow<AnimatedValue<NotificationIconInfo?>> =
        if (StatusBarNoHunBehavior.isEnabled) {
            flowOf(AnimatedValue.NotAnimating(null))
        } else {
            headsUpIconInteractor.isolatedNotification
                .combine(icons) { isolatedNotif, iconsViewData ->
                    isolatedNotif?.let {
                        iconsViewData.visibleIcons.firstOrNull { it.notifKey == isolatedNotif }
                    }
                }
                .distinctUntilChanged()
                .flowOn(bgContext)
                .conflate()
                .distinctUntilChanged()
                .pairwise(initialValue = null)
                .sample(shadeInteractor.shadeExpansion) { (prev, iconInfo), shadeExpansion ->
                    val animate =
                        when {
                            iconInfo?.notifKey == prev?.notifKey -> false
                            iconInfo == null || prev == null -> shadeExpansion == 0f
                            else -> false
                        }
                    AnimatableEvent(iconInfo, animate)
                }
                .toAnimatedValueFlow()
        }

    /** Location to show an isolated icon, if there is one. */
    val isolatedIconLocation: Flow<Rect> =
        if (StatusBarNoHunBehavior.isEnabled) {
            emptyFlow()
        } else {
            headsUpIconInteractor.isolatedIconLocation
                .filterNotNull()
                .conflate()
                .distinctUntilChanged()
        }

    private class IconColorsImpl(override val tint: Int, private val areas: Collection<Rect>) :
        NotificationIconColors {
        override fun staticDrawableColor(viewBounds: Rect): Int {
            return if (DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                tint
            } else {
                DarkIconDispatcher.DEFAULT_ICON_TINT
            }
        }
    }

    private fun buildSosNotificationPresentation(
        eligibleIcons: Set<ActiveNotificationIconModel>,
        activeNotifications: Map<String, ActiveNotificationModel>,
    ): SosNotificationPresentation {
        val eligibleKeys = eligibleIcons.mapTo(mutableSetOf()) { it.notifKey }
        var hiddenCount =
            activeNotifications.values.count {
                !it.isGroupSummary && !it.isRowDismissed && it.key !in eligibleKeys
            }
        val representedSingleTypes = mutableSetOf<SosNotificationType>()
        val candidates = mutableListOf<ActiveNotificationIconModel>()

        eligibleIcons.forEach { icon ->
            val notification = activeNotifications[icon.notifKey]
            if (notification == null) {
                candidates += icon
                return@forEach
            }
            if (notification.isGroupSummary || notification.isRowDismissed) {
                return@forEach
            }

            when (val type = classify(notification.packageName)) {
                SosNotificationType.CALL,
                SosNotificationType.ANDROID,
                SosNotificationType.SYSTEM_UI -> candidates += icon
                SosNotificationType.MESSAGING,
                SosNotificationType.EMAIL -> {
                    if (representedSingleTypes.add(type)) {
                        candidates += icon
                    } else {
                        hiddenCount++
                    }
                }
                SosNotificationType.SOCIAL -> {
                    if (representedSingleTypes.add(type)) {
                        candidates += icon.copy(statusBarIcon = socialStatusBarIcon)
                    } else {
                        hiddenCount++
                    }
                }
                SosNotificationType.HIDDEN,
                SosNotificationType.OTHER -> hiddenCount++
            }
        }

        if (candidates.size > maxIcons) {
            hiddenCount += candidates.size - maxIcons
        }
        return SosNotificationPresentation(
            visibleIcons = candidates.take(maxIcons),
            hiddenCount = hiddenCount,
        )
    }

    private fun classify(packageName: String): SosNotificationType =
        when (packageName) {
            in callPackages -> SosNotificationType.CALL
            in messagingPackages -> SosNotificationType.MESSAGING
            in emailPackages -> SosNotificationType.EMAIL
            in socialPackages -> SosNotificationType.SOCIAL
            in androidPackages -> SosNotificationType.ANDROID
            in systemUiPackages -> SosNotificationType.SYSTEM_UI
            in hiddenPackages -> SosNotificationType.HIDDEN
            else -> SosNotificationType.OTHER
        }

    private data class SosNotificationPresentation(
        val visibleIcons: List<ActiveNotificationIconModel>,
        val hiddenCount: Int,
    )

    private enum class SosNotificationType {
        SOCIAL,
        CALL,
        MESSAGING,
        EMAIL,
        ANDROID,
        SYSTEM_UI,
        HIDDEN,
        OTHER,
    }
}
