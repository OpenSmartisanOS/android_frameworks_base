/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * Hidden compatibility surface used by the original Smartisan notification clients.
 *
 * @hide
 */
public class NotificationSmtEx extends NotificationSmtBase {
    public static final String EXTRA_LEGACY_NOTIFICATION_ICON =
            "android.legacy.notificationicon";
    public static final String EXTRA_TICK_STAY_TIME =
            "smartisanos.extra.notification_tick_stay_time";
    public static final int FLAG_SMT_NOT_IN_NOTICE = 0x01000000;
    public static final int FLAG_SMT_HOLD_IN_HEADSUP = 0x02000000;
    public static final int FLAG_SMT_PURE_IN_HEADSUP = 0x04000000;
    public static final int FLAG_SMT_NOT_CUSTOM = 0x08000000;
    public static final int FLAG_TOP_VISIBILITY = 0x10000000;
    public static final int FLAG_SMT_PUSH = 0x20000000;

    static String loadLabel(Context context, ApplicationInfo applicationInfo) {
        CharSequence label = applicationInfo.loadLabel(context.getPackageManager());
        if (TextUtils.isEmpty(label)) {
            final PackageManager packageManager = context.getPackageManager();
            final Intent launchIntent = packageManager.getLaunchIntentForPackage(
                    applicationInfo.packageName);
            if (launchIntent != null) {
                final List<ResolveInfo> matches = packageManager.queryIntentActivities(
                        launchIntent, 0);
                if (matches != null) {
                    for (ResolveInfo match : matches) {
                        final CharSequence resolvedLabel = match.loadLabel(packageManager);
                        if (!TextUtils.isEmpty(resolvedLabel)) {
                            label = resolvedLabel;
                            break;
                        }
                    }
                }
            }
            if (TextUtils.isEmpty(label)) {
                label = applicationInfo.packageName;
            }
        }
        try {
            return label.toString().replaceAll("\\p{C}", "")
                    .replaceAll("\\p{Z}", " ").trim();
        } catch (PatternSyntaxException ignored) {
            return label.toString();
        }
    }

    /** Original single-button style. */
    public static class SmartisanButtonStyle extends Notification.Style {
        private static final int MAX_ACTION_BUTTONS = 1;

        public SmartisanButtonStyle() {}

        public SmartisanButtonStyle(Notification.Builder builder) {
            setBuilder(builder);
        }

        private RemoteViews makeContentViewWithActions() {
            final RemoteViews result = getStandardView(
                    R.layout.smartisan_notification_template_base);
            final List<Notification.Action> actions = mBuilder.getSosActions();
            final int count = Math.min(actions.size(), MAX_ACTION_BUTTONS);
            result.removeAllViews(R.id.actions);
            result.setViewVisibility(R.id.actions, count == 0
                    ? android.view.View.GONE : android.view.View.VISIBLE);
            for (int i = 0; i < count; i++) {
                final Notification.Action action = actions.get(i);
                final RemoteViews button = mBuilder.newSosRemoteViews(
                        R.layout.smartisan_notification_action);
                final Icon icon = action.getIcon();
                if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
                    button.setInt(R.id.action0, "setBackgroundResource", icon.getResId());
                }
                button.setTextViewText(R.id.action0, action.title);
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
                button.setContentDescription(R.id.action0, action.title);
                result.addView(R.id.actions, button);
            }
            result.setViewLayoutMarginDimen(R.id.notification_header_line,
                    RemoteViews.MARGIN_END,
                    R.dimen.smartisan_button_notification_content_margin_end);
            result.setViewLayoutMarginDimen(R.id.notification_main_column,
                    RemoteViews.MARGIN_END,
                    R.dimen.smartisan_button_notification_content_margin_end);
            return result;
        }

        @Override
        public RemoteViews makeContentView() {
            return makeContentViewWithActions();
        }

        @Override
        public Notification buildStyled(Notification notification) {
            super.buildStyled(notification);
            notification.contentView = makeContentViewWithActions();
            return notification;
        }

