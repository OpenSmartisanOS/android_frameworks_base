package com.android.systemui.volume;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.drawable.TransitionDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.volume.widget.VCountDownTimerView;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** SmartisanOS R2's right-side volume panel backed by Android 16 volume policy. */
public final class VolumeDialogImpl implements VolumeDialog {
    private static final String TAG = "VolumeDialogImpl";
    private static final String SETTING_MUTE = Settings.System.VOLUME_PANEL_MUTE_ENABLE;
    private static final String SETTING_MUTE_TIMEOUT = Settings.System.MUTE_TIMEOUT;
    private static final String MUTE_UI_PREFERENCES = "r2_volume_mute_ui";
    private static final String MUTE_DURATION_PREFIX = "duration_user_";
    private static final long TIMEOUT_COMPACT = 2500;
    private static final long TIMEOUT_EXPANDED = 5000;
    private static final long TIMEOUT_ACCESSIBILITY = 6500;
    private static final long MUTE_CONFIRMATION_DELAY = 300;
    private static final AudioAttributes MEDIA_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();

    private final Context mContext;
    private final VolumeDialogController mController;
    private final AccessibilityManagerWrapper mAccessibility;
    private final CsdWarningDialog.Factory mCsdFactory;
    private final VolumeDialogBrightnessController mBrightnessController;
    private final UserTracker mUserTracker;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final LongSparseArray<Integer> mLastAudibleLevels = new LongSparseArray<>();
    private final Object mSafetyLock = new Object();

    private Dialog mDialog;
    private View mRoot;
    private FrameLayout mRightRoot;
    private ShadowFrameLayout mMainShadow;
    private ShadowFrameLayout mMuteShadow;
    private ShadowFrameLayout mExpandShadow;
    private FrameLayout mMainGroup;
    private FrameLayout mMuteCard;
    private FrameLayout mMuteContent;
    private ImageView mMuteIcon;
    private VCountDownTimerView mMuteTimer;
    private ImageView mMuteCancel;
    private ImageView mExpandButton;
    private ColumnView mBrightness;
    private ColumnView mRinger;
    private ColumnView mMedia;
    private VolumeDialogController.State mPlatformState;
    private VolumeDialogState mState;
    private VolumeDialogLayoutModel.Result mGeometry;
    private boolean mShowing;
    private boolean mExpanded;
    private boolean mMuteEditor;
    private boolean mExitAnimating;
    private boolean mVisibleNotified;
    private boolean mLastTimedMuteActive;
    private int mBrightnessGamma;
    private boolean mBrightnessUnavailable;
    private float mBackgroundScale = -1f;
    private int mAnimationGeneration;
    private int mMuteGeneration;
    private SafetyWarningDialog mSafetyWarning;
    private CsdWarningDialog mCsdWarning;
    private int mCsdGeneration;
    private ViewTreeObserver.OnPreDrawListener mEnterPreDrawListener;

