/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package android.app;

import android.annotation.LayoutRes;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;

import com.android.internal.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Internal template policy for the Smartisan R2 notification presentation.
 *
 * <p>Notification security and semantics continue to be owned by {@link Notification.Builder};
 * this class only selects layouts and presentation rules.</p>
 *
 * @hide
 */
final class NotificationTemplatePolicy {
    private static final Set<String> SMALL_ICON_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.phone",
            "com.smartisanos.mms",
            "com.android.email",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.exchange",
            "com.smartisanos.appstore",
            "com.android.bluetooth",
            "com.smartisanos.recorder",
            "com.android.calendar",
            "com.smartisanos.cleaner",
            "com.smartisanos.clock",
            "com.smartisanos.cloudsync",
            "com.android.server.telecom",
            "com.redteamobile.roaming",
            "com.smartisanos.screenrecorder",
            "com.android.providers.downloads",
            "com.android.gallery3d",
            "com.android.musicfx",
            "com.smartisanos.updater",
            "com.android.desktop.systemui",
            "com.smartisanos.textboom",
            "com.ss.android.smartisan.browser",
            "com.android.browser",
            "com.smartisan.unionpush.proxy",
            "com.smartisan.smpush",
            "com.smartisanos.gamestore",
            "com.smartisanos.boston.phone"));

    private NotificationTemplatePolicy() {}

    @LayoutRes
    static int header() {
        return R.layout.smartisan_notification_template_header;
    }

    @LayoutRes
    static int collapsedBase() {
        return R.layout.smartisan_notification_template_material_base;
    }

    @LayoutRes
    static int expandedBase() {
        return R.layout.smartisan_notification_template_material_big_base;
    }

    @LayoutRes
    static int bigText() {
        return R.layout.smartisan_notification_template_material_big_text;
    }

    @LayoutRes
    static int bigPicture() {
        return R.layout.smartisan_notification_template_material_big_picture;
    }

    @LayoutRes
    static int inbox() {
        return R.layout.smartisan_notification_template_material_inbox;
    }

    @LayoutRes
    static int messaging() {
        return R.layout.smartisan_notification_template_material_messaging;
    }

    @LayoutRes
    static int collapsedMedia() {
        return R.layout.smartisan_notification_template_material_media;
    }

    @LayoutRes
    static int expandedMedia() {
        return R.layout.smartisan_notification_template_material_big_media;
    }

    @LayoutRes
    static int collapsedCall() {
        return collapsedBase();
    }

    @LayoutRes
    static int expandedCall() {
        return expandedBase();
    }

    @LayoutRes
    static int remoteBase() {
        return R.layout.smartisan_notification_remote_base;
    }

    @LayoutRes
    static int dialog() {
        return R.layout.smartisan_notification_template_dialog_alerts;
    }

    @LayoutRes
    static int action(boolean tombstone) {
        return tombstone
                ? R.layout.smartisan_notification_action_tombstone
                : R.layout.smartisan_notification_action;
    }

    static boolean isStandardLayout(@LayoutRes int layoutId) {
        return layoutId == header()
                || layoutId == collapsedBase()
                || layoutId == expandedBase()
                || layoutId == bigText()
                || layoutId == bigPicture()
                || layoutId == inbox()
                || layoutId == messaging()
                || layoutId == collapsedMedia()
                || layoutId == expandedMedia()
                || layoutId == collapsedCall()
                || layoutId == expandedCall()
                || layoutId == remoteBase()
                || layoutId == dialog()
                || layoutId == R.layout.smartisan_notification_action
                || layoutId == R.layout.smartisan_notification_action_devider
                || layoutId == R.layout.smartisan_notification_action_tombstone
                || layoutId == R.layout.smartisan_notification_dialog_action
                || layoutId == R.layout.smartisan_notification_material_media_big_action
                || layoutId == R.layout.smartisan_notification_media_action
                || layoutId == R.layout.smartisan_notification_expand_button
                || layoutId == R.layout.smartisan_notification_material_action_list
                || layoutId == R.layout.smartisan_notification_material_media_action
                || layoutId == R.layout.smartisan_notification_template_base
                || layoutId == R.layout.smartisan_notification_template_icon_group
                || layoutId == R.layout.smartisan_notification_template_part_chronometer
                || layoutId == R.layout.smartisan_notification_template_part_line1
                || layoutId == R.layout.smartisan_notification_template_part_line2
                || layoutId == R.layout.smartisan_notification_template_part_line3
                || layoutId == R.layout.smartisan_notification_template_part_time
                || layoutId == R.layout.smartisan_notification_template_progressbar;
    }

    static boolean shouldUseSmallIcon(String packageName) {
        return SMALL_ICON_PACKAGES.contains(packageName);
    }

    /** The R2 implementation rounds only bitmap-backed icons, using a literal 9px radius. */
    static Icon roundBitmapIcon(Context context, Icon icon) {
        if (icon == null || icon.getType() != Icon.TYPE_BITMAP || icon.getBitmap() == null) {
            return icon;
        }
        final Bitmap source = icon.getBitmap();
        final Bitmap rounded = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(rounded);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect bounds = new Rect(0, 0, source.getWidth(), source.getHeight());
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(0xff424242);
        final android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final float shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        final float scale = Math.max(0f,
                Math.min(shortSide / 1080f, metrics.densityDpi / 400f));
        final float radius = 9f * scale;
        canvas.drawRoundRect(new RectF(bounds), radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, bounds, bounds, paint);
        paint.setXfermode(null);
        return Icon.createWithBitmap(rounded);
    }
}
