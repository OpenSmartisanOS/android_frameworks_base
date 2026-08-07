/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package smartisanos.api;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IWindow;
import android.view.MagnificationSpecSmt;
import android.view.WindowManager;
import android.view.WindowManagerSmtEx;

import com.android.internal.sidebar.ISidebarService;
import com.android.internal.statusbar.SystemUiDecoration;

/** Public Smartisan compatibility facade for window operations. @hide */
public class WindowManagerSmt {
    public static final int IWINDOWMANAGER_REQUEST_MAGNIFICATIONSPEC = 2000;
    public static final int IWINDOWMANAGER_REQUEST_ZOOM_TO_SIDEBAR = 2001;
    public static final int IWINDOW_DISPATCH_ZOOMED_STATE_TRANSACTION = 10000;

    public static final int TYPE_ZOOM_INVALID = -1;
    public static final int TYPE_ZOOM_DISPLAY = 0;
    public static final int TYPE_ZOOM_PINNED = 3;
    public static final int TYPE_SCREENSHOT_APP_WITH_WALLPAPER = 2;
    public static final int TYPE_SCREENSHOT_APP_WITH_KEYGUARD = 4;
    public static final boolean USING_SMARTISAN_KEYGUARD = true;

    private static final WindowManagerSmt sInstance = new WindowManagerSmt();
    private static final Object sSpecLock = new Object();
    private static final MagnificationSpecSmt sCurrentSpec = MagnificationSpecSmt.obtain();

    private WindowManagerSmt() {}

    public static WindowManagerSmt getInstance() {
        return sInstance;
    }

    public static boolean setMagnificationSpecSmt(Context context, MagnificationSpecSmt spec) {
        final boolean result = WindowManagerSmtEx.setMagnificationSpecSmt(context, spec);
        if (result) {
            synchronized (sSpecLock) {
                sCurrentSpec.setTo(spec);
            }
        }
        return result;
    }

    public static boolean isKeyguardSmartisan(WindowManager.LayoutParams params) {
        return WindowManagerSmtEx.isKeyguardSmartisan(params);
    }

    public static WindowManager.LayoutParams setSystemUiDecoration(
            WindowManager.LayoutParams params, String name, int left, int top, int right,
            int bottom) {
        params.getSmtEx().systemUiDecoration = new SystemUiDecoration(name, left, top, right,
                bottom);
        return params;
    }

    /** Kept for binary compatibility; WMS performs dispatch in the current implementation. */
    public static void dispatchZoomedStateSmt(IWindow window, MagnificationSpecSmt spec) {}

    public static MagnificationSpecSmt getCurrentSpec() {
        synchronized (sSpecLock) {
            return sCurrentSpec.isNop() ? null : MagnificationSpecSmt.obtain(sCurrentSpec);
        }
    }

    public void getThumbModeCrop(WindowManager windowManager, Rect outRect) {
        WindowManagerSmtEx.getThumbModeCrop(windowManager, outRect);
    }

    @Deprecated
    public void getThumbModeCropGlobal(WindowManager windowManager, Rect outRect) {
        getThumbModeCrop(windowManager, outRect);
    }

    @Deprecated
    public boolean isWindowInSideBarMode(WindowManager windowManager) {
        return isSidebarShowing();
    }

    @Deprecated
    public boolean isWindowInSideBarModeGlobal(WindowManager windowManager) {
        return isSidebarShowing();
    }

    @Deprecated
    public boolean isWindowInTopDownMode(WindowManager windowManager) {
        return false;
    }

    @Deprecated
    public boolean isWindowInTopDownModeGlobal(WindowManager windowManager) {
        return false;
    }

    @Deprecated
    public boolean isWindowInthumbMode(WindowManager windowManager) {
        return isSidebarShowing();
    }

    @Deprecated
    public boolean isWindowInthumbModeGlobal(WindowManager windowManager) {
        return isSidebarShowing();
    }

    @Deprecated
    public void resetWindowOneHandedState(WindowManager windowManager) {
        final ISidebarService service = getSidebarService();
        if (service != null) {
            try {
                service.resetWindow();
            } catch (RemoteException ignored) {
            }
        }
    }

    private static boolean isSidebarShowing() {
        final ISidebarService service = getSidebarService();
        if (service == null) return false;
        try {
            return service.isInSidebarMode();
        } catch (RemoteException ignored) {
            return false;
        }
    }

    private static ISidebarService getSidebarService() {
        return ISidebarService.Stub.asInterface(ServiceManager.getService("sidebar"));
    }
}