    private final Runnable mTimeout = () -> dismiss(Events.DISMISS_REASON_TIMEOUT);

    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, Context userContext) {
            resetTransientUi();
            mLastAudibleLevels.clear();
            mLastTimedMuteActive = false;
            mExpanded = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.VOLUME_PANEL_IS_EXPANDED, 0, newUser) == 1;
            rebuildState();
            if (mShowing) dismiss(Events.DISMISS_REASON_SETTINGS_CLICKED);
        }
    };

    private final ContentObserver mMuteObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            rebuildState();
        }
    };

    public VolumeDialogImpl(
            Context context,
            VolumeDialogController controller,
            AccessibilityManagerWrapper accessibility,
            CsdWarningDialog.Factory csdFactory,
            VolumeDialogBrightnessController brightnessController,
            UserTracker userTracker) {
        mContext = context;
        mController = controller;
        mAccessibility = accessibility;
        mCsdFactory = csdFactory;
        mBrightnessController = brightnessController;
        mUserTracker = userTracker;
    }

    @Override
    public void init(int windowType, Callback callback) {
        mExpanded = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.VOLUME_PANEL_IS_EXPANDED,
                0, mUserTracker.getUserId()) == 1;
        createDialog(windowType);
        mController.addCallback(mControllerCallback, mHandler);
        mController.getState();
        ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_MUTE), false,
                mMuteObserver, android.os.UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_MUTE_TIMEOUT), false,
                mMuteObserver, android.os.UserHandle.USER_ALL);
        mUserTracker.addCallback(mUserCallback, mContext.getMainExecutor());
        mBrightnessController.start((gamma, automatic, overriddenByWindow, restrictedByPolicy) -> {
            mBrightnessGamma = gamma;
            mBrightnessUnavailable = overriddenByWindow || restrictedByPolicy;
            if (mBrightness != null && mBrightnessUnavailable && mBrightness.sliderTracking) {
                mBrightnessController.cancelInteraction(
                        mBrightness.startProgress, mBrightness.interactionGeneration);
                mBrightness.sliderTracking = false;
                mBrightness.slider.abortTracking();
            }
            if (mBrightness != null && !mBrightness.sliderTracking) {
                mBrightness.slider.setRange(mBrightnessController.minGamma(),
                        mBrightnessController.maxGamma());
                mBrightness.slider.setProgress(gamma);
                final boolean active = gamma > mBrightnessController.minGamma();
                mBrightness.value.setText(R.string.volume_dialog_brightness);
                mBrightness.value.setTextColor(mContext.getColor(active
                        ? R.color.volume_dialog_panel_text_active : R.color.volume_dialog_panel_text));
                mBrightness.slider.setMarkerStyle(active);
                setColumnEnabled(mBrightness, !mBrightnessUnavailable);
                String description = mBrightnessUnavailable
                        ? mContext.getString(R.string.quick_settings_brightness_unable_adjust_msg)
                        : mContext.getString(R.string.volume_dialog_brightness);
                mBrightness.slider.setContentDescription(description);
                mBrightness.icon.setContentDescription(description);
            }
        });
    }

    @Override
    public void destroy() {
        resetTransientUi();
        mHandler.removeCallbacksAndMessages(null);
        mAnimationGeneration++;
        mCsdGeneration++;
        mController.removeCallback(mControllerCallback);
        mBrightnessController.stop();
        mUserTracker.removeCallback(mUserCallback);
        try {
            mContext.getContentResolver().unregisterContentObserver(mMuteObserver);
        } catch (IllegalArgumentException ignored) { }
        synchronized (mSafetyLock) {
            if (mSafetyWarning != null) {
                mSafetyWarning.dismiss();
                mSafetyWarning = null;
            }
            if (mCsdWarning != null) {
                mCsdWarning.dismiss();
                mCsdWarning = null;
            }
        }
        if (mDialog != null) mDialog.dismiss();
        clearEnterPreDrawListener();
        mShowing = false;
        mLastAudibleLevels.clear();
        setControllerVisible(false);
    }

    private void createDialog(int windowType) {
        mDialog = new Dialog(mContext);
        Window window = mDialog.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setDecorFitsSystemWindows(false);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY);
        window.setType(windowType);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.FILL;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        lp.setTitle(TAG);
        lp.windowAnimations = 0;
        window.setAttributes(lp);

        mRoot = LayoutInflater.from(mContext).inflate(R.layout.volume_dialog, null, false);
        mDialog.setContentView(mRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Installing the PhoneWindow decor applies the dialog theme's WRAP_CONTENT defaults.
        // Override them only after setContentView(), otherwise a fully wrap-content subtree whose
        // children are sized by applyGeometry() gets its first surface measured as 1 x 0.
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        window.setGravity(Gravity.FILL);
        bindViews();
        mRoot.setOnTouchListener((view, event) -> {
            rescheduleTimeout();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && mRightRoot != null && !pointInside(mRightRoot, event.getRawX(),
                            event.getRawY())) {
                dismiss(Events.DISMISS_REASON_TOUCH_OUTSIDE);
                return true;
            }
            return false;
        });
        mDialog.setOnDismissListener(ignored -> {
            clearEnterPreDrawListener();
            mHandler.removeCallbacks(mTimeout);
            resetTransientUi();
            mShowing = false;
            mExitAnimating = false;
            setControllerVisible(false);
        });
    }

    private static boolean pointInside(View view, float rawX, float rawY) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX < location[0] + view.getWidth()
                && rawY >= location[1] && rawY < location[1] + view.getHeight();
    }

    private void bindViews() {
        mRightRoot = mRoot.findViewById(R.id.volume_dialog_right_root);
        mMainShadow = mRoot.findViewById(R.id.volume_dialog_main_shadow);
        mMuteShadow = mRoot.findViewById(R.id.volume_dialog_mute_shadow);
        mExpandShadow = mRoot.findViewById(R.id.volume_dialog_expand_shadow);
        mMainGroup = mRoot.findViewById(R.id.volume_dialog_main_group);
        mMuteCard = mRoot.findViewById(R.id.volume_dialog_mute_card);
        mMuteContent = mRoot.findViewById(R.id.volume_dialog_mute_content);
        mMuteIcon = mRoot.findViewById(R.id.volume_dialog_mute_icon);
        mMuteTimer = mRoot.findViewById(R.id.volume_dialog_mute_timer);
        mMuteCancel = mRoot.findViewById(R.id.volume_dialog_mute_cancel);
        mExpandButton = mRoot.findViewById(R.id.volume_dialog_expand_button);
        mBrightness = new ColumnView(mRoot.findViewById(R.id.volume_dialog_brightness_column));
        mRinger = new ColumnView(mRoot.findViewById(R.id.volume_dialog_ringer_column));
        mMedia = new ColumnView(mRoot.findViewById(R.id.volume_dialog_media_column));
        bindColumnListeners();
        // The original visible buttons are only 90 design pixels high, while their shadow
        // containers provide the full touch target. Keep the exact artwork but let taps in the
        // surrounding transparent shadow padding reach the same actions.
        mMuteShadow.setOnClickListener(view -> onMuteClicked());
        mExpandShadow.setOnClickListener(view -> setExpanded(!mExpanded, true));
        mMuteShadow.setFocusable(true);
        mExpandShadow.setFocusable(true);
        mMuteCard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mExpandButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mMuteCancel.setOnClickListener(view -> {
            if (isTimedMuteActive()) {
                cancelTimedMute();
            } else {
                closeMuteEditor();
            }
            rescheduleTimeout();
        });
        mMuteCancel.setOnTouchListener((view, event) -> {
            setCancelMutePressed(event.getActionMasked() == MotionEvent.ACTION_DOWN);
            return false;
        });
        mRightRoot.setOnTouchListener((view, event) -> {
            rescheduleTimeout();
            return false;
        });
        mMuteTimer.setCountDownListener(new VCountDownTimerView.VCountDownStatusListener() {
            @Override public void onCancel() { closeMuteEditor(); }
            @Override public void onFinish() { cancelTimedMute(); }
            @Override public void onStart(int seconds) { startTimedMute(seconds); }
        });
        mRoot.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> applyGeometry());
    }

    private void bindColumnListeners() {
        mBrightness.label = VolumeDialogState.Column.BRIGHTNESS;
        mBrightness.slider.setOnProgressChangedListener(new VerticalSeekBar.OnProgressChangedListener() {
            @Override public void onProgressChanged(VerticalSeekBar seekBar, int progress,
                    boolean fromUser) {
                if (fromUser) {
                    mBrightnessGamma = progress;
                    mBrightnessController.setTemporary(progress,
                            mBrightness.interactionGeneration);
                    rescheduleTimeout();
                }
            }
            @Override public void onStartTrackingTouch(VerticalSeekBar seekBar) {
                mBrightness.sliderTracking = true;
                mBrightness.startProgress = seekBar.getProgress();
                mBrightness.interactionGeneration = mBrightnessController.beginInteraction();
            }
            @Override public void onStopTrackingTouch(VerticalSeekBar seekBar) {
                mBrightness.sliderTracking = false;
                mBrightnessController.setPermanent(seekBar.getProgress(),
                        mBrightness.interactionGeneration);
                rescheduleTimeout();
            }
            @Override public void onCancelTrackingTouch(VerticalSeekBar seekBar) {
                mBrightness.sliderTracking = false;
                mBrightnessController.cancelInteraction(mBrightness.startProgress,
                        mBrightness.interactionGeneration);
                seekBar.setProgress(mBrightness.startProgress);
                rescheduleTimeout();
            }
        });
        bindAudioSlider(mRinger);
        bindAudioSlider(mMedia);
        mRinger.icon.setOnClickListener(v -> cycleRingerMode());
        mMedia.icon.setOnClickListener(v -> toggleAudioColumn(mMedia));
    }

    private void bindAudioSlider(ColumnView column) {
        column.slider.setOnProgressChangedListener(new VerticalSeekBar.OnProgressChangedListener() {
            @Override public void onProgressChanged(VerticalSeekBar seekBar, int progress,
                    boolean fromUser) {
                if (fromUser && column.stream >= 0) {
                    if (mState == null || mState.isStreamRestricted(column.stream)) return;
                    mController.setActiveStream(column.stream, false);
                    mController.setStreamVolume(column.stream, progress, false);
                    if (progress > 0) {
                        mLastAudibleLevels.put(lastAudibleKey(column.stream), progress);
                    }
                    rescheduleTimeout();
                }
            }
            @Override public void onStartTrackingTouch(VerticalSeekBar seekBar) {
                column.sliderTracking = true;
                column.startProgress = seekBar.getProgress();
            }
            @Override public void onStopTrackingTouch(VerticalSeekBar seekBar) {
                column.sliderTracking = false;
                rescheduleTimeout();
            }
            @Override public void onCancelTrackingTouch(VerticalSeekBar seekBar) {
                column.sliderTracking = false;
                if (column.stream >= 0) {
                    mController.setStreamVolume(column.stream, column.startProgress, false);
                }
                seekBar.setProgress(column.startProgress);
                rescheduleTimeout();
            }
        });
    }

    private void onMuteClicked() {
        if (isTimedMuteActive()) {
            cancelTimedMute();
        } else if (mMuteEditor) {
            startTimedMute(mMuteTimer.getSelectedSeconds());
        } else {
            openMuteEditor();
        }
        rescheduleTimeout();
    }

    private void openMuteEditor() {
        beginPanelTransition();
        mMuteEditor = true;
        final int generation = ++mAnimationGeneration;
        mMuteIcon.animate().cancel();
        mMuteContent.animate().cancel();
        mMuteTimer.setSelectedSeconds(readMuteDuration());
        mMuteTimer.showSelector();
        mMuteContent.setVisibility(VISIBLE);
        mMuteContent.setAlpha(0f);
        mMuteContent.animate().alpha(1f).setDuration(200).start();
        mMuteIcon.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (generation == mAnimationGeneration && mMuteEditor) {
                mMuteIcon.setVisibility(GONE);
            }
        }).start();
        applyGeometry();
        updateColumnVisibility(true);
    }

    private void closeMuteEditor() {
        if (!mMuteEditor) return;
        beginPanelTransition();
        mMuteEditor = false;
        final int generation = ++mAnimationGeneration;
        mMuteIcon.animate().cancel();
        mMuteContent.animate().cancel();
        mMuteTimer.stop();
        mMuteIcon.setVisibility(VISIBLE);
        mMuteIcon.setAlpha(0f);
        mMuteIcon.animate().alpha(1f).setDuration(200).start();
        mMuteContent.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (generation == mAnimationGeneration && !mMuteEditor && !isTimedMuteActive()) {
                mMuteContent.setVisibility(GONE);
            }
        }).start();
        applyGeometry();
    }

    private void startTimedMute(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        ContentResolver resolver = mContext.getContentResolver();
        int userId = mUserTracker.getUserId();
        mContext.getSharedPreferences(MUTE_UI_PREFERENCES, Context.MODE_PRIVATE)
                .edit().putInt(MUTE_DURATION_PREFIX + userId, seconds).apply();
        Settings.System.putLongForUser(resolver, SETTING_MUTE_TIMEOUT, deadline, userId);
        Settings.System.putIntForUser(resolver, SETTING_MUTE, 1, userId);
        mMuteEditor = false;
        int generation = ++mMuteGeneration;
        if (Settings.Global.getInt(resolver,
                Settings.Global.TELEPHONY_VIBRATION_ENABLED, 1) == 1
                && mController.hasVibrator()) {
            mHandler.postDelayed(() -> {
                if (generation == mMuteGeneration && userId == mUserTracker.getUserId()
                        && isTimedMuteActive()) {
                    mController.vibrate(VibrationEffect.createWaveform(
                            new long[] {0L, 70L}, new int[] {0, 125}, -1));
                }
            }, MUTE_CONFIRMATION_DELAY);
        }
        rebuildState();
        sendAnnouncement(R.string.volume_dialog_mute_started);
    }

    private void cancelTimedMute() {
        ContentResolver resolver = mContext.getContentResolver();
        int userId = mUserTracker.getUserId();
        Settings.System.putIntForUser(resolver, SETTING_MUTE, 0, userId);
        Settings.System.putLongForUser(resolver, SETTING_MUTE_TIMEOUT, 0L, userId);
        mMuteEditor = false;
        ++mMuteGeneration;
        ++mAnimationGeneration;
        rebuildState();
    }

    private boolean isTimedMuteActive() {
        int user = mUserTracker.getUserId();
        return Settings.System.getIntForUser(mContext.getContentResolver(), SETTING_MUTE, 0,
                user) == 1 && timedMuteDeadline() > System.currentTimeMillis();
    }

    private long timedMuteDeadline() {
        return Settings.System.getLongForUser(mContext.getContentResolver(), SETTING_MUTE_TIMEOUT,
                0L, mUserTracker.getUserId());
    }

    private void setExpanded(boolean expanded, boolean animate) {
        if (mExpanded == expanded && !mMuteEditor) return;
        if (animate && mRightRoot != null && mRightRoot.isLaidOut()) {
            beginPanelTransition();
        }
        mExpanded = expanded;
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.VOLUME_PANEL_IS_EXPANDED,
                expanded ? 1 : 0, mUserTracker.getUserId());
        int generation = ++mAnimationGeneration;
        applyStateToViews(animate);
        if (!expanded) {
            mHandler.postDelayed(() -> {
                if (generation == mAnimationGeneration) applyStateToViews(false);
            }, 200);
        }
        rescheduleTimeout();
    }

    private void beginPanelTransition() {
        if (mRightRoot == null || !mRightRoot.isLaidOut()) return;
        TransitionSet transition = new TransitionSet();
        transition.addTransition(new ChangeBounds());
        transition.addTransition(new CustomColorTransition());
        transition.setOrdering(TransitionSet.ORDERING_TOGETHER);
        transition.setDuration(300);
        transition.setInterpolator(new DecelerateInterpolator());
        TransitionManager.beginDelayedTransition(mRightRoot, transition);
    }

    private void show(int reason) {
        if (mShowing) {
            if (mExitAnimating) {
                mExitAnimating = false;
                ++mAnimationGeneration;
                mRightRoot.animate().setListener(null);
                mRoot.animate().cancel();
                mRightRoot.animate().cancel();
                mRoot.animate().alpha(1f).setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
                mRightRoot.animate().translationX(0f).setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
            rebuildState();
            rescheduleTimeout();
            return;
        }
        mShowing = true;
        mExitAnimating = false;
        setControllerVisible(true);
        rebuildState();
        mRoot.animate().cancel();
        mRightRoot.animate().cancel();
        mRoot.setAlpha(0f);
        mDialog.show();
        Window window = mDialog.getWindow();
        if (window != null) {
            // Dialog.show() re-attaches the DecorView and can restore theme dimensions after a
            // prior dismissal. Reassert the R2 full-screen composition surface on every show.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.FILL);
        }
        final int generation = ++mAnimationGeneration;
        startEnterAnimationWhenLaidOut(generation);
        sendAnnouncement(R.string.volume_dialog_panel_shown);
        rescheduleTimeout();
    }

    private void startEnterAnimationWhenLaidOut(int generation) {
        clearEnterPreDrawListener();
        mEnterPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!mShowing || mExitAnimating || generation != mAnimationGeneration) {
                    clearEnterPreDrawListener();
                    return true;
                }
                applyGeometry();
                // The first pass establishes the full-screen root. applyGeometry() then gives the
                // wrap-content R2 subtree its concrete dimensions, which requires one more pass.
                if (mGeometry == null || mRightRoot.getWidth() <= 0
                        || mRightRoot.getHeight() <= 0) {
                    return false;
                }
                clearEnterPreDrawListener();
                mRightRoot.setTranslationX(mGeometry.enterTranslation);
                mRoot.animate().alpha(1f).setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
                mRightRoot.animate().translationX(0f).setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
                return true;
            }
        };
        mRoot.getViewTreeObserver().addOnPreDrawListener(mEnterPreDrawListener);
        mRoot.requestLayout();
    }

    private void clearEnterPreDrawListener() {
        if (mEnterPreDrawListener == null || mRoot == null) return;
        ViewTreeObserver observer = mRoot.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(mEnterPreDrawListener);
        mEnterPreDrawListener = null;
    }

    private void dismiss(int reason) {
        if (!mShowing || mExitAnimating) return;
        clearEnterPreDrawListener();
        mExitAnimating = true;
        ++mAnimationGeneration;
        ++mMuteGeneration;
        mHandler.removeCallbacks(mTimeout);
        cancelBrightnessInteraction();
        if (mMuteTimer != null) mMuteTimer.stop();
        float translation = mGeometry != null ? mGeometry.enterTranslation : 0f;
        mRoot.animate().cancel();
        mRightRoot.animate().cancel();
        mRoot.animate().alpha(0f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        mRightRoot.animate().translationX(translation).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(2f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        mRightRoot.animate().setListener(null);
                        if (mDialog.isShowing()) mDialog.dismiss();
                    }
                }).start();
        sendAnnouncement(R.string.volume_dialog_panel_hidden);
    }

    private void setControllerVisible(boolean visible) {
        if (mVisibleNotified == visible) return;
        mVisibleNotified = visible;
        mController.notifyVisible(visible);
    }

    private void resetTransientUi() {
        ++mAnimationGeneration;
        ++mMuteGeneration;
        mMuteEditor = false;
        if (mRightRoot != null) {
            TransitionManager.endTransitions(mRightRoot);
            mRightRoot.animate().cancel();
        }
        if (mRoot != null) mRoot.animate().cancel();
        cancelBrightnessInteraction();
        if (mRinger != null) {
            mRinger.sliderTracking = false;
            mRinger.slider.abortTracking();
        }
        if (mMedia != null) {
            mMedia.sliderTracking = false;
            mMedia.slider.abortTracking();
        }
        if (mMuteIcon != null) {
            mMuteIcon.animate().cancel();
            mMuteIcon.setAlpha(1f);
            mMuteIcon.setVisibility(VISIBLE);
        }
        if (mMuteContent != null) {
            mMuteContent.animate().cancel();
            mMuteContent.setAlpha(0f);
            mMuteContent.setVisibility(GONE);
        }
        setCancelMutePressed(false);
        if (mMuteTimer != null) mMuteTimer.stop();
    }

    private void cancelBrightnessInteraction() {
        if (mBrightness != null && mBrightness.sliderTracking) {
            mBrightnessController.cancelInteraction(
                    mBrightness.startProgress, mBrightness.interactionGeneration);
            mBrightness.sliderTracking = false;
            mBrightness.slider.abortTracking();
        } else {
            mBrightnessController.invalidateInteractions();
            if (mBrightness != null) mBrightness.slider.abortTracking();
        }
    }

    private void rebuildState() {
        VolumeDialogState previous = mState;
        VolumeDialogState next = VolumeDialogState.from(mPlatformState, mUserTracker.getUserId(), mExpanded,
                isTimedMuteActive(), timedMuteDeadline(), resolveMediaRoute());
        if (mShowing && previous != null
                && previous.timedMute.active != next.timedMute.active) {
            beginPanelTransition();
        }
        if (previous != null && previous.activeSessionId != next.activeSessionId
                && (previous.activeSessionId != VolumeDialogController.State.NO_ACTIVE_STREAM
                        || next.activeSessionId
                                != VolumeDialogController.State.NO_ACTIVE_STREAM)) {
            // A slider gesture belongs to exactly one remote session. A replacement session must
            // never receive the old gesture's delayed UP/CANCEL write.
            if (mMedia != null) {
                mMedia.sliderTracking = false;
                mMedia.slider.abortTracking();
            }
            mLastAudibleLevels.clear();
        }
        mState = next;
        applyStateToViews(false);
    }

    private VolumeDialogState.Route resolveMediaRoute() {
        try {
            List<AudioDeviceAttributes> routed = mController.getAudioManager()
                    .getDevicesForAttributes(MEDIA_ATTRIBUTES);
            for (AudioDeviceAttributes device : routed) {
                switch (device.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    case AudioDeviceInfo.TYPE_HEARING_AID:
                        return VolumeDialogState.Route.BLUETOOTH_HEADSET;
                    case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case AudioDeviceInfo.TYPE_USB_DEVICE:
                        return VolumeDialogState.Route.WIRED_HEADSET;
                    default:
                        break;
                }
            }
        } catch (RuntimeException ignored) {
            // Android 16 stream state still supplies the Bluetooth route fail-safe.
        }
        return VolumeDialogState.Route.SPEAKER;
    }

    private void applyStateToViews(boolean animate) {
        if (mRoot == null || mState == null) return;
        configureBrightness();
        int middleStream = selectMiddleStream();
        configureAudioColumn(mRinger, middleStream, VolumeDialogState.columnForStream(
                middleStream, mState.streams));
        int mediaStream = selectMediaStream();
        VolumeDialogState.Column mediaLabel = mState.compactColumn == VolumeDialogState.Column.ACCESSIBILITY
                ? VolumeDialogState.Column.ACCESSIBILITY : VolumeDialogState.Column.MEDIA;
        configureAudioColumn(mMedia, mediaStream, mediaLabel);
        updateMuteVisual();
        updateColumnVisibility(animate);
        applyGeometry();
    }

    private void configureBrightness() {
        mBrightness.stream = -1;
        mBrightness.label = VolumeDialogState.Column.BRIGHTNESS;
        mBrightness.icon.setImageResource(mBrightnessGamma <= mBrightnessController.minGamma()
                ? R.drawable.ic_smartisan_volume_panel_brightness_close
                : mBrightnessGamma < mBrightnessController.maxGamma() / 2
                        ? R.drawable.ic_smartisan_volume_panel_brightness_small
                        : R.drawable.ic_smartisan_volume_panel_brightness_hight);
        setColumnEnabled(mBrightness, !mBrightnessUnavailable);
        String description = mBrightnessUnavailable
                ? mContext.getString(R.string.quick_settings_brightness_unable_adjust_msg)
                : mContext.getString(R.string.volume_dialog_brightness);
        mBrightness.icon.setContentDescription(description);
        mBrightness.slider.setContentDescription(description);
        mBrightness.value.setText(R.string.volume_dialog_brightness);
        final boolean active = mBrightnessGamma > mBrightnessController.minGamma();
        mBrightness.value.setTextColor(mContext.getColor(active
                ? R.color.volume_dialog_panel_text_active : R.color.volume_dialog_panel_text));
        mBrightness.slider.setMarkerStyle(active);
    }

    private int selectMiddleStream() {
        switch (mState.compactColumn) {
            case CALL:
                return mState.activeStream;
            case ALARM:
                return AudioManager.STREAM_ALARM;
            default:
                return AudioManager.STREAM_RING;
        }
    }

    private int selectMediaStream() {
        if (mState.compactColumn == VolumeDialogState.Column.ACCESSIBILITY) return mState.activeStream;
        if (mState.streams.get(mState.activeStream) != null
                && mState.streams.get(mState.activeStream).dynamic) return mState.activeStream;
        return AudioManager.STREAM_MUSIC;
    }

    private void configureAudioColumn(ColumnView column, int stream, VolumeDialogState.Column label) {
        if (column.stream != stream && column.sliderTracking) {
            column.sliderTracking = false;
            column.slider.abortTracking();
        }
        column.stream = stream;
        column.label = label;
        VolumeDialogController.StreamState state = mState.stream(stream);
        if (state == null) {
            setColumnEnabled(column, false);
            column.sliderTracking = false;
            column.slider.abortTracking();
            column.slider.setRange(0, 1);
            if (!column.sliderTracking) column.slider.setProgress(0);
            column.slider.setMarkerStyle(false);
            column.value.setText(null);
            column.icon.animate().cancel();
            column.icon.setTag(R.id.volume_dialog_icon, null);
            column.icon.setImageDrawable(null);
            column.icon.setContentDescription(null);
            column.slider.setContentDescription(null);
            return;
        }
        final boolean restricted = mState.isStreamRestricted(stream);
        if (restricted && column.sliderTracking) {
            column.sliderTracking = false;
            column.slider.abortTracking();
        }
        setColumnEnabled(column, !restricted);
        column.slider.setRange(state.levelMin, state.levelMax);
        if (!column.sliderTracking) column.slider.setProgress(state.level);
        column.value.setText(labelFor(label));
        if (state.level > state.levelMin && !state.muted && !restricted) {
            mLastAudibleLevels.put(lastAudibleKey(stream), state.level);
        }
        int icon = restricted ? iconForUnavailable(label) : iconFor(label, state);
        final boolean active = !restricted && !state.muted && state.level > state.levelMin;
        column.value.setTextColor(mContext.getColor(active
                ? R.color.volume_dialog_panel_text_active : R.color.volume_dialog_panel_text));
        column.slider.setMarkerStyle(active);
        crossFadeIcon(column.icon, icon);
        String description = restricted
                ? mContext.getString(R.string.volume_dialog_unavailable_by_dnd)
                : labelFor(label);
        column.icon.setContentDescription(description);
        column.slider.setContentDescription(description);
    }

    private void setColumnEnabled(ColumnView column, boolean enabled) {
        column.root.setEnabled(enabled);
        column.slider.setEnabled(enabled);
        column.icon.setEnabled(enabled);
        column.root.setAlpha(enabled ? 1f : .5f);
    }

    private int iconForUnavailable(VolumeDialogState.Column label) {
        switch (label) {
            case CALL:
                return R.drawable.ic_smartisan_volume_panel_call_close;
            case ALARM:
                return R.drawable.ic_smartisan_volume_panel_alarm_close;
            case RINGER:
                return R.drawable.ic_smartisan_volume_panel_ringtone_mute;
            case MEDIA:
            case ACCESSIBILITY:
            default:
                return R.drawable.ic_smartisan_volume_panel_media_close;
        }
    }

    private long lastAudibleKey(int stream) {
        return ((long) mUserTracker.getUserId() << 32) | (stream & 0xffffffffL);
    }

    private int iconFor(VolumeDialogState.Column label, VolumeDialogController.StreamState state) {
        boolean off = state.muted || state.level <= state.levelMin;
        switch (label) {
            case CALL:
                return off ? R.drawable.ic_smartisan_volume_panel_call_close
                        : R.drawable.ic_smartisan_volume_panel_call_open;
            case ALARM:
                return off ? R.drawable.ic_smartisan_volume_panel_alarm_close
                        : R.drawable.ic_smartisan_volume_panel_alarm_open;
            case RINGER:
                if (mState.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                    return R.drawable.ic_smartisan_volume_panel_vibrate;
                }
                if (off || mState.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    return R.drawable.ic_smartisan_volume_panel_ringtone_mute;
                }
                float fraction = (float) state.level / Math.max(1, state.levelMax);
                return fraction <= .33f ? R.drawable.ic_smartisan_volume_panel_ring_small
                        : fraction <= .66f ? R.drawable.ic_smartisan_volume_panel_ring_middle
                                : R.drawable.ic_smartisan_volume_panel_ring_high;
            case ACCESSIBILITY:
            case MEDIA:
            default:
                if (off) return R.drawable.ic_smartisan_volume_panel_media_close;
                if (mState.route == VolumeDialogState.Route.BLUETOOTH_HEADSET) {
                    return R.drawable.wireless_headset_active;
                }
                if (mState.route == VolumeDialogState.Route.WIRED_HEADSET) {
                    return R.drawable.headset_active;
                }
                return R.drawable.ic_smartisan_volume_panel_media_open;
        }
    }

    private String labelFor(VolumeDialogState.Column label) {
        switch (label) {
            case BRIGHTNESS: return mContext.getString(R.string.volume_dialog_brightness);
            case RINGER: return mContext.getString(R.string.volume_dialog_ring);
            case CALL: return mContext.getString(R.string.volume_dialog_call);
            case ALARM: return mContext.getString(R.string.volume_dialog_alarm);
            case ACCESSIBILITY: return mContext.getString(R.string.volume_dialog_accessibility);
            case MEDIA:
            default: return mContext.getString(R.string.volume_dialog_media);
        }
    }

    private void crossFadeIcon(ImageView view, int resource) {
        Object tag = view.getTag(R.id.volume_dialog_icon);
        if (tag instanceof Integer && ((Integer) tag) == resource) return;
        view.setTag(R.id.volume_dialog_icon, resource);
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(75).withEndAction(() -> {
            if (!Integer.valueOf(resource).equals(view.getTag(R.id.volume_dialog_icon))) return;
            view.setImageResource(resource);
            view.animate().alpha(1f).setDuration(75).start();
        }).start();
    }

    private void updateMuteVisual() {
        boolean muted = mState.timedMute.active;
        mMuteIcon.setImageResource(muted ? R.drawable.mute_active : R.drawable.mute_close);
        mMuteIcon.setContentDescription(mContext.getString(muted
                ? R.string.volume_dialog_unmute : R.string.volume_dialog_mute));
        boolean expandedMute = muted || mMuteEditor;
        mMuteShadow.setClickable(!expandedMute);
        mMuteShadow.setFocusable(!expandedMute);
        mMuteShadow.setContentDescription(expandedMute ? null
                : mContext.getString(R.string.volume_dialog_mute));
        mMuteIcon.animate().cancel();
        mMuteContent.animate().cancel();
        if (expandedMute) {
            mMuteIcon.setVisibility(GONE);
            mMuteContent.setVisibility(VISIBLE);
            mMuteContent.setAlpha(1f);
            if (muted) {
                mMuteTimer.showCountdown(mState.timedMute.deadlineMillis, readMuteDuration());
            } else {
                mMuteTimer.showSelector();
            }
        } else {
            mMuteTimer.stop();
            mMuteContent.setVisibility(GONE);
            mMuteContent.setAlpha(0f);
            mMuteIcon.setVisibility(VISIBLE);
            mMuteIcon.setAlpha(1f);
        }
        if (mShowing && mLastTimedMuteActive && !muted) {
            sendAnnouncement(R.string.volume_dialog_mute_finished);
        }
        mLastTimedMuteActive = muted;
    }

    private int readMuteDuration() {
        return mContext.getSharedPreferences(MUTE_UI_PREFERENCES, Context.MODE_PRIVATE)
                .getInt(MUTE_DURATION_PREFIX + mUserTracker.getUserId(), 900);
    }

    private void setCancelMutePressed(boolean pressed) {
        if (mMuteCancel == null) return;
        if (mMuteCancel.getBackground() instanceof TransitionDrawable) {
            TransitionDrawable background = (TransitionDrawable) mMuteCancel.getBackground();
            if (pressed) background.startTransition(200); else background.reverseTransition(200);
        }
        if (mMuteCancel.getDrawable() instanceof TransitionDrawable) {
            TransitionDrawable icon = (TransitionDrawable) mMuteCancel.getDrawable();
            if (pressed) icon.startTransition(200); else icon.reverseTransition(200);
        }
    }

    private void updateColumnVisibility(boolean animate) {
        if (mExpanded) {
            setColumnVisible(mBrightness, true, animate);
            setColumnVisible(mRinger, true, animate);
            setColumnVisible(mMedia, true, animate);
        } else {
            VolumeDialogState.Column active = mState.compactColumn;
            setColumnVisible(mBrightness, active == VolumeDialogState.Column.BRIGHTNESS, animate);
            setColumnVisible(mRinger, active == VolumeDialogState.Column.RINGER
                    || active == VolumeDialogState.Column.CALL
                    || active == VolumeDialogState.Column.ALARM, animate);
            setColumnVisible(mMedia, active == VolumeDialogState.Column.MEDIA
                    || active == VolumeDialogState.Column.ACCESSIBILITY, animate);
        }
        mExpandButton.setImageResource(mExpanded ? R.drawable.ic_smartisan_volume_new_close
                : R.drawable.ic_smartisan_volume_new_open);
        mExpandShadow.setContentDescription(mContext.getString(mExpanded
                ? R.string.volume_dialog_collapse : R.string.volume_dialog_expand));
        mMainGroup.setBackgroundResource(!mExpanded && isCompactActive()
                ? R.drawable.active_bg_blue : R.drawable.active_bg);
    }

    private boolean isCompactActive() {
        if (mState == null) return false;
        if (mState.compactColumn == VolumeDialogState.Column.BRIGHTNESS) {
            return mBrightnessGamma > mBrightnessController.minGamma();
        }
        final int stream = mState.compactColumn == VolumeDialogState.Column.CALL
                || mState.compactColumn == VolumeDialogState.Column.ALARM
                ? selectMiddleStream() : selectMediaStream();
        VolumeDialogController.StreamState state = mState.stream(stream);
        return state != null && !mState.isStreamRestricted(stream)
                && !state.muted && state.level > state.levelMin;
    }

    private void setColumnVisible(ColumnView column, boolean visible, boolean animate) {
        final int generation = ++column.animationGeneration;
        column.root.animate().cancel();
        final float visibleAlpha = column.root.isEnabled() ? 1f : .5f;
        if (!animate) {
            column.root.setAlpha(visible ? visibleAlpha : 0f);
            column.root.setVisibility(visible ? VISIBLE : GONE);
            return;
        }
        if (visible) {
            column.root.setVisibility(VISIBLE);
            column.root.animate().alpha(visibleAlpha).setDuration(100).start();
        } else {
            column.root.animate().alpha(0f).setDuration(100)
                    .withEndAction(() -> {
                        if (generation == column.animationGeneration) {
                            column.root.setVisibility(GONE);
                        }
                    }).start();
        }
    }

    private void applyGeometry() {
        if (mRoot == null || mRoot.getWidth() == 0 || mRoot.getHeight() == 0) return;
        WindowInsets windowInsets = mRoot.getRootWindowInsets();
        Insets insets = windowInsets == null ? Insets.NONE : windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        boolean landscape = mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mGeometry = VolumeDialogLayoutModel.calculate(mRoot.getWidth(), mRoot.getHeight(),
                mContext.getResources().getDisplayMetrics().density, insets, landscape);
        if (Float.compare(mBackgroundScale, mGeometry.scale) != 0) {
            mBackgroundScale = mGeometry.scale;
            mMainShadow.setPadding(mGeometry.shadowPaddingHorizontal,
                    mGeometry.shadowPaddingVertical, mGeometry.shadowPaddingHorizontal,
                    mGeometry.shadowPaddingVertical);
            mMuteShadow.setPadding(mGeometry.shadowPaddingHorizontal,
                    mGeometry.shadowPaddingVertical, mGeometry.shadowPaddingHorizontal,
                    mGeometry.shadowPaddingVertical);
            mExpandShadow.setPadding(mGeometry.shadowPaddingHorizontal,
                    mGeometry.shadowPaddingVertical, mGeometry.shadowPaddingHorizontal,
                    mGeometry.shadowPaddingVertical);
        }
        int columns = mExpanded ? 3 : 1;
        final boolean muteExpanded = mMuteEditor
                || (mState != null && mState.timedMute.active);
        final int shadowWidth = mGeometry.shadowPaddingHorizontal * 2;
        final int shadowHeight = mGeometry.shadowPaddingVertical * 2;
        FrameLayout.LayoutParams rootLp = (FrameLayout.LayoutParams) mRightRoot.getLayoutParams();
        rootLp.width = mGeometry.columnWidth * columns + shadowWidth
                + (muteExpanded ? mGeometry.muteEditorShift : 0);
        rootLp.height = mGeometry.expandTop + mGeometry.muteHeight + shadowHeight;
        rootLp.rightMargin = mGeometry.rightMargin;
        rootLp.topMargin = mGeometry.topMargin;
        mRightRoot.setLayoutParams(rootLp);

        FrameLayout.LayoutParams mainShadowLp =
                (FrameLayout.LayoutParams) mMainShadow.getLayoutParams();
        mainShadowLp.width = mGeometry.columnWidth * columns + shadowWidth;
        mainShadowLp.height = mGeometry.panelHeight + shadowHeight;
        mainShadowLp.topMargin = mGeometry.mainTop;
        mainShadowLp.rightMargin = muteExpanded ? mGeometry.muteEditorShift : 0;
        mainShadowLp.gravity = Gravity.TOP | Gravity.END;
        mMainShadow.setLayoutParams(mainShadowLp);

        FrameLayout.LayoutParams groupLp = (FrameLayout.LayoutParams) mMainGroup.getLayoutParams();
        groupLp.width = mGeometry.columnWidth * columns;
        groupLp.height = mGeometry.panelHeight;
        groupLp.topMargin = 0;
        groupLp.gravity = Gravity.FILL;
        mMainGroup.setLayoutParams(groupLp);

        layoutColumn(mBrightness, mExpanded ? 0 : compactOffset(mBrightness), mGeometry);
        layoutColumn(mRinger, mExpanded ? 1 : compactOffset(mRinger), mGeometry);
        layoutColumn(mMedia, mExpanded ? 2 : compactOffset(mMedia), mGeometry);

        FrameLayout.LayoutParams muteShadowLp =
                (FrameLayout.LayoutParams) mMuteShadow.getLayoutParams();
        muteShadowLp.width = mGeometry.columnWidth + shadowWidth;
        muteShadowLp.height = (muteExpanded ? mGeometry.panelHeight : mGeometry.muteHeight)
                + shadowHeight;
        muteShadowLp.topMargin = muteExpanded ? mGeometry.mainTop : 0;
        muteShadowLp.gravity = Gravity.TOP | Gravity.END;
        mMuteShadow.setLayoutParams(muteShadowLp);

        FrameLayout.LayoutParams muteLp = (FrameLayout.LayoutParams) mMuteCard.getLayoutParams();
        muteLp.width = mGeometry.columnWidth;
        muteLp.height = muteExpanded ? mGeometry.panelHeight : mGeometry.muteHeight;
        muteLp.topMargin = 0;
        muteLp.gravity = Gravity.FILL;
        mMuteCard.setLayoutParams(muteLp);

        FrameLayout.LayoutParams timerLp =
                (FrameLayout.LayoutParams) mMuteTimer.getLayoutParams();
        timerLp.width = mGeometry.columnWidth;
        timerLp.height = mGeometry.timerHeight;
        timerLp.gravity = Gravity.TOP;
        mMuteTimer.setLayoutParams(timerLp);

        FrameLayout.LayoutParams cancelLp =
                (FrameLayout.LayoutParams) mMuteCancel.getLayoutParams();
        cancelLp.width = mGeometry.columnWidth;
        cancelLp.height = mGeometry.cancelHeight;
        cancelLp.gravity = Gravity.BOTTOM;
        mMuteCancel.setLayoutParams(cancelLp);

        FrameLayout.LayoutParams expandShadowLp =
                (FrameLayout.LayoutParams) mExpandShadow.getLayoutParams();
        expandShadowLp.width = mGeometry.columnWidth + shadowWidth;
        expandShadowLp.height = mGeometry.muteHeight + shadowHeight;
        expandShadowLp.topMargin = mGeometry.expandTop;
        expandShadowLp.gravity = Gravity.TOP | Gravity.END;
        mExpandShadow.setLayoutParams(expandShadowLp);

        FrameLayout.LayoutParams expandLp =
                (FrameLayout.LayoutParams) mExpandButton.getLayoutParams();
        expandLp.width = mGeometry.columnWidth;
        expandLp.height = mGeometry.muteHeight;
        expandLp.topMargin = 0;
        expandLp.gravity = Gravity.FILL;
        mExpandButton.setLayoutParams(expandLp);
    }

    private int compactOffset(ColumnView column) {
        return 0;
    }

    private static void layoutColumn(ColumnView column, int index,
            VolumeDialogLayoutModel.Result geometry) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) column.root.getLayoutParams();
        lp.width = geometry.columnWidth;
        lp.height = geometry.panelHeight;
        lp.leftMargin = index * geometry.columnWidth;
        lp.gravity = Gravity.TOP | Gravity.START;
        column.root.setLayoutParams(lp);

        ViewGroup.LayoutParams valueLp = column.value.getLayoutParams();
        valueLp.height = Math.round(150f * geometry.scale);
        column.value.setLayoutParams(valueLp);
        column.value.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * geometry.scale);

        ViewGroup.LayoutParams iconLp = column.icon.getLayoutParams();
        iconLp.height = Math.round(126f * geometry.scale);
        column.icon.setLayoutParams(iconLp);
        column.slider.setSectionCount(16);
        column.slider.setMarkerSize(Math.round(24f * geometry.scale),
                Math.round(21f * geometry.scale));
    }

    private void cycleRingerMode() {
        if (mState == null || mState.isStreamRestricted(AudioManager.STREAM_RING)) return;
        int next;
        if (mState.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            next = mController.hasVibrator()
                    ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT;
        } else if (mState.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            next = AudioManager.RINGER_MODE_SILENT;
        } else {
            next = AudioManager.RINGER_MODE_NORMAL;
        }
        mController.setRingerMode(next, false);
        rescheduleTimeout();
    }

    private void toggleAudioColumn(ColumnView column) {
        if (column.stream < 0 || mState == null) return;
        if (mState.isStreamRestricted(column.stream)) return;
        VolumeDialogController.StreamState stream = mState.stream(column.stream);
        if (stream == null) return;
        int target = stream.muted || stream.level <= stream.levelMin
                ? Math.max(stream.levelMin + 1,
                        mLastAudibleLevels.get(lastAudibleKey(column.stream),
                                Math.max(1, stream.levelMax / 3)))
                : stream.levelMin;
        mController.setStreamVolume(column.stream, target, false);
        rescheduleTimeout();
    }

    private void rescheduleTimeout() {
        if (!mShowing) return;
        mHandler.removeCallbacks(mTimeout);
        long timeout = mAccessibility.isEnabled() ? TIMEOUT_ACCESSIBILITY
                : mExpanded || mMuteEditor || isTimedMuteActive()
                        ? TIMEOUT_EXPANDED : TIMEOUT_COMPACT;
        mHandler.postDelayed(mTimeout, timeout);
        mController.userActivity();
    }

    private void sendAnnouncement(int stringRes) {
        if (!mAccessibility.isEnabled()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(mContext.getPackageName());
        event.setClassName(getClass().getName());
        event.getText().add(mContext.getString(stringRes));
        mAccessibility.sendAccessibilityEvent(event);
    }

    private void showSafetyWarning(int flags) {
        if ((flags & (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_SHOW_UI_WARNINGS)) == 0
                && !mShowing) return;
        synchronized (mSafetyLock) {
            if (mSafetyWarning != null) return;
            mSafetyWarning = new SafetyWarningDialog(mContext, mController.getAudioManager()) {
                @Override protected void cleanUp() {
                    synchronized (mSafetyLock) { mSafetyWarning = null; }
                }
            };
            mSafetyWarning.show();
        }
        rescheduleTimeout();
    }

    private void showCsdWarning(int warning, int durationMs) {
        synchronized (mSafetyLock) {
            if (mCsdWarning != null) return;
            final int generation = ++mCsdGeneration;
            final CsdWarningDialog[] holder = new CsdWarningDialog[1];
            holder[0] = mCsdFactory.create(warning, () -> {
                synchronized (mSafetyLock) {
                    if (generation == mCsdGeneration && mCsdWarning == holder[0]) {
                        mCsdWarning = null;
                    }
                }
            }, Optional.of(Collections.emptyList()));
            mCsdWarning = holder[0];
            mCsdWarning.show();
            if (durationMs > 0) {
                mHandler.postDelayed(() -> {
                    synchronized (mSafetyLock) {
                        if (generation == mCsdGeneration && mCsdWarning == holder[0]) {
                            holder[0].dismiss();
                        }
                    }
                }, durationMs);
            }
        }
        rescheduleTimeout();
    }

    private final VolumeDialogController.Callbacks mControllerCallback =
            new VolumeDialogController.Callbacks() {
        @Override public void onShowRequested(int reason, boolean keyguardLocked,
                int lockTaskModeState) { show(reason); }
        @Override public void onDismissRequested(int reason) { dismiss(reason); }
        @Override public void onStateChanged(VolumeDialogController.State state) {
            mPlatformState = state == null ? null : state.copy();
            rebuildState();
        }
        @Override public void onLayoutDirectionChanged(int direction) {
            if (mRoot != null) mRoot.setLayoutDirection(direction);
        }
        @Override public void onConfigurationChanged() {
            resetTransientUi();
            applyGeometry();
            rebuildState();
        }
        @Override public void onShowVibrateHint() { rebuildState(); }
        @Override public void onShowSilentHint() { rebuildState(); }
        @Override public void onScreenOff() { dismiss(Events.DISMISS_REASON_SCREEN_OFF); }
        @Override public void onShowSafetyWarning(int flags) { showSafetyWarning(flags); }
        @Override public void onAccessibilityModeChanged(Boolean show) {
            rebuildState();
        }
        @Override public void onCaptionComponentStateChanged(Boolean enabled, Boolean tooltip) { }
        @Override public void onCaptionEnabledStateChanged(Boolean enabled, Boolean switchState) { }
        @Override public void onShowCsdWarning(int warning, int durationMs) {
            showCsdWarning(warning, durationMs);
        }
        @Override public void onVolumeChangedFromKey() { rescheduleTimeout(); }
    };

    private static final class ColumnView {
        final View root;
        final TextView value;
        final VerticalSeekBar slider;
        final ImageView icon;
        int stream = -1;
        VolumeDialogState.Column label;
        boolean sliderTracking;
        int startProgress;
        int animationGeneration;
        int interactionGeneration;

        ColumnView(View root) {
            this.root = root;
            value = root.findViewById(R.id.volume_dialog_level);
            slider = root.findViewById(R.id.volume_dialog_slider);
            icon = root.findViewById(R.id.volume_dialog_icon);
        }
    }
}
