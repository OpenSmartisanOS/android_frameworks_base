/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package smartisanos.api;

import android.view.WindowManagerSmtEx;

import com.android.internal.statusbar.SystemUiDecoration;

/** Legacy constants used by Smartisan applications. @hide */
public class LayoutParamsSmt {
    public static final int PRIVATE_FLAG_EXT_NOT_CAPTURE = 8;
    public static final int PRIVATE_FLAG_NOT_LIMIT_FLOAT_WINDOW = 32;
    public static final int PRIVATE_FLAG_SMARTISAN_DRAW_NAVIGATION_BAR_BACKGROUND = 512;
    public static final int PRIVATE_FLAG_SMARTISAN_FORCE_SHOW_NAVIGATION_BAR = 1024;
    public static final int PRIVATE_FLAG_SMARTISAN_DISABLE_SAVING_SURFACES = 536870912;

    public static final int SMFLAG_INPUTFLINGER_IGNORE_TOUCH_BY_HANDINHAND = 2;
    public static final int SMFLAG_INPUTFLINGER_CALENDAR_DRAG_WINDOW = 4;
    public static final int SMFLAG_INPUTFLINGER_IGNORE_DELAY_DISPATCH_EVENT = 8;
    public static final int SMFLAG_INPUTFLINGER_KEY_MAPPING_WINDOW = 128;
    public static final int SMFLAG_INPUTFLINGER_POINTER_CAPTURE = 256;

    public static final int SM_PRIVATE_FLAG_NOTCH_SMARTISAN = 2;
    public static final int SM_PRIVATE_FLAG_IGNORE_NOTCH_SETTINGS = 8;
    public static final int SM_PRIVATE_FLAG_CAN_INTERCEPT_KEY_WHEN_TOP = 16;
    public static final int SM_PRIVATE_FLAG_HIDE_STATUS_BAR_FOR_KEYGUARD = 32;
    public static final int SM_PRIVATE_FLAG_FACE_ID_WINDOW = 64;
    public static final int SM_PRIVATE_FLAG_HIDE_IF_SECURE_WINDOW_SHOWN = 128;
    public static final int SM_PRIVATE_FLAG_DO_NOT_SCREEN_SHOT = 256;
    public static final int SM_PRIVATE_FLAG_DISABLE_IDEAPILLS = 1024;
    public static final int SM_PRIVATE_FLAG_DISABLE_IDEAPILLS_LONG_PRESS_WHEN_RECORDING = 2048;
    public static final int SM_PRIVATE_FLAG_DISABLE_OVERVIEW_GESTURE = 32768;
    public static final int SM_PRIVATE_FLAG_FAKE_WINDOW = 131072;
    public static final int SM_PRIVATE_FLAG_EXCLUDE_FROM_TAP_OUT_TASK = 524288;
    public static final int SM_PRIVATE_FLAG_NOTCH_SMARTISAN_DOUBLE_SIDE = 2097152;
    public static final int SM_PRIVATE_FLAG_HANDLE_FOD_KEY = 16777216;
    public static final int SM_PRIVATE_FLAG_BRIGHTNESS_DIM_BEHIND = 33554432;
    public static final int SM_PRIVATE_FLAG_SCALE_TASK_FOREGOUND = 67108864;
    public static final int SM_PRIVATE_FLAG_FIXED_ROTATION = 134217728;
    public static final int SM_PRIVATE_FLAG_BLUR_BEHIND = 268435456;
    public static final int SM_PRIVATE_FLAG_SIDEBAR_TOP = 536870912;

    public static final int TYPE_BACK_INDICATOR =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_BACK_INDICATOR;
    public static final int TYPE_DOCK_WINDOW =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_DOCK_WINDOW;
    public static final int TYPE_DREAM_OVERLAY =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_DREAM_OVERLAY;
    public static final int TYPE_SIDEBAR_TOOLS_SIDE_AREA =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_SIDEBAR_TOOLS_SIDE_AREA;
    public static final int TYPE_SIDEBAR_TOOLS =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_SIDEBAR_TOOLS;
    public static final int TYPE_SIDEBAR_DIALOG =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_SIDEBAR_DIALOG;
    public static final int TYPE_PC_SIDE_BAR_LKP =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_PC_SIDE_BAR_LKP;
    public static final int TYPE_PC_REV_TOP_BAR_LKP =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_PC_REV_TOP_BAR_LKP;
    public static final int TYPE_IDEA_PILLS =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_IDEA_PILLS;
    public static final int TYPE_RECENT_PSP =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_RECENT_PSP;
    public static final int TYPE_ROUND_CORNER_OVERLAY =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_ROUND_CORNER_OVERLAY;
    public static final int TYPE_SCREENSHOT_EXT =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_SCREENSHOT_EXT;
    public static final int TYPE_SMARTISAN_CONTEXT_MENU =
            WindowManagerSmtEx.LayoutParamsSmtEx.TYPE_SMARTISAN_CONTEXT_MENU;

    private static final LayoutParamsSmt sInstance = new LayoutParamsSmt();

    private LayoutParamsSmt() {}

    public static LayoutParamsSmt getInstance() {
        return sInstance;
    }

    public void addSMFlagInputWindowHandle(android.view.WindowManager.LayoutParams params,
            int flags) {
        params.getSmtEx().inputFlags |= flags;
    }

    public void add_smartisanPrivateFlag(android.view.WindowManager.LayoutParams params,
            int flags) {
        params.getSmtEx().privateFlags |= flags;
    }

    public float getDimAmountForBrightness(android.view.WindowManager.LayoutParams params) {
        return params.getSmtEx().dimAmountForBrightness;
    }

    public boolean getForbidMoveByThumb(android.view.WindowManager.LayoutParams params) {
        return false;
    }

    public int getSMFlagInputWindowHandle(android.view.WindowManager.LayoutParams params) {
        return params.getSmtEx().inputFlags;
    }

    public int getSmartisanPrivateFlag(android.view.WindowManager.LayoutParams params) {
        return params.getSmtEx().privateFlags;
    }

    public boolean get_isEatHomeKey(android.view.WindowManager.LayoutParams params) {
        return params.getSmtEx().isEatHomeKey;
    }

    public void removeSMFlagInputWindowHandle(android.view.WindowManager.LayoutParams params,
            int flags) {
        params.getSmtEx().inputFlags &= ~flags;
    }

    public void removeSmartisanPrivateFlag(android.view.WindowManager.LayoutParams params,
            int flags) {
        params.getSmtEx().privateFlags &= ~flags;
    }

    public void setBlurAmount(android.view.WindowManager.LayoutParams params, float amount) {
        params.getSmtEx().blurAmount = amount;
    }

    public void setDimAmountForBrightness(android.view.WindowManager.LayoutParams params,
            float amount) {
        params.getSmtEx().dimAmountForBrightness = amount;
    }

    public void setForbidMoveByThumb(android.view.WindowManager.LayoutParams params,
            boolean forbid) {}

    public void setSMFlagInputWindowHandle(android.view.WindowManager.LayoutParams params,
            int flags) {
        params.getSmtEx().inputFlags = flags;
    }

    public void setSystemUiDecoration(android.view.WindowManager.LayoutParams params,
            SystemUiDecoration decoration) {
        params.getSmtEx().systemUiDecoration = decoration;
    }

    public void set_drawDuringAnimation(android.view.WindowManager.LayoutParams params,
            boolean draw) {
        params.getSmtEx().drawDuringAnimation = draw;
    }

    public void set_isEatHomeKey(android.view.WindowManager.LayoutParams params, boolean eat) {
        params.getSmtEx().isEatHomeKey = eat;
    }
}
