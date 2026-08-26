/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.internal.widget;

/** Canonical notification style shared by notification RemoteViews widgets. */
final class NotificationStylePolicy {
    private NotificationStylePolicy() {}

    static boolean notificationsRedesignTemplates() {
        return false;
    }
}
