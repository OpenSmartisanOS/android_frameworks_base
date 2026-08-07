/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package smartisanos.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import java.util.ArrayList;

/** Client helper for entering and leaving Smartisan OneStep. @hide */
public class SidebarHelper {
    public static final int INDEX_TYPE = 0;
    public static final int INDEX_OFFSET_X = 1;
    public static final int INDEX_OFFSET_Y = 2;
    public static final int INDEX_SCALE_X = 3;
    public static final int INDEX_SCALE_Y = 4;
    public static final int INDEX_LENGTH = 5;

    public static final int TYPE_ZOOM_INVALID = -1;
    public static final int TYPE_ZOOM_SIDEBAR_IN_LEFT = 1;
    public static final int TYPE_ZOOM_SIDEBAR_IN_RIGHT = 2;

    public static final int REASON_ZOOM_NONE = 0;
    public static final int REASON_ZOOM_FROM_GESTURE = 100;
    public static final int REASON_ZOOM_RESET_FROM_GESTURE = 101;
    public static final int REASON_ZOOM_FROM_GESTURE_THUMB_PUSH_DOWN = 102;
    public static final int REASON_ZOOM_RESET_FROM_GESTURE_THUMB_PUSH_UP = 103;
    public static final int REASON_ZOOM_FROM_KEY_ACTION = 104;
    public static final int REASON_ZOOM_FROM_SMART_KEY_ACTION = 105;

    public static final String REQUEST_ONESTEP_MODE = "request onestep mode";
    public static final String REQUEST_ONESTEP_REASON = "request onestep resaon";
    public static final String TAG = "SidebarHelper";
    public static final float[] mTmpFloats = {-1.0f, 0.0f, 0.0f, 1.0f, 1.0f};

    private static final int SIDEBAR_WIDTH_PX = 273;
    private static final SidebarHelper sInstance = new SidebarHelper();

    private final ArrayList<Runnable> mStatusChangeListeners = new ArrayList<>();
    private Context mContext;
    private ContentObserver mSettingsObserver;
    private int mLastSidebarZoomType = TYPE_ZOOM_INVALID;
    private int mSidebarZoomType = TYPE_ZOOM_INVALID;
    private boolean mSidebarEnabled = true;
    private boolean mThumbEnabled;
    private float mInterceptTouchSize = 0.38f;

    private SidebarHelper() {}

    public static SidebarHelper getInstance() {
        return sInstance;
    }

    public static void initFromServer(Context context) {
        sInstance.initialize(context);
    }

    private void initialize(Context context) {
        if (context == null) return;
        mContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        refreshSettings();
    }

    public static boolean isSidebarEnable(Context context) {
        return context != null
                && Settings.Global.getInt(context.getContentResolver(), "side_bar_mode", 1) == 1;
    }

    public static boolean isThumbPushDownEnable(Context context) {
        if (context == null) return false;
        final String value = Settings.Global.getString(
                context.getContentResolver(), "thumb_push_down");
        return Boolean.parseBoolean(value);
    }

    public static int getSidebarType(Context context) {
        return context == null ? TYPE_ZOOM_INVALID : Settings.Global.getInt(
                context.getContentResolver(), "side_bar_zoom_type", TYPE_ZOOM_INVALID);
    }

    public void enterLeftSidebar(int reason) {
        requestZoomWithCheck(TYPE_ZOOM_SIDEBAR_IN_LEFT, reason, false);
    }

    public void enterRightSidebar(int reason) {
        requestZoomWithCheck(TYPE_ZOOM_SIDEBAR_IN_RIGHT, reason, false);
    }

    public void exitSidebar(int reason) {
        requestZoomWithCheck(TYPE_ZOOM_INVALID, reason, false);
    }

