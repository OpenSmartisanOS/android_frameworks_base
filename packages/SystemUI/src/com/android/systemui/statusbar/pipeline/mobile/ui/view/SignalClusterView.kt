/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.buildSpec
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.getVisibleStateString
import com.android.systemui.statusbar.phone.StatusBarGeometry
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.SignalIconResource
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModelKairos
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Smartisan fixed-layer mobile signal cluster backed by the platform connectivity pipeline. */
class SignalClusterView(context: Context, attrs: AttributeSet?) :
    ModernStatusBarView(context, attrs) {

    var subId: Int = -1
    var colorIcon: Boolean = false
        private set
    private val wifiDefaultState = MutableStateFlow(false)
    val wifiDefault: StateFlow<Boolean> = wifiDefaultState
    private var hostTint = Color.WHITE
    private var lastLevel = 0
    private var lastShowExclamation = false
    private var lastCarrierChange = false
    private var hasCachedSignal = false

    override fun getStatusBarDotSize(): Int =
        StatusBarGeometry.calculate(this).iconHeight

    override fun toString(): String =
        "SignalClusterView(slot='$slot', subId=$subId, " +
            "isCollecting=${binding.isCollecting()}, " +
            "visibleState=${getVisibleStateString(visibleState)}); ${super.toString()}"

    fun setHostTint(tint: Int) {
        hostTint = tint
        applyTints()
    }

    fun setColorIcon(enabled: Boolean) {
        if (colorIcon == enabled) return
        colorIcon = enabled
        if (hasCachedSignal) {
            renderSignal(lastLevel, lastShowExclamation, lastCarrierChange)
        }
        applyTints()
    }

    fun setWifiDefault(isDefault: Boolean) {
        wifiDefaultState.value = isDefault
    }

    fun renderSignal(
        level: Int,
        showExclamationMark: Boolean,
        carrierNetworkChange: Boolean,
    ): Boolean {
        lastLevel = level
        lastShowExclamation = showExclamationMark
        lastCarrierChange = carrierNetworkChange
        hasCachedSignal = true
        val signal = findViewById<ImageView>(R.id.mobile_signal) ?: return false
        (signal.drawable as? Animatable)?.stop()
        val res =
            SignalIconResource.resolve(
                context = context,
                subscriptionId = subId,
                level = level,
                showExclamationMark = showExclamationMark,
                carrierNetworkChange = carrierNetworkChange,
                colorIcon = colorIcon,
            )
        if (res == 0) return false
        signal.setImageResource(res)
        if (carrierNetworkChange) (signal.drawable as? Animatable)?.start()
        applyTints()
        return true
    }

    override fun setStaticDrawableColor(color: Int, foregroundColor: Int) {
        setHostTint(color)
        super.setStaticDrawableColor(color, foregroundColor)
    }

    private fun applyTints() {
        val signal = findViewById<ImageView>(R.id.mobile_signal) ?: return
        val tint = ColorStateList.valueOf(hostTint)
        signal.imageTintList = if (colorIcon) null else tint
        findViewById<ImageView>(R.id.mobile_type)?.imageTintList = tint
        findViewById<ImageView>(R.id.mobile_in)?.imageTintList = tint
        findViewById<ImageView>(R.id.mobile_out)?.imageTintList = tint
        findViewById<ImageView>(R.id.mobile_roaming)?.imageTintList = tint
    }

    public override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        applySmartisanGeometry()
    }

    private fun applySmartisanGeometry() {
        val iconHeight = StatusBarGeometry.calculate(this).iconHeight
        requireViewById<AlphaOptimizedLinearLayout>(R.id.mobile_group).apply {
            (layoutParams as MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = 0
            }
        }
        requireViewById<ImageView>(R.id.mobile_signal).layoutParams.height = iconHeight
        requireViewById<FrameLayout>(R.id.mobile_type_container).apply {
            (layoutParams as MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = 0
            }
            layoutParams.height = iconHeight
            background = null
        }
        requireViewById<ImageView>(R.id.mobile_type).layoutParams.height = iconHeight
        requireViewById<FrameLayout>(R.id.inout_container).layoutParams.height = iconHeight
        requireViewById<ImageView>(R.id.mobile_in).layoutParams.height = iconHeight
        requireViewById<ImageView>(R.id.mobile_out).layoutParams.height = iconHeight
        requireViewById<ImageView>(R.id.mobile_roaming).layoutParams.height = iconHeight
        requireViewById<ImageView>(R.id.mobile_roaming_updated).visibility = View.GONE
    }

    companion object {
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: LocationBasedMobileViewModel,
        ): SignalClusterView =
            (LayoutInflater.from(context).inflate(R.layout.signal_cluster_view, null)
                    as SignalClusterView)
                .apply {
                    applySmartisanGeometry()
                    subId = viewModel.subscriptionId
                    initView(slot) {
                        MobileIconBinder.bind(
                            view = this,
                            viewModel = viewModel,
                            logger = logger,
                            statusBarPresentation = true,
                        )
                    }
                    setVisibleState(StatusBarIconView.STATE_ICON, false)
                    logger.logNewViewBinding(this, viewModel)
                }

        @ExperimentalKairosApi
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: LocationBasedMobileViewModelKairos,
            scope: CoroutineScope,
            subscriptionId: Int,
            location: StatusBarLocation,
            kairosNetwork: KairosNetwork,
        ): Pair<SignalClusterView, Job> {
            val view =
                (LayoutInflater.from(context).inflate(R.layout.signal_cluster_view, null)
                        as SignalClusterView)
                    .apply {
                        applySmartisanGeometry()
                        subId = subscriptionId
                    }
            lateinit var jobResult: Job
            view.initView(slot) {
                val (binding, job) =
                    MobileIconBinderKairos.bind(
                        view = view,
                        viewModel = buildSpec { viewModel },
                        logger = logger,
                        scope = scope,
                        kairosNetwork = kairosNetwork,
                        subId = subscriptionId,
                        statusBarPresentation = true,
                    )
                jobResult = job
                binding
            }
            view.setVisibleState(StatusBarIconView.STATE_ICON, false)
            logger.logNewViewBinding(view, viewModel, location.name)
            return view to jobResult
        }
    }
}
