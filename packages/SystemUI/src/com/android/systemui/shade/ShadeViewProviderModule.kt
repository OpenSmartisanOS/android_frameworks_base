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

package com.android.systemui.shade

import android.annotation.SuppressLint
import android.view.LayoutInflater
import com.android.keyguard.logging.ScrimLogger
import com.android.systemui.biometrics.AuthRippleView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.res.R
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.phone.TapAgainView
import dagger.Binds
import dagger.Module
import dagger.Provides

/** Module for providing views related to the shade. */
@Module
abstract class ShadeViewProviderModule {

    @Binds
    @SysUISingleton
    // TODO(b/277762009): Only allow this view's binder to inject the view.
    abstract fun bindsNotificationScrollView(
        notificationStackScrollLayout: NotificationStackScrollLayout
    ): NotificationScrollView

    companion object {
        @SuppressLint("InflateParams") // Root views don't have parents.
        @Provides
        @SysUISingleton
        fun providesWindowRootView(
            @ShadeDisplayAware layoutInflater: LayoutInflater,
        ): WindowRootView {
            return layoutInflater.inflate(R.layout.super_notification_shade, null)
                as WindowRootView?
                ?: throw IllegalStateException("Window root view could not be properly inflated")
        }

        // TODO(b/277762009): Do something similar to
        //  {@link StatusBarWindowModule.InternalWindowView} so that only
        //  {@link NotificationShadeWindowViewController} can inject this view.
        @Provides
        @SysUISingleton
        fun providesNotificationShadeWindowView(root: WindowRootView): NotificationShadeWindowView {
            return root as NotificationShadeWindowView?
                ?: throw IllegalStateException("root view not a NotificationShadeWindowView")
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationStackScrollLayout(
            notificationShadeWindowView: NotificationShadeWindowView
        ): NotificationStackScrollLayout {
            return notificationShadeWindowView.requireViewById(R.id.notification_stack_scroller)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationPanelView(
            notificationShadeWindowView: NotificationShadeWindowView
        ): NotificationPanelView {
            return notificationShadeWindowView.requireViewById(R.id.notification_panel)
        }

        @Provides
        @SysUISingleton
        fun providesLightRevealScrim(
            notificationShadeWindowView: NotificationShadeWindowView,
            scrimLogger: ScrimLogger,
        ): LightRevealScrim {
            val scrim =
                notificationShadeWindowView.requireViewById<LightRevealScrim>(
                    R.id.light_reveal_scrim
                )
            scrim.scrimLogger = scrimLogger
            return scrim
        }

        @Provides
        @SysUISingleton
        fun providesKeyguardRootView(
            notificationShadeWindowView: NotificationShadeWindowView
        ): KeyguardRootView {
            return notificationShadeWindowView.requireViewById(R.id.keyguard_root_view)
        }

        @Provides
        @SysUISingleton
        fun providesSharedNotificationContainer(
            notificationShadeWindowView: NotificationShadeWindowView
        ): SharedNotificationContainer {
            return notificationShadeWindowView.requireViewById(R.id.shared_notification_container)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesAuthRippleView(
            notificationShadeWindowView: NotificationShadeWindowView
        ): AuthRippleView? {
            return notificationShadeWindowView.requireViewById(R.id.auth_ripple)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesTapAgainView(notificationPanelView: NotificationPanelView): TapAgainView {
            return notificationPanelView.requireViewById(R.id.shade_falsing_tap_again)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationsQuickSettingsContainer(
            notificationShadeWindowView: NotificationShadeWindowView
        ): NotificationsQuickSettingsContainer {
            return notificationShadeWindowView.requireViewById(R.id.notification_container_parent)
        }

    }
}
