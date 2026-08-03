/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensmartisanos.fakecall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes the foreground service when the scheduled fake call becomes due. */
public final class FakeCallAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!FakeCallService.ACTION_RING.equals(intent.getAction())) {
            return;
        }
        context.startForegroundService(
                new Intent(context, FakeCallService.class)
                        .setAction(FakeCallService.ACTION_RING));
    }
}
