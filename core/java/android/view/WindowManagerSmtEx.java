/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.content.Context;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.statusbar.SystemUiDecoration;

/** Smartisan compatibility extensions for {@link WindowManager}. @hide */
public interface WindowManagerSmtEx extends WindowManagerSmtBase {
    int IWINDOWMANAGER_REQUEST_MAGNIFICATIONSPEC = 2000;
    int IWINDOWMANAGER_REQUEST_ZOOM_TO_SIDEBAR = 2001;
    int IWINDOW_DISPATCH_SCREEN_DIM_TRANSACTION = 10001;
    int IWINDOW_DISPATCH_FOO_POINT_TRANSACTION = 10002;
    int IWINDOW_DISPATCH_RELAYOUT = 10003;
    int IWINDOW_DISPATCH_ZOOM_STATE = 10004;
    int IWINDOW_DISPATCH_ONE_STEP_FAKE_FOCUS = 10005;

    int TYPE_ZOOM_INVALID = -1;
    int TYPE_ZOOM_DISPLAY = 0;
    int TYPE_ZOOM_PINNED = 3;
    int TYPE_SCREENSHOT_APP = 1;
    int TYPE_SCREENSHOT_APP_WITH_DOCKWIDNOW = 8;

    /** Window constants used by Smartisan system applications. */
    class LayoutParamsSmtEx extends WindowManagerSmtBase.LayoutParamsSmtBase {
        public static final String TITLE_FLOATING_POPUP = "smartisan_floating_popup";
        public static final String TITLE_QUICK_SNIPPET = "quick_snippet";
        public static final int TYPE_APPLICATION_OVERLAY = 2050;
        public static final int TYPE_SIDEBAR_TOOLS_SIDE_AREA = 2051;
        public static final int TYPE_SIDEBAR_TOOLS = 2052;
        public static final int TYPE_SIDEBAR_DIALOG = 2053;
        public static final int TYPE_ROUND_CORNER_OVERLAY = 2054;
        public static final int TYPE_SCREENSHOT_EXT = 2055;
        public static final int TYPE_SMARTISAN_CONTEXT_MENU = 2056;
        public static final int TYPE_DOCK_WINDOW = 2057;
        public static final int TYPE_RECENT_PSP = 2058;
        public static final int TYPE_BACK_INDICATOR = 2059;
        public static final int TYPE_DREAM_OVERLAY = 2060;
        public static final int TYPE_PC_SIDE_BAR_LKP = 2061;
        public static final int TYPE_PC_REV_TOP_BAR_LKP = 2062;
        public static final int TYPE_IDEA_PILLS = 2063;
        public static final int TYPE_IDEAL_PILLS = TYPE_IDEA_PILLS;
        public static final int TYPE_DREAM_ACTIVITY = 2064;

        public static final int PRIVATE_FLAG_EXT_NOT_CAPTURE = 8;
        public static final int PRIVATE_FLAG_NOT_LIMIT_FLOAT_WINDOW = 32;
        public static final int PRIVATE_FLAG_SMARTISAN_DRAW_NAVIGATION_BAR_BACKGROUND = 512;
        public static final int PRIVATE_FLAG_SMARTISAN_FORCE_SHOW_NAVIGATION_BAR = 1024;
        public static final int SM_PRIVATE_FLAG_EXT_FORCE_FULL_SCREEN = 4096;
        public static final int PRIVATE_FLAG_SMARTISAN_DISABLE_SAVING_SURFACES = 536870912;

        public static final int SMFLAG_INPUTFLINGER_FORCE_SPLIT = 1;
        public static final int SMFLAG_INPUTFLINGER_IGNORE_TOUCH_BY_HANDINHAND = 2;
        public static final int SMFLAG_INPUTFLINGER_CALENDAR_DRAG_WINDOW = 4;
        public static final int SMFLAG_INPUTFLINGER_IGNORE_DELAY_DISPATCH_EVENT = 8;
        public static final int SMFLAG_INPUTFLINGER_DISABLE_SCROLL_REPEAT = 16;
        public static final int SMFLAG_INPUTFLINGER_FIXED_TOUCHREGION = 32;

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

        public float dimAmountForBrightness = 1.0f;

        public LayoutParamsSmtEx(WindowManager.LayoutParams windowParams) {
            super(windowParams);
        }

        public int copyFrom(LayoutParamsSmtEx other) {
            if (other == null) return 0;
            final boolean changed = privateFlags != other.privateFlags
                    || privateFlags2 != other.privateFlags2
                    || privateFlags3 != other.privateFlags3
                    || Float.compare(blurAmount, other.blurAmount) != 0
                    || smXMLFlagsFromActivityInfo != other.smXMLFlagsFromActivityInfo
                    || smXMLFlagsFromApplicationInfo != other.smXMLFlagsFromApplicationInfo
                    || inputFlags != other.inputFlags
                    || isEatHomeKey != other.isEatHomeKey
                    || drawDuringAnimation != other.drawDuringAnimation
                    || (systemUiDecoration == null && other.systemUiDecoration != null)
                    || (systemUiDecoration != null
                            && !systemUiDecoration.equals(other.systemUiDecoration))
                    || Float.compare(dimAmountForBrightness, other.dimAmountForBrightness) != 0
                    || !smTouchRegion.equals(other.smTouchRegion);
            privateFlags = other.privateFlags;
            privateFlags2 = other.privateFlags2;
            privateFlags3 = other.privateFlags3;
            blurAmount = other.blurAmount;
            smXMLFlagsFromActivityInfo = other.smXMLFlagsFromActivityInfo;
            smXMLFlagsFromApplicationInfo = other.smXMLFlagsFromApplicationInfo;
            inputFlags = other.inputFlags;
            isEatHomeKey = other.isEatHomeKey;
            drawDuringAnimation = other.drawDuringAnimation;
            systemUiDecoration = other.systemUiDecoration != null
                    ? other.systemUiDecoration.clone() : null;
            dimAmountForBrightness = other.dimAmountForBrightness;
            smTouchRegion.set(other.smTouchRegion);
            return changed ? WindowManager.LayoutParams.PRIVATE_FLAGS_CHANGED : 0;
        }

