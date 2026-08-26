/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.statusbar.notification.row;

/** Internal presentation classification; never serialized into {@link android.app.Notification}. */
public enum NotificationContentType {
    STANDARD,
    CUSTOM
}
