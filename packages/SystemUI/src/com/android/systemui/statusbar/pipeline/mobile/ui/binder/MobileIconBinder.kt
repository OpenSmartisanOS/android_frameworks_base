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

package com.android.systemui.statusbar.pipeline.mobile.ui.binder

import android.annotation.ColorInt
import android.content.res.ColorStateList
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Space
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.Flags.statusBarStaticInoutIndicators
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.view.SignalClusterView
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewVisibilityHelper
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarViewBinderConstants.ALPHA_ACTIVE
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarViewBinderConstants.ALPHA_INACTIVE
import com.android.systemui.util.kotlin.pairwiseBy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

data class MobileIconColors(@ColorInt val tint: Int, @ColorInt val contrast: Int)

object MobileIconBinder {
    /** Binds the view to the view-model, continuing to update the former based on the latter */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: LocationBasedMobileViewModel,
        @StatusBarIconView.VisibleState initialVisibilityState: Int = STATE_HIDDEN,
        logger: MobileViewLogger,
        statusBarPresentation: Boolean = false,
    ): ModernStatusBarViewBinding {
        val mobileGroupView = view.requireViewById<ViewGroup>(R.id.mobile_group)
        val activityContainer = view.requireViewById<View>(R.id.inout_container)
        val activityIn = view.requireViewById<ImageView>(R.id.mobile_in)
        val activityOut = view.requireViewById<ImageView>(R.id.mobile_out)
        val networkTypeView = view.requireViewById<ImageView>(R.id.mobile_type)
        val networkTypeContainer = view.requireViewById<FrameLayout>(R.id.mobile_type_container)
        val iconView = view.requireViewById<ImageView>(R.id.mobile_signal)
        val roamingView = view.requireViewById<ImageView>(R.id.mobile_roaming)
        val roamingSpace = view.requireViewById<Space>(R.id.mobile_roaming_space)
        val endSideRoamingView = view.requireViewById<ImageView>(R.id.mobile_roaming_updated)
        val dotView = view.requireViewById<StatusBarIconView>(R.id.status_bar_dot)
        val useStatusBarPresentation = statusBarPresentation
        val statusBarView = view as? SignalClusterView
        val wifiDefault = statusBarView?.wifiDefault ?: flowOf(false)
        val mobileDrawable =
            if (useStatusBarPresentation) null
            else SignalDrawable(view.context).also { iconView.setImageDrawable(it) }
        if (useStatusBarPresentation) {
            roamingView.isVisible = false
            roamingSpace.isVisible = false
            endSideRoamingView.isVisible = false
        }

        view.isVisible = viewModel.isVisible.value
        iconView.isVisible = true

        // TODO(b/238425913): We should log this visibility state.
        @StatusBarIconView.VisibleState
        val visibilityState: MutableStateFlow<Int> = MutableStateFlow(initialVisibilityState)

        val iconTint: MutableStateFlow<MobileIconColors> =
            MutableStateFlow(
                MobileIconColors(
                    tint = DarkIconDispatcher.DEFAULT_ICON_TINT,
                    contrast = DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT,
                )
            )
        val decorTint: MutableStateFlow<Int> = MutableStateFlow(viewModel.defaultColor)

        var isCollecting = false

        view.repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    // isVisible controls the visibility state of the outer group, and thus it needs
                    // to run in the CREATED lifecycle so it can continue to watch while invisible
                    // See (b/291031862) for details
                    launch {
                        viewModel.isVisible.collect { isVisible ->
                            viewModel.verboseLogger?.logBinderReceivedVisibility(
                                view,
                                viewModel.subscriptionId,
                                isVisible,
                            )
                            view.isVisible = isVisible
                            // [StatusIconContainer] can get out of sync sometimes. Make sure to
                            // request another layout when this changes.
                            view.requestLayout()
                        }
                    }
                }
            }

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    logger.logCollectionStarted(view, viewModel)
                    isCollecting = true

                    launch {
                        visibilityState.collect { state ->
                            ModernStatusBarViewVisibilityHelper.setVisibilityState(
                                state,
                                mobileGroupView,
                                dotView,
                            )

                            view.requestLayout()
                        }
                    }

                    // Set the icon for the triangle
                    launch {
                        viewModel.icon
                            .pairwiseBy(initialValue = null) { oldIcon, newIcon ->
                                // Make sure we requestLayout if the number of levels changes
                                val shouldRequestLayout =
                                    when {
                                        oldIcon == null -> true
                                        oldIcon is SignalIconModel.Cellular &&
                                            newIcon is SignalIconModel.Cellular -> {
                                            oldIcon.numberOfLevels != newIcon.numberOfLevels
                                        }
                                        else -> false
                                    }
                                Pair(shouldRequestLayout, newIcon)
                            }
                            .collect { (shouldRequestLayout, newIcon) ->
                                if (newIcon is SignalIconModel.Cellular) {
                                    val packedSignalDrawableState = newIcon.toSignalDrawableState()
                                    viewModel.verboseLogger?.logBinderReceivedSignalCellularIcon(
                                        parentView = view,
                                        subId = viewModel.subscriptionId,
                                        icon = newIcon,
                                        packedSignalDrawableState = packedSignalDrawableState,
                                        shouldRequestLayout = shouldRequestLayout,
                                    )
                                    val level = newIcon.level.coerceIn(0, 5)
                                    if (useStatusBarPresentation) {
                                        val carrierChange = newIcon.carrierNetworkChange
                                        val statusBarView = view as? SignalClusterView
                                        statusBarView?.renderSignal(
                                            level,
                                            newIcon.showExclamationMark,
                                            carrierChange,
                                        )
                                    } else {
                                        mobileDrawable?.let {
                                            if (iconView.drawable !== it) {
                                                iconView.setImageDrawable(it)
                                            }
                                            it.level = packedSignalDrawableState
                                        }
                                    }
                                    viewModel.verboseLogger?.logBinderSignalIconResult(
                                        parentView = view,
                                        subId = viewModel.subscriptionId,
                                        unpackedLevel = level,
                                    )
                                } else if (newIcon is SignalIconModel.Satellite) {
                                    viewModel.verboseLogger?.logBinderReceivedSignalSatelliteIcon(
                                        parentView = view,
                                        subId = viewModel.subscriptionId,
                                        icon = newIcon,
                                    )
                                    IconViewBinder.bind(newIcon.icon, iconView)
                                }

                                if (shouldRequestLayout) {
                                    iconView.requestLayout()
                                }
                            }
                    }

                    launch {
                        viewModel.contentDescription.distinctUntilChanged().collect {
                            MobileContentDescriptionViewBinder.bind(it, view)
                        }
                    }

                    // Set the network type icon
                    launch {
                        if (useStatusBarPresentation) {
                            combine(
                                    viewModel.networkTypeIcon,
                                    viewModel.roaming,
                                    wifiDefault,
                                ) { dataTypeId, isRoaming, wifiDefault ->
                                    Triple(dataTypeId, isRoaming, wifiDefault)
                                }
                                .distinctUntilChanged()
                                .collect { (dataTypeId, isRoaming, wifiDefault) ->
                                    viewModel.verboseLogger?.logBinderReceivedNetworkTypeIcon(
                                        view,
                                        viewModel.subscriptionId,
                                        dataTypeId,
                                    )
                                    dataTypeId?.let {
                                        IconViewBinder.bind(
                                            SignalIconResource.resolveNetworkType(it),
                                            networkTypeView,
                                        )
                                    }
                                    val prevVis = networkTypeContainer.visibility
                                    // R2: hide RAT when Wi-Fi is the default network and not roaming.
                                    networkTypeContainer.visibility =
                                        if (
                                            SignalIconResource.shouldShowRat(
                                                hasDataType = dataTypeId != null,
                                                roaming = isRoaming,
                                                wifiDefault = wifiDefault,
                                            )
                                        ) {
                                            VISIBLE
                                        } else {
                                            GONE
                                        }
                                    if (prevVis != networkTypeContainer.visibility) {
                                        view.requestLayout()
                                    }
                                }
                        } else {
                            viewModel.networkTypeIcon.distinctUntilChanged().collect { dataTypeId ->
                                viewModel.verboseLogger?.logBinderReceivedNetworkTypeIcon(
                                    view,
                                    viewModel.subscriptionId,
                                    dataTypeId,
                                )
                                dataTypeId?.let { IconViewBinder.bind(it, networkTypeView) }
                                val prevVis = networkTypeContainer.visibility
                                networkTypeContainer.visibility =
                                    if (dataTypeId != null) VISIBLE else GONE

                                if (prevVis != networkTypeContainer.visibility) {
                                    view.requestLayout()
                                }
                            }
                        }
                    }

                    // Set the network type background
                    launch {
                        viewModel.networkTypeBackground.collect { background ->
                            networkTypeContainer.setBackgroundResource(
                                if (useStatusBarPresentation) 0 else background?.resId ?: 0
                            )

                            // Tint will invert when this bit changes
                            if (!useStatusBarPresentation && background?.resId != null) {
                                networkTypeContainer.backgroundTintList =
                                    ColorStateList.valueOf(iconTint.value.tint)
                                networkTypeView.imageTintList =
                                    ColorStateList.valueOf(iconTint.value.contrast)
                            } else {
                                networkTypeView.imageTintList =
                                    ColorStateList.valueOf(iconTint.value.tint)
                            }
                        }
                    }

                    // R2 distribution build keeps the original standalone roaming layer.
                    launch {
                        viewModel.roaming.distinctUntilChanged().collect { isRoaming ->
                            if (useStatusBarPresentation) {
                                roamingView.setImageResource(R.drawable.stat_sys_roaming)
                                roamingView.isVisible = isRoaming
                                roamingSpace.isVisible = isRoaming
                                endSideRoamingView.isVisible = false
                            } else if (NewStatusBarIcons.isEnabled) {
                                endSideRoamingView.isVisible = isRoaming
                            } else {
                                roamingView.isVisible = isRoaming
                                roamingSpace.isVisible = isRoaming
                            }
                        }
                    }

                    if (useStatusBarPresentation) {
                        launch {
                            combine(
                                    viewModel.activityInVisible,
                                    viewModel.activityOutVisible,
                                    viewModel.roaming,
                                    viewModel.networkTypeIcon,
                                ) { hasActivityIn, hasActivityOut, isRoaming, dataTypeId ->
                                    SignalIconResource.resolveActivity(
                                        hasActivityIn,
                                        hasActivityOut,
                                        isRoaming,
                                        dataTypeId != null,
                                    )
                                }
                                .distinctUntilChanged()
                                .collect { activityRes ->
                                    if (activityRes != 0) {
                                        activityIn.setImageResource(activityRes)
                                    }
                                    activityIn.isVisible = activityRes != 0
                                    activityOut.isVisible = false
                                    activityContainer.isVisible = activityRes != 0
                                }
                        }
                    } else if (statusBarStaticInoutIndicators()) {
                        // Set the opacity of the activity indicators
                        launch {
                            viewModel.activityInVisible.collect { visible ->
                                activityIn.imageAlpha =
                                    (if (visible) ALPHA_ACTIVE else ALPHA_INACTIVE)
                            }
                        }

                        launch {
                            viewModel.activityOutVisible.collect { visible ->
                                activityOut.imageAlpha =
                                    (if (visible) ALPHA_ACTIVE else ALPHA_INACTIVE)
                            }
                        }
                    } else {
                        // Set the activity indicators
                        launch { viewModel.activityInVisible.collect { activityIn.isVisible = it } }

                        launch {
                            viewModel.activityOutVisible.collect { activityOut.isVisible = it }
                        }
                    }

                    if (!useStatusBarPresentation) {
                        launch {
                            viewModel.activityContainerVisible.collect {
                                activityContainer.isVisible = it
                            }
                        }
                    }

                    // Set the tint
                    launch {
                        iconTint.collect { colors ->
                            val tint = ColorStateList.valueOf(colors.tint)
                            val contrast = ColorStateList.valueOf(colors.contrast)
                            val statusBarView = view as? SignalClusterView

                            iconView.imageTintList =
                                if (useStatusBarPresentation && statusBarView?.colorIcon == true) {
                                    null
                                } else {
                                    tint
                                }

                            // If the bg is visible, tint it and use the contrast for the fg
                            if (
                                !useStatusBarPresentation &&
                                    viewModel.networkTypeBackground.value != null
                            ) {
                                networkTypeContainer.backgroundTintList = tint
                                networkTypeView.imageTintList = contrast
                            } else {
                                networkTypeView.imageTintList = tint
                            }

                            roamingView.imageTintList = tint
                            endSideRoamingView.imageTintList = tint
                            activityIn.imageTintList = tint
                            activityOut.imageTintList = tint
                            dotView.setDecorColor(colors.tint)
                        }
                    }

                    launch { decorTint.collect { tint -> dotView.setDecorColor(tint) } }

                    try {
                        awaitCancellation()
                    } finally {
                        isCollecting = false
                        logger.logCollectionStopped(view, viewModel)
                    }
                }
            }
        }

        return object : ModernStatusBarViewBinding {
            override fun getShouldIconBeVisible(): Boolean {
                return viewModel.isVisible.value
            }

            override fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int) {
                visibilityState.value = state
            }

            override fun onIconTintChanged(newTint: Int, contrastTint: Int) {
                iconTint.value = MobileIconColors(tint = newTint, contrast = contrastTint)
            }

            override fun onDecorTintChanged(newTint: Int) {
                decorTint.value = newTint
            }

            override fun isCollecting(): Boolean {
                return isCollecting
            }
        }
    }
}
