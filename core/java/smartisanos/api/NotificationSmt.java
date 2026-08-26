/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package smartisanos.api;

import android.app.Notification;
import android.app.NotificationSmtEx;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/**
 * Original hidden Smartisan notification facade kept for preinstalled client compatibility.
 *
 * @hide
 */
public final class NotificationSmt {
    public static final int DEFAULT_TYPE = 1;
    public static final int PHONE_TYPE = 2;
    public static final int TNT_TYPE = 4;
    public static final String DIALOG_STYLE_KEY_ENABLED = "button_enabled";
    public static final String DIALOG_STYLE_KEY_TEXT_COLOR = "button_text_color";
    public static final int FLAG_SMT_NOT_IN_NOTICE = NotificationSmtEx.FLAG_SMT_NOT_IN_NOTICE;
    public static final int FLAG_SMT_HOLD_IN_HEADSUP = NotificationSmtEx.FLAG_SMT_HOLD_IN_HEADSUP;
    public static final int FLAG_SMT_PURE_IN_HEADSUP = NotificationSmtEx.FLAG_SMT_PURE_IN_HEADSUP;
    public static final int FLAG_TOP_VISIBILITY = NotificationSmtEx.FLAG_TOP_VISIBILITY;
    public static final int FLAG_SMT_PUSH = NotificationSmtEx.FLAG_SMT_PUSH;

    private static final NotificationSmt INSTANCE = new NotificationSmt();

    private NotificationSmt() {}

    public static NotificationSmt getInstance() {
        return INSTANCE;
    }

    public static Notification.Style newSmartisanDialogStyle(Notification.Builder builder) {
        return new NotificationSmtEx.SmartisanDialogStyle(builder);
    }

    public static Notification.Style new_SmartisanButtonStyle() {
        return new NotificationSmtEx.SmartisanButtonStyle();
    }

    public static Notification.Style new_SmartisanMediaStyle() {
        return new NotificationSmtEx.SmartisanMediaStyle();
    }

    public int getNotificationAppIcon(Notification.Builder builder, Context context) {
        return NotificationSmtEx.getNotificationAppIcon(context);
    }

    public long getNotificationTickStayTime(Notification notification) {
        return notification.extras == null ? -1L : notification.extras.getLong(
                NotificationSmtEx.EXTRA_TICK_STAY_TIME, -1L);
    }

    public Icon getRoundedCornerBitmap(Notification.Builder builder, Icon icon) {
        return NotificationSmtEx.getRoundedCornerBitmap(icon);
    }

    public Notification.Builder setDeviceType(Notification.Builder builder, int type) {
        // The shipping Android 11 facade intentionally never implemented this compatibility
        // stub; callers observe null for every device type.
        return null;
    }

    public void setNotificationTickStayTime(Notification notification, long durationMillis) {
        if (notification.extras == null) {
            notification.extras = new Bundle();
        }
        notification.extras.putLong(NotificationSmtEx.EXTRA_TICK_STAY_TIME, durationMillis);
    }
}
