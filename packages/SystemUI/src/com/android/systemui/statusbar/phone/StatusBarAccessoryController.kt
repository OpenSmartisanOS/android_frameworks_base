/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.statusbar.phone

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbPortStatus
import android.net.TrafficStats
import android.os.Handler
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.DefaultTransport
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.policy.ConfigurationController
import java.text.DecimalFormat
import java.util.IdentityHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shared OneStep, OTG and original two-second network-speed state for R2 hosts. */
@SysUISingleton
class StatusBarAccessoryController
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val handler: Handler,
    private val screenLifecycle: ScreenLifecycle,
    private val demoModeController: DemoModeController,
    private val connectivityRepository: ConnectivityRepository,
    private val configurationController: ConfigurationController,
) : ScreenLifecycle.Observer, DemoMode, ConfigurationController.ConfigurationListener {
    internal data class TrafficSample(val rx: Long, val tx: Long)

    private data class Host(
        val root: View,
        val sidebar: ImageView?,
        val otg: ImageView?,
        val speed: ViewGroup?,
        val speedValue: TextView?,
        val speedUnit: TextView?,
    )

    // Registration is paired with the status-bar host lifecycle. Keep an identity keyed map here:
    // a weak set whose key is the Host wrapper itself can be collected immediately even while its
    // root View remains attached, silently stopping OneStep/OTG/speed updates.
    private val hosts = IdentityHashMap<View, Host>()
    private val resolver: ContentResolver = context.contentResolver
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var listening = false
    private var demoMode = false
    private var previousTraffic: TrafficSample? = null
    private var screenOn = screenLifecycle.screenState == ScreenLifecycle.SCREEN_ON
    private var defaultNetworkUsable = false
    private var networkCollection: Job? = null
    private var networkGeneration = 0L
    private var speedRunning = false
    private var lastSpeedValue = "0.0"
    private var lastSpeedUnit = "K"

    private val speedTick =
        object : Runnable {
            override fun run() {
                updateSpeed()
                if (shouldRunSpeed()) handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    private val otgUpdate = Runnable(::updateOtg)
    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                updateSidebar()
                updateSpeedState()
            }
        }
    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handler.removeCallbacks(otgUpdate)
                handler.postDelayed(otgUpdate, OTG_DEBOUNCE_MS)
            }
        }

    fun registerHost(root: View) {
        if (hosts.containsKey(root)) return
        val host =
            Host(
                root = root,
                sidebar = root.findViewById(R.id.sidebar_drag),
                otg = root.findViewById(R.id.otg),
                speed = root.findViewById(R.id.net_speed_view),
                speedValue = root.findViewById(R.id.net_speed),
                speedUnit = root.findViewById(R.id.net_unit),
            )
        hosts[root] = host
        updateHostGeometry(host)
        if (!listening) startListening()
        updateSidebar()
        updateOtg()
        updateSpeedState()
    }

    fun unregisterHost(root: View) {
        hosts.remove(root)
        if (hosts.isEmpty()) stopListening()
    }

    private fun startListening() {
        listening = true
        // ScreenLifecycle does not replay callbacks. Refresh this every time the first host is
        // attached so a status-bar reconstruction while the display is off cannot start the
        // two-second sampler with a stale SCREEN_ON value.
        screenOn = screenLifecycle.screenState == ScreenLifecycle.SCREEN_ON
        resolver.registerContentObserver(
            Settings.Global.getUriFor(SETTING_SIDE_BAR_MODE),
            false,
            settingsObserver,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(SETTING_SIDEBAR_INNER_DRAG_STATE),
            false,
            settingsObserver,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(SETTING_ORIGINAL_INNER_DRAG_ICON_STATE),
            false,
            settingsObserver,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(SETTING_SHOW_NETWORK_SPEED),
            false,
            settingsObserver,
        )
        val usbFilter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_PORT_CHANGED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
        context.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        screenLifecycle.addObserver(this)
        demoModeController.addCallback(this)
        configurationController.addCallback(this)
        defaultNetworkUsable =
            isUsableDefaultNetwork(connectivityRepository.defaultConnections.value)
        val generation = ++networkGeneration
        networkCollection =
            applicationScope.launch {
                connectivityRepository.defaultConnections.collect { state ->
                    val usable = isUsableDefaultNetwork(state)
                    handler.post {
                        if (
                            !listening ||
                                generation != networkGeneration ||
                                defaultNetworkUsable == usable
                        ) {
                            return@post
                        }
                        defaultNetworkUsable = usable
                        updateSpeedState()
                    }
                }
            }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        resolver.unregisterContentObserver(settingsObserver)
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {}
        screenLifecycle.removeObserver(this)
        demoModeController.removeCallback(this)
        configurationController.removeCallback(this)
        networkCollection?.cancel()
        networkCollection = null
        networkGeneration++
        defaultNetworkUsable = false
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(otgUpdate)
        previousTraffic = null
        speedRunning = false
    }

    private fun updateSidebar() {
        val sideBarMode =
            Settings.Global.getInt(resolver, SETTING_SIDE_BAR_MODE, 1) == 1
        // The distribution service publishes sidebar_innerdrag_state. Preserve the factory R2
        // innerdrag_icon_state key as a migration fallback for devices carrying old userdata.
        val migratedInnerDrag =
            Settings.Global.getString(resolver, SETTING_SIDEBAR_INNER_DRAG_STATE)
        val innerDrag =
            if (migratedInnerDrag != null) {
                migratedInnerDrag.toIntOrNull() == 1
            } else {
                Settings.Global.getInt(resolver, SETTING_ORIGINAL_INNER_DRAG_ICON_STATE, 0) == 1
            }
        val enabled = shouldShowSidebar(sideBarMode, innerDrag, demoMode)
        hosts.values.toList().forEach { host ->
            host.sidebar?.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) host.sidebar?.setImageResource(R.drawable.sidebar_drag)
        }
    }

    private fun updateOtg() {
        val source = !demoMode && isUsbSource()
        hosts.values.toList().forEach {
            it.otg?.visibility = if (source) View.VISIBLE else View.GONE
        }
    }

    private fun isUsbSource(): Boolean =
        try {
            usbManager?.ports?.any { port ->
                val status = port.status
                status != null && isSourcePort(status.isConnected, status.currentPowerRole)
            } == true
        } catch (_: RuntimeException) {
            false
        }

    private fun updateSpeedState() {
        val show = shouldRunSpeed()
        hosts.values.toList().forEach { host ->
            host.speed?.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                host.speedValue?.text = lastSpeedValue
                host.speedUnit?.text = lastSpeedUnit
            }
        }
        if (show == speedRunning) return

        handler.removeCallbacks(speedTick)
        speedRunning = show
        if (show) {
            // Establish one process-wide baseline. Network state emissions commonly arrive in
            // bursts; they must not keep moving this baseline and starve every Host of updates.
            previousTraffic = readTrafficSample()
            formatSpeed(0.0).also { (value, unit) ->
                lastSpeedValue = value
                lastSpeedUnit = unit
            }
            hosts.values.toList().forEach { host ->
                host.speedValue?.text = lastSpeedValue
                host.speedUnit?.text = lastSpeedUnit
            }
            handler.postDelayed(speedTick, UPDATE_INTERVAL_MS)
        } else {
            previousTraffic = null
            lastSpeedValue = "0.0"
            lastSpeedUnit = "K"
        }
    }

    private fun shouldRunSpeed(): Boolean =
        listening &&
            !demoMode &&
            screenOn &&
            Settings.Global.getInt(resolver, SETTING_SHOW_NETWORK_SPEED, 0) == 1 &&
            defaultNetworkUsable

    private fun updateSpeed() {
        if (!shouldRunSpeed()) {
            updateSpeedState()
            return
        }
        val now = readTrafficSample()
        val delta = calculateDelta(previousTraffic, now)
        previousTraffic = now
        val perSecond = delta / (UPDATE_INTERVAL_MS / 1000.0)
        val (value, unit) = formatSpeed(perSecond)
        lastSpeedValue = value
        lastSpeedUnit = unit
        hosts.values.toList().forEach { host ->
            host.speedValue?.text = value
            host.speedUnit?.text = unit
        }
    }

    private fun readTrafficSample(): TrafficSample? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }
        if (rx < 0L || tx < 0L) return null
        return TrafficSample(rx, tx)
    }

    override fun onScreenTurnedOn() {
        screenOn = true
        updateSpeedState()
    }

    override fun onScreenTurnedOff() {
        screenOn = false
        updateSpeedState()
    }

    override fun onDemoModeStarted() {
        demoMode = true
        updateSidebar()
        updateOtg()
        updateSpeedState()
    }

    override fun onDemoModeFinished() {
        demoMode = false
        updateSidebar()
        updateOtg()
        updateSpeedState()
    }

    override fun onConfigChanged(newConfig: Configuration?) {
        hosts.values.toList().forEach(::updateHostGeometry)
    }

    private fun updateHostGeometry(host: Host) {
        host.speed?.let { speed ->
            val width = StatusBarGeometry.calculate(speed).networkSpeedWidth
            if (speed.layoutParams.width != width) {
                speed.layoutParams = speed.layoutParams.apply { this.width = width }
            }
        }
    }

    override fun dispatchDemoCommand(command: String, args: android.os.Bundle) = Unit

    internal companion object {
        const val SETTING_SHOW_NETWORK_SPEED = "show_network_speed"
        const val SETTING_SIDE_BAR_MODE = "side_bar_mode"
        const val SETTING_SIDEBAR_INNER_DRAG_STATE = "sidebar_innerdrag_state"
        const val SETTING_ORIGINAL_INNER_DRAG_ICON_STATE = "innerdrag_icon_state"
        const val UPDATE_INTERVAL_MS = 2_000L
        const val OTG_DEBOUNCE_MS = 200L

        fun shouldShowSidebar(sideBarMode: Boolean, innerDrag: Boolean, demoMode: Boolean): Boolean =
            sideBarMode && innerDrag && !demoMode

        fun isSourcePort(connected: Boolean, powerRole: Int): Boolean =
            connected && powerRole == UsbPortStatus.POWER_ROLE_SOURCE

        fun isUsableDefaultNetwork(state: DefaultConnectionModel): Boolean =
            state.isValidated && state.defaultTransport != DefaultTransport.NONE

        fun formatSpeed(bytesPerSecond: Double): Pair<String, String> {
            val kib = (bytesPerSecond / 1024.0).coerceAtLeast(0.0)
            val value: Double
            val unit: String
            if (kib >= 100.0) {
                value = (kib / 1024.0).coerceAtLeast(0.1)
                unit = "M"
            } else {
                value = kib
                unit = "K"
            }
            val integerDigits = value.toInt().toString().length
            val fractionDigits = abs(integerDigits - 2)
            val pattern =
                if (fractionDigits == 0) "0" else "0." + "0".repeat(fractionDigits)
            return DecimalFormat(pattern).format(value) to unit
        }

        fun combineTrafficBytes(rx: Long, tx: Long): Long? {
            if (
                rx == TrafficStats.UNSUPPORTED.toLong() ||
                    tx == TrafficStats.UNSUPPORTED.toLong() ||
                    rx < 0L ||
                    tx < 0L
            ) {
                return null
            }
            return if (Long.MAX_VALUE - rx < tx) Long.MAX_VALUE else rx + tx
        }

        fun calculateDelta(previous: TrafficSample?, current: TrafficSample?): Long {
            if (
                previous == null ||
                    current == null ||
                    current.rx < previous.rx ||
                    current.tx < previous.tx
            ) {
                return 0L
            }
            return combineTrafficBytes(current.rx - previous.rx, current.tx - previous.tx) ?: 0L
        }
    }
}
