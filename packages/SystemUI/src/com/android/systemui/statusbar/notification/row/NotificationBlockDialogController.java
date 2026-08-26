/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.statusbar.notification.row;

import android.app.INotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.Set;

/** Owns the single R2-style package notification block confirmation dialog. */
public final class NotificationBlockDialogController {
    private static final String TAG = "NotifBlockDialog";

    private static final Set<String> NEVER_BLOCK = Set.of(
            "com.android.phone",
            "android",
            "com.android.cellbroadcastreceiver",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.smartisanos.appstore",
            "com.smartisanos.gamestore",
            "com.android.browser");

    @Nullable private static SystemUIDialog sDialog;

    private NotificationBlockDialogController() {}

    @MainThread
    public static boolean canShow(ExpandableNotificationRow row) {
        if (!true || row == null || row.isBundle()) {
            return false;
        }

        final StatusBarNotification sbn = NotificationBundleUi.isEnabled()
                ? row.getEntryAdapter().getSbn()
                : row.getEntryLegacy().getSbn();
        return sbn != null && sbn.getUserId() != UserHandle.USER_ALL
                && !NEVER_BLOCK.contains(sbn.getPackageName()) && isBlockable(row);
    }

    @MainThread
    public static boolean show(ExpandableNotificationRow row) {
        if (!canShow(row) || !row.isAttachedToWindow()) {
            return false;
        }

        final StatusBarNotification sbn = NotificationBundleUi.isEnabled()
                ? row.getEntryAdapter().getSbn()
                : row.getEntryLegacy().getSbn();
        dismiss();
        final Context context = row.getContext();
        final String packageName = sbn.getPackageName();
        final int postedUid = sbn.getUid();
        final int userId = sbn.getUserId();
        final CharSequence appLabel = loadAppLabel(context, packageName, userId);

        final SystemUIDialog dialog = new SystemUIDialog(
                context, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        dialog.setTitle(appLabel);
        dialog.setMessage(context.getString(R.string.sos_notification_block_message));
        dialog.setButton(SystemUIDialog.BUTTON_NEGATIVE,
                context.getString(android.R.string.cancel), (d, which) -> d.dismiss());
        dialog.setButton(SystemUIDialog.BUTTON_POSITIVE,
                context.getString(R.string.sos_notification_block_confirm), (d, which) -> {
                    d.dismiss();
                    // Re-evaluate the immutable row target at execution time. The row may have
                    // been removed, become admin/critical, or switched generation while the
                    // confirmation dialog was open.
                    if (row.isAttachedToWindow() && canShow(row)) {
                        blockOnBackgroundThread(row, packageName, postedUid, userId);
                    } else {
                        Toast.makeText(context, R.string.sos_notification_block_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
        dialog.setOnDismissListener(d -> {
            row.removeOnAttachStateChangeListener(detachListener);
            if (sDialog == dialog) {
                sDialog = null;
            }
        });
        row.addOnAttachStateChangeListener(detachListener);
        sDialog = dialog;
        dialog.show();
        return true;
    }

    private static final android.view.View.OnAttachStateChangeListener detachListener =
            new android.view.View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(android.view.View view) {}

                @Override
                public void onViewDetachedFromWindow(android.view.View view) {
                    dismiss();
                }
            };

    @MainThread
    public static void dismiss() {
        if (sDialog != null) {
            sDialog.dismiss();
            sDialog = null;
        }
    }

    private static boolean isBlockable(ExpandableNotificationRow row) {
        return NotificationBundleUi.isEnabled()
                ? row.getEntryAdapter().isBlockable()
                : row.getEntryLegacy().isBlockable();
    }

    private static CharSequence loadAppLabel(Context context, String packageName, int userId) {
        final PackageManager packageManager = context.getPackageManager();
        try {
            final ApplicationInfo info = packageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS, userId);
            final CharSequence label = info.loadLabel(packageManager);
            return label != null ? label : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static void blockOnBackgroundThread(ExpandableNotificationRow row,
            String packageName, int postedUid, int userId) {
        final Context context = row.getContext();
        new Thread(() -> {
            boolean success = false;
            try {
                final int resolvedUid = context.getPackageManager()
                        .getPackageUidAsUser(packageName, userId);
                if (resolvedUid != postedUid
                        || UserHandle.getAppId(resolvedUid) != UserHandle.getAppId(postedUid)) {
                    Log.w(TAG, "Package uid changed before block: " + packageName);
                } else {
                    final INotificationManager manager = INotificationManager.Stub.asInterface(
                            ServiceManager.getService(Context.NOTIFICATION_SERVICE));
                    if (manager != null) {
                        manager.setNotificationsEnabledForPackage(packageName, resolvedUid, false);
                        success = true;
                    }
                }
            } catch (PackageManager.NameNotFoundException | RemoteException e) {
                Log.w(TAG, "Unable to block notifications for " + packageName, e);
            }
            if (!success) {
                row.post(() -> Toast.makeText(context,
                        R.string.sos_notification_block_failed, Toast.LENGTH_SHORT).show());
            }
        }, "NotificationBlock").start();
    }
}
