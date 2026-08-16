/*
 * Copyright (C) 2026 OpenSmartisanOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.android.systemui.res.R
import com.smartisanos.keyguard.KeyguardHostView
import com.smartisanos.keyguard.RoundCornerLayout
import com.smartisanos.keyguard.blur.BlurView
import com.smartisanos.keyguard.widgets.ClockView
import com.smartisanos.keyguard.widgets.KeyguardViewPager
import com.smartisanos.keyguard.widgets.ProgressableButton
import com.smartisanos.keyguard.widgets.RepeatingImageButton
import com.smartisanos.keyguard.widgets.TPageMusicWidget
import com.smartisanos.keyguard.widgets.TPageRecorderWidget
import com.smartisanos.keyguard.widgets.WaveView
import com.smartisanos.keyguard.widgets.tpager.TPageBatteryView

/**
 * Typed bridge between the byte-identical R2 XML and Android 16 controllers.
 *
 * Keeping all ID translation here prevents the host/controller code from quietly recreating a
 * second, approximate hierarchy.  The two security placeholders are intentionally disabled:
 * Android 16's bouncer remains the only authentication owner.
 */
class SosOriginalLayoutBinding private constructor(
    val root: KeyguardHostView,
    val appCapture: ImageView,
    val alphaLayer: ImageView,
    val wallpaper: KeyguardViewPager,
    val blur: BlurView,
    val mainPage: FrameLayout,
    val mainSlideLayer: View,
    val widgetPage: RelativeLayout,
    val cameraPage: FrameLayout,
    val cameraContent: ImageView,
    val mainChrome: View,
    val widgetShortcut: ImageView,
    val cameraShortcut: ImageView,
    val time: TextView,
    val amPm: TextView,
    val weekDay: TextView,
    val date: TextView,
    val weatherPanel: RelativeLayout,
    val weatherTemperature: TextView,
    val weatherUnit: TextView,
    val weatherSummary: TextView,
    val weatherAqi: TextView,
    val weatherNoData: TextView,
    val battery: TPageBatteryView,
    val musicPanel: RoundCornerLayout,
    val musicWidget: TPageMusicWidget,
    val musicArtwork: ImageView,
    val musicAppIconContainer: RoundCornerLayout,
    val musicAppIcon: ImageView,
    val musicControls: LinearLayout,
    val musicTitle: TextView,
    val musicArtist: TextView,
    val musicPrevious: RepeatingImageButton,
    val musicExpandedPlayPause: ProgressableButton,
    val musicCollapsedPlayPause: ProgressableButton,
    val musicNext: RepeatingImageButton,
    val recorderPanel: RoundCornerLayout,
    val recorderWidget: TPageRecorderWidget,
    val recorderWave: WaveView,
    val recorderClock: ClockView,
    val recorderMark: ImageView,
    val recorderPause: ImageView,
    val recorderStartStop: ImageView,
    val torchPanel: RoundCornerLayout,
    val torch: ImageView,
    val unlockHandle: ImageView,
) {
    companion object {
        fun inflate(context: Context, parent: ViewGroup): SosOriginalLayoutBinding {
            val root =
                LayoutInflater.from(context)
                    .inflate(R.layout.keyguard_host_view_delta, parent, false) as KeyguardHostView

            val mainPage = root.requireViewById<FrameLayout>(R.id.main_screen)
            val timeStub = mainPage.requireViewById<ViewStub>(R.id.tpage_time_layout)
            val mainChrome = timeStub.inflate()

            // Android 16 renders the real bouncer in KeyguardSecurityContainer.  Leaving either
            // old placeholder interactive would create a transparent input-eating layer.
            root.requireViewById<View>(R.id.kg_unlock_lay).visibility = View.GONE
            root.requireViewById<View>(R.id.kg_sim_unlock_lay).visibility = View.GONE
            val appCapture = root.requireViewById<ImageView>(R.id.app_capture)
            // In the R2 APK this View is only a camera/notes/quick-launch splash. Ordinary unlock
            // composition is owned by Framework's keyguard_background layer.
            appCapture.translationZ = 0f
            appCapture.visibility = View.GONE

            val widgetPage = root.requireViewById<RelativeLayout>(R.id.widget_container)
            val musicPanel = widgetPage.requireViewById<RoundCornerLayout>(R.id.music_container)
            val recorderPanel =
                widgetPage.requireViewById<RoundCornerLayout>(R.id.recorder_container)

            return SosOriginalLayoutBinding(
                root = root,
                appCapture = appCapture,
                alphaLayer = root.requireViewById(R.id.alpha_layer),
                wallpaper = root.requireViewById(R.id.desk_kg),
                blur = root.requireViewById(R.id.blur_background),
                mainPage = mainPage,
                mainSlideLayer = mainPage.requireViewById(R.id.slide_content),
                widgetPage = widgetPage,
                cameraPage = root.requireViewById(R.id.camera_splash_container),
                cameraContent = root.requireViewById(R.id.camera_splash),
                mainChrome = mainChrome,
                widgetShortcut = mainPage.requireViewById(R.id.btn_goto_widget),
                cameraShortcut = mainPage.requireViewById(R.id.btn_goto_camera),
                time = mainChrome.requireViewById(R.id.tv_time),
                amPm = mainChrome.requireViewById(R.id.tv_am_pm),
                weekDay = mainChrome.requireViewById(R.id.tv_week_day),
                date = mainChrome.requireViewById(R.id.tv_date),
                weatherPanel = widgetPage.requireViewById(R.id.weather_container),
                weatherTemperature = widgetPage.requireViewById(R.id.temp_num),
                weatherUnit = widgetPage.requireViewById(R.id.temp_unit),
                weatherSummary = widgetPage.requireViewById(R.id.location_dot_type),
                weatherAqi = widgetPage.requireViewById(R.id.aqi),
                weatherNoData = widgetPage.requireViewById(R.id.no_data_message),
                battery = widgetPage.requireViewById(R.id.battery),
                musicPanel = musicPanel,
                musicWidget = musicPanel.requireViewById(R.id.keyguard_music_widget),
                musicArtwork = musicPanel.requireViewById(R.id.audio_player_album_art),
                musicAppIconContainer =
                    musicPanel.requireViewById(R.id.music_app_icon_round_container),
                musicAppIcon = musicPanel.requireViewById(R.id.music_app_icon),
                musicControls = musicPanel.requireViewById(R.id.music_panel),
                musicTitle = musicPanel.requireViewById(R.id.keyguard_music_track_name),
                musicArtist = musicPanel.requireViewById(R.id.keyguard_musi_singer_name),
                musicPrevious = musicPanel.requireViewById(R.id.audio_player_prev),
                musicExpandedPlayPause = musicPanel.requireViewById(R.id.audio_player_play),
                musicCollapsedPlayPause =
                    musicPanel.requireViewById(R.id.audio_player_play_in_album),
                musicNext = musicPanel.requireViewById(R.id.audio_player_next),
                recorderPanel = recorderPanel,
                recorderWidget = recorderPanel.requireViewById(R.id.keyguard_recorder_widget),
                recorderWave = recorderPanel.requireViewById(R.id.img_sound_wav),
                recorderClock =
                    recorderPanel.requireViewById(R.id.clock_view_recording_time),
                recorderMark = recorderPanel.requireViewById(R.id.btn_mark),
                recorderPause = recorderPanel.requireViewById(R.id.btn_pause_resume),
                recorderStartStop = recorderPanel.requireViewById(R.id.btn_start_stop),
                torchPanel = widgetPage.requireViewById(R.id.torch_container),
                torch = widgetPage.requireViewById(R.id.torch_icon),
                unlockHandle = widgetPage.requireViewById(R.id.unlock_handle),
            )
        }
    }
}
