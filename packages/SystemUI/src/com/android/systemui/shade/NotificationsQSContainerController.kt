/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.LauncherProxyService
import com.android.systemui.LauncherProxyService.LauncherProxyListener
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.fragments.FragmentService
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.util.ViewController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.function.Consumer
import javax.inject.Inject
import kotlin.math.max

@VisibleForTesting internal const val INSET_DEBOUNCE_MILLIS = 500L

@SysUISingleton
class NotificationsQSContainerController
@Inject
constructor(
    view: NotificationsQuickSettingsContainer,
    private val navigationModeController: NavigationModeController,
    private val launcherProxyService: LauncherProxyService,
    private val shadeHeaderController: ShadeHeaderController,
    private val shadeInteractor: ShadeInteractor,
    private val fragmentService: FragmentService,
    private val activityStarter: ActivityStarter,
    @Main private val delayableExecutor: DelayableExecutor,
    private val notificationStackScrollLayoutController: NotificationStackScrollLayoutController,
) : ViewController<NotificationsQuickSettingsContainer>(view), QSContainerController {

    private var isQSDetailShowing = false
    private var isQSCustomizing = false
    private var isQSCustomizerAnimating = false

    private var notificationsBottomMargin = 0
    private var bottomStableInsets = 0
    private var bottomCutoutInsets = 0
    private var panelMarginHorizontal = 0
    private var topMargin = 0
    private var shadeTopInset = 0

    private var isGestureNavigation = true
    private var taskbarVisible = false
    private val taskbarVisibilityListener: LauncherProxyListener =
        object : LauncherProxyListener {
            override fun onTaskbarStatusUpdated(visible: Boolean, stashed: Boolean) {
                taskbarVisible = visible
            }
        }

    // With certain configuration changes (like light/dark changes), the nav bar will disappear
    // for a bit, causing `bottomStableInsets` to be unstable for some time. Debounce the value
    // for 500ms.
    // All interactions with this object happen in the main thread.
    private val delayedInsetSetter =
        object : Runnable, Consumer<WindowInsets> {
            private var canceller: Runnable? = null
            private var stableInsets = 0
            private var cutoutInsets = 0

            override fun accept(insets: WindowInsets) {
                // when taskbar is visible, stableInsetBottom will include its height
                stableInsets = insets.stableInsetBottom
                cutoutInsets = insets.displayCutout?.safeInsetBottom ?: 0
                val currentTopInset =
                    insets
                        .getInsetsIgnoringVisibility(
                            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                        )
                        .top
                if (shadeTopInset != currentTopInset) {
                    shadeTopInset = currentTopInset
                    topMargin = currentTopInset
                    mView.setTopInset(currentTopInset)
                    updateConstraints()
                }
                canceller?.run()
                canceller = delayableExecutor.executeDelayed(this, INSET_DEBOUNCE_MILLIS)
            }

            override fun run() {
                bottomStableInsets = stableInsets
                bottomCutoutInsets = cutoutInsets
                updateBottomSpacing()
                canceller = null
            }

            fun cancel() {
                canceller?.run()
                canceller = null
            }
        }

    override fun onInit() {
        mView.repeatWhenAttached {
            lifecycleScope.launch {
                shadeInteractor.isQsExpanded.collect { _ -> mView.invalidate() }
            }
        }
        val currentMode: Int =
            navigationModeController.addListener { mode: Int ->
                val gestureNavigation = QuickStepContract.isGesturalMode(mode)
                if (isGestureNavigation != gestureNavigation) {
                    isGestureNavigation = gestureNavigation
                    if (mView.isAttachedToWindow) {
                        updateBottomSpacing()
                    }
                }
            }
        isGestureNavigation = QuickStepContract.isGesturalMode(currentMode)

        mView.setStackScroller(notificationStackScrollLayoutController.getView())
    }

    public override fun onViewAttached() {
        updateResources()
        mView.setSearchAction {
            activityStarter.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                true /* dismissShade */,
            )
        }
        mView.setSettingsAction {
            activityStarter.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                true /* dismissShade */,
            )
        }
        launcherProxyService.addCallback(taskbarVisibilityListener)
        mView.setInsetsChangedListener(delayedInsetSetter)
        mView.setQSFragmentAttachedListener { qs: QS -> qs.setContainerController(this) }
        mView.setConfigurationChangedListener { updateResources() }
        shadeHeaderController.attach(mView.rootView)
        mView.setPanelStatusBarVisibleListener(shadeHeaderController::setVisible)
        mView.setPanelStatusBarExpansionListener(shadeHeaderController::setExpansion)
        mView.setPanelStatusBarTopInsetListener(shadeHeaderController::setTopInset)
        fragmentService.getFragmentHostManager(mView).addTagListener(QS.TAG, mView)
    }

    override fun onViewDetached() {
        delayedInsetSetter.cancel()
        mView.setSearchAction(null)
        mView.setSettingsAction(null)
        mView.setPanelStatusBarVisibleListener(null)
        mView.setPanelStatusBarExpansionListener(null)
        mView.setPanelStatusBarTopInsetListener(null)
        shadeHeaderController.detach()
        launcherProxyService.removeCallback(taskbarVisibilityListener)
        mView.removeOnInsetsChangedListener()
        mView.removeQSFragmentAttachedListener()
        mView.setConfigurationChangedListener(null)
        fragmentService.getFragmentHostManager(mView).removeTagListener(QS.TAG, mView)
    }

    fun updateResources() {
        notificationsBottomMargin =
            resources.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom)
        panelMarginHorizontal =
            resources.getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal)
        topMargin = shadeTopInset
        mView.setTopInset(shadeTopInset)
        updateConstraints()

        updateBottomSpacing()
    }

    override fun setCustomizerAnimating(animating: Boolean) {
        if (isQSCustomizerAnimating != animating) {
            isQSCustomizerAnimating = animating
            mView.invalidate()
        }
    }

    override fun setCustomizerShowing(showing: Boolean, animationDuration: Long) {
        if (showing != isQSCustomizing) {
            isQSCustomizing = showing
            shadeHeaderController.startCustomizingAnimation(showing, animationDuration)
            updateBottomSpacing()
        }
    }

    override fun setDetailShowing(showing: Boolean) {
        isQSDetailShowing = showing
        updateBottomSpacing()
    }

    private fun updateBottomSpacing() {
        val notificationsMargin = calculateNotificationsBottomMargin()
        mView.setPadding(0, 0, 0, 0)
        mView.setNotificationsMarginBottom(notificationsMargin)
        mView.setQSContainerPaddingBottom(0)
        // Gesture navigation keeps its recognition region but contributes no visual/layout
        // reserve. Three-button navigation and a physical bottom cutout retain their platform
        // safety inset.
        mView.setBottomInset(
            if (isGestureNavigation) bottomCutoutInsets
            else max(bottomStableInsets, bottomCutoutInsets)
        )
    }

    private fun calculateNotificationsBottomMargin(): Int {
        return if (isQSCustomizing || isQSDetailShowing) {
            0
        } else if (isGestureNavigation) {
            notificationsBottomMargin
        } else if (taskbarVisible) {
            notificationsBottomMargin
        } else {
            bottomStableInsets + notificationsBottomMargin
        }
    }

    fun updateConstraints() {
        // To change the constraints at runtime, all children of the ConstraintLayout must have ids
        ensureAllViewsHaveIds(mView)
        val constraintSet = ConstraintSet()
        constraintSet.clone(mView)
        setQsConstraints(constraintSet)
        mView.applyConstraints(constraintSet)
    }

    private fun setQsConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            connect(R.id.qs_frame, END, PARENT_ID, END)
            setMargin(R.id.qs_frame, START, panelMarginHorizontal)
            setMargin(R.id.qs_frame, END, panelMarginHorizontal)
            setMargin(R.id.qs_frame, ConstraintSet.TOP, topMargin)
        }
    }

    private fun ensureAllViewsHaveIds(parentView: ViewGroup) {
        for (i in 0 until parentView.childCount) {
            val childView = parentView.getChildAt(i)
            if (childView.id == View.NO_ID) {
                childView.id = View.generateViewId()
            }
        }
    }
}
