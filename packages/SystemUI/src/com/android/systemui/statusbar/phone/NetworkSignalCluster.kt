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
package com.android.systemui.statusbar.phone

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.android.systemui.res.R
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.pipeline.mobile.ui.view.SignalClusterView
import com.android.systemui.statusbar.pipeline.wifi.ui.view.WifiView

/**
 * Fixed LTR network cluster: Wi-Fi, SIM1/2/3, no-SIM, airplane. Connectivity views are attached
 * here instead of [StatusIconMerger].
 */
class NetworkSignalCluster
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private lateinit var wifiSlot: FrameLayout
    private lateinit var mobileSlot: LinearLayout
    private lateinit var noSim: ImageView
    private lateinit var airplaneSlot: FrameLayout
    private lateinit var airplaneIcon: ImageView
    private val mobiles = LinkedHashSet<SignalClusterView>()
    private val stateController by lazy { NetworkClusterStateController.get(context) }
    private val stateCallback =
        NetworkClusterStateController.Callback {
            dispatchDefaultTransport()
            refreshContainerVisibility()
            requestLayout()
        }
    private var airplaneView: View? = null
    // Bindables such as stacked_mobile stay attached so their binders keep collecting, but R2
    // never draws them. AOSP STATE_HIDDEN uses INVISIBLE to preserve width for StatusIconContainer
    // overflow math; this cluster is wrap_content and must collapse unused slots like factory GONE.
    private val hiddenAttachments = LinkedHashSet<View>()
    private var hostTint = 0xFFFFFFFF.toInt()
    private var hostForegroundTint = 0xFF000000.toInt()
    private var colorIcons = false
    private var colorIconsRequested = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        wifiSlot = requireViewById(R.id.wifi_slot)
        mobileSlot = requireViewById(R.id.mobile_slot_container)
        noSim = requireViewById(R.id.no_sim_icon)
        airplaneSlot = requireViewById(R.id.airplane_slot_container)
        airplaneIcon =
            ImageView(context).apply {
                setImageResource(R.drawable.stat_sys_signal_flightmode)
                imageTintList = android.content.res.ColorStateList.valueOf(hostTint)
                contentDescription =
                    context.getString(R.string.accessibility_airplane_mode)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        airplaneSlot.addView(airplaneIcon)
        applySmartisanGeometry()
        refreshContainerVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stateController.addCallback(stateCallback)
    }

    override fun onDetachedFromWindow() {
        stateController.removeCallback(stateCallback)
        super.onDetachedFromWindow()
    }

    fun attach(view: View) {
        when (view) {
            is WifiView -> attachToSlot(wifiSlot, view)
            is SignalClusterView -> attachMobile(view)
            else -> {
                if (isAirplane(view)) {
                    airplaneView = view
                    (view.parent as? ViewGroup)?.removeView(view)
                    airplaneSlot.addView(view, frameSlotParams())
                    view.visibility = GONE
                } else {
                    (view.parent as? ViewGroup)?.removeView(view)
                    addView(view, linearSlotParams())
                    hiddenAttachments.add(view)
                    view.visibility = GONE
                }
            }
        }
        applyAppearance(view)
        refreshContainerVisibility()
    }

    fun detach(view: View) {
        when (view) {
            is WifiView -> detachFromSlot(wifiSlot, view)
            is SignalClusterView -> {
                mobiles.remove(view)
                (view.parent as? ViewGroup)?.removeView(view)
            }
            else -> {
                if (airplaneView === view) {
                    airplaneView = null
                    (view.parent as? ViewGroup)?.removeView(view)
                } else {
                    hiddenAttachments.remove(view)
                    (view.parent as? ViewGroup)?.removeView(view)
                }
            }
        }
        refreshContainerVisibility()
    }

    fun setHostTint(tint: Int) {
        setHostTint(tint, tint)
    }

    fun setHostTint(tint: Int, foregroundTint: Int) {
        hostTint = tint
        hostForegroundTint = foregroundTint
        noSim.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        if (::airplaneIcon.isInitialized) {
            airplaneIcon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        }
        forEachChild { applyAppearance(it) }
        applyRequestedColorMode()
    }

    fun setColorIcon(enabled: Boolean) {
        colorIconsRequested = enabled
        applyRequestedColorMode()
    }

    fun currentHostTint(): Int = hostTint

    private fun applyRequestedColorMode() {
        val enabled =
            colorIconsRequested && hostTint == DarkIconDispatcher.DEFAULT_ICON_TINT
        if (colorIcons == enabled) return
        colorIcons = enabled
        forEachChild { applyAppearance(it) }
    }

    fun hasAttached(view: View): Boolean {
        return view.parent === this ||
            view.parent === wifiSlot ||
            view.parent === mobileSlot ||
            view.parent === airplaneSlot ||
            mobiles.contains(view)
    }

    private fun attachMobile(view: SignalClusterView) {
        mobiles.removeIf { it !== view && it.subId == view.subId }
        mobiles.add(view)
        (view.parent as? ViewGroup)?.removeView(view)
        val ordered =
            mobiles.sortedWith(
                compareBy<SignalClusterView>(
                        { NetworkClusterStateController.slotIndex(it.subId) },
                        { it.subId },
                    )
            )
        mobileSlot.removeAllViews()
        for (mobile in ordered) {
            (mobile.parent as? ViewGroup)?.removeView(mobile)
            clearChildMargins(mobile)
            mobileSlot.addView(mobile, linearSlotParams())
        }
        dispatchDefaultTransport()
    }

    private fun dispatchDefaultTransport() {
        val wifiDefault =
            (wifiSlot.getChildAt(0) as? WifiView)?.isDefaultNetwork() == true
        for (mobile in mobiles) {
            mobile.setWifiDefault(wifiDefault)
        }
    }

    fun onWifiStateChanged() {
        dispatchDefaultTransport()
        refreshContainerVisibility()
        requestLayout()
    }

    private fun clearChildMargins(view: View) {
        (view.layoutParams as? MarginLayoutParams)?.let {
            it.marginStart = 0
            it.marginEnd = 0
            view.layoutParams = it
        }
    }

    private fun attachToSlot(slot: FrameLayout, view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        slot.removeAllViews()
        clearChildMargins(view)
        slot.addView(view, frameSlotParams())
        if (view is WifiView) dispatchDefaultTransport()
    }

    private fun detachFromSlot(slot: FrameLayout, view: View) {
        if (view.parent === slot) {
            slot.removeView(view)
        }
        if (view is WifiView) dispatchDefaultTransport()
    }

    private fun refreshContainerVisibility() {
        if (!::wifiSlot.isInitialized) return
        val demoAirplane = stateController.demoAirplane()
        val airplane = airplaneView
        val airplaneOn = demoAirplane ?: airplaneVisible()
        // R2 owns a stable airplane drawable. Android's bindable airplane View is kept attached
        // only as a state source because it may not exist at all until airplane mode is first
        // enabled, and its View visibility is independent from StatusIconDisplayable.visibleState.
        airplane?.visibility = GONE
        setVisibilityIfChanged(airplaneIcon, airplaneOn)
        setVisibilityIfChanged(wifiSlot, hasLogicallyVisibleChild(wifiSlot))
        setVisibilityIfChanged(
            mobileSlot,
            !airplaneOn && hasLogicallyVisibleChild(mobileSlot),
        )
        setVisibilityIfChanged(airplaneSlot, airplaneOn)
        setVisibilityIfChanged(noSim, !airplaneOn && stateController.shouldShowNoSim())
        for (hidden in hiddenAttachments) {
            setVisibilityIfChanged(hidden, false)
        }
    }

    private fun hasLogicallyVisibleChild(parent: ViewGroup): Boolean {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is StatusIconDisplayable) {
                if (child.isIconVisible && !child.isIconBlocked) return true
            } else if (child.visibility == VISIBLE) {
                return true
            }
        }
        return false
    }

    private fun airplaneVisible(): Boolean {
        val view = airplaneView ?: return false
        return if (view is StatusIconDisplayable) view.isIconVisible else view.visibility == VISIBLE
    }

    private fun isAirplane(view: View): Boolean {
        val slot =
            (view as? StatusIconDisplayable)?.slot
                ?: (view as? StatusBarIconView)?.slot
                ?: return false
        val airplane = context.getString(com.android.internal.R.string.status_bar_airplane)
        return slot == airplane
    }

    private fun applyAppearance(view: View) {
        when (view) {
            is WifiView -> {
                view.setStaticDrawableColor(hostTint, hostForegroundTint)
                view.setColorIcon(colorIcons)
            }
            is SignalClusterView -> {
                view.setStaticDrawableColor(hostTint, hostForegroundTint)
                view.setColorIcon(colorIcons)
            }
            is StatusIconDisplayable -> {
                view.setStaticDrawableColor(hostTint, hostForegroundTint)
                view.setDecorColor(hostTint)
            }
        }
    }

    private fun forEachChild(block: (View) -> Unit) {
        for (i in 0 until wifiSlot.childCount) block(wifiSlot.getChildAt(i))
        for (i in 0 until mobileSlot.childCount) block(mobileSlot.getChildAt(i))
        airplaneView?.let(block)
    }

    private fun linearSlotParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            .apply {
                marginStart = itemMarginStart
                marginEnd = itemMarginEnd
            }

    private fun frameSlotParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL,
            )
            .apply {
                marginStart = itemMarginStart
                marginEnd = itemMarginEnd
            }

    private var itemMarginStart = 0
    private var itemMarginEnd = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshContainerVisibility()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        applySmartisanGeometry()
    }

    private fun applySmartisanGeometry() {
        if (!::noSim.isInitialized) return
        val metrics = StatusBarGeometry.calculate(this)
        itemMarginStart = metrics.itemMarginStart
        itemMarginEnd = metrics.itemMarginEnd
        (noSim.layoutParams as? MarginLayoutParams)?.let {
            it.height = metrics.iconHeight
            it.marginStart = metrics.itemMarginStart
            it.marginEnd = metrics.itemMarginEnd
            noSim.layoutParams = it
        }
        if (::airplaneIcon.isInitialized) {
            airplaneIcon.layoutParams =
                FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        metrics.iconHeight,
                        Gravity.CENTER_VERTICAL,
                    )
                    .apply {
                        marginStart = metrics.itemMarginStart
                        marginEnd = metrics.itemMarginEnd
                    }
        }
        // Preserve the LayoutParams class required by each concrete parent. Assigning this
        // LinearLayout's LayoutParams to a child of wifiSlot (FrameLayout) crashes the next
        // measure after a rotation.
        for (i in 0 until wifiSlot.childCount) {
            val child = wifiSlot.getChildAt(i)
            child.layoutParams =
                (child.layoutParams as? FrameLayout.LayoutParams ?: frameSlotParams()).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = itemMarginStart
                    marginEnd = itemMarginEnd
                }
        }
        for (i in 0 until mobileSlot.childCount) {
            val child = mobileSlot.getChildAt(i)
            child.layoutParams =
                (child.layoutParams as? LinearLayout.LayoutParams ?: linearSlotParams()).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    marginStart = itemMarginStart
                    marginEnd = itemMarginEnd
                }
        }
    }

    private fun setVisibilityIfChanged(view: View, visible: Boolean) {
        val target = if (visible) VISIBLE else GONE
        if (view.visibility != target) view.visibility = target
    }
}
