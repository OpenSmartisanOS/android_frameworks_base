/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.annotation.NonNull;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.MergedConfiguration;

import java.util.ArrayList;

/** Smartisan extensions attached to each application ViewRoot. @hide */
public class ViewRootImplSmtEx {
    private static final int IWINDOW_DISPATCH_SCREEN_DIM_TRANSACTION = 10001;
    private static final int IWINDOW_DISPATCH_FOO_POINT_TRANSACTION = 10002;
    private static final int IWINDOW_DISPATCH_RELAYOUT = 10003;
    private static final int IWINDOW_DISPATCH_ZOOM_STATE = 10004;
    private static final int IWINDOW_DISPATCH_ONE_STEP_FAKE_FOCUS = 10005;

    private final ViewRootImpl mViewRootImpl;
    private final MagnificationSpecSmt mZoomSpec = MagnificationSpecSmt.obtain();
    private final ArrayList<Runnable> mZoomStatusChangeListeners = new ArrayList<>();
    private final android.graphics.PointF mLastTouchPointJustForDrag =
            new android.graphics.PointF();
    private volatile boolean mScreenDim;
    private boolean mInterceptScreenDimTouch;

    public boolean mHasImeFocus = true;
    public boolean mHasSurfaceViewOrWebView;
    public boolean mSysUiChangeInConfigChanging;
    public boolean mAvoidDropInputEvent;

    public ViewRootImplSmtEx(ViewRootImpl viewRootImpl) {
        mViewRootImpl = viewRootImpl;
    }

    public void addZoomStatusChangeListener(Runnable listener) {
        if (listener == null) return;
        synchronized (mZoomStatusChangeListeners) {
            if (!mZoomStatusChangeListeners.contains(listener)) {
                mZoomStatusChangeListeners.add(listener);
            }
        }
    }

    public void removeZoomStatusChangeListener(Runnable listener) {
        synchronized (mZoomStatusChangeListeners) {
            mZoomStatusChangeListeners.remove(listener);
        }
    }

    public boolean isInPinnedZoom() {
        return mZoomSpec.type == MagnificationSpecSmt.TYPE_ZOOM_PINNED;
    }

    public boolean isInSidebarZoom() {
        return mZoomSpec.type == MagnificationSpecSmt.TYPE_ZOOM_DISPLAY
                || mZoomSpec.type == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                || mZoomSpec.type == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT;
    }

    public void getZoomRect(@NonNull Rect outRect) {
        if (!isInSidebarZoom() || mViewRootImpl.mDisplay == null) {
            outRect.setEmpty();
            return;
        }
        final Point size = new Point();
        mViewRootImpl.mDisplay.getRealSize(size);
        outRect.set(Math.round(mZoomSpec.offsetX), Math.round(mZoomSpec.offsetY),
                Math.round(mZoomSpec.offsetX + size.x * mZoomSpec.scaleX),
                Math.round(mZoomSpec.offsetY + size.y * mZoomSpec.scaleY));
    }

    @Deprecated
    public void getScreenSizeOffset(int[] outOffsets) {
        if (outOffsets == null || outOffsets.length < 2) return;
        outOffsets[0] = isInSidebarZoom() ? Math.round(mZoomSpec.offsetX) : 0;
        outOffsets[1] = isInSidebarZoom() ? Math.round(mZoomSpec.offsetY) : 0;
    }

    public void getLastTouchPointForDrag(Point outPoint) {
        if (outPoint == null) return;
        outPoint.set(Math.round(mLastTouchPointJustForDrag.x),
                Math.round(mLastTouchPointJustForDrag.y));
    }

    public Rect getAppFrame() {
        final Rect result = new Rect();
        if (mViewRootImpl.mView != null) {
            mViewRootImpl.mView.getWindowVisibleDisplayFrame(result);
        }
        return result;
    }

    public boolean isMirrored() {
        return false;
    }

    public void dispatchOnStatusBarClick() {
        // Kept as an ABI-safe hook. AOSP has no system-wide status-bar-click dispatch contract.
    }

