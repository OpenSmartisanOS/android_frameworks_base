/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensmartisanos.fakecall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores a pending fake-call alarm after boot or package replacement. */
public final class FakeCallBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final int state =
                context.getSharedPreferences(FakeCallService.PREFS, Context.MODE_PRIVATE)
                        .getInt(FakeCallService.PREF_STATE, FakeCallService.STATE_IDLE);
        if (state == FakeCallService.STATE_SCHEDULED) {
            context.startForegroundService(
                    new Intent(context, FakeCallService.class)
                            .setAction(FakeCallService.ACTION_RESTORE));
        }
    }
}