        public void init(Parcel parcel) {
            privateFlags = parcel.readInt();
            privateFlags2 = parcel.readInt();
            privateFlags3 = parcel.readInt();
            blurAmount = parcel.readFloat();
            smXMLFlagsFromActivityInfo = parcel.readInt();
            smXMLFlagsFromApplicationInfo = parcel.readInt();
            inputFlags = parcel.readInt();
            isEatHomeKey = parcel.readBoolean();
            drawDuringAnimation = parcel.readBoolean();
            dimAmountForBrightness = parcel.readFloat();
            if (parcel.readInt() != 0) {
                systemUiDecoration = SystemUiDecoration.CREATOR.createFromParcel(parcel);
            } else {
                systemUiDecoration = null;
            }
            smTouchRegion.set(parcel.readInt(), parcel.readInt(), parcel.readInt(),
                    parcel.readInt());
        }

        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(privateFlags);
            parcel.writeInt(privateFlags2);
            parcel.writeInt(privateFlags3);
            parcel.writeFloat(blurAmount);
            parcel.writeInt(smXMLFlagsFromActivityInfo);
            parcel.writeInt(smXMLFlagsFromApplicationInfo);
            parcel.writeInt(inputFlags);
            parcel.writeBoolean(isEatHomeKey);
            parcel.writeBoolean(drawDuringAnimation);
            parcel.writeFloat(dimAmountForBrightness);
            if (systemUiDecoration != null) {
                parcel.writeInt(1);
                systemUiDecoration.writeToParcel(parcel, flags);
            } else {
                parcel.writeInt(0);
            }
            parcel.writeInt(smTouchRegion.left);
            parcel.writeInt(smTouchRegion.top);
            parcel.writeInt(smTouchRegion.right);
            parcel.writeInt(smTouchRegion.bottom);
        }

        public void setSurfaceInsets(int left, int top, int right, int bottom) {
            mWindowParams.surfaceInsets.set(Math.max(left, 0), Math.max(top, 0),
                    Math.max(right, 0), Math.max(bottom, 0));
            mWindowParams.hasManualSurfaceInsets = true;
            mWindowParams.preservePreviousSurfaceInsets = false;
        }
    }

    static boolean setMagnificationSpecSmt(Context context, MagnificationSpecSmt spec) {
        if (spec == null) return false;
        final IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindowManager");
            spec.writeToParcel(data, 0);
            windowManager.asBinder().transact(
                    IWINDOWMANAGER_REQUEST_MAGNIFICATIONSPEC, data, reply, 0);
            reply.readException();
            return reply.readByte() != 0;
        } catch (RemoteException | RuntimeException e) {
            Log.e("WindowManagerSmt", "Unable to apply OneStep magnification", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void dispatchScreenDim(IWindow window, boolean dim) {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindow");
            data.writeBoolean(dim);
            window.asBinder().transact(IWINDOW_DISPATCH_SCREEN_DIM_TRANSACTION, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w("WindowManagerSmt", "Unable to dispatch dim state", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void dispatchFooPoint(IWindow window, FooPointInfo point) {
        if (window == null || point == null) return;
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindow");
            point.writeToParcel(data, 0);
            window.asBinder().transact(IWINDOW_DISPATCH_FOO_POINT_TRANSACTION, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w("WindowManagerSmt", "Unable to dispatch Foo point", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void dispatchRelayout(IWindow window) {
        if (window == null) return;
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindow");
            window.asBinder().transact(IWINDOW_DISPATCH_RELAYOUT, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w("WindowManagerSmt", "Unable to dispatch relayout", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void dispatchZoomState(IWindow window, MagnificationSpecSmt spec) {
        if (window == null) return;
        final MagnificationSpecSmt actualSpec = spec != null ? spec : MagnificationSpecSmt.obtain();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindow");
            actualSpec.writeToParcel(data, 0);
            window.asBinder().transact(IWINDOW_DISPATCH_ZOOM_STATE, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w("WindowManagerSmt", "Unable to dispatch OneStep state", e);
        } finally {
            if (spec == null) actualSpec.recycle();
            reply.recycle();
            data.recycle();
        }
    }

    static boolean isKeyguardSmartisan(WindowManager.LayoutParams params) {
        return params != null && (params.type == WindowManager.LayoutParams.TYPE_KEYGUARD
                || params.type == WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE);
    }

    static void getThumbModeCrop(WindowManager windowManager, Rect outRect) {
        if (outRect == null) return;
        final MagnificationSpecSmt spec = smartisanos.api.WindowManagerSmt.getCurrentSpec();
        if (spec == null || spec.isNop()) {
            outRect.setEmpty();
            if (spec != null) spec.recycle();
            return;
        }
        final android.view.Display display = windowManager != null
                ? windowManager.getDefaultDisplay() : null;
        if (display == null) {
            outRect.setEmpty();
            spec.recycle();
            return;
        }
        final android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        outRect.set(Math.round(spec.offsetX), Math.round(spec.offsetY),
                Math.round(spec.offsetX + size.x * spec.scaleX),
                Math.round(spec.offsetY + size.y * spec.scaleY));
        spec.recycle();
    }
}