    public void reportResult(CharSequence result, Rect rect, Paint paint) {
        reportResult(result, rect, paint, true);
    }

    public void reportResult(CharSequence result, Rect rect, Paint paint, boolean drawHighlight) {
        // Foo/text-recognition rendering belongs to the optional ecosystem layer.
    }

    void noteLastTouchPoint(MotionEvent event) {
        if (event != null && event.isTouchEvent()) {
            mLastTouchPointJustForDrag.set(event.getRawX(), event.getRawY());
        }
    }

    boolean filterForScreenDim(InputEvent inputEvent) {
        if (!(inputEvent instanceof MotionEvent)) return false;
        final MotionEvent event = (MotionEvent) inputEvent;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mInterceptScreenDimTouch = mScreenDim
                    && mViewRootImpl.mWindowAttributes.type
                    != WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
        }
        final boolean intercept = mInterceptScreenDimTouch;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mInterceptScreenDimTouch = false;
        }
        return intercept;
    }

    public void setAvoidDropInputEvent(boolean avoid) {
        mAvoidDropInputEvent = avoid;
    }

    public void translateEvent(MotionEvent event) {
        // InputDispatcher inherits the SurfaceControl transform and already supplies local
        // coordinates. Keeping this method as a no-op avoids applying the inverse twice.
        noteLastTouchPoint(event);
    }

    void onRelayoutWindow(MergedConfiguration configuration) {
        if (configuration == null) return;
        mHasImeFocus = configuration.getSmtEx().mHasImeFocus;
        updateZoomSpec(configuration.getSmtEx().mMagnificationSpec);
    }

    void updateZoomSpec(MagnificationSpecSmt spec) {
        if (MagnificationSpecSmt.same(mZoomSpec, spec)) return;
        mZoomSpec.setTo(spec);
        final Runnable[] listeners;
        synchronized (mZoomStatusChangeListeners) {
            listeners = mZoomStatusChangeListeners.toArray(new Runnable[0]);
        }
        mViewRootImpl.mHandler.post(() -> {
            for (Runnable listener : listeners) listener.run();
            if (mViewRootImpl.mView != null) mViewRootImpl.mView.invalidate();
        });
    }

    boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case IWINDOW_DISPATCH_SCREEN_DIM_TRANSACTION:
                data.enforceInterface("android.view.IWindow");
                final boolean dim = data.readBoolean();
                mViewRootImpl.mHandler.post(() -> mScreenDim = dim);
                if (reply != null) reply.writeNoException();
                return true;
            case IWINDOW_DISPATCH_FOO_POINT_TRANSACTION:
                data.enforceInterface("android.view.IWindow");
                FooPointInfo.CREATOR.createFromParcel(data);
                if (reply != null) reply.writeNoException();
                return true;
            case IWINDOW_DISPATCH_RELAYOUT:
                data.enforceInterface("android.view.IWindow");
                mViewRootImpl.mHandler.post(() -> {
                    mViewRootImpl.mForceNextWindowRelayout = true;
                    mViewRootImpl.requestLayout();
                });
                if (reply != null) reply.writeNoException();
                return true;
            case IWINDOW_DISPATCH_ZOOM_STATE:
                data.enforceInterface("android.view.IWindow");
                final MagnificationSpecSmt spec = MagnificationSpecSmt.CREATOR.createFromParcel(data);
                mViewRootImpl.mHandler.post(() -> {
                    updateZoomSpec(spec);
                    spec.recycle();
                });
                if (reply != null) reply.writeNoException();
                return true;
            case IWINDOW_DISPATCH_ONE_STEP_FAKE_FOCUS:
                data.enforceInterface("android.view.IWindow");
                final boolean fakeFocused = data.readBoolean();
                // The factory ActivityStackView reports focus directly to its hosted client
                // without changing DisplayContent's real input focus. Keep the same separation:
                // this affects rendering/lifecycle only and never grants an input channel.
                mViewRootImpl.mHandler.post(
                        () -> mViewRootImpl.windowFocusChanged(fakeFocused));
                if (reply != null) reply.writeNoException();
                return true;
            default:
                return false;
        }
    }
}
