/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.content.ClipData;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Compatibility surface used by legacy Smartisan applications.
 *
 * <p>OneStep-related geometry and drag methods are functional. Unrelated Foo, OCR and voice
 * state is retained locally so callers can safely feature-detect without crashing.</p>
 * @hide
 */
public class ViewSmtEx extends ViewSmtBase {
    public static final int BOOM_TYPE_PLAIN_TEXT = 0;
    public static final int BOOM_TYPE_IMAGE = 1;
    public static final int BOOM_TYPE_RICH_TEXT = 2;
    public static final int DRAG_FLAG_GLOBAL_EXCLUDE_SELF = 1 << 20;
    public static final int POINT_EVENT_TO_CAPTURE_WORD = 0;
    public static final int POINT_EVENT_TO_BOMB_TEXT = 1;
    public static final int SYSTEM_UI_FLAG_DISABLE_FIX_STATE = 32;
    public static final int SYSTEM_UI_FLAG_COLOR_STATUS_BAR = 64;

    public boolean mDrawScrollBar;
    public boolean mIsWebViewOrContentView;
    public boolean mScrollToTopEnable;

    public interface BoomEditCallback {
        void onTextChanged(String text);
    }

    public interface PointerEventCallback {
        void onPointerIn(float x, float y, int action);
        void onPointerOut();
    }

    public interface SnippetEditCallback {
        void insert(String text);
    }

    private boolean mFooDisplayAccessible;
    private boolean mHandlingTouchEvent;
    private boolean mNeedCutOffDragEvent;
    private boolean mShowMenuBaseOnRightBottom;
    private boolean mSupportOcrBoom;
    private boolean mVoiceInputVisibility;
    private int mBoomType;
    private int mCanvasClipRoundRadius;
    private int mFindTextIndex;
    private String mFindText;
    private BoomEditCallback mBoomEditCallback;
    private PointerEventCallback mPointerEventCallback;
    private SnippetEditCallback mSnippetEditCallback;

    public ViewSmtEx(View view) {
        super(view);
    }

    public static boolean setTextFromSidebar(View view, String text) {
        if (view instanceof android.widget.TextView) {
            ((android.widget.TextView) view).setText(text);
            return true;
        }
        return false;
    }

    public View dispatchFindView(float x, float y, boolean includeSelf) {
        if (mView instanceof ViewGroup) return findTouchedView(x, y);
        return includeSelf ? mView : null;
    }

    public void dispatchOnStatusBarClick() {}

    public ViewParent findRootParent() {
        ViewParent parent = mView.getParent();
        ViewParent root = parent;
        while (parent != null) {
            root = parent;
            parent = parent.getParent();
        }
        return root;
    }