    public void requestZoomWithCheck(int mode, int reason, boolean fromServer) {
        final IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindowManager");
            data.writeInt(mode);
            data.writeInt(reason);
            windowManager.asBinder().transact(2001, data, reply, 0);
            reply.readException();
        } catch (RemoteException ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public boolean isSidebarEnable() {
        if (mContext != null) mSidebarEnabled = isSidebarEnable(mContext);
        return mSidebarEnabled;
    }

    public boolean isThumbPushDownEnable() {
        if (mContext != null) mThumbEnabled = isThumbPushDownEnable(mContext);
        return mThumbEnabled;
    }

    public boolean isSidebarInLeft() {
        return getSidebarType() == TYPE_ZOOM_SIDEBAR_IN_LEFT;
    }

    public boolean isSidebarInRight() {
        return getSidebarType() == TYPE_ZOOM_SIDEBAR_IN_RIGHT;
    }

    public boolean isSidebarShow() {
        return getSidebarType() != TYPE_ZOOM_INVALID;
    }

    public int getSidebarType() {
        if (mContext != null) mSidebarZoomType = getSidebarType(mContext);
        return mSidebarZoomType;
    }

    public int getLastSidebarType() {
        return mLastSidebarZoomType;
    }

    public float getSidebarScale() {
        final Point size = getPortraitDisplaySize();
        return size.x > 0 ? Math.max(0.1f, 1.0f - SIDEBAR_WIDTH_PX / (float) size.x) : 1.0f;
    }

    public float getPreciseOffsetValueBySidebarScale(float value) {
        final Point size = getPortraitDisplaySize();
        return size.x > 0 ? (SIDEBAR_WIDTH_PX * value) / size.x : value;
    }

    public float getInterceptTouchSize() {
        return mInterceptTouchSize;
    }

    public void getSidebarRect(Rect outRect) {
        getSidebarRectInPort(outRect);
        if (mContext == null || mContext.getDisplay() == null) return;
        final Point size = new Point();
        mContext.getDisplay().getRealSize(size);
        final Matrix rotation = new Matrix();
        createRotationMatrix(mContext.getDisplay().getRotation(), size.x, size.y, rotation);
        final Matrix inverse = new Matrix();
        if (rotation.invert(inverse)) {
            final android.graphics.RectF rect = new android.graphics.RectF(outRect);
            inverse.mapRect(rect);
            rect.round(outRect);
        }
    }

    public void getSidebarRectInPort(Rect outRect) {
        final Point size = getPortraitDisplaySize();
        if (!isSidebarShow()) {
            outRect.set(0, 0, size.x, size.y);
            return;
        }
        final float scale = getSidebarScale();
        final int width = Math.round(size.x * scale);
        final int height = Math.round(size.y * scale);
        if (isSidebarInLeft()) {
            outRect.set(size.x - width, size.y - height, size.x, size.y);
        } else {
            outRect.set(0, size.y - height, width, size.y);
        }
    }

    public static void createRotationMatrix(int rotation, int width, int height, Matrix matrix) {
        switch (rotation) {
            case 1:
                matrix.setRotate(90.0f);
                matrix.postTranslate(height, 0.0f);
                break;
            case 2:
                matrix.setRotate(180.0f);
                matrix.postTranslate(width, height);
                break;
            case 3:
                matrix.setRotate(270.0f);
                matrix.postTranslate(0.0f, width);
                break;
            default:
                matrix.reset();
        }
    }

    public void addStatusChangeListener(Runnable listener) {
        if (listener == null) return;
        synchronized (mStatusChangeListeners) {
            if (!mStatusChangeListeners.contains(listener)) mStatusChangeListeners.add(listener);
        }
    }

    public void removeStatusChangeListener(Runnable listener) {
        synchronized (mStatusChangeListeners) {
            mStatusChangeListeners.remove(listener);
        }
    }

    public void registerSettingsObserver(Context context, Handler handler) {
        initialize(context);
        if (mSettingsObserver != null || mContext == null) return;
        mSettingsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                final int previous = mSidebarZoomType;
                refreshSettings();
                if (previous != mSidebarZoomType) {
                    mLastSidebarZoomType = previous;
                    final Runnable[] listeners;
                    synchronized (mStatusChangeListeners) {
                        listeners = mStatusChangeListeners.toArray(new Runnable[0]);
                    }
                    for (Runnable listener : listeners) listener.run();
                }
            }
        };
        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Global.getUriFor("side_bar_mode"), false,
                mSettingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor("side_bar_zoom_type"), false,
                mSettingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor("thumb_push_down"), false,
                mSettingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor("thumb_trigger_area"), false,
                mSettingsObserver);
    }

    public void unregisterSettingsObserver() {
        if (mContext != null && mSettingsObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
        mSettingsObserver = null;
    }

    private void refreshSettings() {
        if (mContext == null) return;
        mSidebarEnabled = isSidebarEnable(mContext);
        mThumbEnabled = isThumbPushDownEnable(mContext);
        mSidebarZoomType = getSidebarType(mContext);
        final int area = Settings.Global.getInt(
                mContext.getContentResolver(), "thumb_trigger_area", 2);
        mInterceptTouchSize = area <= 0 ? 0.2f : area == 1 ? 0.3f : area == 2 ? 0.38f : 0.5f;
    }

    private Point getPortraitDisplaySize() {
        final Point size = new Point();
        if (mContext != null && mContext.getDisplay() != null) {
            mContext.getDisplay().getRealSize(size);
        }
        if (size.x > size.y) {
            final int oldX = size.x;
            size.x = size.y;
            size.y = oldX;
        }
        return size;
    }
}
