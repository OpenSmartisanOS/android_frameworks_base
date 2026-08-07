/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package smartisanos.api;

import android.content.ClipData;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.ViewSmtBase;
import android.view.ViewSmtEx;

/** Public compatibility facade for Smartisan view and OneStep extensions. @hide */
public class ViewSmt {
    private static final ViewSmt sInstance = new ViewSmt();
    private boolean mFingerTouch;
    private float mForceTouchPressure = 0.6f;

    public interface BoomEditCallback extends ViewSmtEx.BoomEditCallback {}
    public interface OnForceTouchListener extends ViewSmtBase.OnForceTouchListener {}
    public interface OnLongClickAndMoveListener extends ViewSmtBase.OnLongClickAndMoveListener {}
    public interface OnShowContextMenuListener extends ViewSmtBase.OnShowContextMenuListener {}
    public interface PointerEventCallback extends ViewSmtEx.PointerEventCallback {}

    public ViewSmt() {}

    public static ViewSmt getInstance() { return sInstance; }

    public static void getZoomRect(View view, Rect outRect) {
        final ViewRootImpl root = view != null ? view.getViewRootImpl() : null;
        if (root != null) root.getSmtEx().getZoomRect(outRect);
        else if (outRect != null) outRect.setEmpty();
    }

    public static boolean isInPinnedZoom(View view) {
        final ViewRootImpl root = view != null ? view.getViewRootImpl() : null;
        return root != null && root.getSmtEx().isInPinnedZoom();
    }

    public static boolean isInSidebarZoom(View view) {
        final ViewRootImpl root = view != null ? view.getViewRootImpl() : null;
        return root != null && root.getSmtEx().isInSidebarZoom();
    }

    public void addZoomStatusChangeListener(View view, Runnable listener) {
        final ViewRootImpl root = view != null ? view.getViewRootImpl() : null;
        if (root != null) root.getSmtEx().addZoomStatusChangeListener(listener);
    }

    public void removeZoomStatusChangeListener(View view, Runnable listener) {
        final ViewRootImpl root = view != null ? view.getViewRootImpl() : null;
        if (root != null) root.getSmtEx().removeZoomStatusChangeListener(listener);
    }

