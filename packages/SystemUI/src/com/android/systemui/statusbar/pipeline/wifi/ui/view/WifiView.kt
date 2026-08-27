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
package com.android.systemui.statusbar.pipeline.wifi.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.phone.StatusBarGeometry
import com.android.systemui.statusbar.phone.NetworkSignalCluster
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.DefaultTransport
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import com.android.systemui.statusbar.pipeline.wifi.ui.binder.WifiViewBinder
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIconPresentation
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiState
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel

/** Smartisan fixed-layer Wi-Fi status view backed by the platform Wi-Fi pipeline. */
class WifiView(context: Context, attrs: AttributeSet?) : ModernStatusBarView(context, attrs) {
    private var state = WifiState()
    private var hostTint = Color.WHITE
    private var colorIcon = false

    override fun getStatusBarDotSize(): Int =
        StatusBarGeometry.calculate(this).iconHeight

    override fun toString(): String =
        "WifiView(slot='$slot', isCollecting=${binding.isCollecting()}, " +
            "visibleState=${StatusBarIconView.getVisibleStateString(visibleState)}); " +
            super.toString()

    public override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        applySmartisanGeometry()
        renderState()
    }

    private fun applySmartisanGeometry() {
        val metrics = StatusBarGeometry.calculate(this)
        val iconHeight = metrics.iconHeight
        requireViewById<AlphaOptimizedLinearLayout>(R.id.wifi_group).apply {
            (layoutParams as MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = 0
            }
        }
        requireViewById<ViewGroup>(R.id.wifi_canvas).apply {
            (layoutParams as MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = 0
                height = iconHeight
            }
        }
        requireViewById<ImageView>(R.id.wifi_signal).apply {
            adjustViewBounds = true
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams.height = iconHeight
        }
        requireViewById<ImageView>(R.id.wifi_inout).layoutParams.height = iconHeight
        requireViewById<ImageView>(R.id.wifi_no).layoutParams.height = iconHeight
    }

    internal fun setState(state: WifiState) {
        val networkClusterStateChanged =
            this.state.connected != state.connected ||
                this.state.defaultTransport != state.defaultTransport
        this.state = state
        renderState()
        if (networkClusterStateChanged) findNetworkCluster()?.onWifiStateChanged()
    }

    fun isConnected(): Boolean = state.connected

    fun isDefaultNetwork(): Boolean = state.defaultTransport == DefaultTransport.WIFI

    private fun findNetworkCluster(): NetworkSignalCluster? {
        var current = parent
        while (true) {
            val currentView = current as? View ?: return null
            if (currentView is NetworkSignalCluster) return currentView
            current = currentView.parent
        }
    }

    fun setHostTint(tint: Int) {
        hostTint = tint
        renderState()
    }

    override fun setStaticDrawableColor(color: Int, foregroundColor: Int) {
        // Keep the local Smartisan renderer and the modern binder on the same committed host
        // state. Otherwise a later model emission can restore the binders' previous tint.
        setHostTint(color)
        super.setStaticDrawableColor(color, foregroundColor)
    }

    /** Original full-color artwork switch. Normal light/dark status bars do not enable this. */
    fun setColorIcon(enabled: Boolean) {
        if (colorIcon == enabled) return
        colorIcon = enabled
        renderState()
    }

    private fun renderState() {
        val signal = findViewById<ImageView>(R.id.wifi_signal) ?: return
        val wifi6 = requireViewById<ImageView>(R.id.wifi_no)
        val activity = requireViewById<ImageView>(R.id.wifi_inout)
        val tint = ColorStateList.valueOf(hostTint)

        signal.setImageResource(WifiIconPresentation.resolveSignal(state, colorIcon))
        signal.imageTintList = if (colorIcon && state.connected) null else tint

        wifi6.visibility =
            if (state.visible && state.connected && state.wifi6) VISIBLE else GONE
        if (wifi6.visibility == VISIBLE) {
            wifi6.setImageResource(WifiIconPresentation.resolveWifi6(colorIcon))
            wifi6.imageTintList = if (colorIcon) null else tint
        }

        val showActivity =
            resources.getBoolean(R.bool.config_showActivity) &&
                state.visible &&
                state.connected
        activity.visibility = if (showActivity) VISIBLE else GONE
        if (showActivity) {
            activity.setImageResource(WifiIconPresentation.resolveActivity(state.activity))
            activity.imageTintList = tint
        }

        requireViewById<View>(R.id.wifi_group).contentDescription = state.contentDescription
    }

    companion object {
        @SuppressLint("InflateParams")
        @JvmStatic
        fun constructAndBind(
            context: Context,
            slot: String,
            wifiViewModel: LocationBasedWifiViewModel,
        ): WifiView =
            (LayoutInflater.from(context).inflate(R.layout.status_bar_wifi_view, null) as WifiView)
                .apply {
                    applySmartisanGeometry()
                    initView(slot) {
                        WifiViewBinder.bind(this, wifiViewModel)
                    }
                    // Network cluster is not a StatusIconContainer, so nothing else will promote
                    // this from the default STATE_HIDDEN (which keeps wifi_group INVISIBLE).
                    setVisibleState(StatusBarIconView.STATE_ICON, false)
                }
    }
}
