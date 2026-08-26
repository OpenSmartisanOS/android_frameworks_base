/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.Notification.EXTRA_BUILDER_APPLICATION_INFO;
import static android.app.NotificationChannel.SYSTEM_RESERVED_IDS;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MAX;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.service.notification.Adjustment.KEY_CONTEXTUAL_ACTIONS;
import static android.service.notification.Adjustment.KEY_SUMMARIZATION;
import static android.service.notification.Adjustment.KEY_TEXT_REPLIES;
import static android.service.notification.Adjustment.KEY_TYPE;

import static com.android.app.animation.Interpolators.FAST_OUT_SLOW_IN;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Flags;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.Annotation;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.collection.EntryAdapter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.promoted.domain.interactor.PackageDemotionInteractor;
import com.android.systemui.statusbar.notification.row.icon.AppIconProvider;
import com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProvider;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationInfo extends LinearLayout implements NotificationGuts.GutsContent {
    private static final String TAG = "InfoGuts";
    private int mActualHeight;

    private TextView mPriorityDescriptionView;
    private TextView mSilentDescriptionView;
    private TextView mAutomaticDescriptionView;

    private RadioButton mSilent;
    private RadioButton mBlock;
    private RadioButton mReset;
    private SeekBar mImportanceSeekBar;
    private TextView mImportanceTitle;
    private TextView mImportanceSummary;
    protected INotificationManager mINotificationManager;
    private AppIconProvider mAppIconProvider;
    private NotificationIconStyleProvider mIconStyleProvider;
    protected OnUserInteractionCallback mOnUserInteractionCallback;
    private PackageManager mPm;
    private MetricsLogger mMetricsLogger;
    private ChannelEditorDialogController mChannelEditorDialogController;
    private AssistantFeedbackController mAssistantFeedbackController;

    private String mPackageName;
    protected String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    protected NotificationChannel mSingleNotificationChannel;
    private int mStartingChannelImportance;
    private boolean mWasShownHighPriority;
    private boolean mPressedApply;
    private boolean mPresentingChannelEditorDialog = false;
    private boolean mShowAutomaticSetting;

    /**
     * The last importance level chosen by the user.  Null if the user has not chosen an importance
     * level; non-null once the user takes an action which indicates an explicit preference.
     */
    @Nullable
    private Integer mChosenImportance;
    private boolean mIsAutomaticChosen;
    private boolean mIsSingleDefaultChannel;
    private boolean mIsNonblockable;
    protected boolean mIsDismissable;
    protected NotificationEntry mEntry;
    protected StatusBarNotification mSbn;
    private NotificationListenerService.Ranking mRanking;
    protected EntryAdapter mEntryAdapter;
    private boolean mIsDeviceProvisioned;
    private boolean mIsSystemRegisteredCall;

    private OnSettingsClickListener mOnSettingsClickListener;
    private OnAppSettingsClickListener mAppSettingsClickListener;
    private OnFeedbackClickListener mFeedbackClickListener;
    protected NotificationGuts mGutsContainer;
    private Drawable mPkgIcon;
    private UiEventLogger mUiEventLogger;

    @VisibleForTesting
    boolean mSkipPost = false;

    // used by standard ui
    private final OnClickListener mOnAutomatic = v -> {
        mIsAutomaticChosen = true;
        applyAlertingBehavior(BEHAVIOR_AUTOMATIC, true /* userTriggered */);
    };

    // used by standard ui
    private final OnClickListener mOnAlert = v -> {
        mChosenImportance = IMPORTANCE_DEFAULT;
        mIsAutomaticChosen = false;
        applyAlertingBehavior(BEHAVIOR_ALERTING, true /* userTriggered */);
    };

    // used by standard ui
    private final OnClickListener mOnSilent = v -> {
        mChosenImportance = IMPORTANCE_LOW;
        mIsAutomaticChosen = false;
        applyAlertingBehavior(BEHAVIOR_SILENT, true /* userTriggered */);
    };

    // used by standard ui
    protected final OnClickListener mOnDismissSettings = v -> {
        mPressedApply = true;
        mGutsContainer.closeControls(v, /* save= */ true);
    };
    protected OnClickListener mOnCloseClickListener;

    public NotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mPriorityDescriptionView = findViewById(R.id.alert_summary);
        mSilentDescriptionView = findViewById(R.id.silence_summary);
        mAutomaticDescriptionView = findViewById(R.id.automatic_summary);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public interface OnAppSettingsClickListener {
        void onClick(View v, Intent intent);
    }

    public interface OnFeedbackClickListener {
        void onClick(View v, Intent intent);
    }

    public void bindNotification(
            PackageManager pm,
            INotificationManager iNotificationManager,
            AppIconProvider appIconProvider,
            NotificationIconStyleProvider iconStyleProvider,
            OnUserInteractionCallback onUserInteractionCallback,
            ChannelEditorDialogController channelEditorDialogController,
            PackageDemotionInteractor packageDemotionInteractor,
            String pkg,
            NotificationListenerService.Ranking ranking,
            StatusBarNotification sbn,
            NotificationEntry entry,
            EntryAdapter entryAdapter,
            OnSettingsClickListener onSettingsClick,
            OnAppSettingsClickListener onAppSettingsClick,
            OnFeedbackClickListener onFeedbackClickListener,
            UiEventLogger uiEventLogger,
            boolean isDeviceProvisioned,
            boolean isNonblockable,
            boolean isDismissable,
            boolean wasShownHighPriority,
            AssistantFeedbackController assistantFeedbackController,
            MetricsLogger metricsLogger,
            OnClickListener onCloseClick)
            throws RemoteException {
        mINotificationManager = iNotificationManager;
        mAppIconProvider = appIconProvider;
        mIconStyleProvider = iconStyleProvider;
        mMetricsLogger = metricsLogger;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mChannelEditorDialogController = channelEditorDialogController;
        mAssistantFeedbackController = assistantFeedbackController;
        mPackageName = pkg;
        mSbn = sbn;
        mRanking = ranking;
        mEntry = entry;
        mEntryAdapter = entryAdapter;
        mPm = pm;
        mAppSettingsClickListener = onAppSettingsClick;
        mFeedbackClickListener = onFeedbackClickListener;
        mAppName = mPackageName;
        mOnSettingsClickListener = onSettingsClick;
        mSingleNotificationChannel = ranking.getChannel();
        mStartingChannelImportance = mSingleNotificationChannel.getImportance();
        mWasShownHighPriority = wasShownHighPriority;
        mIsNonblockable = isNonblockable;
        mIsDismissable = isDismissable;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;
        mShowAutomaticSetting = mAssistantFeedbackController.isFeedbackEnabled();
        mUiEventLogger = uiEventLogger;
        mOnCloseClickListener = onCloseClick;

        mIsSystemRegisteredCall = mSbn.getNotification().isStyle(Notification.CallStyle.class)
                && mINotificationManager.isInCall(mSbn.getPackageName(), mSbn.getUid());

        int numTotalChannels = mINotificationManager.getNumNotificationChannelsForPackage(
                pkg, mAppUid, false /* includeDeleted */);
        mIsSingleDefaultChannel = mSingleNotificationChannel.getId().equals(
                NotificationChannel.DEFAULT_CHANNEL_ID) && numTotalChannels == 1;
        mIsAutomaticChosen = getAlertingBehavior() == BEHAVIOR_AUTOMATIC;

        bindHeader();
        bindInlineControls();

        logUiEvent(NotificationControlsEvent.NOTIFICATION_CONTROLS_OPEN);
        mMetricsLogger.write(notificationControlsLogMaker());
    }

    /** Binds the Android 11 Smartisan controls while retaining the channel-level A16 backend. */
    protected void bindInlineControls() {
        mSilent = findViewById(R.id.sos_silent_importance);
        mBlock = findViewById(R.id.sos_block_importance);
        mReset = findViewById(R.id.sos_reset_importance);
        mImportanceSeekBar = findViewById(R.id.sos_importance_seekbar);
        mImportanceTitle = findViewById(R.id.sos_importance_title);
        mImportanceSummary = findViewById(R.id.sos_importance_summary);

        final View cantChange = findViewById(R.id.sos_cant_change_importance);
        final View importanceControls = findViewById(R.id.sos_importance_controls);
        final View importanceButtons = findViewById(R.id.sos_importance_buttons);
        final View importanceSlider = findViewById(R.id.sos_importance_slider);
        final boolean canChange = !mIsNonblockable && !mIsSystemRegisteredCall;
        cantChange.setVisibility(canChange ? GONE : VISIBLE);
        importanceControls.setVisibility(canChange ? VISIBLE : GONE);
        // R2 exposed the full 0..5 control behind the same tuner key and otherwise showed the
        // compact silent/block/automatic radios. Both presentations commit through the modern
        // channel API below.
        final boolean showSlider = Settings.Secure.getInt(getContext().getContentResolver(),
                "show_importance_slider", 0) == 1;
        importanceButtons.setVisibility(showSlider ? GONE : VISIBLE);
        importanceSlider.setVisibility(showSlider ? VISIBLE : GONE);

        mSilent.setOnClickListener(v -> selectImportance(IMPORTANCE_LOW, false));
        mBlock.setOnClickListener(v -> selectImportance(IMPORTANCE_NONE, false));
        mReset.setOnClickListener(v -> selectImportance(
                mStartingChannelImportance, true));
        mImportanceSeekBar.setMax(IMPORTANCE_MAX);
        mImportanceSeekBar.setMin(IMPORTANCE_NONE);
        mImportanceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateImportanceDescription(progress, false);
                if (fromUser) {
                    mChosenImportance = progress;
                    mIsAutomaticChosen = false;
                    mReset.setChecked(false);
                    mSilent.setChecked(progress == IMPORTANCE_LOW);
                    mBlock.setChecked(progress == IMPORTANCE_NONE);
                    mGutsContainer.resetFalsingCheck();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mGutsContainer.resetFalsingCheck();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        final boolean automatic = !mSingleNotificationChannel.hasUserSetImportance();
        selectImportance(mStartingChannelImportance, automatic);
        findViewById(R.id.sos_auto_importance).setOnClickListener(v ->
                selectImportance(mStartingChannelImportance, !mIsAutomaticChosen));

        final View done = findViewById(R.id.done);
        done.setOnClickListener(mOnDismissSettings);
        done.setAccessibilityDelegate(mGutsContainer.getAccessibilityDelegate());
    }

    private void selectImportance(int importance, boolean automatic) {
        final int bounded = Math.max(IMPORTANCE_NONE, Math.min(IMPORTANCE_MAX, importance));
        mIsAutomaticChosen = automatic;
        mChosenImportance = bounded;
        mImportanceSeekBar.setEnabled(!automatic);
        mImportanceSeekBar.setAlpha(automatic ? 0.5f : 1f);
        mImportanceSeekBar.setProgress(bounded);
        mReset.setChecked(automatic || (bounded != IMPORTANCE_LOW
                && bounded != IMPORTANCE_NONE));
        mSilent.setChecked(!automatic && bounded == IMPORTANCE_LOW);
        mBlock.setChecked(!automatic && bounded == IMPORTANCE_NONE);
        updateImportanceDescription(bounded, automatic);
        mGutsContainer.resetFalsingCheck();
    }

    private void updateImportanceDescription(int importance, boolean automatic) {
        if (automatic) {
            mImportanceTitle.setText(R.string.sos_notification_importance_automatic_title);
            mImportanceSummary.setText(R.string.sos_notification_importance_automatic_summary);
            return;
        }
        switch (importance) {
            case IMPORTANCE_NONE:
                mImportanceTitle.setText(R.string.sos_notification_importance_level_0);
                mImportanceSummary.setText(R.string.sos_notification_importance_summary_0);
                break;
            case 1:
                mImportanceTitle.setText(R.string.sos_notification_importance_level_1);
                mImportanceSummary.setText(R.string.sos_notification_importance_summary_1);
                break;
            case IMPORTANCE_LOW:
                mImportanceTitle.setText(R.string.sos_notification_importance_level_2);
                mImportanceSummary.setText(R.string.sos_notification_importance_summary_2);
                break;
            case IMPORTANCE_DEFAULT:
                mImportanceTitle.setText(R.string.sos_notification_importance_level_3);
                mImportanceSummary.setText(R.string.sos_notification_importance_summary_3);
                break;
            case 4:
                mImportanceTitle.setText(R.string.sos_notification_importance_level_4);
                mImportanceSummary.setText(R.string.sos_notification_importance_summary_4);
                break;
            default:
                mImportanceTitle.setText(R.string.sos_notification_importance_level_5);
                mImportanceSummary.setText(R.string.sos_notification_importance_summary_5);
        }
    }

    @SuppressLint("WrongThread")
    private void bindHeader() {
        mPkgIcon = null;
        // filled in if missing during notification inflation, which must have happened if
        // we have a notification to long press on
        ApplicationInfo info =
                mSbn.getNotification().extras.getParcelable(EXTRA_BUILDER_APPLICATION_INFO,
                        ApplicationInfo.class);
        if (info != null) {
            try {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
                mPkgIcon = mPm.getApplicationIcon(info);
            } catch (Exception ignored) {
            }
        }
        if (mPkgIcon == null) {
            // app is gone, just show package name and generic icon
            mPkgIcon = mPm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkg_icon)).setImageDrawable(mPkgIcon);
        ((TextView) findViewById(R.id.pkg_name)).setText(mAppName);

        // Delegate
        bindDelegate();


        findViewById(R.id.feedback).setVisibility(GONE);
        findViewById(R.id.app_settings).setVisibility(GONE);

        // System Settings button.
        final View settingsButton = findViewById(R.id.info);
        settingsButton.setOnClickListener(getSettingsOnClickListener());
        settingsButton.setVisibility(settingsButton.hasOnClickListeners() ? VISIBLE : GONE);
    }

    private void bindFeedback() {
        View feedbackButton = findViewById(R.id.feedback);
        if (feedbackButton == null) {
            return;
        }
        Intent intent = getAssistantFeedbackIntent(
                mINotificationManager, mPm, mSbn.getKey(), mRanking);
        if ((!android.app.Flags.notificationClassificationUi() &&
                !com.android.systemui.Flags.notificationAnimatedActionsTreatment())
                 || intent == null) {
            feedbackButton.setVisibility(GONE);
        } else {
            feedbackButton.setVisibility(VISIBLE);
            feedbackButton.setOnClickListener((View v) -> {
                if (mFeedbackClickListener != null) {
                    mFeedbackClickListener.onClick(v, intent);
                }
            });
        }
    }
    private static boolean isAnimatedReply(CharSequence choice) {
        if (choice instanceof Spanned) {
            Spanned spanned = (Spanned) choice;
            Annotation[] annotations = spanned.getSpans(0, choice.length(), Annotation.class);
            if (annotations != null) { // Add null check
                for (Annotation annotation : annotations) {
                    if ("isAnimatedReply".equals(annotation.getKey())
                            && "1".equals(annotation.getValue())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    public static Intent getAssistantFeedbackIntent(INotificationManager inm, PackageManager pm,
            String key, NotificationListenerService.Ranking ranking) {
        try {
            ComponentName assistant = inm.getAllowedNotificationAssistant();
            if (assistant == null) {
                return null;
            }
            Intent intent = new Intent(
                    NotificationAssistantService.ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS)
                    .setPackage(assistant.getPackageName());
            final List<ResolveInfo> resolveInfos = pm.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
            );
            if (resolveInfos == null || resolveInfos.size() == 0 || resolveInfos.get(0) == null) {
                return null;
            }
            final ActivityInfo activityInfo = resolveInfos.get(0).activityInfo;
            intent.setClassName(activityInfo.packageName, activityInfo.name);

            intent.putExtra(NotificationAssistantService.EXTRA_NOTIFICATION_KEY, key);
            if (ranking.getSummarization() != null ||
                    SYSTEM_RESERVED_IDS.contains(ranking.getChannel().getId())) {
                intent.putExtra(NotificationAssistantService.EXTRA_NOTIFICATION_ADJUSTMENT,
                        ranking.getSummarization() != null
                        ? KEY_SUMMARIZATION
                        : KEY_TYPE);
            }
            ArrayList<String> keys = new ArrayList<>();
            NotificationChannel channel = ranking.getChannel(); // Get channel from ranking

            // Check for summarization
            if (!TextUtils.isEmpty(ranking.getSummarization())) {
                keys.add(KEY_SUMMARIZATION);
            }

            // Check if it's a reserved system channel type
            if (channel != null && SYSTEM_RESERVED_IDS.contains(channel.getId())) {
                keys.add(KEY_TYPE);
            }

            // Check for animated smart actions
            List<Notification.Action> smartActions = ranking.getSmartActions();
            if (smartActions != null) {
                for (Notification.Action action : smartActions) {
                    if (action != null && action.getExtras() != null &&
                            action.getExtras().getBoolean(Notification.Action.EXTRA_IS_ANIMATED,
                                    false)) {
                        keys.add(KEY_CONTEXTUAL_ACTIONS);
                        break;
                    }
                }
            }

            // Check for animated smart replies
            List<CharSequence> smartReplies = ranking.getSmartReplies();
            if (smartReplies != null) {
                for (CharSequence reply : smartReplies) {
                    if (isAnimatedReply(reply)) {
                        keys.add(KEY_TEXT_REPLIES);
                        break;
                    }
                }
            }

            if (!keys.isEmpty()) {
                intent.putStringArrayListExtra(
                        NotificationAssistantService.EXTRA_NOTIFICATION_ADJUSTMENTS, keys);
            }

            return intent;
        } catch (Exception e) {
            Slog.d(TAG, "no assistant?", e);
            return null;
        }
    }

    private OnClickListener getSettingsOnClickListener() {
        if (mAppUid >= 0 && mOnSettingsClickListener != null && mIsDeviceProvisioned) {
            final int appUidF = mAppUid;
            return ((View view) -> {
                mOnSettingsClickListener.onClick(view, mSingleNotificationChannel, appUidF);
            });
        }
        return null;
    }

    OnClickListener getTurnOffNotificationsClickListener(NotificationChannel channel) {
        return ((View view) -> {
            if (!mPresentingChannelEditorDialog && mChannelEditorDialogController != null) {
                mPresentingChannelEditorDialog = true;

                mChannelEditorDialogController.prepareDialogForApp(mAppName, mPackageName, mAppUid,
                        channel, mPkgIcon, mOnSettingsClickListener);
                mChannelEditorDialogController.setOnFinishListener(() -> {
                    mPresentingChannelEditorDialog = false;
                    mGutsContainer.closeControls(this, false);
                });
                mChannelEditorDialogController.show();
            }
        });
    }

    private void bindChannelDetails() throws RemoteException {
        bindName();
        bindGroup();
    }

    private void bindName() {
        final TextView channelName = findViewById(R.id.channel_name);
        if (mIsSingleDefaultChannel) {
            channelName.setVisibility(View.GONE);
        } else {
            channelName.setText(mSingleNotificationChannel.getName());
        }
    }

    private void bindDelegate() {
        TextView delegateView = findViewById(R.id.delegate_name);

        CharSequence delegatePkg = null;
        if (!TextUtils.equals(mPackageName, mDelegatePkg)) {
            // this notification was posted by a delegate!
            delegateView.setVisibility(View.VISIBLE);
        } else {
            delegateView.setVisibility(View.GONE);
        }
    }

    private void bindGroup() throws RemoteException {
        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (mSingleNotificationChannel != null && mSingleNotificationChannel.getGroup() != null) {
            final NotificationChannelGroup notificationChannelGroup =
                    mINotificationManager.getNotificationChannelGroupForPackage(
                            mSingleNotificationChannel.getGroup(), mPackageName, mAppUid);
            if (notificationChannelGroup != null) {
                groupName = notificationChannelGroup.getName();
            }
        }
        TextView groupNameView = findViewById(R.id.group_name);
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(VISIBLE);
        } else {
            groupNameView.setVisibility(GONE);
        }
    }

    private void saveImportance() {
        if (!mIsNonblockable) {
            if (mChosenImportance == null) {
                mChosenImportance = mStartingChannelImportance;
            }
            updateImportance();
        }
    }

    /**
     * Commits the updated importance values on the background thread.
     */
    private void updateImportance() {
        if (mChosenImportance != null) {
            logUiEvent(NotificationControlsEvent.NOTIFICATION_CONTROLS_SAVE_IMPORTANCE);
            mMetricsLogger.write(importanceChangeLogMaker());

            int newImportance = mChosenImportance;

            Handler bgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
            bgHandler.post(
                    new UpdateImportanceRunnable(mINotificationManager, mPackageName, mAppUid,
                            mSingleNotificationChannel,
                            mStartingChannelImportance, newImportance, mIsAutomaticChosen));
            if (NotificationBundleUi.isEnabled()) {
                mEntryAdapter.onImportanceChanged();
            } else {
                mOnUserInteractionCallback.onImportanceChanged(mEntry);
            }
        }
    }

    @Override
    public boolean post(Runnable action) {
        if (mSkipPost) {
            action.run();
            return true;
        } else {
            return super.post(action);
        }
    }

    private void applyAlertingBehavior(@AlertingBehavior int behavior, boolean userTriggered) {
        if (userTriggered) {
            TransitionSet transition = new TransitionSet();
            transition.setOrdering(TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(new Fade(Fade.OUT))
                    .addTransition(new ChangeBounds())
                    .addTransition(
                            new Fade(Fade.IN)
                                    .setStartDelay(150)
                                    .setDuration(200)
                                    .setInterpolator(FAST_OUT_SLOW_IN));
            transition.setDuration(350);
            transition.setInterpolator(FAST_OUT_SLOW_IN);
            TransitionManager.beginDelayedTransition(this, transition);
        }

        View alert = findViewById(R.id.alert);
        View silence = findViewById(R.id.silence);
        View automatic = findViewById(R.id.automatic);

        switch (behavior) {
            case BEHAVIOR_ALERTING:
                mPriorityDescriptionView.setVisibility(VISIBLE);
                mSilentDescriptionView.setVisibility(GONE);
                mAutomaticDescriptionView.setVisibility(GONE);
                post(() -> {
                    alert.setSelected(true);
                    silence.setSelected(false);
                    automatic.setSelected(false);
                });
                break;

            case BEHAVIOR_SILENT:
                mSilentDescriptionView.setVisibility(VISIBLE);
                mPriorityDescriptionView.setVisibility(GONE);
                mAutomaticDescriptionView.setVisibility(GONE);
                post(() -> {
                    alert.setSelected(false);
                    silence.setSelected(true);
                    automatic.setSelected(false);
                });
                break;

            case BEHAVIOR_AUTOMATIC:
                mAutomaticDescriptionView.setVisibility(VISIBLE);
                mPriorityDescriptionView.setVisibility(GONE);
                mSilentDescriptionView.setVisibility(GONE);
                post(() -> {
                    automatic.setSelected(true);
                    alert.setSelected(false);
                    silence.setSelected(false);
                });
                break;

            default:
                throw new IllegalArgumentException("Unrecognized alerting behavior: " + behavior);
        }

        boolean isAChange = getAlertingBehavior() != behavior;
        TextView done = findViewById(R.id.done);
        done.setText(isAChange
                ? R.string.inline_ok_button
                : R.string.inline_done_button);
    }

    @Override
    public void onFinishedClosing() {
        bindInlineControls();

        logUiEvent(NotificationControlsEvent.NOTIFICATION_CONTROLS_CLOSE);
        mMetricsLogger.write(notificationControlsLogMaker().setType(MetricsEvent.TYPE_CLOSE));
    }

    @Override
    public boolean needsFalsingProtection() {
        return true;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null &&
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (mGutsContainer.isExposed()) {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_opened_accessibility, mAppName));
            } else {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_closed_accessibility, mAppName));
            }
        }
    }

    private Intent getAppSettingsIntent(PackageManager pm, String packageName,
            NotificationChannel channel, int id, String tag) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)
                .setPackage(packageName);
        final List<ResolveInfo> resolveInfos = pm.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (resolveInfos == null || resolveInfos.isEmpty() || resolveInfos.get(0) == null) {
            return null;
        }
        final ActivityInfo activityInfo = resolveInfos.get(0).activityInfo;
        intent.setClassName(activityInfo.packageName, activityInfo.name);
        if (channel != null) {
            intent.putExtra(Notification.EXTRA_CHANNEL_ID, channel.getId());
        }
        intent.putExtra(Notification.EXTRA_NOTIFICATION_ID, id);
        intent.putExtra(Notification.EXTRA_NOTIFICATION_TAG, tag);
        return intent;
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean willBeRemoved() {
        return false;
    }

    @Override
    public boolean shouldBeSavedOnClose() {
        return mPressedApply;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (mPresentingChannelEditorDialog && mChannelEditorDialogController != null) {
            mPresentingChannelEditorDialog = false;
            // No need for the finish listener because we're closing
            mChannelEditorDialogController.setOnFinishListener(null);
            mChannelEditorDialogController.close();
        }

        // Save regardless of the importance so we can lock the importance field if the user wants
        // to keep getting notifications
        if (save) {
            saveImportance();
        }

        // Clear the selected importance when closing, so when when we open again,
        // we starts from a clean state.
        mChosenImportance = null;
        mPressedApply = false;

        return false;
    }

    @Override
    public int getActualHeight() {
        // Because we're animating the bounds, getHeight will return the small height at the
        // beginning of the animation. Instead we'd want it to already return the end value
        return mActualHeight;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mActualHeight = getHeight();
    }

    @VisibleForTesting
    public boolean isAnimating() {
        return false;
    }

    /**
     * Runnable to either update the given channel (with a new importance value) or, if no channel
     * is provided, update notifications enabled state for the package.
     */
    private static class UpdateImportanceRunnable implements Runnable {
        private final INotificationManager mINotificationManager;
        private final String mPackageName;
        private final int mAppUid;
        private final @Nullable NotificationChannel mChannelToUpdate;
        private final int mCurrentImportance;
        private final int mNewImportance;
        private final boolean mUnlockImportance;


        public UpdateImportanceRunnable(INotificationManager notificationManager,
                String packageName, int appUid, @Nullable NotificationChannel channelToUpdate,
                int currentImportance, int newImportance, boolean unlockImportance) {
            mINotificationManager = notificationManager;
            mPackageName = packageName;
            mAppUid = appUid;
            mChannelToUpdate = channelToUpdate;
            mCurrentImportance = currentImportance;
            mNewImportance = newImportance;
            mUnlockImportance = unlockImportance;
        }

        @Override
        public void run() {
            try {
                if (mChannelToUpdate != null) {
                    if (mUnlockImportance) {
                        mINotificationManager.unlockNotificationChannel(
                                mPackageName, mAppUid, mChannelToUpdate.getId());
                    } else {
                        mChannelToUpdate.setImportance(mNewImportance);
                        mChannelToUpdate.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
                        mINotificationManager.updateNotificationChannelForPackage(
                                mPackageName, mAppUid, mChannelToUpdate);
                    }
                } else {
                    // For notifications with more than one channel, update notification enabled
                    // state. If the importance was lowered, we disable notifications.
                    mINotificationManager.setNotificationsEnabledWithImportanceLockForPackage(
                            mPackageName, mAppUid, mNewImportance >= mCurrentImportance);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to update notification importance", e);
            }
        }
    }

    private void logUiEvent(NotificationControlsEvent event) {
        if (mSbn != null) {
            mUiEventLogger.logWithInstanceId(event,
                    mSbn.getUid(), mSbn.getPackageName(), mSbn.getInstanceId());
        }
    }

    /**
     * Returns a LogMaker with all available notification information.
     * Caller should set category, type, and maybe subtype, before passing it to mMetricsLogger.
     *
     * @return LogMaker
     */
    private LogMaker getLogMaker() {
        // The constructor requires a category, so also do it in the other branch for consistency.
        return mSbn == null ? new LogMaker(MetricsEvent.NOTIFICATION_BLOCKING_HELPER)
                : mSbn.getLogMaker().setCategory(MetricsEvent.NOTIFICATION_BLOCKING_HELPER);
    }

    /**
     * Returns an initialized LogMaker for logging importance changes.
     * The caller may override the type before passing it to mMetricsLogger.
     *
     * @return LogMaker
     */
    private LogMaker importanceChangeLogMaker() {
        int chosenImportance =
                mChosenImportance != null ? mChosenImportance : mStartingChannelImportance;
        return getLogMaker().setCategory(MetricsEvent.ACTION_SAVE_IMPORTANCE)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(chosenImportance - mStartingChannelImportance);
    }

    /**
     * Returns an initialized LogMaker for logging open/close of the info display.
     * The caller may override the type before passing it to mMetricsLogger.
     *
     * @return LogMaker
     */
    private LogMaker notificationControlsLogMaker() {
        return getLogMaker().setCategory(MetricsEvent.ACTION_NOTE_CONTROLS)
                .setType(MetricsEvent.TYPE_OPEN)
                .setSubtype(MetricsEvent.BLOCKING_HELPER_UNKNOWN);
    }

    private @AlertingBehavior int getAlertingBehavior() {
        if (mShowAutomaticSetting && !mSingleNotificationChannel.hasUserSetImportance()) {
            return BEHAVIOR_AUTOMATIC;
        }
        return mWasShownHighPriority ? BEHAVIOR_ALERTING : BEHAVIOR_SILENT;
    }

    @Retention(SOURCE)
    @IntDef({BEHAVIOR_ALERTING, BEHAVIOR_SILENT, BEHAVIOR_AUTOMATIC})
    private @interface AlertingBehavior {
    }

    private static final int BEHAVIOR_ALERTING = 0;
    private static final int BEHAVIOR_SILENT = 1;
    private static final int BEHAVIOR_AUTOMATIC = 2;
}
