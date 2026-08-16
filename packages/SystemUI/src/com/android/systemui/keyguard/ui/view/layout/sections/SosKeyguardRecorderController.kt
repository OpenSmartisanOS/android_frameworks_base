/*
 * Copyright (C) 2026 OpenSmartisanOS
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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.smartisanos.recorder.service.ICallback
import com.smartisanos.recorder.service.IRecorderCreatorService

/**
 * Client for the original Smartisan Recorder creator service used by the lockscreen widget.
 *
 * Unlike the Lineage Recorder's internal, non-exported service, this is the public Recorder
 * integration point.  The package name intentionally remains Lineage's so the compatibility
 * service can live in the currently shipped Recorder APK while preserving Smartisan's Binder
 * contract.
 */
internal class SosKeyguardRecorderController(
    context: Context,
    private val listener: Listener,
) {
    enum class State { READY, RECORDING, PAUSED, UNAVAILABLE }

    interface Listener {
        fun onStateChanged(state: State)
        fun onElapsedTimeChanged(seconds: Long)
        fun onAmplitudeChanged(amplitude: Int)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: IRecorderCreatorService? = null
    private var bound = false
    private val recordingCallback =
        object : ICallback.Stub() {
            override fun done(data: Bundle?) {
                // Binder callbacks arrive on a binder pool thread.  Views are driven by the
                // keyguard main thread, just as the original widget's Handler did.
                val snapshot = if (data == null) null else Bundle(data)
                mainHandler.post { handleRecorderCallback(snapshot) }
            }
        }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    val recorder = IRecorderCreatorService.Stub.asInterface(binder)
                    service = recorder
                    // This is the original TPageRecorderWidget bind sequence.  Register first
                    // so a state change between the query and registration cannot be lost.
                    recorder.registerRecordingCallback(recordingCallback)
                    recorder.getRecordingCurrentState(recordingCallback)
                    notifyState(recorder.getRecordingState())
                } catch (error: RemoteException) {
                    Log.w(TAG, "Recorder creator service disconnected while registering", error)
                    service = null
                    notifyUnavailable()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                notifyUnavailable()
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
                bound = false
                notifyUnavailable()
            }

            override fun onNullBinding(name: ComponentName) {
                service = null
                notifyUnavailable()
            }
        }

    fun attach() {
        if (bound) return
        bound =
            runCatching {
                appContext.bindService(
                    creatorServiceIntent(),
                    connection,
                    // Android 11+ does not implicitly pass a top client's while-in-use
                    // capabilities across a binding.  Recorder needs the microphone
                    // capability while this lockscreen-hosted client is visible.
                    Context.BIND_AUTO_CREATE or
                        Context.BIND_IMPORTANT or
                        Context.BIND_INCLUDE_CAPABILITIES,
                )
            }
            .getOrDefault(false)
        if (!bound) notifyUnavailable()
    }

    fun detach() {
        if (!bound) return
        runCatching {
            service?.unregisterRecordingCallback(recordingCallback)
        }
        runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
    }

    fun start() {
        if (service == null) {
            Log.w(TAG, "Recorder creator service is unavailable for start")
            notifyUnavailable()
            return
        }
        // The original implementation pre-dates Android's foreground-service contract and
        // started its recorder through Binder only.  Android 16 requires the user click to
        // create a started FGS before microphone work begins; otherwise the service dies when
        // the lockscreen host later unbinds.  The action is explicit and protected by
        // STATUS_BAR; the original AIDL endpoint remains responsible for state and controls.
        runCatching { appContext.startForegroundService(foregroundServiceIntent()) }
            .onFailure { error ->
                Log.w(TAG, "Unable to start Recorder as a foreground service", error)
                notifyUnavailable()
            }
    }

    fun pause() = callRecorder("pause") { it.pauseRecording(recordingCallback) }

    fun resume() = callRecorder("resume") { it.resumeRecording(recordingCallback) }

    fun mark() = callRecorder("mark") { it.mark(recordingCallback) }

    fun stop() = callRecorder("stop") { it.stopRecordingWithNotification(recordingCallback) }

    private fun callRecorder(operation: String, block: (IRecorderCreatorService) -> Unit) {
        val recorder = service
        if (recorder == null) {
            Log.w(TAG, "Recorder creator service is unavailable for $operation")
            notifyUnavailable()
            return
        }
        try {
            block(recorder)
        } catch (error: RemoteException) {
            Log.w(TAG, "Recorder creator service failed to $operation", error)
            service = null
            notifyUnavailable()
        }
    }

    private fun handleRecorderCallback(data: Bundle?) {
        if (data == null) return

        // The compatibility service emits the short names below.  The fallback names are the
        // original RecorderService payload, preserved for a direct Smartisan Recorder port.
        if (data.containsKey(KEY_RECORD_STATE)) {
            notifyState(data.getInt(KEY_RECORD_STATE))
        } else if (data.containsKey(KEY_ORIGINAL_RECORD_STATE)) {
            notifyState(data.getInt(KEY_ORIGINAL_RECORD_STATE))
        } else if (data.containsKey(KEY_EVENT)) {
            when (data.getInt(KEY_EVENT)) {
                EVENT_RECORD_START,
                EVENT_RECORD_RESUME -> listener.onStateChanged(State.RECORDING)
                EVENT_RECORD_PAUSE -> listener.onStateChanged(State.PAUSED)
                EVENT_RECORD_STOP,
                EVENT_RECORD_FILE_SAVED -> listener.onStateChanged(State.READY)
            }
        }

        when {
            data.containsKey(KEY_ELAPSED_SECONDS) ->
                listener.onElapsedTimeChanged(data.getLong(KEY_ELAPSED_SECONDS))
            data.containsKey(KEY_TOTAL_POINT) ->
                listener.onElapsedTimeChanged(data.getLong(KEY_TOTAL_POINT) * WAVE_POINT_MS / 1000L)
        }

        when {
            data.containsKey(KEY_AMPLITUDE) -> listener.onAmplitudeChanged(data.getInt(KEY_AMPLITUDE))
            data.containsKey(KEY_WAVE_DATA) -> {
                // The original client renders the waveform itself.  This port keeps a compact
                // indicator and derives a compatible 16-bit peak amplitude from that payload.
                val peak = data.getByteArray(KEY_WAVE_DATA)?.maxOfOrNull { it.toInt() and 0xFF } ?: 0
                listener.onAmplitudeChanged(peak * 257)
            }
        }
    }

    private fun notifyState(recorderState: Int) {
        listener.onStateChanged(
            when (recorderState) {
                RECORD_STATE_IDLE,
                RECORD_STATE_STOPPED -> State.READY
                RECORD_STATE_RECORDING -> State.RECORDING
                RECORD_STATE_PAUSED,
                RECORD_STATE_PAUSED_BY_CALL -> State.PAUSED
                else -> State.UNAVAILABLE
            }
        )
    }

    private fun notifyUnavailable() {
        listener.onStateChanged(State.UNAVAILABLE)
    }

    private fun creatorServiceIntent(): Intent {
        return Intent(ACTION_GET_RECORDER_CREATOR_SERVICE).apply {
            // Do not bind the Lineage internal SoundRecorderService directly: it is deliberately
            // not exported.  The exported compatibility service owns this original action.
            setPackage(RECORDER_PACKAGE)
        }
    }

    private fun foregroundServiceIntent(): Intent {
        return Intent(ACTION_START_SMARTISAN_LOCKSCREEN).apply {
            // This does not need an intent-filter: an explicit target avoids exposing an
            // additional public action, while the service's STATUS_BAR permission protects it.
            component = ComponentName(RECORDER_PACKAGE, RECORDER_SERVICE_CLASS)
        }
    }

    private companion object {
        const val TAG = "SosKeyguardRecorder"
        const val RECORDER_PACKAGE = "org.lineageos.recorder"
        const val RECORDER_SERVICE_CLASS =
            "org.lineageos.recorder.service.SoundRecorderService"
        const val ACTION_GET_RECORDER_CREATOR_SERVICE = "action_get_recorder_creator_service"
        const val ACTION_START_SMARTISAN_LOCKSCREEN =
            "org.lineageos.recorder.action.START_SMARTISAN_LOCKSCREEN"

        // Recorder compatibility service payload.
        const val KEY_RECORD_STATE = "record_state"
        const val KEY_ELAPSED_SECONDS = "elapsed_seconds"
        const val KEY_AMPLITUDE = "amplitude"

        // Original Smartisan RecorderService payload.
        const val KEY_ORIGINAL_RECORD_STATE = "recorder_state"
        const val KEY_EVENT = "event"
        const val KEY_TOTAL_POINT = "total_point"
        const val KEY_WAVE_DATA = "wave_data"

        const val RECORD_STATE_IDLE = 0
        const val RECORD_STATE_RECORDING = 1
        const val RECORD_STATE_PAUSED = 2
        const val RECORD_STATE_PAUSED_BY_CALL = 3
        const val RECORD_STATE_STOPPED = 4

        const val EVENT_RECORD_START = 61
        const val EVENT_RECORD_STOP = 63
        const val EVENT_RECORD_RESUME = 65
        const val EVENT_RECORD_PAUSE = 66
        const val EVENT_RECORD_FILE_SAVED = 69
        const val WAVE_POINT_MS = 20L
    }
}
