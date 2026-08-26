/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;

import java.util.Set;

/**
 * Original Smartisan notification compatibility helpers.
 *
 * @hide
 */
public class NotificationSmtBase {
    private static final Set<String> NOTIFICATION_WHITE_LIST = Set.of(
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
            "com.smartisanos.boston.phone");

    protected static final String TAG = "NotificationSmtEx";

    public static int getNotificationAppIcon(Context context) {
        if (context == null || NOTIFICATION_WHITE_LIST.contains(context.getPackageName())) {
            return 0;
        }
        try {
            return context.getApplicationInfo().icon;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** The original implementation only rounds bitmap-backed icons, with a literal 9px radius. */
    public static Icon getRoundedCornerBitmap(Icon icon) {
        if (icon == null || icon.getType() != Icon.TYPE_BITMAP || icon.getBitmap() == null) {
            return icon;
        }
        final Bitmap source = icon.getBitmap();
        final Bitmap result = Bitmap.createBitmap(
                source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect bounds = new Rect(0, 0, source.getWidth(), source.getHeight());
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(0xff424242);
        canvas.drawRoundRect(new RectF(bounds), 9f, 9f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, bounds, bounds, paint);
        paint.setXfermode(null);
        return Icon.createWithBitmap(result);
    }
}