    public View findTouchedView(float x, float y) {
        if (!(mView instanceof ViewGroup)) return mView;
        final ViewGroup group = (ViewGroup) mView;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            final View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            final float childX = x + group.getScrollX() - child.getLeft();
            final float childY = y + group.getScrollY() - child.getTop();
            if (childX >= 0 && childY >= 0
                    && childX < child.getWidth() && childY < child.getHeight()) {
                return child instanceof ViewGroup
                        ? child.getSmtEx().findTouchedView(childX, childY) : child;
            }
        }
        return mView;
    }

    public Rect getAppFrame() {
        final Rect result = new Rect();
        getRealBoundsOnScreen(result);
        return result;
    }

    public BoomEditCallback getBoomEditCallback() { return mBoomEditCallback; }
    public int getBoomType() { return mBoomType; }
    public int getCanvasClipRoundRadius() { return mCanvasClipRoundRadius; }
    public boolean getDrawScrollBar() { return mDrawScrollBar; }
    public String getFindText() { return mFindText; }
    public boolean getFindTextEditable() { return mView instanceof android.widget.EditText; }
    public int getFindTextIndex() { return mFindTextIndex; }
    public Paint getFooPaint() { return null; }
    public float getLongClickX() { return 0f; }
    public float getLongClickY() { return 0f; }
    public PointerEventCallback getPointerEventCallback() { return mPointerEventCallback; }

    public void getRealBoundsOnScreen(Rect outRect) {
        if (outRect == null) return;
        final int[] location = getRealLocationOnScreen();
        outRect.set(location[0], location[1], location[0] + mView.getWidth(),
                location[1] + mView.getHeight());
    }

    public int[] getRealLocationOnScreen() {
        final int[] location = new int[2];
        mView.getLocationOnScreen(location);
        return location;
    }

    public boolean getScrollToTopEnable() { return mScrollToTopEnable; }
    public SnippetEditCallback getSnippetEditCallback() { return mSnippetEditCallback; }
    public boolean getVoiceInputVisibility() { return mVoiceInputVisibility; }
    public boolean hasDefaultFocusSmt() { return mView.hasDefaultFocus(); }
    public boolean isFooDisplayAccessible() { return mFooDisplayAccessible; }
    public boolean isHandlingTouchEvent() { return mHandlingTouchEvent; }
    public boolean isLongPressSwipe() { return false; }
    public boolean isNeedCutOffDragEvent() { return mNeedCutOffDragEvent; }
    public boolean isRecognizing() { return false; }
    public boolean isShowMenuBaseOnRightBottom() { return mShowMenuBaseOnRightBottom; }
    public boolean isSupportOcrBoom() { return mSupportOcrBoom; }
    public boolean requestTouchFocus() { return mView.requestFocus(); }

    public void setAlwaysCanAcceptDrag(boolean value) {}
    public void setBoomEditCallback(BoomEditCallback callback) { mBoomEditCallback = callback; }
    public void setBoomType(int type) { mBoomType = type; }
    public void setCanvasClipRoundRadius(int radius) { mCanvasClipRoundRadius = radius; }
    public void setClipToBounds(boolean clip) { mView.setClipToOutline(clip); }
    public void setDrawScrollBar(boolean draw) { mDrawScrollBar = draw; }
    public void setFindText(String text) { setFindText(text, false); }
    public void setFindText(String text, boolean editable) { mFindText = text; }
    public void setFindTextAndIndex(String text, int index) {
        setFindTextAndIndex(text, index, false);
    }
    public void setFindTextAndIndex(String text, int index, boolean editable) {
        mFindText = text;
        mFindTextIndex = index;
    }
    public void setFindTextIndex(int index) { mFindTextIndex = index; }
    public void setFooDisplayAccessible(boolean accessible) {
        mFooDisplayAccessible = accessible;
    }
    public void setHandlingTouchEvent(boolean handling) { mHandlingTouchEvent = handling; }
    public void setNeedCutOffDragEvent(boolean cutOff) { mNeedCutOffDragEvent = cutOff; }
    public void setOnForceTouchListener(OnForceTouchListener listener) {}
    public void setOnLongClickAndMoveListener(OnLongClickAndMoveListener listener) {}
    public void setOnShowContextMenuListener(OnShowContextMenuListener listener) {}
    public void setPointerEventCallback(PointerEventCallback callback) {
        mPointerEventCallback = callback;
    }
    public void setScrollToTopEnable(boolean enabled) { mScrollToTopEnable = enabled; }
    public void setShowMenuBaseOnRightBottom(boolean show) {
        mShowMenuBaseOnRightBottom = show;
    }
    public void setSnippetEditCallback(SnippetEditCallback callback) {
        mSnippetEditCallback = callback;
    }
    public void setSupportOcrBoom(boolean support) { mSupportOcrBoom = support; }
    public void setVoiceInputVisibility(boolean visible) { mVoiceInputVisibility = visible; }

    public boolean removeInputInterceptor(View view) { return false; }
    public boolean setInputInerceptor(View view, boolean intercept) { return false; }

    public final boolean startDrag(ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY) {
        return startDrag(data, shadowBuilder, localState, flags, touchX, touchY, 0);
    }

    public final boolean startDrag(ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY, int itemCount) {
        return startDrag(data, shadowBuilder, localState, flags, touchX, touchY, itemCount,
                false, null, null, true);
    }

    public final boolean startDrag(ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY, boolean isText) {
        return startDrag(data, shadowBuilder, localState, flags, touchX, touchY, 0,
                isText, null, null, true);
    }

    public final boolean startDrag(ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY, int itemCount,
            boolean isText, CharSequence title, CharSequence description) {
        return startDrag(data, shadowBuilder, localState, flags, touchX, touchY, itemCount,
                isText, title, description, true);
    }

    public final boolean startDrag(ClipData data, View.DragShadowBuilder shadowBuilder,
            Object localState, int flags, float touchX, float touchY, int itemCount,
            boolean isText, CharSequence title, CharSequence description, boolean showShadow) {
        final int supportedFlags = flags & ~DRAG_FLAG_GLOBAL_EXCLUDE_SELF;
        return mView.startDragAndDrop(data, shadowBuilder, localState, supportedFlags);
    }
}