        @Override
        public boolean areNotificationsVisiblyDifferent(Notification.Style other) {
            return other == null || getClass() != other.getClass();
        }
    }

    /** Original four-button alert layout. */
    public static class SmartisanDialogStyle extends Notification.Style {
        public static final String KEY_ENABLED = "button_enabled";
        public static final String KEY_TEXT_COLOR = "button_text_color";
        public static final int VALUE_TEXT_COLOR_DEFAULT = 0x99000000;
        private static final int MAX_ACTION_BUTTONS = 4;

        public SmartisanDialogStyle() {}

        public SmartisanDialogStyle(Notification.Builder builder) {
            setBuilder(builder);
        }

        @Override
        public RemoteViews makeExpandedContentView() {
            return makeBigContentView();
        }

        @Override
        public RemoteViews makeHeadsUpContentView() {
            return makeBigContentView();
        }

        /** Binary-compatible Android 11 Smartisan entry point. */
        public RemoteViews makeBigContentView() {
            return makeDialogView();
        }

        /** Binary-compatible Android 11 Smartisan entry point. */
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            return makeBigContentView();
        }

        @Override
        public Notification buildStyled(Notification notification) {
            super.buildStyled(notification);
            notification.contentView = makeBigContentView();
            return notification;
        }

        private RemoteViews makeDialogView() {
            final Context context = mBuilder.getSosContext();
            final Notification notification = mBuilder.getSosNotification();
            final RemoteViews result = mBuilder.newSosRemoteViews(
                    R.layout.smartisan_notification_template_dialog_alerts);
            result.setImageViewIcon(R.id.dialog_alerts_icon, notification.getSmallIcon());
            result.setTextViewText(R.id.dialog_alerts_title,
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE));
            result.setTextViewText(R.id.dialog_alerts_text,
                    notification.extras.getCharSequence(Notification.EXTRA_TEXT));
            final List<Notification.Action> actions = mBuilder.getSosActions();
            final int count = Math.min(actions.size(), MAX_ACTION_BUTTONS);
            result.removeAllViews(R.id.actions);
            for (int i = 0; i < count; i++) {
                final Notification.Action action = actions.get(i);
                final RemoteViews button = mBuilder.newSosRemoteViews(
                        R.layout.smartisan_notification_dialog_action);
                button.setBoolean(R.id.text_action0, "setEnabled",
                        action.getExtras().getBoolean(KEY_ENABLED, true));
                button.setTextViewText(R.id.text_action0, action.title);
                button.setTextColor(R.id.text_action0,
                        action.getExtras().getInt(KEY_TEXT_COLOR, VALUE_TEXT_COLOR_DEFAULT));
                button.setOnClickPendingIntent(R.id.text_action0, action.actionIntent);
                final int background = count == 1
                        ? R.drawable.selector_smartisan_notification_dialog_alerts_button_only_bg
                        : i == 0
                                ? R.drawable.selector_smartisan_notification_dialog_alerts_button_left_bg
                                : i == count - 1
                                        ? R.drawable.selector_smartisan_notification_dialog_alerts_button_right_bg
                                        : R.drawable.selector_smartisan_notification_dialog_alerts_button_medium_bg;
                button.setInt(R.id.text_action0, "setBackgroundResource", background);
                result.addView(R.id.actions, button);
            }
            return result;
        }

        @Override
        public boolean areNotificationsVisiblyDifferent(Notification.Style other) {
            return other == null || getClass() != other.getClass();
        }
    }

    /** Original compact four-icon media style. */
    public static class SmartisanMediaStyle extends Notification.Style {
        private static final int MAX_ACTION_BUTTONS = 4;

        public SmartisanMediaStyle() {}

        public SmartisanMediaStyle(Notification.Builder builder) {
            setBuilder(builder);
        }

        private RemoteViews makeContentViewWithActions() {
            final RemoteViews result = getStandardView(
                    R.layout.smartisan_notification_template_base);
            final List<Notification.Action> actions = mBuilder.getSosActions();
            final int count = Math.min(actions.size(), MAX_ACTION_BUTTONS);
            result.removeAllViews(R.id.actions);
            result.setViewVisibility(R.id.actions, count == 0
                    ? android.view.View.GONE : android.view.View.VISIBLE);
            for (int i = 0; i < count; i++) {
                final Notification.Action action = actions.get(i);
                final RemoteViews button = mBuilder.newSosRemoteViews(
                        R.layout.smartisan_notification_media_action);
                final Icon icon = action.getIcon();
                if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
                    button.setImageViewResource(R.id.action0, icon.getResId());
                }
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
                button.setContentDescription(R.id.action0, action.title);
                result.addView(R.id.actions, button);
            }
            result.setViewLayoutMarginDimen(R.id.notification_header_line,
                    RemoteViews.MARGIN_END,
                    R.dimen.smartisan_meida_notification_content_margin_end);
            result.setViewLayoutMarginDimen(R.id.notification_main_column,
                    RemoteViews.MARGIN_END,
                    R.dimen.smartisan_meida_notification_content_margin_end);
            result.setViewLayoutMarginDimen(R.id.actions, RemoteViews.MARGIN_END,
                    R.dimen.notification_content_margin_end);
            return result;
        }

        @Override
        public RemoteViews makeContentView() {
            return makeContentViewWithActions();
        }

        @Override
        public Notification buildStyled(Notification notification) {
            super.buildStyled(notification);
            notification.contentView = makeContentViewWithActions();
            return notification;
        }

        @Override
        public boolean areNotificationsVisiblyDifferent(Notification.Style other) {
            return other == null || getClass() != other.getClass();
        }
    }
}