    public void dispatchFindView(View view, float x, float y, boolean includeSelf) {
        if (view != null) view.getSmtEx().dispatchFindView(x, y, includeSelf);
    }
    public void dispatchOnStatusBarClick(View view) {
        if (view != null) view.getSmtEx().dispatchOnStatusBarClick();
    }
    public ViewParent findRootParent(View view) {
        return view != null ? view.getSmtEx().findRootParent() : null;
    }
    public View findTouchedView(ViewGroup view, float x, float y) {
        return view != null ? view.getSmtEx().findTouchedView(x, y) : null;
    }
    public ViewSmtEx.BoomEditCallback getBoomEditCallback(View view) {
        return view != null ? view.getSmtEx().getBoomEditCallback() : null;
    }
    public int getBoomType(View view) { return view != null ? view.getSmtEx().getBoomType() : 0; }
    public boolean getDrawScrollBar(View view) {
        return view != null && view.getSmtEx().getDrawScrollBar();
    }
    public int getFindTextIndex(View view) {
        return view != null ? view.getSmtEx().getFindTextIndex() : 0;
    }
    public float getLongClickX(View view) {
        return view != null ? view.getSmtEx().getLongClickX() : 0f;
    }
    public float getLongClickY(View view) {
        return view != null ? view.getSmtEx().getLongClickY() : 0f;
    }
    public void getRealBoundsOnScreen(View view, Rect outRect) {
        if (view != null) view.getSmtEx().getRealBoundsOnScreen(outRect);
        else if (outRect != null) outRect.setEmpty();
    }
    public int[] getRealLocationOnScreen(View view) {
        return view != null ? view.getSmtEx().getRealLocationOnScreen() : new int[2];
    }
    public boolean getScrollToTopEnable(View view) {
        return view != null && view.getSmtEx().getScrollToTopEnable();
    }
    public void initForceTouchPress(float pressure) { mForceTouchPressure = pressure; }
    public boolean isForceTouch(android.content.Context context, float pressure) {
        return mFingerTouch && pressure >= mForceTouchPressure;
    }
    public boolean isForceTouch(View view, android.content.Context context, float pressure) {
        return isForceTouch(context, pressure);
    }
    public boolean isHandlingTouchEvent(View view) {
        return view != null && view.getSmtEx().isHandlingTouchEvent();
    }
    public boolean isLongPressSwipe(View view) {
        return view != null && view.getSmtEx().isLongPressSwipe();
    }
    public boolean isRecognizing(View view) {
        return view != null && view.getSmtEx().isRecognizing();
    }
    public boolean isSupportOcrBoom(View view) {
        return view != null && view.getSmtEx().isSupportOcrBoom();
    }
    public boolean removeInputInterceptor(View view, View interceptor) {
        return view != null && view.getSmtEx().removeInputInterceptor(interceptor);
    }
    public boolean requestTouchFocus(View view) {
        return view != null && view.getSmtEx().requestTouchFocus();
    }
    public void setAlwaysCanAcceptDrag(View view, boolean value) {
        if (view != null) view.getSmtEx().setAlwaysCanAcceptDrag(value);
    }
    public void setBoomEditCallback(View view, BoomEditCallback callback) {
        if (view != null) view.getSmtEx().setBoomEditCallback(callback);
    }
    public void setClipToBounds(View view, boolean clip) {
        if (view != null) view.getSmtEx().setClipToBounds(clip);
    }
    public void setDrawScrollBar(View view, boolean draw) {
        if (view != null) view.getSmtEx().setDrawScrollBar(draw);
    }
    public void setFindText(View view, String text) { setFindText(view, text, false); }
    public void setFindText(View view, String text, boolean editable) {
        if (view != null) view.getSmtEx().setFindText(text, editable);
    }
    public void setFindTextAndIndex(View view, String text, int index) {
        setFindTextAndIndex(view, text, index, false);
    }
    public void setFindTextAndIndex(View view, String text, int index, boolean editable) {
        if (view != null) view.getSmtEx().setFindTextAndIndex(text, index, editable);
    }
    public void setFindTextIndex(View view, int index) {
        if (view != null) view.getSmtEx().setFindTextIndex(index);
    }
    public void setFingerTouchState(boolean fingerTouch) { mFingerTouch = fingerTouch; }
    public void setFooDisplayAccessible(View view, boolean accessible) {
        if (view != null) view.getSmtEx().setFooDisplayAccessible(accessible);
    }
    public void setHandlingTouchEvent(View view, boolean handling) {
        if (view != null) view.getSmtEx().setHandlingTouchEvent(handling);
    }
    public boolean setInputInerceptor(View view, View interceptor, boolean enabled) {
        return view != null && view.getSmtEx().setInputInerceptor(interceptor, enabled);
    }
    public void setOnForceTouchListener(View view, OnForceTouchListener listener) {
        if (view != null) view.getSmtEx().setOnForceTouchListener(listener);
    }
    public void setOnLongClickAndMoveListener(View view, OnLongClickAndMoveListener listener) {
        if (view != null) view.getSmtEx().setOnLongClickAndMoveListener(listener);
    }
    public void setOnShowContextMenuListener(View view, OnShowContextMenuListener listener) {
        if (view != null) view.getSmtEx().setOnShowContextMenuListener(listener);
    }
    public void setPointerEventCallback(View view, PointerEventCallback callback) {
        if (view != null) view.getSmtEx().setPointerEventCallback(callback);
    }
    public void setScrollToTopEnable(View view, boolean enabled) {
        if (view != null) view.getSmtEx().setScrollToTopEnable(enabled);
    }
    public void setShowMenuBaseOnRightBottom(View view, boolean show) {
        if (view != null) view.getSmtEx().setShowMenuBaseOnRightBottom(show);
    }
    public void setSupportOcrBoom(View view, boolean support) {
        if (view != null) view.getSmtEx().setSupportOcrBoom(support);
    }
    public void setVoiceInputVisibility(View view, boolean visible) {
        if (view != null) view.getSmtEx().setVoiceInputVisibility(visible);
    }
    public void startDrag(View view, ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY) {
        if (view != null) view.getSmtEx().startDrag(data, shadowBuilder, localState, flags,
                touchX, touchY);
    }
    public boolean startDrag(View view, ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY, int itemCount) {
        return view != null && view.getSmtEx().startDrag(data, shadowBuilder, localState, flags,
                touchX, touchY, itemCount);
    }
}
