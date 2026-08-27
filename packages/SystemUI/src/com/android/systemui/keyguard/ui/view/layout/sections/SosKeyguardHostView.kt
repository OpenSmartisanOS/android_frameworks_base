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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.StatusBarManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.view.ViewTreeObserver
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Scroller
import android.widget.TextView
import android.widget.VideoView
import com.android.keyguard.SosBouncerVisualCoordinator
import com.android.internal.policy.SystemBarUtils
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dependency
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants
import com.android.systemui.keyguard.SosKeyguardRuntime
import com.android.systemui.res.R
import com.android.systemui.biometrics.AuthController
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.shade.CameraLauncher
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.ui.SystemIconsController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.smartisanos.keyguard.RoundCornerLayout
import com.smartisanos.keyguard.widgets.ClockView
import com.smartisanos.keyguard.widgets.QuickLaunchPickerView
import java.io.FileInputStream
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Modern implementation of the SmartisanOS R2 Delta lockscreen surface.
 *
 * The view owns only presentation and gesture arbitration. Authentication, camera policy,
 * battery state and flashlight state remain backed by their Android 16 SystemUI controllers.
 */
class SosKeyguardHostView(
    context: Context,
    private val cameraLauncher: CameraLauncher,
    private val flashlightController: FlashlightController,
    private val batteryController: BatteryController,
    private val notificationMediaManager: NotificationMediaManager,
    private val navigationModeController: NavigationModeController,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val systemIconsController: SystemIconsController,
    private val screenLifecycle: ScreenLifecycle,
    private val authController: AuthController,
    private val mainExecutor: Executor,
    private val backgroundExecutor: Executor,
) : FrameLayout(context),
    SosKeyguardUnlockGestureTarget,
    FlashlightController.FlashlightListener,
    BatteryController.BatteryStateChangeCallback,
    StatusBarStateController.StateListener {

    internal enum class WallpaperLoadState {
        IDLE,
        PENDING,
        READY,
        FAILED,
    }

    private val originalBinding: SosOriginalLayoutBinding
    private val wallpaperView: ImageView
    private val pageContainer: View
    private val widgetPage: View
    private val mainPage: View
    private val cameraPage: View
    private val cameraContent: ImageView
    private val pinnedPreviewLayer: FrameLayout
    private val aodLayer: View
    private val aodTimeView: TextView
    private val aodSecondaryView: TextView
    private val aodSecondContainer: LinearLayout
    private val aodBatteryView: ImageView
    private val aodChargeView: ImageView
    private val aodFingerprintView: ImageView
    private val chargingOverlay: FrameLayout
    private val chargingVideoView: VideoView
    private val chargingFrameView: ImageView
    private val quickSelectorView: SosQuickLaunchSelectorView
    private val quickPickerView: QuickLaunchPickerView
    private val mainChrome: View
    private val widgetShortcutView: ImageView
    private val cameraShortcutView: ImageView
    private val mainTimeView: TextView
    private val mainAmPmView: TextView
    private val mainWeekDayView: TextView
    private val mainDateView: TextView
    private val weatherTemperatureView: TextView
    private val weatherUnitView: TextView
    private val weatherSummaryView: TextView
    private val weatherAqiView: TextView
    private val batteryView: SosR2BatteryView
    private val weatherPanel: View
    private val musicPanel: View
    private val recorderPanel: View
    private val torchPanel: View
    private val unlockHandle: ImageView
    private val torchButton: ImageView
    private val musicArtworkView: ImageView
    private val musicAppIconContainer: RoundCornerLayout
    private val musicAppIconView: ImageView
    private val musicControlsView: LinearLayout
    private val musicTitleView: TextView
    private val musicArtistView: TextView
    private val musicPreviousView: ImageView
    private val musicPlayPauseView: ImageView
    private val musicExpandedPlayPauseView: ImageView
    private val musicNextView: ImageView
    private val recorderClockView: ClockView
    private val recorderWaveView: SosR2RecorderWaveView
    private val recorderMarkView: ImageView
    private val recorderPauseView: ImageView
    private val recorderStartStopView: ImageView
    private val timeController: SosKeyguardTimeController
    private val recorderController: SosKeyguardRecorderController
    // Initialized in init before screenObserver is registered in onAttachedToWindow.
    private lateinit var pinnedTaskController: SosKeyguardPinnedTaskController

    private val quickLaunchStore = SosQuickLaunchStore(context)
    private val quickLaunchResolver = SosQuickLaunchResolver(context)
    private val keyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor::class.java)
    /**
     * Display.STATE_ON is also used by this device's AOD, so ScreenLifecycle cannot distinguish an
     * ambient panel from an interactive wake.  This session bit follows wakefulness callbacks and
     * is initialized from KeyguardUpdateMonitor for process starts that attach directly in AOD.
     */
    private var awakeLockscreenSession = keyguardUpdateMonitor.isDeviceInteractive
    private var quickLaunchSlots: List<SosQuickLaunchTarget?> = SosQuickLaunchTarget.DEFAULT_SLOTS
    private var quickPressEligible = false
    private var quickSelectorExpanded = false
    private var quickEditorVisible = false
    private var quickDownRawX = 0f
    private var quickDownRawY = 0f
    private var quickDownTime = 0L
    private var quickLastRawX = 0f
    private var quickLastRawY = 0f
    private data class PendingQuickLaunch(
        val generation: Int,
        val userId: Int,
        val target: SosResolvedQuickLaunchTarget,
    )

    private data class PendingQuickConfiguration(
        val generation: Int,
        val userId: Int,
        val slots: List<SosQuickLaunchTarget?>,
    )

    private var pendingQuickLaunchTarget: PendingQuickLaunch? = null
    private var pendingQuickConfiguration: PendingQuickConfiguration? = null
    private var quickActionGeneration = 0
    private var quickCandidateLoadGeneration = 0

    private var currentPage = PAGE_MAIN
    private var pagePosition = PAGE_MAIN.toFloat()
    private var pageAnimator: ValueAnimator? = null
    private var musicAnimator: ValueAnimator? = null
    private var mainScaleAnimator: ValueAnimator? = null
    private var mainScalePercent = 0f
    private var mainTouching = false
    private var pressedWidget: View? = null
    private var pureWallpaper = false
    private var mainTapRawX = Float.NaN
    private var mainTapRawY = Float.NaN
    private var flashlightAvailable = false
    private var flashlightEnabled = false
    private var batteryLevel = 0
    private var batteryPluggedIn = false
    private var batteryCharging = false
    private var recorderState = SosKeyguardRecorderController.State.READY
    private var musicExpanded = false
    private var aodAtTop = true
    private var aodLastMoveTime = 0L
    private var layoutInsets = SosKeyguardLayoutModel.Insets()
    private var layoutMetrics: SosKeyguardLayoutModel.Metrics? = null
    private var navigationMode =
        resources.getInteger(com.android.internal.R.integer.config_navBarInteractionMode)
    private var pageBlurProgress = 0f
    private var bouncerBlurProgress = 0f
    private var wallpaperTheme = SosKeyguardWallpaperTheme.DARK_WALLPAPER
    @Volatile private var wallpaperLoadGeneration = 0
    @Volatile private var wallpaperLoadState = WallpaperLoadState.IDLE
    @Volatile private var wallpaperLoadStateGeneration = -1
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)
    private val wallpaperCache = SosKeyguardWallpaperCache(context)
    private var wallpaperWorker: ExecutorService = newWallpaperWorker()
    private var wallpaperRetryRunnable: Runnable? = null
    private var wallpaperUsingOpaqueFallback = false
    private var ownedWallpaperBitmap: Bitmap? = null
    private var wallpaperInstalledGeneration = -1
    private val wallpaperCacheWriteLock = Any()
    private val wallpaperCacheWriteRefs = IdentityHashMap<Bitmap, Int>()
    private val wallpaperRecycleAfterCacheWrite =
        Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
    private val smartisanRegularTypeface by lazy {
        resources.getFont(R.font.smartisan_compact_regular)
    }
    private val smartisanBoldTypeface by lazy {
        resources.getFont(R.font.smartisan_compact_bold)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var gestureMode = GESTURE_NONE
    private var topGestureExcluded = false
    private var maximumPointerCount = 1
    private var velocityTracker: VelocityTracker? = null
    private var verticalOffset = 0f
    private var verticalScroller: Scroller? = null
    private var verticalSettleTarget = SosBouncerVisualCoordinator.SettleTarget.MAIN
    private var verticalBouncerRequested = false
    private var externalVerticalGestureOwned = false
    /**
     * Keeps the final R2 slide-out frame stable while Android hands the surface to Launcher.
     * Bouncer/detach callbacks can arrive during that hand-off and must not snap the host back
     * to its resting geometry before the keyguard surface is actually removed.
     */
    private var noSecurityUnlockCommitted = false
    private var launcherDismissNotified = false
    private var verticalGestureStartedWithSecurity = false
    private var verticalGestureCandidateWithSecurity: Boolean? = null
    private var verticalGestureDownY = 0f
    private var verticalOriginPage = PAGE_MAIN
    private var verticalOriginPosition = PAGE_MAIN.toFloat()
    private var credentialDismissActive = false
    private var credentialDismissView: View? = null
    private var credentialDismissFinishRunnable: Runnable? = null
    private var credentialDismissLauncherNotified = false
    private var credentialDismissGeneration = 0L
    private var credentialDismissUserId = UserHandle.USER_NULL
    /**
     * R2 keeps the existing main page in a wallpaper-only state while a secure upward gesture
     * returns TPage to the centre. This is deliberately separate from [pureWallpaper], which is
     * the user-selectable resting main-page mode.
     */
    private var widgetSecureTransitionActive = false
    private var firstFrameNeedsRelayout = false
    private var firstFramePreDrawAttempts = 0
    private var firstFramePreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private val aodHandler = Handler(Looper.getMainLooper())
    private val aodTicker =
        object : Runnable {
            override fun run() {
                updateAod()
                aodHandler.postDelayed(this, AOD_UPDATE_INTERVAL_MS)
            }
        }
    private val hideChargingRunnable = Runnable { hideChargingAnimation() }
    private val showQuickSelectorRunnable = Runnable { showQuickSelector() }
    private val showQuickEditorRunnable = Runnable { showQuickEditor() }
    private val notifyLauncherDismissRunnable = Runnable { notifyLauncherDismissIfNeeded() }
    private val notifyCredentialDismissRunnable =
        Runnable { notifyCredentialDismissLauncherIfNeeded() }
    private val keyguardUpdateCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onUserSwitching(userId: Int) {
                dispatchToMain {
                    cancelWallpaperRetry()
                    wallpaperLoadGeneration++
                    installOpaqueBlackWallpaper()
                    setWallpaperLoadState(
                        wallpaperLoadGeneration,
                        WallpaperLoadState.PENDING,
                    )
                    cancelOriginalCredentialDismiss()
                    cancelQuickLaunchState(returnToMain = true)
                }
            }

            override fun onUserSwitchComplete(userId: Int) {
                dispatchToMain {
                    quickLaunchSlots = quickLaunchStore.load(userId)
                    pinnedTaskController.onUserChanged()
                    loadLockWallpaper()
                }
            }

            override fun onUserUnlocked() {
                dispatchToMain { loadLockWallpaper() }
            }

            override fun onPhoneStateChanged(phoneState: Int) {
                if (phoneState != TelephonyManager.CALL_STATE_IDLE) {
                    dispatchToMain {
                        cancelOriginalCredentialDismiss()
                        cancelQuickLaunchState(returnToMain = true)
                    }
                }
            }

            override fun onStartedGoingToSleep(why: Int) {
                awakeLockscreenSession = false
                dispatchToMain {
                    cancelOriginalCredentialDismiss()
                    cancelQuickLaunchState(returnToMain = true)
                }
            }

            override fun onStartedWakingUp() {
                awakeLockscreenSession = true
                // AOD panels may stay in Display.STATE_ON for the entire sleep session, so this
                // device legitimately skips ScreenLifecycle.onScreenTurningOn() on some wakes.
                // Restore the complete R2 scene here as the wakefulness-authoritative path; doing
                // only updateDozing(false) leaves pure-wallpaper mode, the clock and status bar in
                // their previous hidden state.
                dispatchToMain { restoreAwakeLockscreenScene() }
            }

            override fun onFinishedGoingToSleep(why: Int) {
                dispatchToMain {
                    awakeLockscreenSession = false
                    updateDozing(statusBarStateController.isDozing)
                }
            }
        }
    private val mediaListener =
        object : NotificationMediaManager.MediaListener {
            override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
                dispatchToMain { bindMedia(metadata, state) }
            }
        }

    /**
     * Mirrors the original KeyguardHostView.onScreenTurnedOff(): page state is reset when the
     * panel has really turned off, not only when Doze happens to be entered.  The latter is not
     * dispatched for every screen-off path (for example, when AOD is disabled).
     */
    private val screenObserver =
        object : ScreenLifecycle.Observer {
            override fun onScreenTurningOn() {
                awakeLockscreenSession = true
                // Screen-off can race view attachment on Android 16. The original always starts
                // a new visible keyguard session on MAIN, so enforce that invariant before the
                // first wake frame is drawn as well.
                dispatchToMain {
                    restoreAwakeLockscreenScene()
                    if (wallpaperUsingOpaqueFallback) loadLockWallpaper()
                }
            }

            override fun onScreenTurnedOn() {
                awakeLockscreenSession = true
                dispatchToMain {
                    startMainBreathing()
                }
            }

            override fun onScreenTurnedOff() {
                awakeLockscreenSession = false
                dispatchToMain {
                    cancelQuickLaunchState(returnToMain = false)
                    stopMainBreathing(resetScale = true)
                    pinnedTaskController.closePreview()
                    resetOriginalVerticalGesture(hideBouncer = true)
                    resetToMainPage()
                }
            }
        }

    private val wallpaperObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                loadLockWallpaper()
            }
        }
    private val wallpaperColorsListener =
        WallpaperManager.OnColorsChangedListener { colors, which ->
            if (
                which and (WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM) != 0
            ) {
                applySystemWallpaperTheme(colors)
                loadLockWallpaper()
            }
        }
    private val navigationModeListener =
        NavigationModeController.ModeChangedListener { mode ->
            if (navigationMode != mode) {
                navigationMode = mode
                requestApplyInsets()
                rootWindowInsets?.let {
                    layoutInsets = resolveStableLayoutInsets(it)
                    applyResponsiveLayout()
                }
            }
        }
    private val weatherObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                loadWeather()
            }
        }

    init {
        id = R.id.sos_keyguard_host_view
        // Never expose the imported XML's raw pre-constraint coordinates.  Cold SystemUI startup
        // can otherwise draw its clock at the RelativeLayout origin for one frame before Insets
        // and the 1080x2242 transform arrive.
        alpha = 0f
        clipChildren = false
        clipToPadding = false
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        originalBinding = SosOriginalLayoutBinding.inflate(context, this)
        pageContainer = originalBinding.root
        addView(pageContainer)
        originalBinding.alphaLayer.setBackgroundColor(Color.BLACK)

        // app_capture keeps its original camera/notes/quick-launch splash role. Framework owns the
        // ordinary unlock background, so no task preview view is inserted into this hierarchy.
        originalBinding.appCapture.visibility = View.GONE
        wallpaperView = originalBinding.wallpaper
        widgetPage = originalBinding.widgetPage
        mainPage = originalBinding.mainPage
        mainChrome = originalBinding.mainChrome
        widgetShortcutView = originalBinding.widgetShortcut
        cameraShortcutView = originalBinding.cameraShortcut
        cameraPage = originalBinding.cameraPage
        cameraContent = originalBinding.cameraContent
        widgetShortcutView.setOnClickListener { animateToPage(PAGE_WIDGET) }
        cameraShortcutView.setOnTouchListener(::onCameraShortcutTouch)
        // Preserve the original empty-background pure-wallpaper gesture without making the
        // inflated clock part of that click target.
        mainPage.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                mainTapRawX = event.rawX
                mainTapRawY = event.rawY
            }
            false
        }
        mainPage.setOnClickListener {
            if (!isRawPointInside(mainChrome, mainTapRawX, mainTapRawY)) {
                togglePureWallpaper()
            }
            mainTapRawX = Float.NaN
            mainTapRawY = Float.NaN
        }
        settlePageVisibility()

        pinnedPreviewLayer =
            FrameLayout(context).apply {
                visibility = View.GONE
                isClickable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
        addView(pinnedPreviewLayer)

        aodLayer = createAodLayer()
        addView(aodLayer)
        aodTimeView = aodLayer.requireViewById(R.id.sos_delta_aod_time)
        aodSecondaryView = aodLayer.requireViewById(R.id.sos_delta_aod_secondary)
        aodSecondContainer =
            aodLayer.requireViewById(R.id.sos_delta_aod_second_container)
        aodBatteryView = aodLayer.requireViewById(R.id.sos_delta_aod_battery)
        aodChargeView = aodLayer.requireViewById(R.id.sos_delta_aod_charge)
        aodFingerprintView = aodLayer.requireViewById(R.id.sos_delta_aod_fingerprint)
        aodAtTop =
            context
                .getSharedPreferences(AOD_POSITION_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(AOD_POSITION_TOP, true)

        chargingOverlay = createChargingOverlay()
        addView(chargingOverlay)
        chargingVideoView =
            chargingOverlay.requireViewById(R.id.sos_delta_charging_video)
        chargingFrameView =
            chargingOverlay.requireViewById(R.id.sos_delta_charging_frames)

        quickSelectorView = SosQuickLaunchSelectorView(context)
        addView(
            quickSelectorView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        quickPickerView = QuickLaunchPickerView(context)
        addView(
            quickPickerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        quickLaunchSlots = quickLaunchStore.load(ActivityManager.getCurrentUser())

        mainTimeView = originalBinding.time
        mainAmPmView = originalBinding.amPm
        mainWeekDayView = originalBinding.weekDay
        mainDateView = originalBinding.date
        weatherTemperatureView = originalBinding.weatherTemperature
        weatherUnitView = originalBinding.weatherUnit
        weatherSummaryView = originalBinding.weatherSummary
        weatherAqiView = originalBinding.weatherAqi
        batteryView = originalBinding.battery
        weatherPanel = originalBinding.weatherPanel
        musicPanel = originalBinding.musicPanel
        recorderPanel = originalBinding.recorderPanel
        torchPanel = originalBinding.torchPanel
        unlockHandle = originalBinding.unlockHandle
        torchButton = originalBinding.torch
        musicArtworkView = originalBinding.musicArtwork
        musicAppIconContainer = originalBinding.musicAppIconContainer
        musicAppIconView = originalBinding.musicAppIcon
        musicAppIconContainer.setRoundCornerMaskResId(R.drawable.music_app_icon_mask)
        musicControlsView = originalBinding.musicControls
        musicTitleView = originalBinding.musicTitle
        musicArtistView = originalBinding.musicArtist
        musicPreviousView = originalBinding.musicPrevious
        musicPlayPauseView = originalBinding.musicCollapsedPlayPause
        musicExpandedPlayPauseView = originalBinding.musicExpandedPlayPause
        musicNextView = originalBinding.musicNext
        recorderClockView = originalBinding.recorderClock
        recorderWaveView = originalBinding.recorderWave
        recorderMarkView = originalBinding.recorderMark
        recorderPauseView = originalBinding.recorderPause
        recorderStartStopView = originalBinding.recorderStartStop
        recorderController =
            SosKeyguardRecorderController(
                context,
                object : SosKeyguardRecorderController.Listener {
                    override fun onStateChanged(state: SosKeyguardRecorderController.State) {
                        dispatchToMain { bindRecorderState(state) }
                    }

                    override fun onElapsedTimeChanged(seconds: Long) {
                        dispatchToMain {
                            recorderClockView.timeChanged(seconds * 1_000L)
                            recorderWaveView.setElapsedSeconds(seconds)
                        }
                    }

                    override fun onAmplitudeChanged(amplitude: Int) {
                        dispatchToMain {
                            recorderWaveView.addAmplitude(amplitude)
                        }
                    }
                },
            )
        wireOriginalControls()

        pinnedTaskController =
            SosKeyguardPinnedTaskController(
                context,
                statusBarKeyguardViewManager,
                mainExecutor,
                backgroundExecutor,
                pinnedPreviewLayer,
                object : SosKeyguardPinnedTaskController.Listener {
                    override fun onPinnedTasksChanged(
                        tasks: List<SosKeyguardPinnedTaskController.PinnedTask>
                    ) {
                        // The original PinRootView is a top-slide overlay. Pinned tasks must never
                        // be inserted as an extra card in the TPage widget stack.
                    }
                },
            )

        timeController =
            SosKeyguardTimeController(
                context,
                mainTimeView,
                mainAmPmView,
                mainWeekDayView,
                mainDateView,
            )
        applySmartisanTypefaces()
        applyOriginalWallpaperTheme(wallpaperTheme, force = true)
        timeController.update()
        updateBattery()
        updateFlashlight()
        bindMedia(null, PlaybackState.STATE_NONE)
        bindRecorderState(SosKeyguardRecorderController.State.READY)

        setOnApplyWindowInsetsListener { _, insets ->
            val updated = resolveStableLayoutInsets(insets)
            if (updated != layoutInsets) {
                layoutInsets = updated
                applyResponsiveLayout()
            }
            insets
        }
    }

    /** Connects the original XML controls to the existing Android 16 controller boundaries. */
    private fun wireOriginalControls() {
        unlockHandle.setImageResource(R.drawable.icon_slide_unlock_light)
        unlockHandle.contentDescription = context.getString(R.string.sos_delta_unlock)
        unlockHandle.setOnClickListener { statusBarKeyguardViewManager.dismissAndCollapse() }

        torchButton.contentDescription = context.getString(R.string.quick_settings_flashlight_label)
        torchButton.setOnClickListener {
            if (flashlightAvailable) flashlightController.setFlashlight(!flashlightEnabled)
        }

        musicArtworkView.setOnClickListener { setMusicExpanded(!musicExpanded) }
        musicArtworkView.setOnLongClickListener {
            setMusicExpanded(!musicExpanded, fast = true)
            musicArtworkView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            true
        }
        musicPreviousView.setOnClickListener {
            notificationMediaManager.skipPrimarySessionToPrevious()
        }
        musicNextView.setOnClickListener { notificationMediaManager.skipPrimarySessionToNext() }
        val playPause = View.OnClickListener {
            notificationMediaManager.playPausePrimarySession()
        }
        musicPlayPauseView.setOnClickListener(playPause)
        musicExpandedPlayPauseView.setOnClickListener(playPause)

        recorderMarkView.setOnClickListener {
            recorderController.mark()
            recorderWaveView.addMark()
        }
        recorderPauseView.setOnClickListener {
            when (recorderState) {
                SosKeyguardRecorderController.State.RECORDING -> recorderController.pause()
                SosKeyguardRecorderController.State.PAUSED -> recorderController.resume()
                else -> Unit
            }
        }
        recorderStartStopView.setOnClickListener {
            when (recorderState) {
                SosKeyguardRecorderController.State.READY -> recorderController.start()
                SosKeyguardRecorderController.State.RECORDING,
                SosKeyguardRecorderController.State.PAUSED -> recorderController.stop()
                SosKeyguardRecorderController.State.UNAVAILABLE -> Unit
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        awakeLockscreenSession = keyguardUpdateMonitor.isDeviceInteractive
        // Restore the original stable scene synchronously.  Waiting for a screen callback leaves
        // Android's default top/left constraints visible during SystemUI's first layout pass.
        noSecurityUnlockCommitted = false
        resetOriginalVerticalGesture(hideBouncer = true)
        resetToMainPage()
        timeController.update()
        systemIconsController.setKeyguardWallpaperTheme(
            systemIconsController.keyguardWallpaperSupportsDarkText()
        )
        navigationMode = navigationModeController.addListener(navigationModeListener)
        timeController.start()
        flashlightController.addCallback(this)
        batteryController.addCallback(this)
        statusBarStateController.addCallback(this)
        screenLifecycle.addObserver(screenObserver)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateCallback)
        notificationMediaManager.addCallback(mediaListener)
        notificationMediaManager.findAndUpdateMediaNotifications()
        recorderController.attach()
        pinnedTaskController.attach()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(LOCKSCREEN_BACKGROUND),
            false,
            wallpaperObserver,
        )
        wallpaperManager?.addOnColorsChangedListener(wallpaperColorsListener, aodHandler)
        runCatching {
            context.contentResolver.registerContentObserver(
                WEATHER_CURRENT_URI,
                false,
                weatherObserver,
            )
        }
        loadLockWallpaper()
        loadWeather()
        updateDozing(statusBarStateController.isDozing)
        armFirstFrameReveal()
        post(::startMainBreathing)
    }

    /**
     * Covers the complete R2 host with an opaque first-frame barrier until one full layout pass has
     * consumed the real viewport and WindowInsets. Returning false from the first pre-draw is
     * intentional: layout parameters changed by [applyResponsiveLayout] must be measured and
     * positioned before imported XML pixels become visible.
     */
    private fun armFirstFrameReveal() {
        removeFirstFramePreDrawListener()
        // Keep the shade window opaque while the imported XML receives its first responsive
        // coordinates. Making the whole host transparent here exposes Launcher and is exactly the
        // cold-boot flash this barrier is intended to prevent.
        alpha = 1f
        foreground = ColorDrawable(Color.BLACK)
        firstFrameNeedsRelayout = true
        firstFramePreDrawAttempts = 0
        val listener =
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!isAttachedToWindow) {
                        removeFirstFramePreDrawListener()
                        return true
                    }
                    firstFramePreDrawAttempts++
                    val allowFallback = firstFramePreDrawAttempts >= MAX_FIRST_FRAME_PREDRAWS
                    val viewportWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val viewportHeight =
                        height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                    val insets = rootWindowInsets
                    if (viewportWidth <= 0 || viewportHeight <= 0 || (insets == null && !allowFallback)) {
                        return false
                    }

                    val resolvedInsets =
                        insets?.let(::resolveStableLayoutInsets) ?: SosKeyguardLayoutModel.Insets()
                    if (resolvedInsets != layoutInsets) {
                        layoutInsets = resolvedInsets
                    }

                    if (firstFrameNeedsRelayout) {
                        firstFrameNeedsRelayout = false
                        resetOriginalVerticalGesture(hideBouncer = true)
                        resetToMainPage()
                        timeController.update()
                        applyResponsiveLayout(viewportWidth, viewportHeight)
                        updatePageTranslations(pagePosition)
                        requestLayout()
                        if (allowFallback) {
                            layoutMetrics?.let { metrics ->
                                applyOriginalMainTimeAnchor(
                                    mainChrome,
                                    metrics.mainTime.bottom,
                                    viewportHeight,
                                )
                            }
                            if (isFirstFrameWallpaperReady()) {
                                removeFirstFramePreDrawListener()
                                foreground = null
                                return true
                            }
                        }
                        return false
                    }

                    val metrics = layoutMetrics ?: return false
                    val timeLayoutParams =
                        mainChrome.layoutParams as? android.widget.RelativeLayout.LayoutParams
                    val expectedBottomMargin =
                        max(0, viewportHeight - metrics.mainTime.bottom.roundToInt())
                    val timeReady =
                        mainChrome.measuredWidth > 0 &&
                            mainChrome.measuredHeight > 0 &&
                            mainChrome.bottom > 0 &&
                            timeLayoutParams?.bottomMargin == expectedBottomMargin
                    if (!timeReady) {
                        applyOriginalMainTimeAnchor(
                            mainChrome,
                            metrics.mainTime.bottom,
                            viewportHeight,
                        )
                        requestLayout()
                        if (allowFallback && isFirstFrameWallpaperReady()) {
                            removeFirstFramePreDrawListener()
                            foreground = null
                            return true
                        }
                        return false
                    }

                    if (!isFirstFrameWallpaperReady()) return false
                    removeFirstFramePreDrawListener()
                    foreground = null
                    return true
                }
            }
        firstFramePreDrawListener = listener
        viewTreeObserver.addOnPreDrawListener(listener)
        requestLayout()
    }

    private fun isFirstFrameWallpaperReady(): Boolean {
        return isWallpaperFrameReady(
            state = wallpaperLoadState,
            stateGeneration = wallpaperLoadStateGeneration,
            currentGeneration = wallpaperLoadGeneration,
            usingOpaqueFallback = wallpaperUsingOpaqueFallback,
            hasBitmap = ownedWallpaperBitmap?.isRecycled == false,
            installedGeneration = wallpaperInstalledGeneration,
        )
    }

    private fun removeFirstFramePreDrawListener() {
        val listener = firstFramePreDrawListener ?: return
        val observer = viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
        firstFramePreDrawListener = null
        firstFrameNeedsRelayout = false
        firstFramePreDrawAttempts = 0
    }

    /** Restores the one stable awake frame shared by cold boot, AOD wake and normal screen wake. */
    private fun restoreAwakeLockscreenScene() {
        // Android's raw doze flag can remain true briefly after waking. Remove the old AOD layer
        // synchronously; the session bit prevents a late true callback from covering this frame.
        updateDozing(false)
        resetOriginalVerticalGesture(hideBouncer = true)
        resetToMainPage()
        timeController.update()
    }

    override fun onDetachedFromWindow() {
        removeFirstFramePreDrawListener()
        foreground = null
        alpha = 0f
        cancelQuickLaunchState(returnToMain = false)
        cancelOriginalCredentialDismiss()
        if (noSecurityUnlockCommitted) {
            abortOriginalVerticalScroller()
            externalVerticalGestureOwned = false
            verticalBouncerRequested = false
        } else {
            resetOriginalVerticalGesture(hideBouncer = true)
        }
        pageAnimator?.cancel()
        pageAnimator = null
        musicAnimator?.cancel()
        musicAnimator = null
        pressedWidget?.let {
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
        }
        pressedWidget = null
        stopMainBreathing(resetScale = true)
        hideChargingAnimation()
        runCatching { context.contentResolver.unregisterContentObserver(wallpaperObserver) }
        runCatching { context.contentResolver.unregisterContentObserver(weatherObserver) }
        wallpaperManager?.removeOnColorsChangedListener(wallpaperColorsListener)
        navigationModeController.removeListener(navigationModeListener)
        flashlightController.removeCallback(this)
        batteryController.removeCallback(this)
        statusBarStateController.removeCallback(this)
        screenLifecycle.removeObserver(screenObserver)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateCallback)
        notificationMediaManager.removeCallback(mediaListener)
        recorderController.detach()
        pinnedTaskController.detach()
        aodHandler.removeCallbacks(aodTicker)
        timeController.stop()
        removeCallbacks(notifyLauncherDismissRunnable)
        removeCallbacks(notifyCredentialDismissRunnable)
        credentialDismissView = null
        credentialDismissFinishRunnable = null
        credentialDismissActive = false
        credentialDismissLauncherNotified = false
        credentialDismissGeneration = 0L
        credentialDismissUserId = UserHandle.USER_NULL
        cancelWallpaperRetry()
        wallpaperLoadGeneration++
        wallpaperLoadState = WallpaperLoadState.IDLE
        wallpaperLoadStateGeneration = wallpaperLoadGeneration
        wallpaperView.setImageDrawable(null)
        ownedWallpaperBitmap = null
        wallpaperInstalledGeneration = -1
        pageBlurProgress = 0f
        bouncerBlurProgress = 0f
        originalBinding.blur.cleanup()
        wallpaperWorker.shutdown()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        // The imported RelativeLayout otherwise gets measured once with its raw XML rules before
        // onSizeChanged() supplies the 1080x2242 transform. On a cold boot that one pass is enough
        // to put the R2 time at (0, 0). Prepare all child rules before the first real measurement.
        if (
            viewportWidth > 0 &&
                viewportHeight > 0 &&
                (layoutMetrics == null || width != viewportWidth || height != viewportHeight)
        ) {
            applyResponsiveLayout(viewportWidth, viewportHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (
            !noSecurityUnlockCommitted &&
                !credentialDismissActive &&
                oldw > 0 &&
                oldh > 0 &&
                (w != oldw || h != oldh)
        ) {
            resetOriginalVerticalGesture(hideBouncer = true)
        }
        applyResponsiveLayout(w, h)
        updatePageTranslations(pagePosition)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelQuickLaunchState(returnToMain = true)
        if (!noSecurityUnlockCommitted) resetOriginalVerticalGesture(hideBouncer = true)
        applyResponsiveLayout()
        loadLockWallpaper()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (quickEditorVisible) {
            return event.y < (layoutMetrics?.quickPicker?.top ?: height.toFloat())
        }
        if (quickSelectorExpanded) return false
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGestureTracking(event)
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maximumPointerCount = maxOf(maximumPointerCount, event.pointerCount)
            }
            MotionEvent.ACTION_MOVE -> {
                maximumPointerCount = maxOf(maximumPointerCount, event.pointerCount)
                return startGestureIfNeeded(event)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                releaseMainPress()
                releaseWidgetPress()
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (quickEditorVisible) return true
        velocityTracker?.addMovement(event)
        maximumPointerCount = maxOf(maximumPointerCount, event.pointerCount)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGestureTracking(event)
            MotionEvent.ACTION_MOVE -> {
                // If no widget child accepts ACTION_DOWN, ViewGroup dispatch sends later events
                // straight here without another interception pass. Detect the gesture here too,
                // matching the original KeyguardHostView which centrally owns TPage swipes.
                startGestureIfNeeded(event)
                if (gestureMode == GESTURE_HORIZONTAL && width > 0) {
                    val dx = event.x - downX
                    preparePagesForDrag(dx)
                    val draggedPosition = currentPage - dx / width.toFloat()
                    pagePosition = draggedPosition.coerceIn(PAGE_WIDGET.toFloat(), PAGE_CAMERA.toFloat())
                    updatePageTranslations(pagePosition)
                } else if (gestureMode == GESTURE_VERTICAL && height > 0) {
                    val scale = layoutMetrics?.scale ?: 1f
                    val offset =
                        (-((event.y - downY)) - SosBouncerVisualCoordinator.ORIGINAL_IGNORE_MOVE_PX * scale)
                            .coerceAtLeast(0f)
                    updateOriginalVerticalOffset(offset, requestBouncer = true)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityX = velocityTracker?.xVelocity ?: 0f
                val velocityY = velocityTracker?.yVelocity ?: 0f
                when (gestureMode) {
                    GESTURE_HORIZONTAL -> finishHorizontalGesture(dx, velocityX)
                    GESTURE_VERTICAL -> finishOriginalVerticalGesture(velocityY)
                }
                releaseMainPress()
                releaseWidgetPress()
                resetGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gestureMode == GESTURE_VERTICAL) {
                    settleOriginalVerticalGesture(
                        SosBouncerVisualCoordinator.SettleTarget.MAIN,
                        velocityY = 0f,
                    )
                } else {
                    animateToPage(currentPage)
                }
                releaseMainPress()
                releaseWidgetPress()
                resetGesture()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (currentPage == PAGE_MAIN) togglePureWallpaper()
        return true
    }

    override fun canStartUnlockGesture(x: Float, y: Float): Boolean {
        if (!isAttachedToWindow || visibility != View.VISIBLE || !keyguardStateController.isShowing) {
            return false
        }
        if (
            statusBarStateController.isDozing ||
                currentPage == PAGE_CAMERA ||
                quickSelectorExpanded ||
                quickEditorVisible ||
                chargingOverlay.visibility == View.VISIBLE ||
                primaryBouncerInteractor.isShowing.value ||
                primaryBouncerInteractor.isShowingSoon.value
        ) {
            return false
        }
        if (y <= resources.getDimensionPixelSize(R.dimen.sos_delta_top_gesture_region)) return false
        val minimumTarget = 48f * resources.displayMetrics.density
        currentUdfpsBounds()?.let { udfps ->
            val horizontalPadding = ((minimumTarget - udfps.width) / 2f).coerceAtLeast(0f)
            val verticalPadding = ((minimumTarget - udfps.height) / 2f).coerceAtLeast(0f)
            if (
                x >= udfps.left - horizontalPadding &&
                    x <= udfps.right + horizontalPadding &&
                    y >= udfps.top - verticalPadding &&
                    y <= udfps.bottom + verticalPadding
            ) {
                return false
            }
        }
        return currentPage == PAGE_MAIN || currentPage == PAGE_WIDGET
    }

    override fun prepareUnlockGestureCandidate() {
        // Freeze security at ACTION_DOWN as the original host did. Trust/SIM/strong-auth changes
        // after this point may only tighten the path; they must not turn a secure gesture into a
        // task-preview unlock halfway through the drag.
        verticalGestureCandidateWithSecurity = hasOriginalSecurityLayer()
    }

    override fun beginUnlockGesture(downY: Float) {
        abortOriginalVerticalScroller()
        pageAnimator?.cancel()
        pageAnimator = null
        SosKeyguardRuntime.finishOriginalInteractiveTransition()
        SosKeyguardRuntime.clearOriginalUnlockAnimationCompletion()
        removeCallbacks(notifyLauncherDismissRunnable)
        if (launcherDismissNotified) {
            sendLauncherUnlockPhase(LAUNCHER_PHASE_CANCEL, 0L)
        }
        launcherDismissNotified = false
        noSecurityUnlockCommitted = false
        // A completed PRIMARY_BOUNCER -> LOCKSCREEN transition hides the Android bouncer in its
        // repository. Do not let a request bit from the previous R2 gesture suppress show() for
        // this new gesture if the host missed (or has not yet received) the final expansion frame.
        if (
            !primaryBouncerInteractor.isShowing.value &&
                !primaryBouncerInteractor.isShowingSoon.value
        ) {
            verticalBouncerRequested = false
        }
        externalVerticalGestureOwned = true
        verticalGestureDownY = downY
        verticalOriginPage = currentPage
        verticalOriginPosition = pagePosition
        verticalOffset = 0f
        verticalSettleTarget = SosBouncerVisualCoordinator.SettleTarget.MAIN
        stopMainBreathing(resetScale = true)
        val hasSecurity = verticalGestureCandidateWithSecurity ?: hasOriginalSecurityLayer()
        verticalGestureCandidateWithSecurity = null
        verticalGestureStartedWithSecurity = hasSecurity
        if (hasSecurity) activateWidgetSecureTransitionIfNeeded()
        if (!hasSecurity) {
            SosKeyguardRuntime.beginOriginalInteractiveTransition()
        }
        Log.d(
            TAG,
            "begin unlock security=$hasSecurity frameworkBackground=true canDismiss=" +
                "${keyguardStateController.canDismissLockScreen()} sim=${keyguardUpdateMonitor.isSimPinSecure}",
        )
    }

    override fun updateUnlockGesture(currentY: Float) {
        if (!externalVerticalGestureOwned) return
        val scale = layoutMetrics?.scale ?: 1f
        val offset =
            (verticalGestureDownY - currentY -
                    SosBouncerVisualCoordinator.ORIGINAL_IGNORE_MOVE_PX * scale)
                .coerceAtLeast(0f)
        updateOriginalVerticalOffset(offset, requestBouncer = true)
    }

    override fun endUnlockGesture(velocityY: Float, rejected: Boolean) {
        if (!externalVerticalGestureOwned) return
        if (rejected) {
            // Falsing cancels the owned gesture. R2 returns to the page where the gesture began;
            // forcing TPage to main here creates a second, unrelated horizontal transition.
            settleOriginalVerticalGesture(SosBouncerVisualCoordinator.SettleTarget.MAIN, 0f)
        } else {
            finishOriginalVerticalGesture(velocityY)
        }
    }

    override fun cancelUnlockGesture() {
        if (!externalVerticalGestureOwned) return
        settleOriginalVerticalGesture(SosBouncerVisualCoordinator.SettleTarget.MAIN, 0f)
    }

    /** Applies the original host/time/blur response while Android 16 owns bouncer expansion. */
    fun setBouncerVisualProgress(progress: Float) {
        if (noSecurityUnlockCommitted || credentialDismissActive) return
        val constrained = progress.coerceIn(0f, 1f)
        if (
            constrained == 0f &&
                verticalBouncerRequested &&
                !externalVerticalGestureOwned
        ) {
            // R2's slideDownAndLock(false) is a complete state transition: it hides the security
            // wrapper, clears blur and leaves WALLPAPER mode for the ordinary main page. Android 16
            // owns the wrapper visibility, while this host must close the matching visual/request
            // session. Keeping verticalBouncerRequested=true here makes the next upward gesture
            // skip PrimaryBouncerInteractor.show(), leaving only the progressive blur visible.
            completeBouncerReturnToMain()
        }
        if (
            constrained == 0f &&
                widgetSecureTransitionActive &&
                !externalVerticalGestureOwned
        ) {
            // The bouncer was cancelled after TPage had completed its move to the wallpaper-only
            // main state. Resume the ordinary main page instead of leaving its chrome suppressed.
            widgetSecureTransitionActive = false
        }
        if (constrained > 0f && quickSelectorExpanded) {
            cancelQuickPressTimers()
            quickPressEligible = false
            quickSelectorExpanded = false
            quickSelectorView.hide()
        }
        if (
            constrained > 0f &&
                quickEditorVisible &&
                pendingQuickConfiguration == null
        ) {
            cancelQuickLaunchState(returnToMain = false)
        }
        // The outer R2 router is the sole progress source for an owned swipe. Android 16 feeds
        // this callback back synchronously from setPanelExpansion(); accepting it here would run
        // the same geometry twice and prematurely collapse TPage.
        if (externalVerticalGestureOwned) return
        if (constrained > 0f && currentPage != PAGE_MAIN) resetToMainPage()
        val scale = layoutMetrics?.scale ?: 1f
        applyOriginalSecureVisualState(
            SosBouncerVisualCoordinator.calculate(
                constrained * height / 3f,
                height.toFloat(),
                ORIGINAL_SHORTCUT_TRAVEL_PX * scale,
                ORIGINAL_PIN_TRAVEL_PX * scale,
            )
        )
        if (constrained == 0f) {
            if (verticalScroller == null) verticalOffset = 0f
            startMainBreathing()
        } else {
            stopMainBreathing(resetScale = true)
        }
    }

    /**
     * Runs the R2 drag geometry immediately instead of waiting for the AOSP bouncer animation.
     * Android 16 still owns which security screen is shown and whether authentication succeeds.
     */
    private fun updateOriginalVerticalOffset(offset: Float, requestBouncer: Boolean) {
        if (height <= 0) return
        verticalOffset = offset.coerceAtLeast(0f)
        if (!verticalGestureStartedWithSecurity && hasOriginalSecurityLayer()) {
            // A late SIM/strong-auth transition immediately restores the non-revealing secure
            // visual path; Framework's prepared layer remains fully covered by the lock curtain.
            verticalGestureStartedWithSecurity = true
        }
        if (verticalGestureRequiresSecurity()) activateWidgetSecureTransitionIfNeeded()
        updateVerticalOriginPageTransition()
        if (!verticalGestureRequiresSecurity()) {
            stopMainBreathing(resetScale = true)
            applyOriginalNoSecurityVisualState(verticalOffset)
            return
        }

        if (requestBouncer && verticalOffset > 0f) requestOriginalBouncer()
        val scale = layoutMetrics?.scale ?: 1f
        val state =
            SosBouncerVisualCoordinator.calculate(
                verticalOffset,
                height.toFloat(),
                ORIGINAL_SHORTCUT_TRAVEL_PX * scale,
                ORIGINAL_PIN_TRAVEL_PX * scale,
            )
        applyOriginalSecureVisualState(state)
        if (
            verticalBouncerRequested ||
                primaryBouncerInteractor.isShowing.value ||
                primaryBouncerInteractor.isShowingSoon.value
        ) {
            primaryBouncerInteractor.setPanelExpansion(
                KeyguardBouncerConstants.EXPANSION_HIDDEN - state.blurProgress
            )
        }
    }

    private fun applyOriginalSecureVisualState(state: SosBouncerVisualCoordinator.State) {
        bouncerBlurProgress = state.blurProgress
        applyCombinedBlurProgress()
        resetOriginalNoSecurityLayerTransforms()
        if (widgetSecureTransitionActive) {
            applyWidgetSecureWallpaperState()
            return
        }
        mainChrome.translationY = 0f
        mainChrome.alpha = if (pureWallpaper) 0f else state.timeAlpha
        mainChrome.visibility = if (pureWallpaper) View.INVISIBLE else View.VISIBLE

        widgetShortcutView.translationX = -state.shortcutTranslation
        cameraShortcutView.translationX = state.shortcutTranslation
        widgetShortcutView.translationY = 0f
        cameraShortcutView.translationY = 0f
        val shortcutsFinished = state.shortcutTranslation > 0f && state.timeAlpha == 0f
        val shortcutVisibility =
            if (pureWallpaper || shortcutsFinished) View.GONE else View.VISIBLE
        widgetShortcutView.visibility = shortcutVisibility
        cameraShortcutView.visibility = shortcutVisibility

        pinnedPreviewLayer.alpha = state.timeAlpha
        pinnedPreviewLayer.translationY = state.pinTranslationY
        if (state.blurProgress > 0f) stopMainBreathing(resetScale = true)
    }

    /** Mirrors MainPageAnimHelper.hideTimeWithoutAnim() without creating another background. */
    private fun applyWidgetSecureWallpaperState() {
        val shortcutTravel = ORIGINAL_SHORTCUT_TRAVEL_PX * (layoutMetrics?.scale ?: 1f)
        mainChrome.animate().cancel()
        mainChrome.translationY = 0f
        mainChrome.alpha = 0f
        // Keep main_screen in the hierarchy; only its information is hidden in R2's WALLPAPER
        // state. Its existing desk_kg and BlurView therefore remain pixel-identical to main.
        mainChrome.visibility = View.VISIBLE
        widgetShortcutView.translationX = -shortcutTravel
        cameraShortcutView.translationX = shortcutTravel
        widgetShortcutView.translationY = 0f
        cameraShortcutView.translationY = 0f
        widgetShortcutView.visibility = View.GONE
        cameraShortcutView.visibility = View.GONE
        pinnedPreviewLayer.alpha = 0f
        pinnedPreviewLayer.translationY = 0f
        stopMainBreathing(resetScale = true)
    }

    /** Mirrors R2 KeyguardHostView.slideLockScreen() without moving the transparent root. */
    private fun applyOriginalNoSecurityVisualState(offset: Float) {
        val constrainedOffset = offset.coerceAtLeast(0f)
        val translation = -constrainedOffset
        pageContainer.translationY = 0f
        originalBinding.mainSlideLayer.translationY = translation
        wallpaperView.translationY = translation
        originalBinding.blur.translationY = translation
        widgetPage.translationY = translation
        val alpha =
            SosBouncerVisualCoordinator.noSecurityRevealAlpha(constrainedOffset, height)
        originalBinding.alphaLayer.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    private fun resetOriginalNoSecurityLayerTransforms() {
        pageContainer.translationY = 0f
        originalBinding.mainSlideLayer.translationY = 0f
        wallpaperView.translationY = 0f
        originalBinding.blur.translationY = 0f
        widgetPage.translationY = 0f
        originalBinding.alphaLayer.setBackgroundColor(Color.BLACK)
    }

    private fun hasOriginalSecurityLayer(): Boolean =
        // A configured credential is not necessarily required for this particular entry: trust,
        // an already accepted biometric or a dismissible keyguard must complete through the R2
        // slide-out path. Asking PrimaryBouncer to show in that state makes it auto-dismiss early
        // and hands the animation back to AOSP. SIM PIN/PUK remains authoritative even though the
        // legacy canDismissLockScreen value may temporarily report true while telephony settles.
        !keyguardStateController.canDismissLockScreen() || keyguardUpdateMonitor.isSimPinSecure

    private fun verticalGestureRequiresSecurity(): Boolean =
        verticalGestureStartedWithSecurity || hasOriginalSecurityLayer()

    private fun requestOriginalBouncer() {
        if (!externalVerticalGestureOwned && currentPage != PAGE_MAIN) resetToMainPage()
        val bouncerIsActive =
            primaryBouncerInteractor.isShowing.value ||
                primaryBouncerInteractor.isShowingSoon.value
        if (bouncerIsActive) {
            verticalBouncerRequested = true
            return
        }
        // The Android repository is authoritative. A local request without a showing/showing-soon
        // bouncer belongs to an already cancelled session and must never block a fresh show().
        verticalBouncerRequested = false
        verticalBouncerRequested =
            primaryBouncerInteractor.show(
                isScrimmed = false,
                reason = "SosKeyguardHostView#verticalGesture",
            ) ||
                primaryBouncerInteractor.isShowing.value ||
                primaryBouncerInteractor.isShowingSoon.value
    }

    /** Completes the original PASSWORD_VIEW -> SLIDE_VIEW return as one atomic host reset. */
    private fun completeBouncerReturnToMain() {
        verticalBouncerRequested = false
        verticalGestureStartedWithSecurity = false
        verticalGestureCandidateWithSecurity = null
        verticalOffset = 0f
        verticalSettleTarget = SosBouncerVisualCoordinator.SettleTarget.MAIN
        verticalOriginPage = PAGE_MAIN
        verticalOriginPosition = PAGE_MAIN.toFloat()
        widgetSecureTransitionActive = false
        bouncerBlurProgress = 0f
        resetOriginalNoSecurityLayerTransforms()
        resetToMainPage()
    }

    private fun finishOriginalVerticalGesture(velocityY: Float) {
        val scale = layoutMetrics?.scale ?: 1f
        val target =
            SosBouncerVisualCoordinator.settleTarget(
                verticalOffset,
                velocityY,
                height.toFloat(),
                scale,
            )
        settleOriginalVerticalGesture(target, velocityY)
    }

    private fun settleOriginalVerticalGesture(
        target: SosBouncerVisualCoordinator.SettleTarget,
        velocityY: Float,
    ) {
        if (height <= 0) return
        if (
            target == SosBouncerVisualCoordinator.SettleTarget.MAIN &&
                !verticalGestureRequiresSecurity()
        ) {
            SosKeyguardRuntime.cancelOriginalInteractiveTransition()
        }
        abortOriginalVerticalScroller()
        val scale = layoutMetrics?.scale ?: 1f
        if (
            target == SosBouncerVisualCoordinator.SettleTarget.BOUNCER &&
                verticalGestureRequiresSecurity()
        ) {
            requestOriginalBouncer()
        }
        val currentScrollerY = height.toFloat() - verticalOffset
        val finalScrollerY =
            if (target == SosBouncerVisualCoordinator.SettleTarget.BOUNCER) {
                -ORIGINAL_UNLOCK_OVERSHOOT_PX * scale
            } else {
                height.toFloat()
            }
        val duration =
            when (target) {
                SosBouncerVisualCoordinator.SettleTarget.BOUNCER ->
                    if (verticalGestureRequiresSecurity()) {
                        SosBouncerVisualCoordinator.showDurationMillis(
                            verticalOffset,
                            velocityY,
                            height.toFloat(),
                            scale,
                        )
                    } else {
                        ORIGINAL_NO_SECURITY_UNLOCK_DURATION_MS
                    }
                SosBouncerVisualCoordinator.SettleTarget.MAIN ->
                    SosBouncerVisualCoordinator.hideDurationMillis(verticalOffset, velocityY, scale)
            }
        verticalSettleTarget = target
        if (currentScrollerY == finalScrollerY || duration <= 0L) {
            completeOriginalVerticalSettle()
            return
        }
        val useFastDownInterpolator =
            target == SosBouncerVisualCoordinator.SettleTarget.MAIN &&
                velocityY > ORIGINAL_FLING_VELOCITY_PX_PER_SECOND * scale
        verticalScroller =
            Scroller(context, if (useFastDownInterpolator) AccelerateInterpolator() else null)
                .also {
                    it.startScroll(
                        0,
                        currentScrollerY.roundToInt(),
                        0,
                        (finalScrollerY - currentScrollerY).roundToInt(),
                        duration.toInt(),
                    )
                }
        if (
            target == SosBouncerVisualCoordinator.SettleTarget.BOUNCER &&
                !verticalGestureRequiresSecurity()
        ) {
            postDelayed(notifyLauncherDismissRunnable, ORIGINAL_LAUNCHER_NOTIFY_DELAY_MS)
        }
        postInvalidateOnAnimation()
    }

    private fun notifyLauncherDismissIfNeeded() {
        if (launcherDismissNotified || verticalGestureRequiresSecurity()) return
        launcherDismissNotified = true
        sendLauncherUnlockPhase(LAUNCHER_PHASE_PREPARE, 0L)
    }

    private fun notifyCredentialDismissLauncherIfNeeded() {
        if (!credentialDismissActive || credentialDismissLauncherNotified) return
        val generation = credentialDismissGeneration
        val userId = credentialDismissUserId
        if (
            userId == UserHandle.USER_NULL ||
                ActivityManager.getCurrentUser() != userId ||
                !SosKeyguardRuntime.isOriginalCredentialSession(generation, userId)
        ) {
            cancelOriginalCredentialDismiss()
            return
        }
        if (!SosKeyguardRuntime.prepareOriginalCredentialTransition(generation)) return
        credentialDismissLauncherNotified = true
        sendLauncherUnlockPhase(LAUNCHER_PHASE_PREPARE, generation, userId)
        statusBarKeyguardViewManager.prepareOriginalCredentialUnlockSurface(generation, userId)
    }

    private fun sendLauncherUnlockPhase(
        phase: String,
        generation: Long,
        userId: Int = ActivityManager.getCurrentUser(),
    ) {
        if (userId == UserHandle.USER_NULL) return
        val intent =
            Intent(ACTION_KEYGUARD_TO_DISMISS)
                .setPackage(LAUNCHER_PACKAGE)
                .putExtra(EXTRA_UNLOCK_PHASE, phase)
                .putExtra(EXTRA_UNLOCK_GENERATION, generation)
                .putExtra(EXTRA_UNLOCK_USER, userId)
        context.sendBroadcastAsUser(
            intent,
            UserHandle.of(userId),
            LAUNCHER_UNLOCK_PERMISSION,
        )
    }

    /**
     * Drives the original snapToTopDismiss(true) path after Android 16 has accepted a PIN.
     * Authentication remains complete inside Android 16, but its finish callback is deliberately
     * held until the R2 curtain, wallpaper, blur, TPage and credential page have moved together.
     */
    fun startCredentialDismissAnimation(
        credentialView: View,
        finishRunnable: Runnable?,
    ): Boolean {
        if (!isAttachedToWindow || height <= 0 || noSecurityUnlockCommitted) return false
        if (credentialDismissActive) return true

        abortOriginalVerticalScroller()
        pageAnimator?.cancel()
        pageAnimator = null
        removeCallbacks(notifyCredentialDismissRunnable)
        SosKeyguardRuntime.clearOriginalUnlockAnimationCompletion()

        credentialDismissActive = true
        credentialDismissUserId = ActivityManager.getCurrentUser()
        credentialDismissGeneration =
            SosKeyguardRuntime.beginOriginalCredentialTransition(credentialDismissUserId)
        credentialDismissView = credentialView
        credentialDismissFinishRunnable = finishRunnable
        credentialDismissLauncherNotified = false
        externalVerticalGestureOwned = false
        verticalGestureStartedWithSecurity = true
        verticalGestureCandidateWithSecurity = null
        verticalOffset = 0f
        stopMainBreathing(resetScale = true)

        credentialView.animate().cancel()
        credentialView.alpha = 1f
        credentialView.translationY = 0f
        applyOriginalCredentialDismissVisualState(0f)

        val scale = layoutMetrics?.scale ?: 1f
        val finalScrollerY = -ORIGINAL_UNLOCK_OVERSHOOT_PX * scale
        verticalScroller =
            Scroller(context, null).also {
                it.startScroll(
                    0,
                    height,
                    0,
                    (finalScrollerY - height).roundToInt(),
                    ORIGINAL_CREDENTIAL_DISMISS_DURATION_MS.toInt(),
                )
            }
        postDelayed(
            notifyCredentialDismissRunnable,
            ORIGINAL_LAUNCHER_NOTIFY_DELAY_MS,
        )
        postInvalidateOnAnimation()
        return true
    }

    private fun applyOriginalCredentialDismissVisualState(offset: Float) {
        verticalOffset = offset.coerceAtLeast(0f)
        applyOriginalNoSecurityVisualState(verticalOffset)
        credentialDismissView?.let { credential ->
            credential.alpha = 1f
            credential.translationY = -verticalOffset
        }
    }

    private fun completeOriginalCredentialDismiss() {
        if (!credentialDismissActive) return
        verticalScroller?.abortAnimation()
        verticalScroller = null
        removeCallbacks(notifyCredentialDismissRunnable)
        notifyCredentialDismissLauncherIfNeeded()

        val completedOffset =
            SosBouncerVisualCoordinator.completedOffset(
                height.toFloat(),
                layoutMetrics?.scale ?: 1f,
        )
        applyOriginalCredentialDismissVisualState(completedOffset)
        credentialDismissActive = false
        val generation = credentialDismissGeneration
        val userId = credentialDismissUserId
        if (
            userId == UserHandle.USER_NULL ||
                ActivityManager.getCurrentUser() != userId ||
                !SosKeyguardRuntime.isOriginalCredentialSession(generation, userId)
        ) {
            cancelOriginalCredentialDismiss()
            return
        }
        if (!SosKeyguardRuntime.commitOriginalCredentialTransition(generation)) {
            cancelOriginalCredentialDismiss()
            return
        }
        noSecurityUnlockCommitted = true
        sendLauncherUnlockPhase(LAUNCHER_PHASE_COMMIT, generation, userId)
        statusBarKeyguardViewManager.commitOriginalCredentialUnlockSurface(generation, userId)

        val finish = credentialDismissFinishRunnable
        credentialDismissFinishRunnable = null
        finish?.run()
    }

    /** Cancels an interrupted authenticated curtain without converting it into a completed unlock. */
    private fun cancelOriginalCredentialDismiss() {
        val localGeneration = credentialDismissGeneration
        val activeGeneration = SosKeyguardRuntime.getOriginalCredentialGeneration()
        val generation = localGeneration.takeIf { it != 0L } ?: activeGeneration
        val localUserId = credentialDismissUserId
        val userId =
            localUserId.takeIf { it != UserHandle.USER_NULL }
                ?: SosKeyguardRuntime.getOriginalCredentialUserId()
        if (!credentialDismissActive && generation == 0L) return
        // The matching WMS completion already closed this generation. Clear the Host's retained
        // token without sending a late CANCEL to a Launcher session that successfully committed.
        val sessionStillActive = generation != 0L && activeGeneration == generation
        val launcherWasNotified = credentialDismissLauncherNotified
        removeCallbacks(notifyCredentialDismissRunnable)
        verticalScroller?.abortAnimation()
        verticalScroller = null
        credentialDismissView?.let { credential ->
            credential.animate().cancel()
            credential.alpha = 1f
            credential.translationY = 0f
        }
        credentialDismissActive = false
        credentialDismissFinishRunnable = null
        credentialDismissLauncherNotified = false
        credentialDismissView = null
        credentialDismissGeneration = 0L
        credentialDismissUserId = UserHandle.USER_NULL
        if (
            sessionStillActive &&
                userId != UserHandle.USER_NULL &&
                SosKeyguardRuntime.isOriginalCredentialSession(generation, userId) &&
                SosKeyguardRuntime.cancelOriginalCredentialTransition(generation)
        ) {
            if (launcherWasNotified) {
                sendLauncherUnlockPhase(LAUNCHER_PHASE_CANCEL, generation, userId)
            }
            statusBarKeyguardViewManager.cancelOriginalCredentialUnlockSurface(generation, userId)
            SosKeyguardRuntime.finishOriginalCredentialTransition(generation)
        }
    }

    override fun computeScroll() {
        super.computeScroll()
        val scroller = verticalScroller ?: return
        if (scroller.computeScrollOffset()) {
            val offset = height - scroller.currY.toFloat()
            if (credentialDismissActive) {
                applyOriginalCredentialDismissVisualState(offset)
            } else {
                updateOriginalVerticalOffset(offset, requestBouncer = false)
            }
            if (!scroller.isFinished) {
                postInvalidateOnAnimation()
                return
            }
        }
        if (
            credentialDismissActive ||
                (credentialDismissGeneration != 0L &&
                    credentialDismissGeneration ==
                        SosKeyguardRuntime.getOriginalCredentialGeneration())
        ) {
            completeOriginalCredentialDismiss()
            return
        }
        completeOriginalVerticalSettle()
    }

    private fun completeOriginalVerticalSettle() {
        verticalScroller = null
        Log.d(
            TAG,
            "complete unlock target=$verticalSettleTarget external=$externalVerticalGestureOwned " +
                "security=${verticalGestureRequiresSecurity()} offset=$verticalOffset",
        )
        when (verticalSettleTarget) {
            SosBouncerVisualCoordinator.SettleTarget.MAIN -> {
                verticalOffset = 0f
                applyOriginalSecureVisualState(
                    SosBouncerVisualCoordinator.calculate(0f, height.toFloat())
                )
                removeCallbacks(notifyLauncherDismissRunnable)
                if (launcherDismissNotified) {
                    sendLauncherUnlockPhase(LAUNCHER_PHASE_CANCEL, 0L)
                }
                launcherDismissNotified = false
                SosKeyguardRuntime.finishOriginalInteractiveTransition()
                if (verticalBouncerRequested) {
                    primaryBouncerInteractor.setPanelExpansion(
                        KeyguardBouncerConstants.EXPANSION_HIDDEN
                    )
                    primaryBouncerInteractor.hide()
                }
                verticalBouncerRequested = false
                restoreVerticalOriginPage()
                widgetSecureTransitionActive = false
                // Restore the dormant main-page chrome after returning to TPage. The page blur
                // remains owned by pagePosition, so the resting widget page stays fully blurred.
                applyOriginalSecureVisualState(
                    SosBouncerVisualCoordinator.calculate(0f, height.toFloat())
                )
                externalVerticalGestureOwned = false
                startMainBreathing()
            }
            SosBouncerVisualCoordinator.SettleTarget.BOUNCER -> {
                val hasSecurity = verticalGestureRequiresSecurity()
                if (hasSecurity && verticalOriginPage != PAGE_MAIN) {
                    currentPage = PAGE_MAIN
                    pagePosition = PAGE_MAIN.toFloat()
                    updatePageTranslations(pagePosition)
                    // Do not start the main-page breathing animation underneath the bouncer.
                    setPageVisibility(PAGE_MAIN, PAGE_MAIN)
                }
                verticalOffset =
                    SosBouncerVisualCoordinator.completedOffset(
                        height.toFloat(),
                        layoutMetrics?.scale ?: 1f,
                    )
                if (hasSecurity) {
                    updateOriginalVerticalOffset(verticalOffset, requestBouncer = false)
                    primaryBouncerInteractor.setPanelExpansion(
                        KeyguardBouncerConstants.EXPANSION_VISIBLE
                    )
                } else {
                    notifyLauncherDismissIfNeeded()
                    sendLauncherUnlockPhase(LAUNCHER_PHASE_COMMIT, 0L)
                    applyOriginalNoSecurityVisualState(verticalOffset)
                    noSecurityUnlockCommitted = true
                    // This is the first and only point at which Android 16 may enter going-away.
                    // The one-shot marker makes its remote target settle at amount=1 with no AOSP
                    // scale/translation pass. Framework owns the 600ms background hand-off.
                    SosKeyguardRuntime.commitOriginalInteractiveTransition()
                    SosKeyguardRuntime.markOriginalUnlockAnimationCompleted()
                    statusBarKeyguardViewManager.dismissAndCollapse()
                }
                externalVerticalGestureOwned = false
            }
        }
    }

    private fun updateVerticalOriginPageTransition() {
        if (
            !externalVerticalGestureOwned ||
                !verticalGestureRequiresSecurity() ||
                verticalOriginPage != PAGE_WIDGET ||
                height <= 0
        ) {
            return
        }
        pagePosition =
            widgetSecurePagePosition(
                offset = verticalOffset,
                screenHeight = height.toFloat(),
                originPosition = verticalOriginPosition,
            )
        updatePageTranslations(pagePosition)
    }

    private fun activateWidgetSecureTransitionIfNeeded() {
        if (
            widgetSecureTransitionActive ||
                !externalVerticalGestureOwned ||
                verticalOriginPage != PAGE_WIDGET
        ) {
            return
        }
        widgetSecureTransitionActive = true
        preparePagesForTransition(verticalOriginPosition, PAGE_MAIN.toFloat())
        applyWidgetSecureWallpaperState()
        applyCombinedBlurProgress()
    }

    private fun restoreVerticalOriginPage() {
        if (verticalOriginPage == PAGE_WIDGET) {
            currentPage = PAGE_WIDGET
            pagePosition = verticalOriginPosition
            updatePageTranslations(pagePosition)
            settlePageVisibility()
        }
        verticalOriginPage = PAGE_MAIN
        verticalOriginPosition = PAGE_MAIN.toFloat()
    }

    private fun abortOriginalVerticalScroller() {
        removeCallbacks(notifyLauncherDismissRunnable)
        verticalScroller?.abortAnimation()
        verticalScroller = null
    }

    private fun resetOriginalVerticalGesture(hideBouncer: Boolean) {
        if (credentialDismissActive) {
            // Screen-off, user switches and configuration loss must retain Keyguard even though
            // Android has already authenticated. Never convert an interrupted curtain into an
            // early completed unlock.
            cancelOriginalCredentialDismiss()
        }
        if (!noSecurityUnlockCommitted) {
            SosKeyguardRuntime.finishOriginalInteractiveTransition()
            SosKeyguardRuntime.clearOriginalUnlockAnimationCompletion()
        }
        if (launcherDismissNotified && !noSecurityUnlockCommitted) {
            sendLauncherUnlockPhase(LAUNCHER_PHASE_CANCEL, 0L)
        }
        launcherDismissNotified = false
        verticalGestureStartedWithSecurity = false
        verticalGestureCandidateWithSecurity = null
        noSecurityUnlockCommitted = false
        abortOriginalVerticalScroller()
        verticalOffset = 0f
        verticalSettleTarget = SosBouncerVisualCoordinator.SettleTarget.MAIN
        widgetSecureTransitionActive = false
        if (height > 0) {
            applyOriginalSecureVisualState(
                SosBouncerVisualCoordinator.calculate(0f, height.toFloat())
            )
        } else {
            bouncerBlurProgress = 0f
            applyCombinedBlurProgress()
        }
        if (hideBouncer && verticalBouncerRequested) primaryBouncerInteractor.hide()
        verticalBouncerRequested = false
        externalVerticalGestureOwned = false
        verticalOriginPage = PAGE_MAIN
        verticalOriginPosition = PAGE_MAIN.toFloat()
    }

    override fun onFlashlightChanged(enabled: Boolean) {
        dispatchToMain {
            flashlightEnabled = enabled
            updateFlashlight()
        }
    }

    override fun onFlashlightError() {
        dispatchToMain {
            flashlightEnabled = false
            updateFlashlight()
        }
    }

    override fun onFlashlightAvailabilityChanged(available: Boolean) {
        dispatchToMain {
            flashlightAvailable = available
            updateFlashlight()
        }
    }

    override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
        dispatchToMain {
            val wasPluggedIn = batteryPluggedIn
            batteryLevel = level
            batteryPluggedIn = pluggedIn
            batteryCharging = charging
            updateBattery()
            updateAod()
            if (!wasPluggedIn && pluggedIn) {
                showChargingAnimation()
            } else if (wasPluggedIn && !pluggedIn) {
                hideChargingAnimation()
            }
        }
    }

    override fun onDozingChanged(isDozing: Boolean) {
        dispatchToMain {
            updateDozing(isDozing)
        }
    }

    private fun createAodLayer(): FrameLayout {
        return FrameLayout(context).apply {
            id = R.id.sos_delta_aod_layer
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(
                FrameLayout(context).apply {
                    id = R.id.sos_delta_aod_content
                    addView(
                        TextView(context).apply {
                            id = R.id.sos_delta_aod_time
                            gravity = Gravity.START
                            setIncludeFontPadding(false)
                            setTextColor(Color.WHITE)
                            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                        },
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        LinearLayout(context).apply {
                            id = R.id.sos_delta_aod_second_container
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                TextView(context).apply {
                                    id = R.id.sos_delta_aod_secondary
                                    gravity = Gravity.START
                                    setTextColor(Color.WHITE)
                                },
                                LinearLayout.LayoutParams(
                                    LayoutParams.WRAP_CONTENT,
                                    LayoutParams.WRAP_CONTENT,
                                ),
                            )
                            addView(
                                ImageView(context).apply {
                                    id = R.id.sos_delta_aod_battery
                                    setBackgroundResource(R.drawable.battery_frame)
                                    setImageResource(R.drawable.doze_battery_level_icon)
                                },
                                LinearLayout.LayoutParams(1, 1),
                            )
                            addView(
                                ImageView(context).apply {
                                    id = R.id.sos_delta_aod_charge
                                    setImageResource(R.drawable.battery_charge)
                                },
                                LinearLayout.LayoutParams(1, 1),
                            )
                        },
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            addView(
                ImageView(context).apply {
                    id = R.id.sos_delta_aod_fingerprint
                    setImageResource(R.drawable.fingerprint_icon_in_doze)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    visibility = View.GONE
                },
                LayoutParams(1, 1),
            )
        }
    }

    private fun createChargingOverlay(): FrameLayout {
        return FrameLayout(context).apply {
            id = R.id.sos_delta_charging_overlay
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(
                VideoView(context).apply {
                    id = R.id.sos_delta_charging_video
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                }
            )
            addView(
                ImageView(context).apply {
                    id = R.id.sos_delta_charging_frames
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                }
            )
        }
    }

    /**
     * NotificationShade lays out edge-to-edge and may deliver already-consumed insets to child
     * blueprints. Prefer the root's stable sources, then use the same framework resources that
     * size StatusBar and NavigationBar so the R2 scene never anchors to the physical panel edge.
     */
    private fun resolveStableLayoutInsets(dispatchedInsets: WindowInsets): SosKeyguardLayoutModel.Insets {
        val nonNavigationTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
        val dispatchedSafe = dispatchedInsets.getInsetsIgnoringVisibility(nonNavigationTypes)
        val rootSafe =
            rootWindowInsets?.getInsetsIgnoringVisibility(nonNavigationTypes) ?: dispatchedSafe
        val dispatchedNavigation =
            dispatchedInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        val rootNavigation =
            rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                ?: dispatchedNavigation

        return SosKeyguardInsetsPolicy.resolve(
            SosKeyguardInsetsPolicy.Input(
                nonNavigationInsets =
                    SosKeyguardLayoutModel.Insets(
                        left = maxOf(dispatchedSafe.left, rootSafe.left).toFloat(),
                        top =
                            maxOf(
                                    dispatchedSafe.top,
                                    rootSafe.top,
                                    SystemBarUtils.getStatusBarHeight(context),
                                )
                                .toFloat(),
                        right = maxOf(dispatchedSafe.right, rootSafe.right).toFloat(),
                        bottom = maxOf(dispatchedSafe.bottom, rootSafe.bottom).toFloat(),
                    ),
                navigationInsets =
                    SosKeyguardLayoutModel.Insets(
                        left = maxOf(dispatchedNavigation.left, rootNavigation.left).toFloat(),
                        top = maxOf(dispatchedNavigation.top, rootNavigation.top).toFloat(),
                        right = maxOf(dispatchedNavigation.right, rootNavigation.right).toFloat(),
                        bottom =
                            maxOf(dispatchedNavigation.bottom, rootNavigation.bottom).toFloat(),
                    ),
                isGesturalMode = QuickStepContract.isGesturalMode(navigationMode),
                showNavigationBar =
                    resources.getBoolean(com.android.internal.R.bool.config_showNavigationBar),
                navigationBarCanMove =
                    resources.getBoolean(com.android.internal.R.bool.config_navBarCanMove),
                rotation = display?.rotation ?: Surface.ROTATION_0,
                navigationBarHeight =
                    resources
                        .getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height)
                        .toFloat(),
                navigationBarWidth =
                    resources
                        .getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_width)
                        .toFloat(),
            )
        )
    }

    /** Applies one shared R2 design-space transform to every lockscreen scene. */
    private fun applyResponsiveLayout(viewportWidth: Int = width, viewportHeight: Int = height) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        musicAnimator?.end()
        val metrics =
            SosKeyguardLayoutModel.calculate(
                SosKeyguardLayoutModel.Input(
                    widthPx = viewportWidth.toFloat(),
                    heightPx = viewportHeight.toFloat(),
                    density = resources.displayMetrics.density,
                    fontScale = resources.configuration.fontScale,
                    insets = layoutInsets,
                    scene = currentLayoutScene(),
                    udfpsBounds = currentUdfpsBounds(),
                )
            )
        layoutMetrics = metrics
        pinnedTaskController.updateLayout(metrics)
        quickSelectorView.setGeometry(metrics)
        quickPickerView.layoutParams =
            LayoutParams(
                metrics.quickPicker.width.roundToInt(),
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin =
                    (viewportHeight - metrics.quickPicker.bottom).roundToInt().coerceAtLeast(0)
            }

        // The imported XML owns hierarchy and IDs.  Runtime geometry only substitutes scaled R2
        // coordinates; it never creates replacement cards or controls.
        originalBinding.widgetPage.setPadding(0, 0, 0, 0)
        applyOriginalMainTimeAnchor(mainChrome, metrics.mainTime.bottom, viewportHeight)
        applyOriginalTouchBounds(
            widgetShortcutView,
            metrics.widgetShortcut,
            metrics.minimumTouchTarget,
        )
        applyOriginalTouchBounds(
            cameraShortcutView,
            metrics.cameraShortcut,
            metrics.minimumTouchTarget,
        )
        applyOriginalBounds(weatherPanel, metrics.weather)
        applyOriginalBounds(musicPanel, metrics.music)
        applyOriginalBounds(recorderPanel, metrics.recorder)
        applyOriginalBounds(torchPanel, metrics.torch)
        applyOriginalTouchBounds(unlockHandle, metrics.unlockHandle, metrics.minimumTouchTarget)

        val scale = metrics.scale
        mainTimeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 90f * scale)
        mainAmPmView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f * scale)
        mainWeekDayView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f * scale)
        mainDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f * scale)
        (mainAmPmView.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginEnd = (18f * scale).roundToInt()
            mainAmPmView.layoutParams = it
        }
        (mainDateView.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginStart = (18f * scale).roundToInt()
            mainDateView.layoutParams = it
        }

        weatherTemperatureView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 216f * scale)
        weatherUnitView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 63f * scale)
        weatherSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 42f * scale)
        weatherAqiView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * scale)
        musicTitleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 73.5f * scale)
        musicArtistView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 45.5f * scale)

        weatherTemperatureView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        weatherUnitView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (3f * scale).roundToInt()
                topMargin = (55f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.END_OF, R.id.temp_num)
                addRule(android.widget.RelativeLayout.ALIGN_TOP, R.id.temp_num)
            }
        (weatherSummaryView.parent as? LinearLayout)?.let { details ->
            details.layoutParams =
                android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = (40f * scale).roundToInt()
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                }
        }
        (weatherAqiView.layoutParams as? LinearLayout.LayoutParams)?.also {
            it.marginStart = (20f * scale).roundToInt()
            it.bottomMargin = (3f * scale).roundToInt()
            weatherAqiView.layoutParams = it
        }
        batteryView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                (327f * scale).roundToInt(),
                (174f * scale).roundToInt(),
            ).apply {
                topMargin = (76f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            }

        applyMusicGeometry(scale)
        recorderWaveView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (114f * scale).roundToInt(),
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
            }
        originalBinding.recorderWidget.setPadding(
            (48f * scale).roundToInt(),
            (42f * scale).roundToInt(),
            (48f * scale).roundToInt(),
            (48f * scale).roundToInt(),
        )
        val recorderButtonSize = (138f * scale).roundToInt()
        recorderMarkView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(recorderButtonSize, recorderButtonSize).apply {
                addRule(android.widget.RelativeLayout.START_OF, R.id.btn_pause_resume)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        recorderPauseView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(recorderButtonSize, recorderButtonSize).apply {
                marginStart = (45f * scale).roundToInt()
                marginEnd = (45f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.START_OF, R.id.btn_start_stop)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        recorderStartStopView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(recorderButtonSize, recorderButtonSize).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        torchButton.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                (138f * scale).roundToInt(),
                (138f * scale).roundToInt(),
            ).apply {
                bottomMargin = (48f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }

        val roundCorner = 40f * scale
        originalBinding.musicPanel.setCornerRadius(roundCorner)
        originalBinding.recorderPanel.setCornerRadius(roundCorner)
        originalBinding.torchPanel.setCornerRadius(roundCorner)

        // Both original resource branches resolve to the same 360 design pixels:
        // 144dp at sw432dp/400dpi and 120dp at xxhdpi. R2 was not a 560dpi canvas.
        val aodContent = aodLayer.requireViewById<View>(R.id.sos_delta_aod_content)
        // Child coordinates below are absolute screen coordinates. Keep the scene root at the
        // display origin so cutout/status-bar insets are applied exactly once.
        applyFrameBounds(
            aodContent,
            SosKeyguardLayoutModel.Bounds(
                0f,
                0f,
                viewportWidth.toFloat(),
                viewportHeight.toFloat(),
            ),
        )
        aodTimeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 360f * scale)
        aodTimeView.setLineSpacing(0f, 0.75f)
        aodTimeView.layoutParams =
            LayoutParams(
                metrics.aodTime.width.roundToInt(),
                metrics.aodTime.height.roundToInt(),
            ).apply {
                leftMargin = metrics.aodTime.left.roundToInt()
                topMargin = metrics.aodTime.top.roundToInt()
            }
        aodSecondaryView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 45f * scale)
        aodSecondContainer.layoutParams =
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = metrics.aodSecond.left.roundToInt()
                topMargin = metrics.aodSecond.top.roundToInt()
            }
        val aodBatteryWidth = (45f * scale).roundToInt()
        val aodBatteryHeight = (32f * scale).roundToInt()
        aodBatteryView.layoutParams =
            LinearLayout.LayoutParams(aodBatteryWidth, aodBatteryHeight).apply {
                marginStart = (24f * scale).roundToInt()
                topMargin = (15f * scale).roundToInt()
            }
        aodChargeView.layoutParams =
            LinearLayout.LayoutParams((22f * scale).roundToInt(), aodBatteryHeight).apply {
                topMargin = (15f * scale).roundToInt()
            }
        currentUdfpsBounds()?.let { udfps ->
            val fingerprintSize = minOf(190f * scale, udfps.width, udfps.height)
            applyFrameBounds(
                aodFingerprintView,
                SosKeyguardLayoutModel.Bounds(
                    udfps.centerX - fingerprintSize / 2f,
                    udfps.centerY - fingerprintSize / 2f,
                    udfps.centerX + fingerprintSize / 2f,
                    udfps.centerY + fingerprintSize / 2f,
                ),
            )
        }
        applyAodPosition(animate = false)
    }

    private fun currentLayoutScene(): SosKeyguardLayoutModel.Scene =
        when {
            statusBarStateController.isDozing -> SosKeyguardLayoutModel.Scene.AOD
            chargingOverlay.visibility == View.VISIBLE -> SosKeyguardLayoutModel.Scene.CHARGING
            pinnedPreviewLayer.visibility == View.VISIBLE -> SosKeyguardLayoutModel.Scene.PIN_ROOT
            currentPage == PAGE_WIDGET -> SosKeyguardLayoutModel.Scene.TPAGE
            else -> SosKeyguardLayoutModel.Scene.MAIN
        }

    private fun currentUdfpsBounds(): SosKeyguardLayoutModel.Bounds? {
        val location = authController.udfpsLocation ?: return null
        val radius = authController.udfpsRadius
        if (radius <= 0f) return null
        return SosKeyguardLayoutModel.Bounds(
            location.x - radius,
            location.y - radius,
            location.x + radius,
            location.y + radius,
        )
    }

    private fun applyMusicGeometry(scale: Float) {
        originalBinding.musicWidget.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        musicArtworkView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (MUSIC_ALBUM_SIZE_PX * scale).roundToInt(),
            ).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP) }
        musicAppIconContainer.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                (168f * scale).roundToInt(),
                (168f * scale).roundToInt(),
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
            }
        musicTitleView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                (600f * scale).roundToInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = (48f * scale).roundToInt()
                topMargin = (698f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
            }
        musicArtistView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                (600f * scale).roundToInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = (48f * scale).roundToInt()
                bottomMargin = (28f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.BELOW, R.id.keyguard_music_track_name)
                addRule(android.widget.RelativeLayout.ALIGN_START, R.id.keyguard_music_track_name)
                addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, R.id.audio_player_album_art)
            }
        val buttonSize = (138f * scale).roundToInt()
        musicPreviousView.layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        musicExpandedPlayPauseView.layoutParams =
            LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                marginStart = (90f * scale).roundToInt()
                marginEnd = (90f * scale).roundToInt()
            }
        musicNextView.layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        musicControlsView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (MUSIC_PANEL_HEIGHT_PX * scale).roundToInt(),
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
            }
        musicPlayPauseView.layoutParams =
            android.widget.RelativeLayout.LayoutParams(buttonSize, buttonSize).apply {
                marginEnd = (51f * scale).roundToInt()
                bottomMargin = (48f * scale).roundToInt()
                addRule(android.widget.RelativeLayout.ALIGN_END, R.id.audio_player_album_art)
                addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, R.id.audio_player_album_art)
            }
        layoutMetrics?.let { applyMusicExpansionFinalGeometry(it, musicExpanded) }
    }

    private fun setMusicExpanded(expanded: Boolean, fast: Boolean = false) {
        if (musicExpanded == expanded) return
        musicAnimator?.end()
        musicExpanded = expanded
        val metrics = layoutMetrics ?: return
        val duration =
            when {
                !expanded -> MUSIC_CONTRACT_DURATION_MS
                fast -> MUSIC_EXPAND_FAST_DURATION_MS
                else -> MUSIC_EXPAND_DURATION_MS
            }
        musicControlsView.visibility = View.VISIBLE
        musicPlayPauseView.visibility = View.VISIBLE
        if (!expanded) {
            for (index in 0 until musicControlsView.childCount) {
                musicControlsView.getChildAt(index).isClickable = false
            }
        }
        val panelHeight = MUSIC_PANEL_HEIGHT_PX * metrics.scale
        val recorderExtendedHeight = RECORDER_EXTENDED_HEIGHT_PX * metrics.scale
        val recorderContractedHeight = RECORDER_CONTRACTED_HEIGHT_PX * metrics.scale
        val panelInterpolator = DecelerateInterpolator(if (expanded) 2f else 1.5f)
        val recorderInterpolator = DecelerateInterpolator()
        val recorderTopInterpolator = OvershootInterpolator(1.1f)
        val animator =
            ValueAnimator.ofFloat(0f, duration.toFloat()).apply {
                this.duration = duration
                addUpdateListener { valueAnimator ->
                    val time = valueAnimator.animatedValue as Float
                    val fraction = valueAnimator.animatedFraction
                    val panelDuration = duration * if (expanded) 0.7f else 0.6f
                    val panelProgress = (time / panelDuration).coerceIn(0f, 1f)
                    val panelTranslation =
                        if (expanded) {
                            panelHeight * panelInterpolator.getInterpolation(panelProgress)
                        } else {
                            panelHeight * (1f - panelInterpolator.getInterpolation(panelProgress))
                        }
                    val recorderDelay = duration * if (expanded) 0.075f else 0.094f
                    val recorderDuration = duration * if (expanded) 0.67f else 0.53f
                    val recorderProgress =
                        ((time - recorderDelay) / recorderDuration).coerceIn(0f, 1f)
                    val recorderHeight =
                        if (expanded) {
                            recorderExtendedHeight -
                                (recorderExtendedHeight - recorderContractedHeight) *
                                    recorderInterpolator.getInterpolation(recorderProgress)
                        } else {
                            recorderContractedHeight +
                                (recorderExtendedHeight - recorderContractedHeight) *
                                    recorderInterpolator.getInterpolation(recorderProgress)
                        }
                    val topProgress = recorderTopInterpolator.getInterpolation(fraction)
                    val recorderTopOffset =
                        if (expanded) panelHeight * topProgress
                        else panelHeight * (1f - topProgress)
                    val controlsAlpha =
                        if (expanded) fraction.pow(4f)
                        else (1f - fraction.pow(0.25f) * 1.5f).coerceAtLeast(0f)
                    val compactAlpha =
                        if (expanded) (((1f - fraction) * 2f) - 1f).coerceAtLeast(0f)
                        else 1f - controlsAlpha
                    applyMusicExpansionFrame(
                        metrics,
                        panelTranslation,
                        recorderTopOffset,
                        recorderHeight,
                        controlsAlpha,
                        compactAlpha,
                    )
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (musicAnimator !== animation) return
                            musicAnimator = null
                            applyMusicExpansionFinalGeometry(metrics, expanded)
                        }
                    }
                )
            }
        musicAnimator = animator
        animator.start()
    }

    private fun applyMusicExpansionFinalGeometry(
        metrics: SosKeyguardLayoutModel.Metrics,
        expanded: Boolean,
    ) {
        val panelHeight = if (expanded) MUSIC_PANEL_HEIGHT_PX * metrics.scale else 0f
        val recorderHeight =
            (if (expanded) RECORDER_CONTRACTED_HEIGHT_PX else RECORDER_EXTENDED_HEIGHT_PX) *
                metrics.scale
        applyMusicExpansionFrame(
            metrics,
            panelHeight,
            panelHeight,
            recorderHeight,
            if (expanded) 1f else 0f,
            if (expanded) 0f else 1f,
        )
        musicControlsView.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
        musicPlayPauseView.visibility = if (expanded) View.GONE else View.VISIBLE
        for (index in 0 until musicControlsView.childCount) {
            musicControlsView.getChildAt(index).isClickable = expanded
        }
    }

    private fun applyMusicExpansionFrame(
        metrics: SosKeyguardLayoutModel.Metrics,
        panelTranslation: Float,
        recorderTopOffset: Float,
        recorderHeight: Float,
        controlsAlpha: Float,
        compactAlpha: Float,
    ) {
        applyOriginalBounds(
            musicPanel,
            metrics.music.copy(bottom = metrics.music.bottom + panelTranslation),
        )
        applyOriginalBounds(
            recorderPanel,
            metrics.recorder.copy(
                top = metrics.recorder.top + recorderTopOffset,
                bottom = metrics.recorder.top + recorderTopOffset + recorderHeight,
            ),
        )
        applyOriginalBounds(
            torchPanel,
            metrics.torch.copy(
                top = metrics.torch.top + recorderTopOffset,
                bottom = metrics.torch.top + recorderTopOffset + recorderHeight,
            ),
        )
        musicControlsView.alpha = controlsAlpha.coerceIn(0f, 1f)
        musicPlayPauseView.alpha = compactAlpha.coerceIn(0f, 1f)
        recorderWaveView.alpha = compactAlpha.coerceIn(0f, 1f)
    }

    private fun applySmartisanTypefaces() {
        mainTimeView.typeface = smartisanBoldTypeface
        mainAmPmView.typeface = smartisanBoldTypeface
        mainWeekDayView.typeface = smartisanBoldTypeface
        mainDateView.typeface = smartisanBoldTypeface
        weatherTemperatureView.typeface = smartisanRegularTypeface
        weatherUnitView.typeface = smartisanBoldTypeface
        weatherSummaryView.typeface = smartisanBoldTypeface
        weatherAqiView.typeface = smartisanRegularTypeface
        musicTitleView.typeface = smartisanBoldTypeface
        musicArtistView.typeface = smartisanRegularTypeface
        aodTimeView.typeface = smartisanBoldTypeface
        aodSecondaryView.typeface = smartisanBoldTypeface
        batteryView.setSmartisanTypeface(smartisanBoldTypeface)
        recorderWaveView.setSmartisanTypeface(smartisanRegularTypeface)
    }

    private fun applyFrameBounds(view: View, bounds: SosKeyguardLayoutModel.Bounds) {
        view.layoutParams =
            LayoutParams(max(1, bounds.width.roundToInt()), max(1, bounds.height.roundToInt())).apply {
                leftMargin = bounds.left.roundToInt()
                topMargin = bounds.top.roundToInt()
            }
    }

    private fun applyOriginalBounds(view: View, bounds: SosKeyguardLayoutModel.Bounds) {
        val viewWidth = max(1, bounds.width.roundToInt())
        val viewHeight = max(1, bounds.height.roundToInt())
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        view.layoutParams =
            when (view.parent) {
                is android.widget.RelativeLayout ->
                    android.widget.RelativeLayout.LayoutParams(viewWidth, viewHeight).apply {
                        leftMargin = left
                        topMargin = top
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                    }
                else ->
                    LayoutParams(viewWidth, viewHeight).apply {
                        leftMargin = left
                        topMargin = top
                    }
            }
    }

    /** Keeps the original wrap-content font metrics while anchoring its bottom in design space. */
    private fun applyOriginalMainTimeAnchor(view: View, bottom: Float, viewportHeight: Int) {
        view.layoutParams =
            android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    bottomMargin = max(0, viewportHeight - bottom.roundToInt())
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                }
    }

    private fun applyOriginalTouchBounds(
        view: ImageView,
        visualBounds: SosKeyguardLayoutModel.Bounds,
        minimumTouchTarget: Float,
    ) {
        val touchWidth = max(visualBounds.width, minimumTouchTarget)
        val touchHeight = max(visualBounds.height, minimumTouchTarget)
        val left = visualBounds.centerX - touchWidth / 2f
        val top = visualBounds.centerY - touchHeight / 2f
        applyOriginalBounds(
            view,
            SosKeyguardLayoutModel.Bounds(left, top, left + touchWidth, top + touchHeight),
        )
        val horizontalPadding = max(0f, (touchWidth - visualBounds.width) / 2f).roundToInt()
        val verticalPadding = max(0f, (touchHeight - visualBounds.height) / 2f).roundToInt()
        view.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun finishHorizontalGesture(dx: Float, velocityX: Float) {
        if (
            maximumPointerCount >= FOUR_FINGER_COUNT &&
                currentPage == PAGE_MAIN &&
                abs(dx) >=
                    (layoutMetrics?.wallpaperSwipeThreshold
                        ?: resources.getDimensionPixelSize(R.dimen.sos_delta_four_finger_distance)
                            .toFloat())
        ) {
            requestWallpaperCycle(if (dx < 0f) DIRECTION_NEXT else DIRECTION_PREVIOUS)
            animateToPage(PAGE_MAIN)
            return
        }
        val scale = layoutMetrics?.scale ?: (width / SosKeyguardLayoutModel.DESIGN_WIDTH)
        val movingThroughCamera =
            currentPage == PAGE_CAMERA || (currentPage == PAGE_MAIN && dx < 0f)
        val distanceThreshold =
            (if (movingThroughCamera) CAMERA_PAGE_THRESHOLD_PX else PAGE_THRESHOLD_PX) * scale
        val fling =
            abs(velocityX) >= ViewConfiguration.get(context).scaledMinimumFlingVelocity &&
                velocityX * dx > 0f
        val destination =
            when {
                dx <= -distanceThreshold || (fling && velocityX < 0f) -> currentPage + 1
                dx >= distanceThreshold || (fling && velocityX > 0f) -> currentPage - 1
                else -> currentPage
            }
        animateToPage(destination.coerceIn(PAGE_WIDGET, PAGE_CAMERA), velocityX)
    }

    private fun beginGestureTracking(event: MotionEvent) {
        pageAnimator?.cancel()
        abortOriginalVerticalScroller()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        downX = event.x
        downY = event.y
        gestureMode = GESTURE_NONE
        maximumPointerCount = 1
        topGestureExcluded =
            downY <= resources.getDimensionPixelSize(R.dimen.sos_delta_top_gesture_region)
        pressMainChrome()
        pressWidgetAt(event)
    }

    private fun startGestureIfNeeded(event: MotionEvent): Boolean {
        if (gestureMode != GESTURE_NONE) return true
        if (topGestureExcluded) return false
        val dx = event.x - downX
        val dy = event.y - downY
        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
            gestureMode = GESTURE_HORIZONTAL
            preparePagesForDrag(dx)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (dy < -touchSlop && abs(dy) > abs(dx)) {
            gestureMode = GESTURE_VERTICAL
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return false
    }

    private fun animateToPage(destination: Int, velocityX: Float = 0f) {
        pageAnimator?.cancel()
        pageAnimator = null
        val start = pagePosition
        val end = destination.toFloat()
        preparePagesForTransition(start, end)
        currentPage = destination
        if (destination != PAGE_MAIN) stopMainBreathing(resetScale = true)
        val animator =
            ValueAnimator.ofFloat(start, end).apply {
                val startOffset = (PAGE_MAIN - start) * width
                val endOffset = (PAGE_MAIN - end) * width
                val delta = endOffset - startOffset
                val originalDuration =
                    if (delta * velocityX < 0f && velocityX != 0f) {
                        abs(delta / velocityX) * 1000f * ORIGINAL_REBOUND_VELOCITY_FACTOR
                    } else {
                        Math.pow(abs(delta).toDouble(), 0.25).toFloat() *
                            ORIGINAL_DISTANCE_DURATION_FACTOR
                    }
                duration =
                    originalDuration
                        .roundToInt()
                        .coerceIn(1, MAX_PAGE_SETTLE_DURATION_MS)
                        .toLong()
                addUpdateListener {
                    pagePosition = it.animatedValue as Float
                    updatePageTranslations(pagePosition)
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (pageAnimator !== animation) return
                            pageAnimator = null
                            if (!cancelled) {
                                pagePosition = end
                                updatePageTranslations(pagePosition)
                            }
                            settlePageVisibility()
                            if (!cancelled && destination == PAGE_CAMERA) launchCamera()
                        }
                    }
                )
            }
        pageAnimator = animator
        animator.start()
    }

    private fun preparePagesForDrag(dx: Float) {
        val adjacentPage =
            if (dx >= 0f) {
                (currentPage - 1).coerceAtLeast(PAGE_WIDGET)
            } else {
                (currentPage + 1).coerceAtMost(PAGE_CAMERA)
            }
        setPageVisibility(currentPage, adjacentPage)
    }

    private fun preparePagesForTransition(start: Float, end: Float) {
        val first = kotlin.math.floor(minOf(start, end).toDouble()).toInt()
        val last = kotlin.math.ceil(maxOf(start, end).toDouble()).toInt()
        setPageVisibility(first, last)
    }

    private fun setPageVisibility(firstPage: Int, lastPage: Int) {
        val first = minOf(firstPage, lastPage)
        val last = maxOf(firstPage, lastPage)
        widgetPage.visibility =
            if (PAGE_WIDGET in first..last) View.VISIBLE else View.INVISIBLE
        mainPage.visibility =
            if (PAGE_MAIN in first..last) View.VISIBLE else View.INVISIBLE
        cameraPage.visibility =
            if (PAGE_CAMERA in first..last) View.VISIBLE else View.INVISIBLE
    }

    private fun settlePageVisibility() {
        setPageVisibility(currentPage, currentPage)
        if (currentPage == PAGE_MAIN) startMainBreathing()
    }

    /**
     * Original MainPageAnimHelper breathes the clock and both shortcuts from 100% to 105% in
     * 1500 ms, back in 1500 ms, then waits 1000 ms before the next cycle. Touch-down converges to
     * 105% in 300 ms and touch-up returns to 100% in 300 ms.
     */
    private fun startMainBreathing() {
        if (
            mainTouching ||
                currentPage != PAGE_MAIN ||
                pureWallpaper ||
                statusBarStateController.isDozing ||
                screenLifecycle.screenState != ScreenLifecycle.SCREEN_ON ||
                !isAttachedToWindow ||
                mainScaleAnimator?.isStarted == true
        ) {
            return
        }
        animateMainScale(
            targetPercent = MAIN_ZOOM_PERCENT,
            durationMs = MAIN_ZOOM_DURATION_MS,
            startDelayMs = MAIN_ZOOM_DELAY_MS,
        ) {
            animateMainScale(0f, MAIN_ZOOM_DURATION_MS) { startMainBreathing() }
        }
    }

    private fun pressMainChrome() {
        if (
            mainTouching ||
                currentPage != PAGE_MAIN ||
                pureWallpaper ||
                statusBarStateController.isDozing
        ) {
            return
        }
        mainTouching = true
        animateMainScale(MAIN_ZOOM_PERCENT, MAIN_TOUCH_SCALE_DURATION_MS)
    }

    private fun releaseMainPress() {
        if (!mainTouching) return
        mainTouching = false
        animateMainScale(0f, MAIN_TOUCH_SCALE_DURATION_MS) { startMainBreathing() }
    }

    private fun stopMainBreathing(resetScale: Boolean) {
        mainTouching = false
        mainScaleAnimator?.cancel()
        mainScaleAnimator = null
        if (resetScale) updateMainScale(0f)
    }

    private fun animateMainScale(
        targetPercent: Float,
        durationMs: Long,
        startDelayMs: Long = 0L,
        onEnd: (() -> Unit)? = null,
    ) {
        mainScaleAnimator?.cancel()
        val animator =
            ValueAnimator.ofFloat(mainScalePercent, targetPercent).apply {
                duration = durationMs
                startDelay = startDelayMs
                interpolator =
                    android.view.animation.AnimationUtils.loadInterpolator(
                        context,
                        android.R.anim.accelerate_decelerate_interpolator,
                    )
                addUpdateListener { updateMainScale(it.animatedValue as Float) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (mainScaleAnimator !== animation) return
                            mainScaleAnimator = null
                            if (!cancelled) onEnd?.invoke()
                        }
                    }
                )
            }
        mainScaleAnimator = animator
        animator.start()
    }

    private fun updateMainScale(percent: Float) {
        mainScalePercent = percent
        val scale = 1f + percent / 100f
        mainChrome.scaleX = scale
        mainChrome.scaleY = scale
        widgetShortcutView.scaleX = scale
        widgetShortcutView.scaleY = scale
        cameraShortcutView.scaleX = scale
        cameraShortcutView.scaleY = scale
    }

    private fun pressWidgetAt(event: MotionEvent) {
        if (currentPage != PAGE_WIDGET || pressedWidget != null) return
        val candidates =
            arrayOf(
                torchPanel to TORCH_TOUCH_SCALE,
                musicPanel to MUSIC_TOUCH_SCALE,
                recorderPanel to RECORDER_TOUCH_SCALE,
            )
        candidates.firstOrNull { (view, _) -> isRawPointInside(view, event.rawX, event.rawY) }
            ?.let { (view, scale) ->
                pressedWidget = view
                view.animate().cancel()
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(WIDGET_TOUCH_SCALE_DURATION_MS)
                    .setInterpolator(
                        android.view.animation.AnimationUtils.loadInterpolator(
                            context,
                            android.R.anim.accelerate_decelerate_interpolator,
                        )
                    )
                    .start()
            }
    }

    private fun releaseWidgetPress() {
        val view = pressedWidget ?: return
        pressedWidget = null
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(WIDGET_TOUCH_SCALE_DURATION_MS)
            .setInterpolator(
                android.view.animation.AnimationUtils.loadInterpolator(
                    context,
                    android.R.anim.accelerate_decelerate_interpolator,
                )
            )
            .start()
    }

    private fun isRawPointInside(view: View, rawX: Float, rawY: Float): Boolean {
        if (!rawX.isFinite() || !rawY.isFinite() || view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] &&
            rawX <= location[0] + view.width &&
            rawY >= location[1] &&
            rawY <= location[1] + view.height
    }

    private fun updatePageTranslations(position: Float) {
        if (width <= 0) return
        val mainProgress = PAGE_MAIN - position
        val mainRawTranslation = mainProgress * width
        mainPage.translationX =
            if (abs(mainRawTranslation) == width.toFloat()) {
                mainRawTranslation
            } else {
                mainRawTranslation * MAIN_PAGE_PARALLAX
            }
        mainPage.alpha =
            if (mainProgress in -MAIN_ALPHA_WINDOW..MAIN_ALPHA_WINDOW) {
                1f - abs(mainProgress) / MAIN_ALPHA_WINDOW
            } else {
                0f
            }

        val widgetProgress = PAGE_WIDGET - position
        val widgetRawTranslation = widgetProgress * width
        if (widgetRawTranslation in -width.toFloat()..0f) {
            if (abs(widgetProgress) > WIDGET_PARALLAX_LIMIT) {
                widgetPage.translationX = widgetRawTranslation
                widgetPage.alpha = 0f
            } else {
                widgetPage.translationX = widgetRawTranslation * MAIN_PAGE_PARALLAX
                widgetPage.alpha =
                    1f - abs(widgetProgress) / WIDGET_PARALLAX_LIMIT
            }
        } else {
            widgetPage.translationX = widgetRawTranslation
            widgetPage.alpha = 0f
        }

        val cameraOffset = ((PAGE_CAMERA - position) * width).coerceIn(0f, width.toFloat())
        val cameraProgress = 1f - cameraOffset / width
        cameraPage.translationX = cameraOffset
        cameraContent.translationX = -cameraOffset
        cameraContent.alpha = cameraProgress
        cameraContent.scaleX = CAMERA_MIN_SCALE + (1f - CAMERA_MIN_SCALE) * cameraProgress
        cameraContent.scaleY = cameraContent.scaleX
        // R2 pages move above a stationary center-cropped wallpaper.  A horizontal ImageView
        // translation both deviates from the original hierarchy and exposes stale crop edges
        // after an attach/rotation race.
        wallpaperView.translationX = 0f
        pageBlurProgress = abs(PAGE_MAIN - position).coerceIn(0f, 1f)
        applyCombinedBlurProgress()
    }

    private fun applyCombinedBlurProgress() {
        // Original KeyguardHostView.setBackgroundBlur() pins TPage at full blur whenever its
        // security view is entering or already visible. Taking max() here used to dip to 0.5 as
        // page blur fell while bouncer blur rose, producing a visibly different wallpaper.
        val progress =
            combinedBlurProgress(
                pageProgress = pageBlurProgress,
                bouncerProgress = bouncerBlurProgress,
                forceWidgetSecurityBlur = widgetSecureTransitionActive,
            )
        originalBinding.blur.setBlurProgress(progress)
    }

    private fun onCameraShortcutTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (
                    currentPage != PAGE_MAIN ||
                        pureWallpaper ||
                        statusBarStateController.isDozing ||
                        quickEditorVisible
                ) {
                    return false
                }
                quickPressEligible = true
                quickDownRawX = event.rawX
                quickDownRawY = event.rawY
                quickLastRawX = event.rawX
                quickLastRawY = event.rawY
                quickDownTime = event.eventTime
                aodHandler.removeCallbacks(showQuickSelectorRunnable)
                aodHandler.removeCallbacks(showQuickEditorRunnable)
                aodHandler.postDelayed(showQuickSelectorRunnable, QUICK_SELECTOR_DELAY_MS)
                aodHandler.postDelayed(showQuickEditorRunnable, QUICK_EDITOR_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                quickLastRawX = event.rawX
                quickLastRawY = event.rawY
                if (!quickPressEligible) return false
                if (!quickSelectorExpanded) {
                    if (
                        abs(event.rawX - quickDownRawX) > touchSlop ||
                            abs(event.rawY - quickDownRawY) > touchSlop
                    ) {
                        cancelQuickPressTimers()
                        quickPressEligible = false
                    }
                } else {
                    val local = rawToLocal(event.rawX, event.rawY)
                    quickSelectorView.updatePointer(local.first, local.second)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                quickLastRawX = event.rawX
                quickLastRawY = event.rawY
                cancelQuickPressTimers()
                if (quickEditorVisible) {
                    quickPressEligible = false
                    quickSelectorExpanded = false
                    return true
                }
                if (quickSelectorExpanded) {
                    val local = rawToLocal(event.rawX, event.rawY)
                    val target = quickSelectorView.updatePointer(local.first, local.second)
                    quickPressEligible = false
                    quickSelectorExpanded = false
                    if (target != null) launchQuickTarget(target) else quickSelectorView.hide()
                    releaseMainPress()
                    return true
                }
                val isShortTap =
                    quickPressEligible && event.eventTime - quickDownTime <= QUICK_SELECTOR_DELAY_MS
                quickPressEligible = false
                if (isShortTap) animateToPage(PAGE_CAMERA)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!quickEditorVisible) cancelQuickLaunchState(returnToMain = false)
                return true
            }
        }
        return false
    }

    private fun showQuickSelector() {
        if (
            !quickPressEligible ||
                quickEditorVisible ||
                currentPage != PAGE_MAIN ||
                !isAttachedToWindow ||
                statusBarStateController.isDozing
        ) {
            return
        }
        val userId = ActivityManager.getCurrentUser()
        val useLightAssets = wallpaperTheme == SosKeyguardWallpaperTheme.DARK_WALLPAPER
        val resolved =
            quickLaunchSlots.map { target ->
                target?.let { quickLaunchResolver.resolve(it, userId, useLightAssets) }
            }
        quickSelectorExpanded = true
        stopMainBreathing(resetScale = true)
        quickSelectorView.show(resolved, useLightAssets)
        val local = rawToLocal(quickLastRawX, quickLastRawY)
        quickSelectorView.updatePointer(local.first, local.second)
        cameraShortcutView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun showQuickEditor(slots: List<SosQuickLaunchTarget?> = quickLaunchSlots) {
        if (
            (!quickPressEligible && pendingQuickConfiguration == null) ||
                currentPage != PAGE_MAIN ||
                !isAttachedToWindow ||
                statusBarStateController.isDozing
        ) {
            return
        }
        cancelQuickPressTimers()
        quickSelectorExpanded = false
        quickSelectorView.hide(immediate = true)
        quickEditorVisible = true
        quickPressEligible = false
        stopMainBreathing(resetScale = true)
        cameraShortcutView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

        val generation = ++quickCandidateLoadGeneration
        val userId = ActivityManager.getCurrentUser()
        Thread(
                {
                    val candidates = quickLaunchResolver.candidates(userId)
                    post {
                        if (
                            generation != quickCandidateLoadGeneration ||
                                !quickEditorVisible ||
                                !isAttachedToWindow
                        ) {
                            return@post
                        }
                        quickPickerView.show(
                            candidates,
                            slots,
                            wallpaperTheme == SosKeyguardWallpaperTheme.DARK_WALLPAPER,
                            object : QuickLaunchPickerView.Listener {
                                override fun onCancel() {
                                    pendingQuickConfiguration = null
                                    quickPickerView.hide {
                                        quickEditorVisible = false
                                        startMainBreathing()
                                    }
                                }

                                override fun onSave(slots: List<SosQuickLaunchTarget?>) {
                                    requestQuickConfigurationSave(slots)
                                }
                            },
                        )
                    }
                },
                "SosQuickLaunchCandidates",
            )
            .start()
    }

    private fun requestQuickConfigurationSave(slots: List<SosQuickLaunchTarget?>) {
        val userId = ActivityManager.getCurrentUser()
        if (!quickLaunchResolver.validate(slots, userId)) return
        val session =
            PendingQuickConfiguration(
                generation = ++quickActionGeneration,
                userId = userId,
                slots = slots.toList(),
            )
        pendingQuickConfiguration = session
        quickPickerView.hide { quickEditorVisible = false }
        statusBarKeyguardViewManager.dismissWithAction(
            object : OnDismissAction {
                override fun onDismiss(): Boolean {
                    if (!isCurrentQuickAction(session.generation, session.userId)) return false
                    val pending = pendingQuickConfiguration
                    if (pending?.generation != session.generation) return false
                    pendingQuickConfiguration = null
                    if (quickLaunchResolver.validate(session.slots, session.userId)) {
                        quickLaunchStore.save(session.userId, session.slots)
                        quickLaunchSlots = session.slots
                    }
                    cancelQuickLaunchState(returnToMain = true)
                    return false
                }
            },
            {
                val pending = pendingQuickConfiguration
                if (
                    pending?.generation == session.generation &&
                        isCurrentQuickAction(session.generation, session.userId)
                ) {
                    quickEditorVisible = true
                    showQuickEditor(session.slots)
                }
            },
            true,
        )
    }

    private fun launchQuickTarget(target: SosResolvedQuickLaunchTarget) {
        if (!target.available) {
            quickSelectorView.hide()
            resetToMainPage()
            return
        }
        quickSelectorView.showLaunching(target)
        if (target.target.kind == SosQuickLaunchTarget.Kind.SECURE_CAMERA) {
            launchSecureCameraFromSelector()
            cancelQuickLaunchState(returnToMain = true)
            return
        }
        val userId = ActivityManager.getCurrentUser()
        val session =
            PendingQuickLaunch(
                generation = ++quickActionGeneration,
                userId = userId,
                target = target,
            )
        pendingQuickLaunchTarget = session
        statusBarKeyguardViewManager.dismissWithAction(
            object : OnDismissAction {
                override fun onDismiss(): Boolean {
                    if (!isCurrentQuickAction(session.generation, session.userId)) return false
                    val pending = pendingQuickLaunchTarget
                    if (pending?.generation != session.generation) return false
                    pendingQuickLaunchTarget = null
                    val refreshed =
                        quickLaunchResolver.resolve(
                            session.target.target,
                            session.userId,
                            lightTheme = false,
                        )
                    val intent = refreshed.launchIntent
                    if (refreshed.available && intent != null) {
                        runCatching {
                                context.startActivityAsUser(intent, UserHandle.of(session.userId))
                            }
                            .onFailure { Log.w(TAG, "Unable to launch ${session.target.target}", it) }
                    }
                    cancelQuickLaunchState(returnToMain = true)
                    return false
                }
            },
            {
                if (isCurrentQuickAction(session.generation, session.userId)) {
                    cancelQuickLaunchState(returnToMain = true)
                }
            },
            true,
        )
    }

    private fun isCurrentQuickAction(generation: Int, userId: Int): Boolean =
        isQuickActionSessionCurrent(
            expectedGeneration = generation,
            currentGeneration = quickActionGeneration,
            expectedUserId = userId,
            currentUserId = ActivityManager.getCurrentUser(),
            attached = isAttachedToWindow,
        )

    private fun launchSecureCameraFromSelector() {
        hideChargingAnimation()
        if (cameraLauncher.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)) {
            cameraLauncher.launchCamera(
                StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE,
                true,
            )
        }
    }

    private fun cancelQuickLaunchState(returnToMain: Boolean) {
        cancelQuickPressTimers()
        quickCandidateLoadGeneration++
        quickActionGeneration++
        quickPressEligible = false
        quickSelectorExpanded = false
        quickEditorVisible = false
        pendingQuickLaunchTarget = null
        pendingQuickConfiguration = null
        quickSelectorView.hide(immediate = true)
        quickPickerView.cancelTransientState()
        if (returnToMain && currentPage != PAGE_MAIN) {
            resetToMainPage()
        } else if (returnToMain) {
            startMainBreathing()
        }
    }

    private fun cancelQuickPressTimers() {
        aodHandler.removeCallbacks(showQuickSelectorRunnable)
        aodHandler.removeCallbacks(showQuickEditorRunnable)
    }

    private fun rawToLocal(rawX: Float, rawY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rawX - location[0] to rawY - location[1]
    }

    private fun launchCamera() {
        if (currentPage != PAGE_CAMERA || !isAttachedToWindow) return
        hideChargingAnimation()
        if (cameraLauncher.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)) {
            cameraLauncher.launchCamera(
                StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE,
                true,
            )
        }
        postDelayed(
            {
                if (currentPage == PAGE_CAMERA) {
                    currentPage = PAGE_MAIN
                    pagePosition = PAGE_MAIN.toFloat()
                    updatePageTranslations(pagePosition)
                    settlePageVisibility()
                }
            },
            CAMERA_RESET_DELAY_MS,
        )
    }

    private fun togglePureWallpaper() {
        pureWallpaper = !pureWallpaper
        if (pureWallpaper) stopMainBreathing(resetScale = true)
        mainChrome.animate().cancel()
        if (!pureWallpaper) {
            mainChrome.visibility = View.VISIBLE
        }
        mainChrome
            .animate()
            .alpha(if (pureWallpaper) 0f else 1f)
            .setDuration(PURE_WALLPAPER_FADE_MS)
            .withEndAction {
                mainChrome.visibility = if (pureWallpaper) View.INVISIBLE else View.VISIBLE
                if (!pureWallpaper) startMainBreathing()
            }
            .start()
        widgetShortcutView.visibility = if (pureWallpaper) View.INVISIBLE else View.VISIBLE
        cameraShortcutView.visibility = if (pureWallpaper) View.INVISIBLE else View.VISIBLE
    }

    private fun resetGesture() {
        gestureMode = GESTURE_NONE
        maximumPointerCount = 1
        parent?.requestDisallowInterceptTouchEvent(false)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun loadLockWallpaper() {
        cancelWallpaperRetry()
        val resolver = context.contentResolver
        val configured =
            Settings.System.getStringForUser(
                resolver,
                LOCKSCREEN_BACKGROUND,
                UserHandle.USER_CURRENT,
            )
        val value = configured?.takeIf { it.isNotBlank() }
        val generation = ++wallpaperLoadGeneration
        setWallpaperLoadState(generation, WallpaperLoadState.PENDING)
        val userId = ActivityManager.getCurrentUser()
        enqueueWallpaperLoad(generation, userId, value, attempt = 0, loadCache = true)
    }

    private fun enqueueWallpaperLoad(
        generation: Int,
        userId: Int,
        configured: String?,
        attempt: Int,
        loadCache: Boolean,
    ) {
        ensureWallpaperWorker()
        wallpaperWorker.execute {
            if (!isCurrentWallpaperLoad(generation, userId)) return@execute

            val cachedBitmap = if (loadCache) wallpaperCache.load(userId) else null
            if (cachedBitmap != null) {
                publishWallpaperBitmap(generation, userId, cachedBitmap, "device-protected cache")
            }

            val configuredBitmap = configured?.let(::decodeConfiguredWallpaper)
            val liveBitmap = configuredBitmap ?: decodeWallpaperManagerBitmap()
            if (liveBitmap != null && !liveBitmap.isRecycled) {
                if (!isCurrentWallpaperLoad(generation, userId)) {
                    liveBitmap.recycle()
                    return@execute
                }
                // Do not replace a valid custom-wallpaper cache with the generic system wallpaper
                // when credential-encrypted content is merely unavailable during Direct Boot.
                publishWallpaperBitmap(
                    generation,
                    userId,
                    liveBitmap,
                    "live wallpaper",
                    persistAfterPublish = configuredBitmap != null || configured == null,
                )
                return@execute
            }

            post {
                if (!isCurrentWallpaperLoad(generation, userId)) return@post
                val hasCurrentRealWallpaper =
                    wallpaperInstalledGeneration == generation &&
                        !wallpaperUsingOpaqueFallback &&
                        ownedWallpaperBitmap?.isRecycled == false
                if (!hasCurrentRealWallpaper) {
                    installOpaqueBlackWallpaper()
                    systemIconsController.setKeyguardWallpaperTheme(false)
                    applyOriginalWallpaperTheme(SosKeyguardWallpaperTheme.DARK_WALLPAPER)
                }
                if (
                    wallpaperInstalledGeneration != generation ||
                        wallpaperUsingOpaqueFallback ||
                        ownedWallpaperBitmap?.isRecycled != false
                ) {
                    setWallpaperLoadState(generation, WallpaperLoadState.FAILED)
                }
                invalidate()
                scheduleWallpaperRetry(generation, userId, configured, attempt)
            }
        }
    }

    private fun decodeConfiguredWallpaper(value: String): Bitmap? =
        runCatching {
                val uri = Uri.parse(value)
                val input =
                    if (uri.scheme.isNullOrEmpty()) {
                        FileInputStream(value)
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                input?.use { stream -> BitmapFactory.decodeStream(stream) }
            }
            .onFailure { Log.w(TAG, "Unable to load configured lock wallpaper", it) }
            .getOrNull()

    /** Decode an owned copy; never retain WallpaperManager's shared drawable bitmap. */
    private fun decodeWallpaperManagerBitmap(): Bitmap? =
        runCatching {
                val manager = wallpaperManager ?: return@runCatching null
                val descriptor =
                    manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)
                        ?: manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                descriptor?.use { file ->
                    FileInputStream(file.fileDescriptor).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            }
            .onFailure { Log.w(TAG, "Unable to decode system lock wallpaper", it) }
            .getOrNull()

    private fun publishWallpaperBitmap(
        generation: Int,
        userId: Int,
        bitmap: Bitmap,
        source: String,
        persistAfterPublish: Boolean = false,
    ) {
        val originalTheme = SosKeyguardWallpaperThemeClassifier.classify(bitmap)
        val topSupportsDarkText = statusBarRegionSupportsDarkText(bitmap)
        aodHandler.post {
            if (!isCurrentWallpaperLoad(generation, userId)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@post
            }
            installOwnedWallpaperBitmap(bitmap, generation)
            setWallpaperLoadState(generation, WallpaperLoadState.READY)
            systemIconsController.setKeyguardWallpaperTheme(topSupportsDarkText)
            applyOriginalWallpaperTheme(originalTheme)
            Log.d(TAG, "Installed $source for user=$userId generation=$generation")
            if (persistAfterPublish) {
                retainWallpaperForCacheWrite(bitmap)
                ensureWallpaperWorker()
                wallpaperWorker.execute {
                    if (isCurrentWallpaperLoad(generation, userId)) {
                        wallpaperCache.save(userId, bitmap)
                    }
                    releaseWallpaperAfterCacheWrite(bitmap)
                }
            }
        }
    }

    private fun scheduleWallpaperRetry(
        generation: Int,
        userId: Int,
        configured: String?,
        attempt: Int,
    ) {
        cancelWallpaperRetry()
        // Exhausting the fast boot-time retries must not make a transient black fallback
        // permanent. Pin the counter at the slow-retry bucket to avoid integer overflow while the
        // same generation remains attached for a long lockscreen session.
        val nextAttempt = (attempt + 1).coerceAtMost(WALLPAPER_RETRY_DELAYS_MS.size)
        val retry =
            Runnable {
                wallpaperRetryRunnable = null
                if (isCurrentWallpaperLoad(generation, userId)) {
                    enqueueWallpaperLoad(
                        generation,
                        userId,
                        configured,
                        attempt = nextAttempt,
                        loadCache = false,
                    )
                }
            }
        wallpaperRetryRunnable = retry
        aodHandler.postDelayed(retry, wallpaperRetryDelayForAttempt(nextAttempt))
    }

    private fun cancelWallpaperRetry() {
        wallpaperRetryRunnable?.let(aodHandler::removeCallbacks)
        wallpaperRetryRunnable = null
    }

    private fun isCurrentWallpaperLoad(generation: Int, userId: Int): Boolean =
        isAttachedToWindow &&
            generation == wallpaperLoadGeneration &&
            userId == ActivityManager.getCurrentUser()

    private fun installOwnedWallpaperBitmap(
        bitmap: Bitmap,
        generation: Int = wallpaperLoadGeneration,
    ) {
        if (bitmap.isRecycled) return
        wallpaperUsingOpaqueFallback = false
        ownedWallpaperBitmap = bitmap
        wallpaperInstalledGeneration = generation
        wallpaperView.setImageBitmap(bitmap)
        originalBinding.blur.setOrgBitmap(bitmap) { released ->
            if (released !== ownedWallpaperBitmap) requestWallpaperRecycle(released)
        }
    }

    private fun installOpaqueBlackWallpaper() {
        val fallback = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        fallback.eraseColor(Color.BLACK)
        installOwnedWallpaperBitmap(fallback)
        wallpaperUsingOpaqueFallback = true
    }

    private fun setWallpaperLoadState(generation: Int, state: WallpaperLoadState) {
        if (generation != wallpaperLoadGeneration) return
        wallpaperLoadStateGeneration = generation
        wallpaperLoadState = state
    }

    private fun retainWallpaperForCacheWrite(bitmap: Bitmap) {
        synchronized(wallpaperCacheWriteLock) {
            wallpaperCacheWriteRefs[bitmap] = (wallpaperCacheWriteRefs[bitmap] ?: 0) + 1
        }
    }

    private fun requestWallpaperRecycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val recycleNow =
            synchronized(wallpaperCacheWriteLock) {
                if ((wallpaperCacheWriteRefs[bitmap] ?: 0) > 0) {
                    wallpaperRecycleAfterCacheWrite.add(bitmap)
                    false
                } else {
                    true
                }
            }
        if (recycleNow && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun releaseWallpaperAfterCacheWrite(bitmap: Bitmap) {
        val recycle =
            synchronized(wallpaperCacheWriteLock) {
                val remaining = (wallpaperCacheWriteRefs[bitmap] ?: 1) - 1
                if (remaining > 0) {
                    wallpaperCacheWriteRefs[bitmap] = remaining
                    false
                } else {
                    wallpaperCacheWriteRefs.remove(bitmap)
                    wallpaperRecycleAfterCacheWrite.remove(bitmap)
                }
            }
        if (recycle && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun ensureWallpaperWorker() {
        if (wallpaperWorker.isShutdown) wallpaperWorker = newWallpaperWorker()
    }

    private fun newWallpaperWorker(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SosKeyguardWallpaperLoader").apply { priority = Thread.NORM_PRIORITY - 1 }
        }

    private fun applySystemWallpaperTheme(colors: WallpaperColors?) {
        val supportsDarkText =
            colors != null &&
                colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        systemIconsController.setKeyguardWallpaperTheme(supportsDarkText)
        applyOriginalWallpaperTheme(
            if (supportsDarkText) {
                SosKeyguardWallpaperTheme.LIGHT_WALLPAPER
            } else {
                SosKeyguardWallpaperTheme.DARK_WALLPAPER
            }
        )
    }

    private fun applyOriginalWallpaperTheme(
        theme: SosKeyguardWallpaperTheme,
        force: Boolean = false,
    ) {
        if (!force && wallpaperTheme == theme) return
        wallpaperTheme = theme
        val textColor = theme.textColor
        mainTimeView.setTextColor(textColor)
        mainAmPmView.setTextColor(textColor)
        mainWeekDayView.setTextColor(textColor)
        mainDateView.setTextColor(textColor)

        if (theme == SosKeyguardWallpaperTheme.LIGHT_WALLPAPER) {
            cameraShortcutView.setImageResource(R.drawable.icon_goto_camera_dark)
            widgetShortcutView.setImageResource(R.drawable.icon_goto_widget_dark)
            unlockHandle.setImageResource(R.drawable.icon_slide_unlock_dark)
        } else {
            cameraShortcutView.setImageResource(R.drawable.icon_goto_camera)
            widgetShortcutView.setImageResource(R.drawable.icon_goto_widget)
            unlockHandle.setImageResource(R.drawable.icon_slide_unlock_light)
        }
        if (quickSelectorExpanded) showQuickSelector()
    }

    /**
     * Smartisan derives a separate topTheme instead of tinting the status bar from the whole
     * wallpaper. Sample the top strip so a bright subject lower on the screen cannot make the
     * status icons unreadable.
     */
    private fun statusBarRegionSupportsDarkText(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val bandHeight = (bitmap.height * STATUS_BAR_SAMPLE_FRACTION).roundToInt().coerceAtLeast(1)
        val xStep = (bitmap.width / STATUS_BAR_SAMPLE_COLUMNS).coerceAtLeast(1)
        val yStep = (bandHeight / STATUS_BAR_SAMPLE_ROWS).coerceAtLeast(1)
        var luminanceSum = 0.0
        var samples = 0
        var y = yStep / 2
        while (y < bandHeight) {
            var x = xStep / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= MIN_WALLPAPER_SAMPLE_ALPHA) {
                    val red = Color.red(pixel) / 255.0
                    val green = Color.green(pixel) / 255.0
                    val blue = Color.blue(pixel) / 255.0
                    luminanceSum += red * 0.2126 + green * 0.7152 + blue * 0.0722
                    samples++
                }
                x += xStep
            }
            y += yStep
        }
        return samples > 0 && luminanceSum / samples >= LIGHT_WALLPAPER_LUMINANCE
    }

    private fun dispatchToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            // View.post() may be deferred or dropped while the host is detached. Screen/session
            // state is process state, so it must still be reset before a later first frame.
            aodHandler.post(action)
        }
    }

    private fun requestWallpaperCycle(direction: Int) {
        Thread(
                {
                    val extras = Bundle().apply { putInt(EXTRA_WALLPAPER_DIRECTION, direction) }
                    val changed =
                        runCatching {
                                context.contentResolver.call(
                                    WALLPAPER_PROVIDER_URI,
                                    METHOD_CHANGE_WALLPAPER,
                                    null,
                                    extras,
                                )
                            }
                            .onFailure { Log.w(TAG, "Wallpaper provider is unavailable", it) }
                            .getOrNull()
                            ?.getBoolean("success", false) == true
                    if (changed) post { if (isAttachedToWindow) loadLockWallpaper() }
                },
                "SosKeyguardWallpaper",
            )
            .start()
    }

    private fun loadWeather() {
        Thread(
                {
                    val result =
                        runCatching {
                                context.contentResolver.call(
                                    WEATHER_CURRENT_URI,
                                    METHOD_GET_CURRENT_WEATHER,
                                    null,
                                    null,
                                )
                            }
                            .getOrNull()
                    post { if (isAttachedToWindow) bindWeather(result) }
                },
                "SosKeyguardWeather",
            )
            .start()
    }

    private fun bindWeather(result: Bundle?) {
        if (result == null || result.getBoolean("setupRequired", false)) {
            weatherTemperatureView.text = "--"
            weatherUnitView.text = "°"
            weatherSummaryView.setText(R.string.sos_delta_weather_no_data)
            weatherSummaryView.visibility = View.GONE
            originalBinding.weatherNoData.visibility = View.VISIBLE
            weatherAqiView.visibility = View.GONE
            return
        }
        val temperature =
            when (val value = result.get("temperature")) {
                is Number -> value.toDouble().roundToInt().toString()
                is String -> value.substringBefore('.').ifBlank { "--" }
                else -> "--"
            }
        val unit = result.getString("unit", "C")
        val city = result.getString("city").orEmpty()
        val condition = result.getString("condition").orEmpty()
        weatherTemperatureView.text = temperature
        weatherUnitView.text = if (unit.startsWith("°")) unit else "°$unit"
        weatherSummaryView.text = listOf(city, condition).filter { it.isNotBlank() }.joinToString(" · ")
        weatherSummaryView.visibility = View.VISIBLE
        originalBinding.weatherNoData.visibility = View.GONE
        val aqi = result.get("aqi")?.toString().orEmpty()
        weatherAqiView.text = context.getString(R.string.sos_delta_aqi_format, aqi)
        weatherAqiView.visibility = if (aqi.isBlank() || aqi == "null") View.GONE else View.VISIBLE
    }

    private fun bindMedia(metadata: MediaMetadata?, state: Int) {
        val title =
            metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist =
            metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val artwork =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val hasSession =
            metadata != null &&
                state != PlaybackState.STATE_NONE &&
                state != PlaybackState.STATE_STOPPED &&
                state != PlaybackState.STATE_ERROR
        musicTitleView.text = title?.takeIf { it.isNotBlank() } ?: ""
        musicArtistView.text = artist?.toString().orEmpty()
        if (artwork != null) {
            musicArtworkView.setImageBitmap(artwork)
        } else {
            musicArtworkView.setImageResource(R.drawable.playing_cover_lp_built_in)
        }
        musicArtworkView.visibility = View.VISIBLE
        bindMusicAppIcon()
        val playing = NotificationMediaManager.isPlayingState(state)
        musicPlayPauseView.setImageResource(
            if (playing) R.drawable.music_pause_in_album else R.drawable.music_play_in_album
        )
        musicExpandedPlayPauseView.setBackgroundResource(
            if (playing) R.drawable.btn_playing_pause_selector else R.drawable.btn_playing_play_selector
        )
        musicPlayPauseView.contentDescription =
            context.getString(if (playing) R.string.description_pause else R.string.description_play)
        musicExpandedPlayPauseView.contentDescription = musicPlayPauseView.contentDescription
        musicPreviousView.isEnabled = hasSession
        musicPlayPauseView.isEnabled = hasSession
        musicExpandedPlayPauseView.isEnabled = hasSession
        musicNextView.isEnabled = hasSession
        musicPreviousView.alpha = 1f
        musicPlayPauseView.alpha = 1f
        musicExpandedPlayPauseView.alpha = 1f
        musicNextView.alpha = 1f
    }

    private fun bindMusicAppIcon() {
        val icon =
            runCatching { context.packageManager.getApplicationIcon(SMARTISAN_MUSIC_PACKAGE) }
                .getOrNull()
        musicAppIconView.setImageDrawable(icon)
        musicAppIconView.visibility = if (icon == null) View.GONE else View.VISIBLE
    }

    private fun bindRecorderState(state: SosKeyguardRecorderController.State) {
        recorderState = state
        if (state == SosKeyguardRecorderController.State.RECORDING && musicExpanded) {
            setMusicExpanded(false)
        }
        val active =
            state == SosKeyguardRecorderController.State.RECORDING ||
                state == SosKeyguardRecorderController.State.PAUSED
        recorderStartStopView.isEnabled = state != SosKeyguardRecorderController.State.UNAVAILABLE
        recorderPauseView.isEnabled = active
        recorderMarkView.isEnabled = active
        recorderStartStopView.alpha = if (recorderStartStopView.isEnabled) 1f else 0.35f
        recorderPauseView.alpha = if (active) 1f else 0.35f
        recorderMarkView.alpha = if (active) 1f else 0.35f
        recorderPauseView.setImageResource(
            if (state == SosKeyguardRecorderController.State.PAUSED) {
                R.drawable.recording_resume_static
            } else {
                R.drawable.recording_pause_static
            }
        )
        recorderStartStopView.setImageResource(
            if (active) R.drawable.recording_stop_static else R.drawable.recording_start_static
        )
        recorderStartStopView.contentDescription =
            context.getString(if (active) R.string.sos_delta_recorder_stop else R.string.sos_delta_recorder_start)
        recorderPauseView.contentDescription =
            context.getString(
                if (state == SosKeyguardRecorderController.State.PAUSED) {
                    R.string.sos_delta_recorder_resume
                } else {
                    R.string.sos_delta_recorder_pause
                }
            )
        recorderMarkView.contentDescription = context.getString(R.string.description_mark)
        if (state == SosKeyguardRecorderController.State.READY) {
            recorderClockView.reset()
            recorderWaveView.reset()
        }
    }

    private fun updateBattery() {
        batteryView.setBatteryState(batteryLevel, batteryCharging || batteryPluggedIn)
    }

    private fun updateFlashlight() {
        torchButton.isEnabled = flashlightAvailable
        torchButton.alpha = if (flashlightAvailable) 1f else 0.4f
        torchButton.setImageResource(
            if (flashlightEnabled) {
                R.drawable.flashlight_on_static
            } else {
                R.drawable.flashlight_off_static
            }
        )
        torchButton.isActivated = flashlightEnabled
    }

    private fun updateDozing(isDozing: Boolean) {
        // This panel legitimately keeps ScreenLifecycle at SCREEN_ON while AOD is active.  Only an
        // interactive wake session invalidates a late doze=true callback; physical display state
        // is not an AOD authority.
        val effectiveDozing =
            isDozing && !awakeLockscreenSession && !keyguardUpdateMonitor.isDeviceInteractive
        if (isDozing && !effectiveDozing) {
            Log.d(
                TAG,
                "Ignoring stale doze=true during interactive wake; " +
                    "screenState=${screenLifecycle.screenState}",
            )
        }
        aodHandler.removeCallbacks(aodTicker)
        aodLayer.animate().cancel()
        if (effectiveDozing) {
            cancelQuickLaunchState(returnToMain = false)
            stopMainBreathing(resetScale = true)
            hideChargingAnimation()
            pinnedTaskController.closePreview()
            resetOriginalVerticalGesture(hideBouncer = true)
            resetToMainPage()
        }
        pageContainer.visibility = if (effectiveDozing) View.INVISIBLE else View.VISIBLE
        if (effectiveDozing) {
            aodLayer.alpha = 0f
            aodLayer.visibility = View.VISIBLE
            aodLastMoveTime = System.currentTimeMillis()
            updateAod()
            aodLayer
                .animate()
                .alpha(1f)
                .setStartDelay(AOD_FADE_IN_DELAY_MS)
                .setDuration(AOD_FADE_IN_DURATION_MS)
                .start()
            aodHandler.postDelayed(aodTicker, AOD_UPDATE_INTERVAL_MS)
        } else {
            aodLayer.alpha = 0f
            aodLayer.visibility = View.GONE
            startMainBreathing()
        }
    }

    private fun resetToMainPage() {
        pageAnimator?.cancel()
        pageAnimator = null
        currentPage = PAGE_MAIN
        releaseWidgetPress()
        pagePosition = PAGE_MAIN.toFloat()
        updatePageTranslations(pagePosition)
        pureWallpaper = false
        stopMainBreathing(resetScale = true)
        mainChrome.animate().cancel()
        mainChrome.alpha = 1f
        mainChrome.visibility = View.VISIBLE
        widgetShortcutView.visibility = View.VISIBLE
        cameraShortcutView.visibility = View.VISIBLE
        settlePageVisibility()
    }

    private fun updateAod() {
        if (aodLayer.visibility != View.VISIBLE) return
        val now = Date()
        val is24Hour = DateFormat.is24HourFormat(context)
        aodTimeView.text = DateFormat.format(if (is24Hour) "HH\nmm" else "hh\nmm", now)
        val period = if (is24Hour) "" else DateFormat.format("a  ", now).toString()
        aodSecondaryView.text = "$period${DateFormat.format("E", now)}"
        val batteryDrawable = aodBatteryView.drawable
        batteryDrawable?.level =
            if (batteryLevel >= 100 && (batteryCharging || batteryPluggedIn)) {
                11
            } else {
                (batteryLevel / 10).coerceIn(1, 10)
            }
        aodChargeView.visibility =
            if (batteryPluggedIn && batteryLevel < 100) View.VISIBLE else View.GONE
        aodSecondContainer.visibility = if (batteryLevel <= 10) View.INVISIBLE else View.VISIBLE

        val calendar = java.util.Calendar.getInstance()
        val quietHours = calendar.get(java.util.Calendar.HOUR_OF_DAY) in 0..5
        val contentVisible = !quietHours && batteryLevel > AOD_MINIMUM_BATTERY_LEVEL
        aodLayer.requireViewById<View>(R.id.sos_delta_aod_content).visibility =
            if (!contentVisible) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
        aodFingerprintView.visibility =
            if (
                contentVisible &&
                    authController.isUdfpsSupported &&
                    authController.isUdfpsEnrolled(ActivityManager.getCurrentUser())
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val nowMillis = now.time
        if (!quietHours && nowMillis - aodLastMoveTime >= AOD_MOVE_INTERVAL_MS) {
            aodLastMoveTime = nowMillis
            aodAtTop = !aodAtTop
            context
                .getSharedPreferences(AOD_POSITION_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AOD_POSITION_TOP, aodAtTop)
                .apply()
            applyAodPosition(animate = true)
        }
    }

    private fun applyAodPosition(animate: Boolean) {
        val metrics = layoutMetrics ?: return
        val content = aodLayer.requireViewById<View>(R.id.sos_delta_aod_content)
        val target = if (aodAtTop) 0f else metrics.aodBottomTranslationY
        if (animate) {
            content.animate().translationY(target).setDuration(AOD_MOVE_DURATION_MS).start()
        } else {
            content.animate().cancel()
            content.translationY = target
        }
    }

    private fun showChargingAnimation() {
        if (statusBarStateController.isDozing || chargingOverlay.visibility == View.VISIBLE) return
        chargingOverlay.removeCallbacks(hideChargingRunnable)
        chargingOverlay.visibility = View.VISIBLE
        val style =
            Settings.Global.getInt(
                context.contentResolver,
                SETTING_CHARGING_ANIMATION,
                CHARGING_STYLE_VIDEO,
            )
        if (style == CHARGING_STYLE_FRAMES) {
            chargingVideoView.visibility = View.GONE
            chargingFrameView.visibility = View.VISIBLE
            chargingFrameView.setBackgroundResource(R.drawable.animation_frame_charging)
            (chargingFrameView.background as? AnimationDrawable)?.start()
            chargingOverlay.postDelayed(hideChargingRunnable, CHARGING_FRAMES_DURATION_MS)
        } else {
            chargingFrameView.visibility = View.GONE
            chargingVideoView.visibility = View.VISIBLE
            chargingVideoView.setVideoURI(
                Uri.parse("android.resource://${context.packageName}/${R.raw.charge_anim_1}")
            )
            chargingVideoView.setOnCompletionListener { hideChargingAnimation() }
            chargingVideoView.setOnErrorListener { _, _, _ ->
                hideChargingAnimation()
                true
            }
            chargingVideoView.start()
        }
    }

    private fun hideChargingAnimation() {
        chargingOverlay.removeCallbacks(hideChargingRunnable)
        (chargingFrameView.background as? AnimationDrawable)?.stop()
        chargingFrameView.background = null
        chargingVideoView.stopPlayback()
        chargingOverlay.visibility = View.GONE
    }

    companion object {
        internal fun isWallpaperFrameReady(
            state: WallpaperLoadState,
            stateGeneration: Int,
            currentGeneration: Int,
            usingOpaqueFallback: Boolean,
            hasBitmap: Boolean,
            installedGeneration: Int,
        ): Boolean {
            if (stateGeneration != currentGeneration) return false
            return when (state) {
                WallpaperLoadState.READY ->
                    !usingOpaqueFallback &&
                        hasBitmap &&
                        installedGeneration == currentGeneration
                WallpaperLoadState.FAILED -> true
                WallpaperLoadState.IDLE,
                WallpaperLoadState.PENDING -> false
            }
        }

        internal fun isQuickActionSessionCurrent(
            expectedGeneration: Int,
            currentGeneration: Int,
            expectedUserId: Int,
            currentUserId: Int,
            attached: Boolean,
        ): Boolean =
            attached &&
                expectedGeneration == currentGeneration &&
                expectedUserId == currentUserId

        internal fun wallpaperRetryDelayForAttempt(attempt: Int): Long =
            if (attempt in WALLPAPER_RETRY_DELAYS_MS.indices) {
                WALLPAPER_RETRY_DELAYS_MS[attempt]
            } else {
                WALLPAPER_SLOW_RETRY_DELAY_MS
            }

        internal fun widgetSecurePagePosition(
            offset: Float,
            screenHeight: Float,
            originPosition: Float = 0f,
        ): Float {
            if (screenHeight <= 0f) return originPosition
            val progress = (offset.coerceAtLeast(0f) * 3f / screenHeight).coerceIn(0f, 1f)
            return originPosition + (1f - originPosition) * progress
        }

        internal fun combinedBlurProgress(
            pageProgress: Float,
            bouncerProgress: Float,
            forceWidgetSecurityBlur: Boolean,
        ): Float =
            if (forceWidgetSecurityBlur) 1f
            else max(pageProgress, bouncerProgress).coerceIn(0f, 1f)

        private const val TAG = "SosKeyguardHostView"
        private const val LOCKSCREEN_BACKGROUND = "lockscreen_background"
        private const val SETTING_CHARGING_ANIMATION = "keyguard_charging_anim"
        private const val METHOD_CHANGE_WALLPAPER = "request_change_wallpaper"
        private const val EXTRA_WALLPAPER_DIRECTION = "wallpaper_direction"
        private const val METHOD_GET_CURRENT_WEATHER = "get_current"
        private const val DIRECTION_PREVIOUS = -1
        private const val DIRECTION_NEXT = 1
        private const val PAGE_WIDGET = 0
        private const val PAGE_MAIN = 1
        private const val PAGE_CAMERA = 2
        private const val GESTURE_NONE = 0
        private const val GESTURE_HORIZONTAL = 1
        private const val GESTURE_VERTICAL = 2
        private const val FOUR_FINGER_COUNT = 4
        private const val PAGE_CHANGE_DISTANCE_FRACTION = 0.18f
        private const val PAGE_THRESHOLD_PX = 200f
        private const val CAMERA_PAGE_THRESHOLD_PX = 400f
        private const val MAIN_PAGE_PARALLAX = 0.6f
        private const val MAIN_ALPHA_WINDOW = 0.1f
        private const val WIDGET_PARALLAX_LIMIT = 0.9f
        private const val CAMERA_MIN_SCALE = 0.92f
        private const val MAIN_ZOOM_PERCENT = 5f
        private const val MAIN_ZOOM_DURATION_MS = 1_500L
        private const val MAIN_ZOOM_DELAY_MS = 1_000L
        private const val MAIN_TOUCH_SCALE_DURATION_MS = 300L
        private const val MUSIC_TOUCH_SCALE = 1.03f
        private const val RECORDER_TOUCH_SCALE = 1.03f
        private const val TORCH_TOUCH_SCALE = 1.08f
        private const val WIDGET_TOUCH_SCALE_DURATION_MS = 300L
        private const val MAX_PAGE_SETTLE_DURATION_MS = 200
        private const val ORIGINAL_REBOUND_VELOCITY_FACTOR = 0.8f
        private const val ORIGINAL_DISTANCE_DURATION_FACTOR = 79.77f
        private const val ORIGINAL_SHORTCUT_TRAVEL_PX = 150f
        private const val ORIGINAL_PIN_TRAVEL_PX = 90f
        private const val ORIGINAL_UNLOCK_OVERSHOOT_PX = 200f
        private const val ORIGINAL_FLING_VELOCITY_PX_PER_SECOND = 700f
        private const val ORIGINAL_NO_SECURITY_UNLOCK_DURATION_MS = 300L
        private const val ORIGINAL_CREDENTIAL_DISMISS_DURATION_MS = 300L
        private const val ORIGINAL_LAUNCHER_NOTIFY_DELAY_MS = 150L
        private const val ACTION_KEYGUARD_TO_DISMISS = "action_keyguard_to_dismiss"
        private const val LAUNCHER_PACKAGE = "com.android.launcher3"
        private const val LAUNCHER_UNLOCK_PERMISSION = "com.android.systemui.permission.SELF"
        private const val EXTRA_UNLOCK_PHASE = "com.android.systemui.extra.R2_UNLOCK_PHASE"
        private const val EXTRA_UNLOCK_GENERATION = "com.android.systemui.extra.R2_UNLOCK_GENERATION"
        private const val EXTRA_UNLOCK_USER = "com.android.systemui.extra.R2_UNLOCK_USER"
        private const val LAUNCHER_PHASE_PREPARE = "PREPARE"
        private const val LAUNCHER_PHASE_COMMIT = "COMMIT"
        private const val LAUNCHER_PHASE_CANCEL = "CANCEL"
        private const val CAMERA_RESET_DELAY_MS = 700L
        private const val QUICK_SELECTOR_DELAY_MS = 200L
        private const val QUICK_EDITOR_DELAY_MS = 800L
        private const val PURE_WALLPAPER_FADE_MS = 180L
        private const val MUSIC_EXPAND_DURATION_MS = 400L
        private const val MUSIC_EXPAND_FAST_DURATION_MS = 200L
        private const val MUSIC_CONTRACT_DURATION_MS = 300L
        private const val MUSIC_ALBUM_SIZE_PX = 888f
        private const val MUSIC_PANEL_HEIGHT_PX = 234f
        private const val RECORDER_EXTENDED_HEIGHT_PX = 384f
        private const val RECORDER_CONTRACTED_HEIGHT_PX = 234f
        private const val AOD_UPDATE_INTERVAL_MS = 60_000L
        private const val AOD_MOVE_INTERVAL_MS = 30 * 60_000L
        private const val AOD_MOVE_DURATION_MS = 300L
        private const val AOD_FADE_IN_DELAY_MS = 500L
        private const val AOD_FADE_IN_DURATION_MS = 300L
        private const val AOD_MINIMUM_BATTERY_LEVEL = 10
        private const val AOD_POSITION_PREFERENCES = "sos_r2_doze_position"
        private const val AOD_POSITION_TOP = "is_doze_at_the_top"
        private const val STATUS_BAR_SAMPLE_FRACTION = 0.08f
        private const val STATUS_BAR_SAMPLE_COLUMNS = 24
        private const val STATUS_BAR_SAMPLE_ROWS = 6
        private const val MIN_WALLPAPER_SAMPLE_ALPHA = 128
        private const val LIGHT_WALLPAPER_LUMINANCE = 0.68
        private const val MAX_FIRST_FRAME_PREDRAWS = 3
        private val WALLPAPER_RETRY_DELAYS_MS = longArrayOf(0L, 150L, 500L, 1_500L, 3_000L)
        private const val WALLPAPER_SLOW_RETRY_DELAY_MS = 2_000L
        private const val SMARTISAN_MUSIC_PACKAGE = "com.smartisanos.music"
        private const val CHARGING_STYLE_VIDEO = 0
        private const val CHARGING_STYLE_FRAMES = 1
        private const val CHARGING_FRAMES_DURATION_MS = 2_500L
        private val WALLPAPER_PROVIDER_URI =
            Uri.parse("content://com.smartisanos.wallpaperprovider/wallpapers")
        private val WEATHER_CURRENT_URI =
            Uri.parse("content://app.smartisanweather.revived.lockscreen/current")
    }
}
