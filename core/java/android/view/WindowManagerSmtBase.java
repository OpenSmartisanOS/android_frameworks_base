/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.graphics.Rect;

import com.android.internal.statusbar.SystemUiDecoration;

/** Base Smartisan window compatibility contract. @hide */
public interface WindowManagerSmtBase {
    int IWINDOW_DISPATCH_PAUSING_REFRESH_TRANSACTION = 10001;
    int IWINDOW_DISPATCH_ZOOMED_STATE_TRANSACTION = 10000;
    String TAG = "WindowManagerSmtEx";
    int TRANSIT_APPLICATION_CLOSE = 31;
    int TRANSIT_APPLICATION_OPEN = 30;
    int TRANSIT_NO_ANIM = 1000;
    int TYPE_SCREENSHOT_APP_WITH_KEYGUARD = 4;
    int TYPE_SCREENSHOT_APP_WITH_WALLPAPER = 2;
    boolean USING_SMARTISAN_KEYGUARD = true;

    abstract class LayoutParamsSmtBase {
        public static final int SMFLAG_INPUTFLINGER_FORCE_SPLIT = 1;
        public static final int SMFLAG_INPUTFLINGER_IGNORE_TOUCH_BY_HANDINHAND = 2;
        public static final int SMFLAG_INPUTFLINGER_CALENDAR_DRAG_WINDOW = 4;
        public static final int SMFLAG_INPUTFLINGER_IGNORE_DELAY_DISPATCH_EVENT = 8;
        public static final int SMFLAG_INPUTFLINGER_DISABLE_SCROLL_REPEAT = 16;
        public static final int SMFLAG_INPUTFLINGER_FIXED_TOUCHREGION = 32;

        public static final int SM_PRIVATE_FLAG_NOTCH_ANDROID = 1;
        public static final int SM_PRIVATE_FLAG_NOTCH_SMARTISAN = 2;
        public static final int SM_PRIVATE_FLAG_NOTCH_NONE = 4;
        public static final int SM_PRIVATE_FLAG_NOTCH_MODE = 7;
        public static final int SM_PRIVATE_FLAG_IGNORE_NOTCH_SETTINGS = 8;
        public static final int SM_PRIVATE_FLAG_CAN_INTERCEPT_KEY_WHEN_TOP = 16;
        public static final int SM_PRIVATE_FLAG_HIDE_STATUS_BAR_FOR_KEYGUARD = 32;
        public static final int SM_PRIVATE_FLAG_FACE_ID_WINDOW = 64;
        public static final int SM_PRIVATE_FLAG_HIDE_IF_SECURE_WINDOW_SHOWN = 128;
        public static final int SM_PRIVATE_FLAG_DO_NOT_SCREEN_SHOT = 256;
        public static final int SM_PRIVATE_FLAG_CAN_BE_DRAGGED = 512;
        public static final int SM_PRIVATE_FLAG_DISABLE_IDEAPILLS = 1024;
        public static final int SM_PRIVATE_FLAG_DISABLE_IDEAPILLS_LONG_PRESS_WHEN_RECORDING = 2048;
        public static final int SM_PRIVATE_FLAG_DO_NOT_UPDATE_POSITION = 8192;
        public static final int SM_PRIVATE_FLAG_HAS_DECOR_CAPTION_VIEW = 16384;
        public static final int SM_PRIVATE_FLAG_DISABLE_OVERVIEW_GESTURE = 32768;
        public static final int SM_PRIVATE_FLAG_SCREENSHOT_WINDOW = 65536;
        public static final int SM_PRIVATE_FLAG_FAKE_WINDOW = 131072;
        public static final int SM_PRIVATE_FLAG_WINDOW_HAS_SURFACEVIEW = 262144;
        public static final int SM_PRIVATE_FLAG_EXCLUDE_FROM_TAP_OUT_TASK = 524288;
        public static final int SM_PRIVATE_FLAG_HIDE_CAPTION_BAR = 1048576;
        public static final int SM_PRIVATE_FLAG_NOTCH_SMARTISAN_DOUBLE_SIDE = 2097152;
        public static final int SM_PRIVATE_FLAG_TRANSLUCENT_THEME = 4194304;
        public static final int SM_PRIVATE_FLAG_FULLSCREEN_VOLUME_CONTROL = 8388608;
        public static final int SM_PRIVATE_FLAG_HANDLE_FOD_KEY = 16777216;
        public static final int SM_PRIVATE_FLAG_BRIGHTNESS_DIM_BEHIND = 33554432;
        public static final int SM_PRIVATE_FLAG_SCALE_TASK_FOREGOUND = 67108864;
        public static final int SM_PRIVATE_FLAG_FIXED_ROTATION = 134217728;
        public static final int SM_PRIVATE_FLAG_BLUR_BEHIND = 268435456;
        public static final int SM_PRIVATE_FLAG_SIDEBAR_TOP = 536870912;
        public static final int SM_PRIVATE_FLAG_STARTING_WINDOW_FOR_NAVI_BAR_MODE = 1073741824;

        protected WindowManager.LayoutParams mWindowParams;
        public int privateFlags;
        public int privateFlags2;
        public int privateFlags3;
        public int smXMLFlagsFromActivityInfo;
        public int smXMLFlagsFromApplicationInfo;
        public SystemUiDecoration systemUiDecoration;
        public int inputFlags;
        public float blurAmount;
        public Rect smTouchRegion = new Rect();
        public boolean isEatHomeKey;
        public boolean drawDuringAnimation;

        public LayoutParamsSmtBase(WindowManager.LayoutParams windowParams) {
            mWindowParams = windowParams;
        }
    }
}
