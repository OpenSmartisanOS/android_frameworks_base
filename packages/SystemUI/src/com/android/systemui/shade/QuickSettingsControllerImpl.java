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


package com.android.systemui.shade;

import static android.view.WindowInsets.Type.ime;

import static com.android.systemui.Flags.shadeQsvisibleLogic;
import static com.android.systemui.shade.NotificationPanelViewController.COUNTER_PANEL_OPEN_QS;
import static com.android.systemui.shade.NotificationPanelViewController.FLING_COLLAPSE;
import static com.android.systemui.shade.NotificationPanelViewController.FLING_EXPAND;
import static com.android.systemui.shade.NotificationPanelViewController.FLING_HIDE;
import static com.android.systemui.shade.NotificationPanelViewController.QS_PARALLAX_AMOUNT;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.util.DumpUtilsKt.asIndenting;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Fragment;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.Cuj;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.communal.ui.viewmodel.CommunalTransitionViewModel;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.screenrecord.ScreenRecordUxController;
import com.android.systemui.shade.data.repository.ShadeRepository;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeTouchableRegionManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.utils.windowmanager.WindowManagerProvider;

import lineageos.providers.LineageSettings;

import dalvik.annotation.optimization.NeverCompile;

import dagger.Lazy;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Provider;

/** Handles QuickSettings touch handling, expansion and animation state. */
@SysUISingleton
public class QuickSettingsControllerImpl implements QuickSettingsController, Dumpable {
    public static final String TAG = "QuickSettingsController";

    public static final int SHADE_BACK_ANIM_SCALE_MULTIPLIER = 100;

    private QS mQs;
    private final Lazy<NotificationPanelViewController> mPanelViewControllerLazy;

    private final NotificationPanelView mPanelView;
    private final Lazy<LargeScreenHeaderHelper> mLargeScreenHeaderHelperLazy;
    private final KeyguardStatusBarView mKeyguardStatusBar;
    private final FrameLayout mQsFrame;

    private final QsFrameTranslateController mQsFrameTranslateController;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final LightBarController mLightBarController;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final NotificationShadeDepthController mDepthController;
    private final ShadeHeaderController mShadeHeaderController;
    private final ShadeTouchableRegionManager mShadeTouchableRegionManager;
    private final Provider<StatusBarLongPressGestureDetector> mStatusBarLongPressGestureDetector;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final ScrimController mScrimController;
    private final MediaDataManager mMediaDataManager;
    private final MediaHierarchyManager mMediaHierarchyManager;
    private final AmbientState mAmbientState;
    private final ScreenRecordUxController mRecordingController;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final ShadeLogger mShadeLog;
    private final DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    private final CastController mCastController;
    private final SplitShadeStateController mSplitShadeStateController;
    private final Lazy<InteractionJankMonitor> mInteractionJankMonitorLazy;
    private final ShadeRepository mShadeRepository;
    private final ShadeInteractor mShadeInteractor;
    private final ActiveNotificationsInteractor mActiveNotificationsInteractor;
    private final Lazy<CommunalTransitionViewModel> mCommunalTransitionViewModelLazy;
    private final JavaAdapter mJavaAdapter;
    private final FalsingManager mFalsingManager;
    private final AccessibilityManager mAccessibilityManager;
    private final MetricsLogger mMetricsLogger;
    private final Resources mResources;

    /** Whether the notifications are displayed full width (no margins on the side). */
    private boolean mIsFullWidth;
    private int mTouchSlop;
    private float mSlopMultiplier;
    /** the current {@link StatusBarState} */
    private int mBarState;
    private int mStatusBarMinHeight;
    private boolean mScrimEnabled = true;
    private int mScrimCornerRadius;
    private int mScreenCornerRadius;
    private boolean mUseLargeScreenShadeHeader;
    private int mLargeScreenShadeHeaderHeight;
    private int mDisplayRightInset = 0; // in pixels
    private int mDisplayLeftInset = 0; // in pixels
    private boolean mSplitShadeEnabled;
    private boolean mQuickSettingsPage;
    /**
     * The padding between the start of notifications and the qs boundary on the lockscreen.
     * On lockscreen, notifications aren't inset this extra amount, but we still want the
     * qs boundary to be padded.
     */
    private int mLockscreenNotificationPadding;
    private int mSplitShadeNotificationsScrimMarginBottom;
    private boolean mDozing;
    private boolean mEnableClipping;
    private int mFalsingThreshold;
    /**
     * Position of the qs bottom during the full shade transition. This is needed as the toppadding
     * can change during state changes, which makes it much harder to do animations
     */
    private int mTransitionToFullShadePosition;
    private boolean mCollapsedOnDown;
    private float mShadeExpandedHeight = 0;
    private boolean mLastShadeFlingWasExpanding;

    private float mInitialHeightOnTouch;
    private float mInitialTouchX;
    private float mInitialTouchY;
    /** whether current touch Y delta is above falsing threshold */
    private boolean mTouchAboveFalsingThreshold;
    /** pointerId of the pointer we're currently tracking */
    private int mTrackingPointer;

    /** Indicates QS is at its max height */
    private boolean mFullyExpanded;
    private boolean mExpandedWhenExpandingStarted;
    private boolean mAnimatingHiddenFromCollapsed;
    private boolean mVisible;
    private float mExpansionHeight;
    /**
     * QS height when QS expansion fraction is 0 so when QS is collapsed. That state doesn't really
     * exist for split shade so currently this value is always 0 then.
     */
    private int mMinExpansionHeight;
    /** QS height when QS expansion fraction is 1 so qs is fully expanded */
    private int mMaxExpansionHeight;
    /** Expansion fraction of the notification shade */
    private float mShadeExpandedFraction;
    private float mLastOverscroll;
    private boolean mExpansionFromOverscroll;
    private boolean mExpansionEnabledPolicy = true;
    private boolean mExpansionEnabledAmbient = true;
    private float mQuickQsHeaderHeight;
    private boolean mLastMediaVisibility = false;
    /**
     * Determines if QS should be already expanded when expanding shade.
     * Used for split shade, two finger gesture as well as accessibility shortcut to QS.
     * It needs to be set when movement starts as it resets at the end of expansion/collapse.
     */
    private boolean mTwoFingerExpandPossible;
    /** Whether the ongoing gesture might both trigger the expansion in both the view and QS. */
    private boolean mConflictingExpansionGesture;
    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private boolean mAnimatorExpand;

    /**
     * The gesture inset currently in effect -- used to decide whether a back gesture should
     * receive a horizontal swipe inwards from the left/right vertical edge of the screen.
     * We cache this on ACTION_DOWN, and query it during both ACTION_DOWN and ACTION_MOVE events.
     */
    private Insets mCachedGestureInsets;

    /**
     * The window width currently in effect -- used together with
     * {@link QuickSettingsControllerImpl#mCachedGestureInsets} to decide whether a back gesture should
     * receive a horizontal swipe inwards from the left/right vertical edge of the screen.
     * We cache this on ACTION_DOWN, and query it during both ACTION_DOWN and ACTION_MOVE events.
     */
    private int mCachedWindowWidth;

    /**
     * The amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade. This value can also go beyond 1.1 when we're overshooting!
     */
    private float mTransitioningToFullShadeProgress;
    /** Distance a full shade transition takes in order for qs to fully transition to the shade. */
    private int mDistanceForFullShadeTransition;
    private boolean mStackScrollerOverscrolling;
    /** Indicates QS is animating - set by flingQs */
    private boolean mAnimating;
    /** Whether the current animator is resetting the qs translation. */
    private boolean mIsTranslationResettingAnimator;
    /** Whether the current animator is resetting the pulse expansion after a drag down. */
    private boolean mIsPulseExpansionResettingAnimator;
    /** The translation amount for QS for the full shade transition. */
    private float mTranslationForFullShadeTransition;
    /** Should we animate the next bounds update. */
    private boolean mAnimateNextNotificationBounds;
    /** The delay for the next bounds animation. */
    private long mNotificationBoundsAnimationDelay;
    /** The duration of the notification bounds animation. */
    private long mNotificationBoundsAnimationDuration;

    private int mOneFingerQuickSettingsIntercept;
    private final ContentObserver mOneFingerQuickSettingsInterceptObserver;

    private final Region mInterceptRegion = new Region();
    private final Rect mQsHeaderBounds = new Rect();
    /** The end bounds of a clipping animation. */
    private final Rect mClippingAnimationEndBounds = new Rect();
    private final Rect mLastClipBounds = new Rect();

    /** The animator for the qs clipping bounds. */
    private ValueAnimator mClippingAnimator = null;
    /** The main animator for QS expansion */
    private ValueAnimator mExpansionAnimator;
    /** The animator for QS size change */
    private ValueAnimator mSizeChangeAnimator;

    private ExpansionHeightListener mExpansionHeightListener;
    private ApplyClippingImmediatelyListener mApplyClippingImmediatelyListener;
    private FlingQsWithoutClickListener mFlingQsWithoutClickListener;
    private ExpansionHeightSetToMaxListener mExpansionHeightSetToMaxListener;
    private final QS.HeightListener mQsHeightListener = this::onHeightChanged;
    private final QS.QqsHeightListener mQqsHeightListener = new QS.QqsHeightListener() {
        @Override
        public void onQqsHeightChanged() {
            updateHeightsOnShadeLayoutChange();
            mPanelViewControllerLazy.get().positionClockAndNotifications();
        }
    };
    private final Runnable mQsCollapseExpandAction = this::collapseOrExpandQs;
    private final QS.ScrollListener mQsScrollListener = this::onScroll;

    private final WindowManagerProvider mWindowManagerProvider;

