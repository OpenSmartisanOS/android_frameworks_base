/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.dagger;

import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.SosActionTile;
import com.android.systemui.qs.tiles.SosActionTile.Action;
import com.android.systemui.res.R;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

/** Smartisan tile bindings backed by Android platform services. */
@Module
public interface SosQsModule {
    @Provides @IntoMap @StringKey("sos_disable_buttons")
    static QSTileImpl<?> provideDisableButtons(SosActionTile.Factory factory) {
        return factory.create(
                Action.DND,
                R.string.sos_qs_disable_buttons,
                R.drawable.smartisan_qs_undisturb_on,
                R.drawable.smartisan_qs_undisturb_off);
    }

    @Provides @IntoMap @StringKey("sos_screenshot")
    static QSTileImpl<?> provideScreenshot(SosActionTile.Factory factory) {
        return factory.create(
                Action.SCREENSHOT,
                R.string.sos_qs_screenshot,
                R.drawable.smartisan_qs_screenshot_off,
                R.drawable.smartisan_qs_screenshot_off);
    }

    @Provides @IntoMap @StringKey("sos_vibrate")
    static QSTileImpl<?> provideVibrate(SosActionTile.Factory factory) {
        return factory.create(
                Action.VIBRATE,
                R.string.sos_qs_vibrate,
                R.drawable.smartisan_qs_vibrate_on,
                R.drawable.smartisan_qs_vibrate_off);
    }

    @Provides @IntoMap @StringKey("sos_mute")
    static QSTileImpl<?> provideMute(SosActionTile.Factory factory) {
        return factory.create(
                Action.MUTE,
                R.string.sos_qs_mute,
                R.drawable.smartisan_qs_mute_on,
                R.drawable.smartisan_qs_mute_off);
    }

    @Provides @IntoMap @StringKey("sos_lock_screen")
    static QSTileImpl<?> provideLockScreen(SosActionTile.Factory factory) {
        return factory.create(
                Action.LOCK,
                R.string.sos_qs_lock_screen,
                R.drawable.smartisan_qs_lock_screen_off,
                R.drawable.smartisan_qs_lock_screen_off);
    }

    @Provides @IntoMap @StringKey("sos_protect_eyes")
    static QSTileImpl<?> provideProtectEyes(SosActionTile.Factory factory) {
        return factory.create(
                Action.PROTECT_EYES,
                R.string.sos_qs_protect_eyes,
                R.drawable.smartisan_qs_night_shift_on,
                R.drawable.smartisan_qs_night_shift_off);
    }

    @Provides @IntoMap @StringKey("sos_fake_call")
    static QSTileImpl<?> provideFakeCall(SosActionTile.Factory factory) {
        return factory.create(
                Action.FAKE_CALL,
                R.string.sos_qs_fake_call,
                R.drawable.smartisan_qs_fake_call_on,
                R.drawable.smartisan_qs_fake_call_off);
    }
}