    @Inject
    public QuickSettingsControllerImpl(
            Lazy<NotificationPanelViewController> panelViewControllerLazy,
            NotificationPanelView panelView,
            QsFrameTranslateController qsFrameTranslateController,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationRemoteInputManager remoteInputManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            LightBarController lightBarController,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            NotificationShadeDepthController notificationShadeDepthController,
            ShadeHeaderController shadeHeaderController,
            ShadeTouchableRegionManager shadeTouchableRegionManager,
            Provider<StatusBarLongPressGestureDetector> statusBarLongPressGestureDetector,
            KeyguardStateController keyguardStateController,
            KeyguardBypassController keyguardBypassController,
            ScrimController scrimController,
            MediaDataManager mediaDataManager,
            MediaHierarchyManager mediaHierarchyManager,
            AmbientState ambientState,
            ScreenRecordUxController recordingController,
            FalsingManager falsingManager,
            AccessibilityManager accessibilityManager,
            LockscreenGestureLogger lockscreenGestureLogger,
            MetricsLogger metricsLogger,
            Lazy<InteractionJankMonitor> interactionJankMonitorLazy,
            ShadeLogger shadeLog,
            DumpManager dumpManager,
            DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor,
            ShadeRepository shadeRepository,
            ShadeInteractor shadeInteractor,
            ActiveNotificationsInteractor activeNotificationsInteractor,
            JavaAdapter javaAdapter,
            CastController castController,
            SplitShadeStateController splitShadeStateController,
            Lazy<CommunalTransitionViewModel> communalTransitionViewModelLazy,
            Lazy<LargeScreenHeaderHelper> largeScreenHeaderHelperLazy,
            WindowManagerProvider windowManagerProvider,
            SelectedUserInteractor selectedUserInteractor
    ) {
        SceneContainerFlag.assertInLegacyMode();
        mPanelViewControllerLazy = panelViewControllerLazy;
        mPanelView = panelView;
        mLargeScreenHeaderHelperLazy = largeScreenHeaderHelperLazy;
        mQsFrame = mPanelView.findViewById(R.id.qs_frame);
        mKeyguardStatusBar = mPanelView.findViewById(R.id.keyguard_header);
        mResources = mPanelView.getResources();
        mSplitShadeStateController = splitShadeStateController;
        mSplitShadeEnabled = false;
        mQsFrameTranslateController = qsFrameTranslateController;
        mPulseExpansionHandler = pulseExpansionHandler;
        pulseExpansionHandler.setPulseExpandAbortListener(() -> {
            if (mQs != null) {
                mQs.animateHeaderSlidingOut();
            }
        });
        mRemoteInputManager = remoteInputManager;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLightBarController = lightBarController;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mDepthController = notificationShadeDepthController;
        mShadeHeaderController = shadeHeaderController;
        mShadeTouchableRegionManager = shadeTouchableRegionManager;
        mStatusBarLongPressGestureDetector = statusBarLongPressGestureDetector;
        mKeyguardStateController = keyguardStateController;
        mKeyguardBypassController = keyguardBypassController;
        mScrimController = scrimController;
        mMediaDataManager = mediaDataManager;
        mMediaHierarchyManager = mediaHierarchyManager;
        mAmbientState = ambientState;
        mRecordingController = recordingController;
        mFalsingManager = falsingManager;
        mAccessibilityManager = accessibilityManager;

        mLockscreenGestureLogger = lockscreenGestureLogger;
        mMetricsLogger = metricsLogger;
        mShadeLog = shadeLog;
        mDeviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor;
        mCastController = castController;
        mInteractionJankMonitorLazy = interactionJankMonitorLazy;
        mShadeRepository = shadeRepository;
        mShadeInteractor = shadeInteractor;
        mActiveNotificationsInteractor = activeNotificationsInteractor;
        mCommunalTransitionViewModelLazy = communalTransitionViewModelLazy;
        mJavaAdapter = javaAdapter;

        mLockscreenShadeTransitionController.addCallback(new LockscreenShadeTransitionCallback());

        mOneFingerQuickSettingsInterceptObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                mOneFingerQuickSettingsIntercept = LineageSettings.System.getIntForUser(
                        mPanelView.getContext().getContentResolver(),
                        LineageSettings.System.STATUS_BAR_QUICK_QS_PULLDOWN, 0,
                        selectedUserInteractor.getSelectedUserId());
            }
        };

        dumpManager.registerDumpable(this);

        mWindowManagerProvider = windowManagerProvider;
    }

    @VisibleForTesting
    void setQs(QS qs) {
        mQs = qs;
    }

    void setQuickSettingsPage(boolean quickSettingsPage) {
        if (mQuickSettingsPage == quickSettingsPage) {
            return;
        }
        mQuickSettingsPage = quickSettingsPage;
        updateExpansionFromShade();
    }

    private void updateExpansionFromShade() {
        final float expansion = mQuickSettingsPage
                ? Math.max(0f, Math.min(1f, mShadeExpandedFraction))
                : 0f;
        final int minHeight = getMinExpansionHeight();
        final int maxHeight = getMaxExpansionHeight();
        mExpansionHeight = minHeight + expansion * (maxHeight - minHeight);
        mFullyExpanded = mQuickSettingsPage && expansion == 1f && maxHeight != 0;
        setExpanded(mQuickSettingsPage && expansion > 0f);
        updateExpansion();
    }

    void setExpansionHeightListener(ExpansionHeightListener listener) {
        mExpansionHeightListener = listener;
    }

    void setApplyClippingImmediatelyListener(ApplyClippingImmediatelyListener listener) {
        mApplyClippingImmediatelyListener = listener;
    }

    void setFlingQsWithoutClickListener(FlingQsWithoutClickListener listener) {
        mFlingQsWithoutClickListener = listener;
    }

    void setExpansionHeightSetToMaxListener(ExpansionHeightSetToMaxListener callback) {
        mExpansionHeightSetToMaxListener = callback;
    }

    void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(this.mPanelView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mStatusBarMinHeight = SystemBarUtils.getStatusBarHeight(mPanelView.getContext());
        mScrimCornerRadius = mResources.getDimensionPixelSize(
                R.dimen.notification_scrim_corner_radius);
        mScreenCornerRadius = (int) ScreenDecorationsUtils.getWindowCornerRadius(
                mPanelView.getContext());
        mFalsingThreshold = mResources.getDimensionPixelSize(R.dimen.qs_falsing_threshold);
        mLockscreenNotificationPadding = mResources.getDimensionPixelSize(
                R.dimen.notification_side_paddings);
        mDistanceForFullShadeTransition = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance);
    }

    void updateResources() {
        mSplitShadeEnabled = false;
        if (mQs != null) {
            mQs.setInSplitShade(mSplitShadeEnabled);
        }
        mSplitShadeNotificationsScrimMarginBottom =
                mResources.getDimensionPixelSize(
                        R.dimen.split_shade_notifications_scrim_margin_bottom);

        mUseLargeScreenShadeHeader = false;
        mLargeScreenShadeHeaderHeight = 0;
        int topMargin =
                mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_top);
        mShadeHeaderController.setLargeScreenActive(false);
        mAmbientState.setStackTopMargin(topMargin);

        mQuickQsHeaderHeight = mResources.getDimensionPixelSize(R.dimen.shade_header_height);

        mEnableClipping = mResources.getBoolean(R.bool.qs_enable_clipping);
        updateGestureInsetsCache();
    }

    // TODO (b/265054088): move this and others to a CoreStartable
    void init() {
        initNotificationStackScrollLayoutController();
        mJavaAdapter.alwaysCollectFlow(
                mShadeInteractor.isExpandToQsEnabled(), this::setExpansionEnabledPolicy);
        mJavaAdapter.alwaysCollectFlow(
                mCommunalTransitionViewModelLazy.get().isUmoOnCommunal(),
                this::setShouldUpdateSquishinessOnMedia);
    }

    private void initNotificationStackScrollLayoutController() {
        mNotificationStackScrollLayoutController.setOverscrollTopChangedListener(
                new NsslOverscrollTopChangedListener());
        mNotificationStackScrollLayoutController.setOnStackYChanged(this::onStackYChanged);
        mNotificationStackScrollLayoutController.setOnScrollListener(this::onNotificationScrolled);
    }

    private void onStackYChanged(boolean shouldAnimate) {
        if (isQsFragmentCreated()) {
            if (shouldAnimate) {
                setAnimateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_STANDARD,
                        0 /* delay */);
            }
            setClippingBounds();
        }
    }

    private void onNotificationScrolled(int newScrollPosition) {
        updateExpansionEnabledAmbient();
    }

    @VisibleForTesting
    void setStatusBarMinHeight(int height) {
        mStatusBarMinHeight = height;
    }

    int getHeaderHeight() {
        return isQsFragmentCreated() ? mQs.getHeader().getHeight() : 0;
    }

    private boolean isRemoteInputActiveWithKeyboardUp() {
        //TODO(b/227115380) remove the isVisible(ime()) check once isRemoteInputActive is fixed.
        // The check for keyboard visibility is a temporary workaround that allows QS to expand
        // even when isRemoteInputActive is mistakenly returning true.
        return mRemoteInputManager.isRemoteInputActive()
                && mPanelView.getRootWindowInsets().isVisible(ime());
    }

    boolean isExpansionEnabled() {
        return mExpansionEnabledPolicy && mExpansionEnabledAmbient
            && !isRemoteInputActiveWithKeyboardUp();
    }

    /** */
    @VisibleForTesting
    boolean isExpandImmediate() {
        return mShadeRepository.getLegacyExpandImmediate().getValue();
    }

    float getInitialTouchY() {
        return mInitialTouchY;
    }

    /** Returns whether split shade is enabled and an x coordinate is outside of the QS frame. */
    private boolean isSplitShadeAndTouchXOutsideQs(float touchX) {
        return mSplitShadeEnabled
                && (touchX < mQsFrame.getX() || touchX > mQsFrame.getX() + mQsFrame.getWidth());
    }

    /**
     *  Computes (and caches) the gesture insets for the current window. Intended to be called
     *  on ACTION_DOWN, and safely queried repeatedly thereafter during ACTION_MOVE events.
     */
    void updateGestureInsetsCache() {
        WindowManager wm = mWindowManagerProvider.getWindowManager(this.mPanelView.getContext());
        WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
        mCachedGestureInsets = windowMetrics.getWindowInsets().getInsets(
                WindowInsets.Type.systemGestures());
        mCachedWindowWidth = windowMetrics.getBounds().width();
    }

    /**
     *  Returns whether x coordinate lies in the vertical edges of the screen
     *  (the only place where a back gesture can be initiated).
     */
    boolean shouldBackBypassQuickSettings(float touchX) {
        return (touchX < mCachedGestureInsets.left)
                || (touchX > mCachedWindowWidth - mCachedGestureInsets.right);
    }

    /** Returns whether touch is within QS area */
    private boolean isTouchInQsArea(float x, float y) {
        if (isSplitShadeAndTouchXOutsideQs(x)) {
            return false;
        }
        // TODO (b/265193930): remove dependency on NPVC
        // Let's reject anything at the very bottom around the home handle in gesture nav
        if (mPanelViewControllerLazy.get().isInGestureNavHomeHandleArea(y)) {
            return false;
        }
        return y <= mNotificationStackScrollLayoutController.getBottomMostNotificationBottom()
                || y <= mQs.getView().getY() + mQs.getView().getHeight();
    }

    /** Returns whether or not event should open QS */
    @VisibleForTesting
    boolean isOpenQsEvent(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        final int action = event.getActionMasked();

        final boolean
                twoFingerDrag =
                action == MotionEvent.ACTION_POINTER_DOWN && pointerCount == 2;

        final boolean
                stylusButtonClickDrag =
                action == MotionEvent.ACTION_DOWN && (event.isButtonPressed(
                        MotionEvent.BUTTON_STYLUS_PRIMARY) || event.isButtonPressed(
                        MotionEvent.BUTTON_STYLUS_SECONDARY));

        final boolean
                mouseButtonClickDrag =
                action == MotionEvent.ACTION_DOWN && (event.isButtonPressed(
                        MotionEvent.BUTTON_SECONDARY) || event.isButtonPressed(
                        MotionEvent.BUTTON_TERTIARY));

        final float w = mQs.getView().getMeasuredWidth();
        final float x = event.getX();
        float region = w * 1.f / 4.f; // TODO overlay region fraction?
        boolean showQsOverride = false;

        switch (mOneFingerQuickSettingsIntercept) {
            case 1: // Right side pulldown
                showQsOverride = mQs.getView().isLayoutRtl() ? x < region : w - region < x;
                break;
            case 2: // Left side pulldown
                showQsOverride = mQs.getView().isLayoutRtl() ? w - region < x : x < region;
                break;
        }
        showQsOverride &= mBarState == StatusBarState.SHADE;

        return twoFingerDrag || showQsOverride || stylusButtonClickDrag || mouseButtonClickDrag;
    }

    @Override
    public boolean getExpanded() {
        return mShadeRepository.getLegacyIsQsExpanded().getValue();
    }

    @Override
    public void setPanelExpanded(boolean panelExpanded) {
        if (mQs != null) {
            mQs.setPanelExpanded(panelExpanded);
        }
    }

    @VisibleForTesting
    boolean isTracking() {
        return mShadeRepository.getLegacyQsTracking().getValue();
    }

    boolean getFullyExpanded() {
        return mFullyExpanded;
    }

    boolean isGoingBetweenClosedShadeAndExpandedQs() {
        // Below is true when QS are expanded and we swipe up from the same bottom of panel to
        // close the whole shade with one motion. Also this will be always true when closing
        // split shade as there QS are always expanded so every collapsing motion is motion from
        // expanded QS to closed panel
        return isExpandImmediate() || (getExpanded()
                && !isTracking() && !isExpansionAnimating()
                && !mExpansionFromOverscroll);
    }

    private void setTracking(boolean tracking) {
        mShadeRepository.setLegacyQsTracking(tracking);
    }

    private boolean isQsFragmentCreated() {
        return mQs != null;
    }

    @Override
    public boolean isCustomizing() {
        return isQsFragmentCreated() && mQs.isCustomizing();
    }

    float getExpansionHeight() {
        return mExpansionHeight;
    }

    boolean getExpandedWhenExpandingStarted() {
        return mExpandedWhenExpandingStarted;
    }

    int getMinExpansionHeight() {
        return mMinExpansionHeight;
    }

    boolean isFullyExpandedAndTouchesDisallowed() {
        return isQsFragmentCreated() && getFullyExpanded() && disallowTouches();
    }

    int getMaxExpansionHeight() {
        return mMaxExpansionHeight;
    }

    int getFalsingThreshold() {
        return mFalsingThreshold;
    }

    /**
     * Returns Whether we should intercept a gesture to open Quick Settings.
     */
    @Override
    public boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        boolean keyguardShowing = mBarState == KEYGUARD;
        if (!isExpansionEnabled() || mCollapsedOnDown || (keyguardShowing
                && mKeyguardBypassController.getBypassEnabled()) || mSplitShadeEnabled) {
            return false;
        }
        final Rect headerBounds = getQsHeaderBoundsOnPanel();
        mInterceptRegion.set(headerBounds);
        // Also allow QS to intercept if the touch is near the notch.
        mShadeTouchableRegionManager.updateRegionForNotch(mInterceptRegion);
        final boolean onHeader = mInterceptRegion.contains((int) x, (int) y);

        if (getExpanded()) {
            return onHeader || (yDiff < 0 && isTouchInQsArea(x, y));
        } else {
            return onHeader;
        }
    }

    /** Returns amount header should be translated */
    private float getHeaderTranslation() {
        if (mSplitShadeEnabled) {
            // in split shade QS don't translate, just (un)squish and overshoot
            return 0;
        }
        if (mBarState == KEYGUARD && !mKeyguardBypassController.getBypassEnabled()) {
            return -mQs.getQsMinExpansionHeight();
        }
        float appearAmount = mNotificationStackScrollLayoutController
                .calculateAppearFraction(mShadeExpandedHeight);
        float startHeight = -getExpansionHeight();
        if (mBarState == SHADE) {
            // Small parallax as we pull down and clip QS
            startHeight = -getExpansionHeight() * QS_PARALLAX_AMOUNT;
        }
        if (mKeyguardBypassController.getBypassEnabled() && mBarState == KEYGUARD) {
            appearAmount = mNotificationStackScrollLayoutController.calculateAppearFractionBypass();
            startHeight = -mQs.getQsMinExpansionHeight();
        }
        float translation = MathUtils.lerp(startHeight, 0, Math.min(1.0f, appearAmount));
        return Math.min(0, translation);
    }

    /**
     * Can the panel collapse in this motion because it was started on QQS?
     *
     * @param downX the x location where the touch started
     * @param downY the y location where the touch started
     * Returns true if the panel could be collapsed because it stared on QQS
     */
    boolean canPanelCollapseOnQQS(float downX, float downY) {
        if (mCollapsedOnDown || mBarState == KEYGUARD || getExpanded()) {
            return false;
        }
        final Rect headerBounds = getQsHeaderBoundsOnPanel();
        return downX >= headerBounds.left && downX <= headerBounds.right
                && downY <= headerBounds.bottom;
    }

    /** Returns the QS header bounds in {@link NotificationPanelView}'s coordinate space. */
    private Rect getQsHeaderBoundsOnPanel() {
        final int frameLeft = (int) mQsFrame.getX();
        final int frameTop = mQsFrame.getTop();
        int headerTop = frameTop;
        int headerBottom = frameTop + mStatusBarMinHeight;
        if (mQs != null && mQs.getHeader() != null) {
            headerTop = frameTop + mQs.getHeader().getTop();
            headerBottom = frameTop + mQs.getHeader().getBottom();
            if (headerBottom <= headerTop) {
                headerBottom = headerTop + mStatusBarMinHeight;
            }
        }
        mQsHeaderBounds.set(
                frameLeft,
                headerTop,
                frameLeft + mQsFrame.getWidth(),
                headerBottom);
        return mQsHeaderBounds;
    }

    /** Closes the Qs customizer. */
    @Override
    public void closeQsCustomizer() {
        if (mQs != null) {
            mQs.closeCustomizer();
        }
    }

    /** Returns whether touches from the notification panel should be disallowed */
    boolean disallowTouches() {
        if (mQs != null) {
            return mQs.disallowPanelTouches();
        } else {
            return false;
        }
    }

    void setListening(boolean listening) {
        if (mQs != null) {
            mQs.setListening(listening);
        }
    }

    void hideQsImmediately() {
        if (mQs != null) {
            mQs.hideImmediately();
        }
    }

    void setDozing(boolean dozing) {
        mDozing = dozing;
    }

    @Override
    public void closeQs() {
        if (mSplitShadeEnabled) {
            mShadeLog.d("Closing QS while in split shade");
        }
        cancelExpansionAnimation();
        setExpansionHeight(getMinExpansionHeight());
        // qsExpandImmediate is a safety latch in case we're calling closeQS while we're in the
        // middle of animation - we need to make sure that value is always false when shade if
        // fully collapsed or expanded
        setExpandImmediate(false);
    }

    @VisibleForTesting
    void setExpanded(boolean expanded) {
        boolean changed = getExpanded() != expanded;
        if (changed) {
            mShadeRepository.setLegacyIsQsExpanded(expanded);
            updateQsState();
            mPanelViewControllerLazy.get().onQsExpansionChanged();
            mShadeLog.logQsExpansionChanged("QS Expansion Changed.", expanded,
                    getMinExpansionHeight(), getMaxExpansionHeight(),
                    mStackScrollerOverscrolling, mAnimatorExpand, mAnimating);
        }
    }

    void setLastShadeFlingWasExpanding(boolean expanding) {
        mLastShadeFlingWasExpanding = expanding;
        mShadeLog.logLastFlingWasExpanding(expanding);
    }

    /** update Qs height state */
    void setExpansionHeight(float height) {
        updateExpansionFromShade();
    }

    /** */
    void setHeightOverrideToDesiredHeight() {
        if (isSizeChangeAnimationRunning() && isQsFragmentCreated()) {
            mQs.setHeightOverride(mQs.getDesiredHeight());
        }
    }

    /** Updates quick setting heights and returns old max height. */
    int updateHeightsOnShadeLayoutChange() {
        int oldMaxHeight = getMaxExpansionHeight();
        if (isQsFragmentCreated()) {
            updateMinHeight();
            mMaxExpansionHeight = mQs.getDesiredHeight();
            mNotificationStackScrollLayoutController.setMaxTopPadding(
                    getMaxExpansionHeight());
            mMediaHierarchyManager.onQsHeightUpdated();
        }
        return oldMaxHeight;
    }

    /** Called when Shade view layout changed. Updates QS expansion or
     * starts size change animation if height has changed. */
    void handleShadeLayoutChanged(int oldMaxHeight) {
        if (getExpanded() && mFullyExpanded) {
            mExpansionHeight = mMaxExpansionHeight;
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(true);
            }

            // Size has changed, start an animation.
            if (getMaxExpansionHeight() != oldMaxHeight) {
                startSizeChangeAnimation(oldMaxHeight,
                        getMaxExpansionHeight());
            }
        } else if (!getExpanded()
                && !isExpansionAnimating()) {
            setExpansionHeight(getMinExpansionHeight() + mLastOverscroll);
        } else {
            mShadeLog.v("onLayoutChange: qs expansion not set");
        }
    }

    private boolean isSizeChangeAnimationRunning() {
        return mSizeChangeAnimator != null;
    }

    private void startSizeChangeAnimation(int oldHeight, final int newHeight) {
        if (mSizeChangeAnimator != null) {
            oldHeight = (int) mSizeChangeAnimator.getAnimatedValue();
            mSizeChangeAnimator.cancel();
        }
        mSizeChangeAnimator = ValueAnimator.ofInt(oldHeight, newHeight);
        mSizeChangeAnimator.setDuration(300);
        mSizeChangeAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mSizeChangeAnimator.addUpdateListener(animation -> {
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(true);
            }

            int height = (int) mSizeChangeAnimator.getAnimatedValue();
            mQs.setHeightOverride(height);
        });
        mSizeChangeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSizeChangeAnimator = null;
            }
        });
        mSizeChangeAnimator.start();
    }

    void setNotificationPanelFullWidth(boolean isFullWidth) {
        mIsFullWidth = isFullWidth;
        if (mQs != null) {
            mQs.setIsNotificationPanelFullWidth(isFullWidth);
        }
    }

    void setBarState(int barState) {
        mBarState = barState;
    }

    /** */
    private void setExpansionEnabledPolicy(boolean expansionEnabledPolicy) {
        mExpansionEnabledPolicy = expansionEnabledPolicy;
        if (mQs != null) {
            mQs.setHeaderClickable(isExpansionEnabled());
        }
    }

    private void setShouldUpdateSquishinessOnMedia(boolean shouldUpdate) {
        if (mQs != null) {
            mQs.setShouldUpdateSquishinessOnMedia(shouldUpdate);
        }
    }

    void setOverScrollAmount(int overExpansion) {
        if (mQs != null) {
            mQs.setOverScrollAmount(overExpansion);
        }
    }

    private void setOverScrolling(boolean overscrolling) {
        mStackScrollerOverscrolling = overscrolling;
        if (mQs != null) {
            mQs.setOverscrolling(overscrolling);
        }
    }

    /** Sets Qs ScrimEnabled and updates QS state. */
    void setScrimEnabled(boolean scrimEnabled) {
        boolean changed = mScrimEnabled != scrimEnabled;
        mScrimEnabled = scrimEnabled;
        if (changed) {
            updateQsState();
        }
    }

    void setCollapsedOnDown(boolean collapsedOnDown) {
        mCollapsedOnDown = collapsedOnDown;
    }

    void setShadeExpansion(float expandedHeight, float expandedFraction) {
        mShadeExpandedHeight = expandedHeight;
        mShadeExpandedFraction = expandedFraction;
        mMediaHierarchyManager.setShadeExpandedFraction(expandedFraction);
        updateExpansionFromShade();
    }

    @VisibleForTesting
    float getShadeExpandedHeight() {
        return mShadeExpandedHeight;
    }

    void setExpandImmediate(boolean expandImmediate) {
        if (expandImmediate != isExpandImmediate()) {
            mShadeLog.logQsExpandImmediateChanged(expandImmediate);
            mShadeRepository.setLegacyExpandImmediate(expandImmediate);
        }
    }

    void setTwoFingerExpandPossible(boolean expandPossible) {
        mTwoFingerExpandPossible = expandPossible;
    }

    @VisibleForTesting
    boolean isTwoFingerExpandPossible() {
        return mTwoFingerExpandPossible;
    }

    /** Called when Qs starts expanding */
    private void onExpansionStarted() {
        cancelExpansionAnimation();
        // TODO (b/265193930): remove dependency on NPVC
        mPanelViewControllerLazy.get().cancelHeightAnimator();
        // end
        DejankUtils.notifyRendererOfExpensiveFrame(mPanelView, "onExpansionStarted");

        // Reset scroll position and apply that position to the expanded height.
        float height = mExpansionHeight;
        setExpansionHeight(height);
        mNotificationStackScrollLayoutController.checkSnoozeLeavebehind();

        // When expanding QS, let's authenticate the user if possible,
        // this will speed up notification actions.
        if (height == 0 && !mKeyguardStateController.canDismissLockScreen()) {
            mDeviceEntryFaceAuthInteractor.onShadeExpansionStarted();
        }
    }

    private void setQsFullScreen(boolean qsFullScreen) {
        mShadeRepository.setLegacyQsFullscreen(qsFullScreen);
        mNotificationStackScrollLayoutController.setQsFullScreen(qsFullScreen);
        mNotificationStackScrollLayoutController.setScrollingEnabled(
                mBarState != KEYGUARD && (!qsFullScreen || mExpansionFromOverscroll));
    }

    void updateQsState() {
        if (mQs == null) return;
        mQs.setExpanded(getExpanded());
    }

    /** update expanded state of QS */
    void updateExpansion() {
        if (mQs == null) return;
        boolean qsVisible = mQuickSettingsPage && mShadeExpandedFraction > 0f;
        float qsExpansionFraction = qsVisible ? 1f : 0f;
        mQs.setExpanded(qsVisible);
        mQs.setQsExpansion(
                qsExpansionFraction,
                qsVisible ? 1f : 0f,
                0f,
                1f
        );
        // R2 translates the fixed QS page as part of the shade. Do not feed the page selection
        // into Android's independent media, depth, scrim, or notification-squish animations.
        mMediaHierarchyManager.setQsExpansion(0f);
        mScrimController.setQsPosition(0f, 0);
        mNotificationStackScrollLayoutController.setQsExpansionFraction(0f);
        mDepthController.setQsPanelExpansion(0f);
        mStatusBarKeyguardViewManager.setQsExpansion(
                mBarState == KEYGUARD ? qsExpansionFraction : 0f);
        mShadeRepository.setQsExpansion(qsExpansionFraction);
        mShadeHeaderController.setShadeExpandedFraction(mShadeExpandedFraction);
        mShadeHeaderController.setQsExpandedFraction(qsExpansionFraction);
        mShadeHeaderController.setQsVisible(qsVisible);
        mLightBarController.setQsExpanded(mBarState == KEYGUARD && qsVisible);
        setQsFullScreen(false);
    }

    float getLockscreenShadeDragProgress() {
        // mTransitioningToFullShadeProgress > 0 means we're doing regular lockscreen to shade
        // transition. If that's not the case we should follow QS expansion fraction for when
        // user is pulling from the same top to go directly to expanded QS
        return mTransitioningToFullShadeProgress > 0
                ? mLockscreenShadeTransitionController.getQSDragProgress()
                : computeExpansionFraction();
    }

    /** */
    void updateExpansionEnabledAmbient() {
        final float scrollRangeToTop = mAmbientState.getTopPadding() - mQuickQsHeaderHeight;
        mExpansionEnabledAmbient = mSplitShadeEnabled
                || (mAmbientState.getScrollY() <= scrollRangeToTop);
        if (mQs != null) {
            mQs.setHeaderClickable(isExpansionEnabled());
        }
    }

    /** Calculate y value of bottom of QS */
    private int calculateBottomPosition(float qsExpansionFraction) {
        if (mTransitioningToFullShadeProgress > 0.0f) {
            return mTransitionToFullShadePosition;
        } else {
            int qsBottomYFrom = (int) getHeaderTranslation() + mQs.getQsMinExpansionHeight();
            int expandedTopMargin = mUseLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight : 0;
            int qsBottomYTo = mQs.getDesiredHeight() + expandedTopMargin;
            return (int) MathUtils.lerp(qsBottomYFrom, qsBottomYTo, qsExpansionFraction);
        }
    }

    /** Calculate fraction of current QS expansion state */
    float computeExpansionFraction() {
        return mQuickSettingsPage ? 1f : 0f;
    }

    void updateMinHeight() {
        float previousMin = mMinExpansionHeight;
        if (mBarState == KEYGUARD || mSplitShadeEnabled) {
            mMinExpansionHeight = 0;
        } else {
            mMinExpansionHeight = mQs.getQsMinExpansionHeight();
        }
        if (mExpansionHeight == previousMin) {
            mExpansionHeight = mMinExpansionHeight;
        }
    }

    void updateQsFrameTranslation() {
        // TODO (b/265193930): remove dependency on NPVC
        mQsFrameTranslateController.translateQsFrame(mQsFrame, mQs,
                mPanelViewControllerLazy.get().getNavigationBarBottomHeight()
                        + mAmbientState.getStackTopMargin());
    }

    /** Called when shade starts expanding. */
    void onExpandingStarted(boolean qsFullyExpanded) {
        mNotificationStackScrollLayoutController.onExpansionStarted();
        mExpandedWhenExpandingStarted = qsFullyExpanded;
        mMediaHierarchyManager.setCollapsingShadeFromQS(mExpandedWhenExpandingStarted
                /* We also start expanding when flinging closed Qs. Let's exclude that */
                && !mAnimating);
        if (getExpanded()) {
            onExpansionStarted();
        }
        // Since there are QS tiles in the header now, we need to make sure we start listening
        // immediately so they can be up to date.
        if (mQs == null) return;
        mQs.setHeaderListening(true);
    }

    /** Set animate next notification bounds. */
    private void setAnimateNextNotificationBounds(long duration, long delay) {
        mAnimateNextNotificationBounds = true;
        mNotificationBoundsAnimationDuration = duration;
        mNotificationBoundsAnimationDelay = delay;
    }

    /**
     * Updates scrim bounds, QS clipping, notifications clipping and keyguard status view clipping
     * as well based on the bounds of the shade and QS state.
     */
    void setClippingBounds() {
        if (!isQsFragmentCreated()) {
            return;
        }
        mVisible = mQuickSettingsPage && mShadeExpandedFraction > 0f;
        mQs.setQsVisible(mVisible);
        if (mEnableClipping) {
            mQs.setFancyClipping(
                    mDisplayLeftInset,
                    0,
                    mDisplayRightInset,
                    mQsFrame.getHeight(),
                    0,
                    false,
                    mIsFullWidth);
        }
    }

    /**
     * Applies clipping to quick settings, notifications layout and
     * updates bounds of the notifications background (notifications scrim).
     * <p>
     * The parameters are bounds of the notifications area rectangle, this function
     * calculates bounds for the QS clipping based on the notifications bounds.
     */
    private void applyClippingBounds(int left, int top, int right, int bottom,
            boolean qsVisible) {
        if (!mAnimateNextNotificationBounds || mLastClipBounds.isEmpty()) {
            if (mClippingAnimator != null) {
                // update the end position of the animator
                mClippingAnimationEndBounds.set(left, top, right, bottom);
            } else {
                applyClippingImmediately(left, top, right, bottom, qsVisible);
            }
        } else {
            mClippingAnimationEndBounds.set(left, top, right, bottom);
            final int startLeft = mLastClipBounds.left;
            final int startTop = mLastClipBounds.top;
            final int startRight = mLastClipBounds.right;
            final int startBottom = mLastClipBounds.bottom;
            if (mClippingAnimator != null) {
                mClippingAnimator.cancel();
            }
            mClippingAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            mClippingAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            mClippingAnimator.setDuration(mNotificationBoundsAnimationDuration);
            mClippingAnimator.setStartDelay(mNotificationBoundsAnimationDelay);
            mClippingAnimator.addUpdateListener(animation -> {
                float fraction = animation.getAnimatedFraction();
                int animLeft = (int) MathUtils.lerp(startLeft,
                        mClippingAnimationEndBounds.left, fraction);
                int animTop = (int) MathUtils.lerp(startTop,
                        mClippingAnimationEndBounds.top, fraction);
                int animRight = (int) MathUtils.lerp(startRight,
                        mClippingAnimationEndBounds.right, fraction);
                int animBottom = (int) MathUtils.lerp(startBottom,
                        mClippingAnimationEndBounds.bottom, fraction);
                applyClippingImmediately(animLeft, animTop, animRight, animBottom,
                        qsVisible /* qsVisible */);
            });
            mClippingAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mClippingAnimator = null;
                    mIsTranslationResettingAnimator = false;
                    mIsPulseExpansionResettingAnimator = false;
                }
            });
            mClippingAnimator.start();
        }
        mAnimateNextNotificationBounds = false;
        mNotificationBoundsAnimationDelay = 0;
    }

    private void applyClippingImmediately(int left, int top, int right, int bottom,
            boolean qsVisible) {
        int radius = mScrimCornerRadius;
        boolean clipStatusView = false;
        mLastClipBounds.set(left, top, right, bottom);
        if (mIsFullWidth) {
            clipStatusView = qsVisible;
            float screenCornerRadius =
                    mRecordingController.isRecording() || mCastController.hasConnectedCastDevice()
                            ? 0 : mScreenCornerRadius;
            radius = (int) MathUtils.lerp(screenCornerRadius, mScrimCornerRadius,
                    Math.min(top / (float) mScrimCornerRadius, 1f));

            float bottomRadius = mSplitShadeEnabled ? screenCornerRadius : 0;
            if (!getExpanded()) {
                bottomRadius = calculateBottomCornerRadius(bottomRadius);
            }
            mScrimController.setNotificationBottomRadius(bottomRadius);
        }
        if (isQsFragmentCreated()) {
            float qsTranslation = 0;
            boolean pulseExpanding = mPulseExpansionHandler.isExpanding();
            if (mTransitioningToFullShadeProgress > 0.0f
                    || pulseExpanding || (mClippingAnimator != null
                    && (mIsTranslationResettingAnimator || mIsPulseExpansionResettingAnimator))) {
                if (pulseExpanding || mIsPulseExpansionResettingAnimator) {
                    // qsTranslation should only be positive during pulse expansion because it's
                    // already translating in from the top
                    qsTranslation = Math.max(0, (top - getHeaderHeight()) / 2.0f);
                } else if (!mSplitShadeEnabled) {
                    qsTranslation = (top - getHeaderHeight()) * QS_PARALLAX_AMOUNT;
                }
            }
            mTranslationForFullShadeTransition = qsTranslation;
            updateQsFrameTranslation();
            float currentTranslation = mQsFrame.getTranslationY();
            int clipTop = (int) (top - currentTranslation - mQsFrame.getTop());
            int clipBottom = (int) (bottom - currentTranslation - mQsFrame.getTop());
            mVisible = qsVisible;
            mQs.setQsVisible(qsVisible);
            if (mEnableClipping) {
                mQs.setFancyClipping(
                        mDisplayLeftInset,
                        clipTop,
                        mDisplayRightInset,
                        clipBottom,
                        radius,
                        qsVisible && !mSplitShadeEnabled,
                        mIsFullWidth);
            }

        }

        // Increase the height of the notifications scrim when not in split shade
        // (e.g. portrait tablet) so the rounded corners are not visible at the bottom,
        // in this case they are rendered off-screen
        final int notificationsScrimBottom = mSplitShadeEnabled ? bottom : bottom + radius;
        mScrimController.setNotificationsBounds(left, top, right, notificationsScrimBottom);

        if (mApplyClippingImmediatelyListener != null) {
            mApplyClippingImmediatelyListener.onQsClippingImmediatelyApplied(clipStatusView,
                    mLastClipBounds, top, isQsFragmentCreated(), mVisible);
        }

        mScrimController.setScrimCornerRadius(radius);

        // Convert global clipping coordinates to local ones,
        // relative to NotificationStackScrollLayout.
        int nsslLeft = calculateNsslLeft(left);
        int nsslRight = calculateNsslRight(right);
        int nsslTop = getNotificationsClippingTopBounds(top);
        int nsslBottom = bottom - mNotificationStackScrollLayoutController.getTop();
        int bottomRadius = mSplitShadeEnabled ? radius : 0;
        // TODO (b/265193930): remove dependency on NPVC
        int topRadius = mSplitShadeEnabled
                && mPanelViewControllerLazy.get().isExpandingFromHeadsUp() ? 0 : radius;
        mNotificationStackScrollLayoutController.setRoundedClippingBounds(
                nsslLeft, nsslTop, nsslRight, nsslBottom, topRadius, bottomRadius);
    }

    /**
     * Bottom corner radius should follow screen corner radius unless
     * predictive back is running. We want a smooth transition from screen
     * corner radius to scrim corner radius as the notification scrim is scaled down,
     * but the transition should be brief enough to accommodate very short back gestures.
     */
    @VisibleForTesting
    int calculateBottomCornerRadius(float screenCornerRadius) {
        return (int) MathUtils.lerp(screenCornerRadius, mScrimCornerRadius,
                Math.min(calculateBottomRadiusProgress(), 1f));
    }

    @VisibleForTesting
    float calculateBottomRadiusProgress() {
        return (1 - mScrimController.getBackScaling()) * SHADE_BACK_ANIM_SCALE_MULTIPLIER;
    }

    @VisibleForTesting
    int getScrimCornerRadius() {
        return mScrimCornerRadius;
    }

    void setDisplayInsets(int leftInset, int rightInset) {
        mDisplayLeftInset = leftInset;
        mDisplayRightInset = rightInset;
    }

    private int calculateNsslLeft(int nsslLeftAbsolute) {
        int left = nsslLeftAbsolute - mNotificationStackScrollLayoutController.getLeft();
        if (mIsFullWidth) {
            return left;
        }
        return left - mDisplayLeftInset;
    }

    private int calculateNsslRight(int nsslRightAbsolute) {
        int right = nsslRightAbsolute - mNotificationStackScrollLayoutController.getLeft();
        if (mIsFullWidth) {
            return right;
        }
        return right - mDisplayLeftInset;
    }

    private int getNotificationsClippingTopBounds(int qsTop) {
        // TODO (b/265193930): remove dependency on NPVC
        if (mSplitShadeEnabled && mPanelViewControllerLazy.get().isExpandingFromHeadsUp()) {
            // in split shade nssl has extra top margin so clipping at top 0 is not enough, we need
            // to set top clipping bound to negative value to allow HUN to go up to the top edge of
            // the screen without clipping.
            return -mAmbientState.getStackTopMargin();
        } else {
            return qsTop - mNotificationStackScrollLayoutController.getTop();
        }
    }

    @Override
    public float calculateNotificationsTopPadding(boolean isShadeExpanding,
            int keyguardNotificationStaticPadding, float expandedFraction) {
        SceneContainerFlag.assertInLegacyMode();
        float topPadding;
        boolean keyguardShowing = mBarState == KEYGUARD;
        if (mSplitShadeEnabled) {
            return keyguardShowing
                    ? keyguardNotificationStaticPadding : 0;
        }
        if (keyguardShowing && (isExpandImmediate()
                || isShadeExpanding && getExpandedWhenExpandingStarted())) {

            // Either QS pushes the notifications down when fully expanded, or QS is fully above the
            // notifications (mostly on tablets). maxNotificationPadding denotes the normal top
            // padding on Keyguard, maxQsPadding denotes the top padding from the quick settings
            // panel. We need to take the maximum and linearly interpolate with the panel expansion
            // for a nice motion.
            int maxQsPadding = getMaxExpansionHeight();
            int max = keyguardShowing ? Math.max(
                    keyguardNotificationStaticPadding, maxQsPadding) : maxQsPadding;
            topPadding = (int) MathUtils.lerp((float) getMinExpansionHeight(),
                    (float) max, expandedFraction);
            return topPadding;
        } else if (isSizeChangeAnimationRunning()) {
            topPadding = Math.max((int) mSizeChangeAnimator.getAnimatedValue(),
                    keyguardNotificationStaticPadding);
            return topPadding;
        } else if (keyguardShowing) {
            // We can only do the smoother transition on Keyguard when we also are not collapsing
            // from a scrolled quick settings.
            topPadding = MathUtils.lerp((float) keyguardNotificationStaticPadding,
                    (float) (getMaxExpansionHeight()), computeExpansionFraction());
            return topPadding;
        } else {
            topPadding = Math.max(mQsFrameTranslateController.getNotificationsTopPadding(
                    mExpansionHeight, mNotificationStackScrollLayoutController),
                    mQuickQsHeaderHeight);
            return topPadding;
        }
    }

    @Override
    public int calculatePanelHeightExpanded(int stackScrollerPadding) {
        float
                notificationHeight =
                mNotificationStackScrollLayoutController.getHeight()
                        - mNotificationStackScrollLayoutController.getEmptyBottomMargin()
                        - mNotificationStackScrollLayoutController.getTopPadding();

        // When only empty shade view is visible in QS collapsed state, simulate that we would have
        // it in expanded QS state as well so we don't run into troubles when fading the view in/out
        // and expanding/collapsing the whole panel from/to quick settings.
        if (mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0
                && mNotificationStackScrollLayoutController.isShowingEmptyShadeView()) {
            notificationHeight = mNotificationStackScrollLayoutController.getEmptyShadeViewHeight();
        }
        int maxQsHeight = mMaxExpansionHeight;

        // If an animation is changing the size of the QS panel, take the animated value.
        if (mSizeChangeAnimator != null) {
            maxQsHeight = (int) mSizeChangeAnimator.getAnimatedValue();
        }
        float totalHeight = Math.max(maxQsHeight, mBarState == KEYGUARD ? stackScrollerPadding : 0)
                + notificationHeight
                + mNotificationStackScrollLayoutController.getTopPaddingOverflow();
        if (totalHeight > mNotificationStackScrollLayoutController.getHeight()) {
            float
                    fullyCollapsedHeight =
                    maxQsHeight + mNotificationStackScrollLayoutController.getLayoutMinHeight();
            totalHeight = Math.max(fullyCollapsedHeight,
                    mNotificationStackScrollLayoutController.getHeight());
        }
        return (int) totalHeight;
    }

    private float getEdgePosition() {
        // TODO: replace StackY with unified calculation
        return Math.max(mQuickQsHeaderHeight * mAmbientState.getExpansionFraction(),
                mAmbientState.getStackY()
                        // need to adjust for extra margin introduced by large screen shade header
                        + mAmbientState.getStackTopMargin() * mAmbientState.getExpansionFraction()
                        - mAmbientState.getScrollY());
    }

    private int calculateTopClippingBound(int qsPanelBottomY) {
        int top;
        if (mSplitShadeEnabled) {
            top = Math.min(qsPanelBottomY, mLargeScreenShadeHeaderHeight);
        } else {
            if (mTransitioningToFullShadeProgress > 0.0f) {
                // If we're transitioning, let's use the actual value. The else case
                // can be wrong during transitions when waiting for the keyguard to unlock
                top = mTransitionToFullShadePosition;
            } else {
                final float notificationTop = getEdgePosition();
                if (mBarState == KEYGUARD) {
                    if (mKeyguardBypassController.getBypassEnabled()) {
                        // When bypassing on the keyguard, let's use the panel bottom.
                        // this should go away once we unify the stackY position and don't have
                        // to do this min anymore below.
                        top = qsPanelBottomY;
                    } else {
                        top = (int) Math.min(qsPanelBottomY, notificationTop);
                    }
                } else {
                    top = (int) notificationTop;
                }
            }
            // TODO (b/265193930): remove dependency on NPVC
            top += mPanelViewControllerLazy.get().getOverStretchAmount();
            // Correction for instant expansion caused by HUN pull down/
            float minFraction = mPanelViewControllerLazy.get().getMinFraction();
            if (minFraction > 0f && minFraction < 1f) {
                float realFraction = (mShadeExpandedFraction
                        - minFraction) / (1f - minFraction);
                top *= MathUtils.saturate(realFraction / minFraction);
            }
        }
        return top;
    }

    private int calculateBottomClippingBound(int top) {
        if (mSplitShadeEnabled) {
            return top + mNotificationStackScrollLayoutController.getHeight()
                    + mSplitShadeNotificationsScrimMarginBottom;
        } else {
            return mPanelView.getBottom();
        }
    }

    private int calculateLeftClippingBound() {
        if (mIsFullWidth) {
            // left bounds can ignore insets, it should always reach the edge of the screen
            return 0;
        } else {
            return mNotificationStackScrollLayoutController.getLeft()
                    + mDisplayLeftInset;
        }
    }

    private int calculateRightClippingBound() {
        if (mIsFullWidth) {
            return mPanelView.getRight()
                    + mDisplayRightInset;
        } else {
            return mNotificationStackScrollLayoutController.getRight()
                    + mDisplayLeftInset;
        }
    }

    boolean updateAndGetTouchAboveFalsingThreshold() {
        mTouchAboveFalsingThreshold = mFullyExpanded;
        return mTouchAboveFalsingThreshold;
    }

    @VisibleForTesting
    void onHeightChanged() {
        mMaxExpansionHeight = isQsFragmentCreated() ? mQs.getDesiredHeight() : 0;
        if (getExpanded() && mFullyExpanded) {
            mExpansionHeight = mMaxExpansionHeight;
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(true);
            }
        }
        if (mAccessibilityManager.isEnabled()) {
            // TODO (b/265193930): remove dependency on NPVC
            mPanelView.getRootView().setAccessibilityPaneTitle(
                    mPanelViewControllerLazy.get().determineAccessibilityPaneTitle());
        }
        mNotificationStackScrollLayoutController.setMaxTopPadding(mMaxExpansionHeight);
    }

    private void collapseOrExpandQs() {
        if (mSplitShadeEnabled) {
            return; // QS is always expanded in split shade
        }
        onExpansionStarted();
        if (getExpanded()) {
            flingQs(0, FLING_COLLAPSE, null, true);
        } else if (isExpansionEnabled()) {
            mLockscreenGestureLogger.write(MetricsProto.MetricsEvent.ACTION_SHADE_QS_TAP, 0, 0);
            flingQs(0, FLING_EXPAND, null, true);
        }
    }

    private void onScroll(int scrollY) {
        mShadeHeaderController.setQsScrollY(scrollY);
        if (scrollY > 0 && !mFullyExpanded) {
            // TODO (b/265193930): remove dependency on NPVC
            // If we are scrolling QS, we should be fully expanded.
            mPanelViewControllerLazy.get().expandToQs();
        }
    }

    boolean isTrackingBlocked() {
        return mConflictingExpansionGesture && getExpanded();
    }

    boolean isExpansionAnimating() {
        return mExpansionAnimator != null;
    }

    @VisibleForTesting
    boolean isConflictingExpansionGesture() {
        return mConflictingExpansionGesture;
    }

    /** QS page content never owns the unified shade drag gesture. */
    boolean handleTouch(MotionEvent event, boolean isFullyCollapsed,
            boolean isShadeOrQsHeightAnimationRunning) {
        return false;
    }

    /** The unified shade motion controller is the only panel gesture interceptor. */
    boolean onIntercept(MotionEvent event) {
        return false;
    }

    /**
     * Animate QS closing by flinging it.
     * If QS is expanded, it will collapse into QQS and stop.
     * If in split shade, it will collapse the whole shade.
     *
     * @param animateAway Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    void animateCloseQs(boolean animateAway) {
        if (mExpansionAnimator != null) {
            if (!mAnimatorExpand) {
                return;
            }
            float height = mExpansionHeight;
            mExpansionAnimator.cancel();
            setExpansionHeight(height);
        }
        flingQs(0 /* vel */, animateAway ? FLING_HIDE : FLING_COLLAPSE);
    }

    private void cancelExpansionAnimation() {
        if (mExpansionAnimator != null) {
            mExpansionAnimator.cancel();
        }
    }

    /** @see #flingQs(float, int, Runnable, boolean) */
    void flingQs(float vel, int type) {
        flingQs(vel, type, null /* onFinishRunnable */, false /* isClick */);
    }

    /**
     * Animates QS or QQS as if the user had swiped up or down.
     *
     * @param vel              Finger velocity or 0 when not initiated by touch events.
     * @param type             Either FLING_EXPAND, FLING_COLLAPSE or FLING_HIDE.
     * @param onFinishRunnable Runnable to be executed at the end of animation.
     * @param isClick          If originated by click (different interpolator and duration.)
     */
    private void flingQs(float vel, int type, final Runnable onFinishRunnable,
            boolean isClick) {
        mShadeLog.flingQs(type, isClick);
        float target;
        switch (type) {
            case FLING_EXPAND:
                target = getMaxExpansionHeight();
                break;
            case FLING_COLLAPSE:
                if (mSplitShadeEnabled) {
                    Log.wtfStack(TAG, "FLING_COLLAPSE called in split shade");
                }
                setExpandImmediate(false);
                target = getMinExpansionHeight();
                break;
            case FLING_HIDE:
            default:
                if (isQsFragmentCreated()) {
                    mQs.closeDetail();
                }
                target = 0;
        }
        if (target == mExpansionHeight) {
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
            traceQsJank(false, type != FLING_EXPAND);
            return;
        }

        // If we move in the opposite direction, reset velocity and use a different duration.
        boolean oppositeDirection = false;
        boolean expanding = type == FLING_EXPAND;
        if (vel > 0 && !expanding || vel < 0 && expanding) {
            vel = 0;
            oppositeDirection = true;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(
                mExpansionHeight, target);
        if (isClick) {
            animator.setInterpolator(Interpolators.TOUCH_RESPONSE);
            animator.setDuration(368);
        } else {
            if (mFlingQsWithoutClickListener != null) {
                mFlingQsWithoutClickListener.onFlingQsWithoutClick(animator, mExpansionHeight,
                        target, vel);
            }
        }
        if (oppositeDirection) {
            animator.setDuration(350);
        }
        animator.addUpdateListener(
                animation -> setExpansionHeight((Float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mIsCanceled;

            @Override
            public void onAnimationStart(Animator animation) {
                mPanelViewControllerLazy.get().notifyExpandingStarted();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mIsCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimatingHiddenFromCollapsed = false;
                mAnimating = false;
                mPanelViewControllerLazy.get().notifyExpandingFinished();
                mNotificationStackScrollLayoutController.resetCheckSnoozeLeavebehind();
                mExpansionAnimator = null;
                if (onFinishRunnable != null) {
                    onFinishRunnable.run();
                }
                traceQsJank(false, mIsCanceled);
            }
        });
        // Let's note that we're animating QS. Moving the animator here will cancel it immediately,
        // so we need a separate flag.
        mAnimating = true;
        animator.start();
        mExpansionAnimator = animator;
        mAnimatorExpand = expanding;
        mAnimatingHiddenFromCollapsed =
                computeExpansionFraction() == 0.0f && target == 0;
    }

    @NeverCompile
    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println(TAG + ":");
        IndentingPrintWriter ipw = asIndenting(pw);
        ipw.increaseIndent();
        ipw.print("getExpanded()=");
        ipw.println(getExpanded());
        ipw.print("isExpandImmediate()=");
        ipw.println(isExpandImmediate());
        ipw.print("mAnimateNextNotificationBounds=");
        ipw.println(mAnimateNextNotificationBounds);
        ipw.print("mAnimating=");
        ipw.println(mAnimating);
        ipw.print("mAnimatingHiddenFromCollapsed=");
        ipw.println(mAnimatingHiddenFromCollapsed);
        ipw.print("mAnimatorExpand=");
        ipw.println(mAnimatorExpand);
        ipw.print("mBarState=");
        ipw.println(mBarState);
        ipw.print("mCachedGestureInsets=");
        ipw.println(mCachedGestureInsets);
        ipw.print("mCachedWindowWidth=");
        ipw.println(mCachedWindowWidth);
        ipw.print("mClippingAnimationEndBounds=");
        ipw.println(mClippingAnimationEndBounds);
        ipw.print("mCollapsedOnDown=");
        ipw.println(mCollapsedOnDown);
        ipw.print("mConflictingExpansionGesture=");
        ipw.println(mConflictingExpansionGesture);
        ipw.print("mDistanceForFullShadeTransition=");
        ipw.println(mDistanceForFullShadeTransition);
        ipw.print("mDisplayLeftInset=");
        ipw.println(mDisplayLeftInset);
        ipw.print("mDisplayRightInset=");
        ipw.println(mDisplayRightInset);
        ipw.print("mDozing=");
        ipw.println(mDozing);
        ipw.print("mEnableClipping=");
        ipw.println(mEnableClipping);
        ipw.print("mExpandedWhenExpandingStarted=");
        ipw.println(mExpandedWhenExpandingStarted);
        ipw.print("mExpansionEnabledAmbient=");
        ipw.println(mExpansionEnabledAmbient);
        ipw.print("mExpansionEnabledPolicy=");
        ipw.println(mExpansionEnabledPolicy);
        ipw.print("mExpansionFromOverscroll=");
        ipw.println(mExpansionFromOverscroll);
        ipw.print("mExpansionHeight=");
        ipw.println(mExpansionHeight);
        ipw.print("mFalsingThreshold=");
        ipw.println(mFalsingThreshold);
        ipw.print("mFullyExpanded=");
        ipw.println(mFullyExpanded);
        ipw.print("mInitialHeightOnTouch=");
        ipw.println(mInitialHeightOnTouch);
        ipw.print("mInitialTouchX=");
        ipw.println(mInitialTouchX);
        ipw.print("mInitialTouchY=");
        ipw.println(mInitialTouchY);
        ipw.print("mInterceptRegion=");
        ipw.println(mInterceptRegion);
        ipw.print("mIsFullWidth=");
        ipw.println(mIsFullWidth);
        ipw.print("mIsPulseExpansionResettingAnimator=");
        ipw.println(mIsPulseExpansionResettingAnimator);
        ipw.print("mIsTranslationResettingAnimator=");
        ipw.println(mIsTranslationResettingAnimator);
        ipw.print("mLargeScreenShadeHeaderHeight=");
        ipw.println(mLargeScreenShadeHeaderHeight);
        ipw.print("mLastClipBounds=");
        ipw.println(mLastClipBounds);
        ipw.print("mLastOverscroll=");
        ipw.println(mLastOverscroll);
        ipw.print("mLastShadeFlingWasExpanding=");
        ipw.println(mLastShadeFlingWasExpanding);
        ipw.print("mLockscreenNotificationPadding=");
        ipw.println(mLockscreenNotificationPadding);
        ipw.print("mMaxExpansionHeight=");
        ipw.println(mMaxExpansionHeight);
        ipw.print("mMinExpansionHeight=");
        ipw.println(mMinExpansionHeight);
        ipw.print("mNotificationBoundsAnimationDelay=");
        ipw.println(mNotificationBoundsAnimationDelay);
        ipw.print("mNotificationBoundsAnimationDuration=");
        ipw.println(mNotificationBoundsAnimationDuration);
        ipw.print("mQuickQsHeaderHeight=");
        ipw.println(mQuickQsHeaderHeight);
        ipw.print("mScrimCornerRadius=");
        ipw.println(mScrimCornerRadius);
        ipw.print("mScrimEnabled=");
        ipw.println(mScrimEnabled);
        ipw.print("mScreenCornerRadius=");
        ipw.println(mScreenCornerRadius);
        ipw.print("mShadeExpandedFraction=");
        ipw.println(mShadeExpandedFraction);
        ipw.print("mShadeExpandedHeight=");
        ipw.println(mShadeExpandedHeight);
        ipw.print("mSlopMultiplier=");
        ipw.println(mSlopMultiplier);
        ipw.print("mSplitShadeEnabled=");
        ipw.println(mSplitShadeEnabled);
        ipw.print("mSplitShadeNotificationsScrimMarginBottom=");
        ipw.println(mSplitShadeNotificationsScrimMarginBottom);
        ipw.print("mStackScrollerOverscrolling=");
        ipw.println(mStackScrollerOverscrolling);
        ipw.print("mStatusBarMinHeight=");
        ipw.println(mStatusBarMinHeight);
        ipw.print("mTouchAboveFalsingThreshold=");
        ipw.println(mTouchAboveFalsingThreshold);
        ipw.print("mTouchSlop=");
        ipw.println(mTouchSlop);
        ipw.print("mTracking=");
        ipw.println(isTracking());
        ipw.print("mTrackingPointer=");
        ipw.println(mTrackingPointer);
        ipw.print("mTransitionToFullShadePosition=");
        ipw.println(mTransitionToFullShadePosition);
        ipw.print("mTransitioningToFullShadeProgress=");
        ipw.println(mTransitioningToFullShadeProgress);
        ipw.print("mTranslationForFullShadeTransition=");
        ipw.println(mTranslationForFullShadeTransition);
        ipw.print("mTwoFingerExpandPossible=");
        ipw.println(mTwoFingerExpandPossible);
        ipw.print("mUseLargeScreenShadeHeader=");
        ipw.println(mUseLargeScreenShadeHeader);
        ipw.print("mVisible=");
        ipw.println(mVisible);
    }

    /** */
    FragmentHostManager.FragmentListener getQsFragmentListener() {
        return new QsFragmentListener();
    }

    /** */
    public final class QsFragmentListener implements FragmentHostManager.FragmentListener {

        private int mLastDisplayIdWithMediaVisibilityChange = Display.DEFAULT_DISPLAY;

        /** */
        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            mQs = (QS) fragment;
            mQs.setPanelView(mQsHeightListener);
            mQs.setCollapseExpandAction(mQsCollapseExpandAction);
            mQs.setHeaderClickable(isExpansionEnabled());
            mQs.setOverscrolling(mStackScrollerOverscrolling);
            mQs.setInSplitShade(mSplitShadeEnabled);
            mQs.setIsNotificationPanelFullWidth(mIsFullWidth);
            // recompute internal state when qspanel height changes
            mQs.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height != oldHeight) {
                            onHeightChanged();
                        }
                    });
            mQs.setCollapsedMediaVisibilityChangedListener((visible) -> {
                if (visible != mLastMediaVisibility) {
                    mLastMediaVisibility = visible;
                    mPanelViewControllerLazy.get().positionClockAndNotifications();
                }
                boolean shouldSkipAnimation = false;

                if (ShadeWindowGoesAround.isEnabled() && mLastDisplayIdWithMediaVisibilityChange
                        != getDisplayId()) {
                    // Let's not animate the bounds if the visibility change is due to the
                    // display being changed, otherwise we'll see some animation after the
                    // shade changes display.
                    shouldSkipAnimation = true;
                    mLastDisplayIdWithMediaVisibilityChange = getDisplayId();
                }
                if (mQs.isHeaderShown() && !shouldSkipAnimation) {
                    setAnimateNextNotificationBounds(
                            StackStateAnimator.ANIMATION_DURATION_STANDARD, 0);
                    mNotificationStackScrollLayoutController.animateNextTopPaddingChange();

                }
            });
            mLockscreenShadeTransitionController.setQS(mQs);
            mNotificationStackScrollLayoutController.setQsHeader((ViewGroup) mQs.getHeader());
            mQs.setScrollListener(mQsScrollListener);
            mPanelView.getContext().getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.STATUS_BAR_QUICK_QS_PULLDOWN),
                    false, mOneFingerQuickSettingsInterceptObserver,
                    UserHandle.USER_ALL);
            mOneFingerQuickSettingsInterceptObserver.onChange(true);
            updateExpansion();
        }

        private int getDisplayId() {
            return mPanelView.getContext().getDisplayId();
        }

        /** */
        @Override
        public void onFragmentViewDestroyed(String tag, Fragment fragment) {
            mPanelView.getContext().getContentResolver().unregisterContentObserver(
                    mOneFingerQuickSettingsInterceptObserver);
            // Manual handling of fragment lifecycle is only required because this bridges
            // non-fragment and fragment code. Once we are using a fragment for the notification
            // panel, mQs will not need to be null cause it will be tied to the same lifecycle.
            if (fragment == mQs) {
                // Clear it to remove bindings to mQs from the provider.
                mNotificationStackScrollLayoutController.setQsHeader(null);
                mQs = null;
            }
        }
    }

    private final class LockscreenShadeTransitionCallback
            implements LockscreenShadeTransitionController.Callback {
        /** Called when pulse expansion has finished and this is going to the full shade. */
        @Override
        public void onPulseExpansionFinished() {
            setAnimateNextNotificationBounds(
                    StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE, 0);
            mIsPulseExpansionResettingAnimator = true;
        }

        @Override
        public void setTransitionToFullShadeAmount(float pxAmount, boolean animate, long delay) {
            if (animate && mIsFullWidth) {
                setAnimateNextNotificationBounds(
                        StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE, delay);
                mIsTranslationResettingAnimator = mTranslationForFullShadeTransition > 0.0f;
            }
            float endPosition = 0;
            if (pxAmount > 0.0f) {
                if (mSplitShadeEnabled) {
                    float qsHeight = MathUtils.lerp(getMinExpansionHeight(),
                            getMaxExpansionHeight(),
                            mLockscreenShadeTransitionController.getQSDragProgress());
                    setExpansionHeight(qsHeight);
                }

                boolean hasNotifications =
                        mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue();
                if (!hasNotifications && !mMediaDataManager.hasActiveMedia()) {
                    // No notifications are visible, let's animate to the height of qs instead
                    if (isQsFragmentCreated()) {
                        // Let's interpolate to the header height instead of the top padding,
                        // because the toppadding is way too low because of the large clock.
                        // we still want to take into account the edgePosition though as that nicely
                        // overshoots in the stackscroller
                        endPosition = getEdgePosition()
                                - mNotificationStackScrollLayoutController.getTopPadding()
                                + getHeaderHeight();
                    }
                } else {
                    // Interpolating to the new bottom edge position!
                    endPosition = getEdgePosition() + mNotificationStackScrollLayoutController
                            .getFullShadeTransitionInset();
                    if (mBarState == KEYGUARD) {
                        endPosition -= mLockscreenNotificationPadding;
                    }
                }
            }

            // Calculate the overshoot amount such that we're reaching the target after our desired
            // distance, but only reach it fully once we drag a full shade length.
            mTransitioningToFullShadeProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                    MathUtils.saturate(pxAmount / mDistanceForFullShadeTransition));

            int position = (int) MathUtils.lerp((float) 0, endPosition,
                    mTransitioningToFullShadeProgress);
            if (mTransitioningToFullShadeProgress > 0.0f) {
                // we want at least 1 pixel otherwise the panel won't be clipped
                position = Math.max(1, position);
            }
            mTransitionToFullShadePosition = position;
            updateExpansion();
        }
    }

    private final class NsslOverscrollTopChangedListener implements
            NotificationStackScrollLayout.OnOverscrollTopChangedListener {
        @Override
        public void onOverscrollTopChanged(float amount, boolean isRubberbanded) {
            // When in split shade, overscroll shouldn't carry through to QS
            if (mSplitShadeEnabled) {
                return;
            }
            cancelExpansionAnimation();
            if (!isExpansionEnabled()) {
                amount = 0f;
            }
            float rounded = amount >= 1f ? amount : 0f;
            setOverScrolling(rounded != 0f && isRubberbanded);
            mExpansionFromOverscroll = rounded != 0f;
            mLastOverscroll = rounded;
            updateQsState();
            setExpansionHeight(getMinExpansionHeight() + rounded);
        }

        @Override
        public void flingTopOverscroll(float velocity, boolean open) {
            // in split shade mode we want to expand/collapse QS only when touch happens within QS
            if (isSplitShadeAndTouchXOutsideQs(mInitialTouchX)) {
                return;
            }
            mLastOverscroll = 0f;
            mExpansionFromOverscroll = false;
            if (open) {
                // During overscrolling, qsExpansion doesn't actually change that the qs is
                // becoming expanded. Any layout could therefore reset the position again. Let's
                // make sure we can expand
                setOverScrolling(false);
            }
            setExpansionHeight(getExpansionHeight());
            boolean canExpand = isExpansionEnabled();
            flingQs(!canExpand && open ? 0f : velocity,
                    open && canExpand ? FLING_EXPAND : FLING_COLLAPSE, () -> {
                        setOverScrolling(false);
                        updateQsState();
                    }, false);
        }
    }

    void beginJankMonitoring(boolean isFullyCollapsed) {
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor == null) {
            return;
        }
        // TODO (b/265193930): remove dependency on NPVC
        InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withView(
                        Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
                        mPanelView).setTag(isFullyCollapsed ? "Expand" : "Collapse");
        monitor.begin(builder);
    }

    void endJankMonitoring() {
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor == null) {
            return;
        }
        monitor.end(Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
    }

    void cancelJankMonitoring() {
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor == null) {
            return;
        }
        monitor.cancel(Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
    }

    void traceQsJank(boolean startTracing, boolean wasCancelled) {
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor == null) {
            return;
        }
        if (startTracing) {
            monitor.begin(mPanelView, Cuj.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
        } else {
            if (wasCancelled) {
                monitor.cancel(Cuj.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
            } else {
                monitor.end(Cuj.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
            }
        }
    }

    interface ExpansionHeightSetToMaxListener {
        void onExpansionHeightSetToMax(boolean requestPaddingUpdate);
    }

    interface ExpansionHeightListener {
        void onQsSetExpansionHeightCalled(boolean qsFullyExpanded);
    }

    interface ApplyClippingImmediatelyListener {
        void onQsClippingImmediatelyApplied(boolean clipStatusView, Rect lastQsClipBounds,
                int top, boolean qsFragmentCreated, boolean qsVisible);
    }

    interface FlingQsWithoutClickListener {
        void onFlingQsWithoutClick(ValueAnimator animator, float qsExpansionHeight,
                float target, float vel);
    }
}
