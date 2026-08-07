/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.wm;

import android.content.Context;
import android.provider.Settings;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.view.accessibility.AccessibilityManager;

import java.io.PrintWriter;

/**
 * Policy-level implementation of Smartisan's corner and thumb OneStep gestures.
 *
 * <p>The factory listener observes the default display's pointer stream next to the ordinary
 * system-bar gesture listener. It does not own an input channel; after a gesture is recognized it
 * cancels that stream at the original target so Launcher or a side card cannot also consume it.
 * All coordinates are normalized to the display's natural portrait orientation before applying
 * the original 1080-wide R2 thresholds.</p>
 */
final class OneStepGestureDetector implements PointerEventListener {
    static final int REASON_CORNER_ENTER = 100;
    static final int REASON_CORNER_EXIT = 101;
    static final int REASON_THUMB_ENTER = 102;
    static final int REASON_THUMB_EXIT = 103;

    private static final long CORNER_TIMEOUT_MS = 500;
    private static final long THUMB_TIMEOUT_MS = 1_000;
    private static final long CORNER_CALLBACK_DELAY_MS = 65;
    private static final float REFERENCE_WIDTH = 1080f;
    private static final float[] THUMB_SIZE_THRESHOLDS = {.31f, .36f, .40f, .46f, .50f};

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_ENTER_LEFT = 1;
    private static final int GESTURE_ENTER_RIGHT = 2;
    private static final int GESTURE_EXIT_LEFT = 3;
    private static final int GESTURE_EXIT_RIGHT = 4;
    private static final int GESTURE_THUMB_ENTER = 5;
    private static final int GESTURE_THUMB_EXIT = 6;

    interface Callbacks {
        int getCommittedMode();
        int getSceneGeneration();
        boolean canEnter();
        void cancelCurrentTouch();
        void requestMode(int mode, int reason);
    }

    private final Context mContext;
    private final android.os.Handler mHandler;
    private final Callbacks mCallbacks;
    private final AccessibilityManager mAccessibilityManager;

    private int mLogicalWidth;
    private int mLogicalHeight;
    private int mNaturalWidth;
    private int mNaturalHeight;
    private int mRotation;
    private int mGesture;
    private float mDownX;
    private float mDownY;
    private int mDownMode = android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID;
    private int mDownSceneGeneration;
    private final android.graphics.RectF mDownZoomRect = new android.graphics.RectF();
    private long mDownTime;
    private float mThumbThreshold;
    private boolean mFireable;
    private int mGeneration;
    private Runnable mPendingFire;
    private String mLastResult = "none";

    OneStepGestureDetector(Context context, android.os.Handler handler, Callbacks callbacks) {
        mContext = context;
        mHandler = handler;
        mCallbacks = callbacks;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
    }

    void onDisplayInfoChanged(DisplayInfo info) {
        if (info == null) return;
        mLogicalWidth = info.logicalWidth;
        mLogicalHeight = info.logicalHeight;
        mRotation = info.rotation;
        if (mRotation == Surface.ROTATION_90 || mRotation == Surface.ROTATION_270) {
            mNaturalWidth = mLogicalHeight;
            mNaturalHeight = mLogicalWidth;
        } else {
            mNaturalWidth = mLogicalWidth;
            mNaturalHeight = mLogicalHeight;
        }
        cancel("display changed");
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (!event.isTouchEvent()) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancel("multi-touch");
                break;
            case MotionEvent.ACTION_MOVE:
                update(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancel("up/cancel");
                break;
            default:
                break;
        }
    }

    private void begin(MotionEvent event) {
        cancel("new stream");
        if (event.getPointerCount() != 1 || mNaturalWidth <= 0 || mNaturalHeight <= 0) return;
        if (mAccessibilityManager != null && mAccessibilityManager.isTouchExplorationEnabled()) {
            mLastResult = "rejected: touch exploration";
            return;
        }
        if (!settingEnabled("side_bar_mode", true)) {
            mLastResult = "rejected: disabled";
            return;
        }

        final float[] natural = toNatural(event.getX(), event.getY());
        final float x = natural[0];
        final float y = natural[1];
        final float scale = mNaturalWidth / REFERENCE_WIDTH;
        final float cornerLength = 80f * scale;
        final float cornerWidth = 70f * scale;
        final float narrowWidth = 50f * scale;
        final float narrowHeight = 150f * scale;
        final int mode = mCallbacks.getCommittedMode();
        mDownMode = mode;
        mDownSceneGeneration = mCallbacks.getSceneGeneration();
        mDownZoomRect.setEmpty();

        if (mode == android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID) {
            final boolean leftCorner = (x <= cornerLength && y <= cornerWidth)
                    || (x <= narrowWidth && y <= narrowHeight);
            final boolean rightCorner = (x >= mNaturalWidth - cornerLength && y <= cornerWidth)
                    || (x >= mNaturalWidth - narrowWidth && y <= narrowHeight);
            if (leftCorner) {
                mGesture = GESTURE_ENTER_LEFT;
            } else if (rightCorner) {
                mGesture = GESTURE_ENTER_RIGHT;
            } else if (settingEnabled("thumb_push_down", false) && y >= 150f * scale) {
                mGesture = GESTURE_THUMB_ENTER;
                final int area = Settings.Global.getInt(mContext.getContentResolver(),
                        "thumb_trigger_area", 2);
                mThumbThreshold = THUMB_SIZE_THRESHOLDS[Math.max(0,
                        Math.min(THUMB_SIZE_THRESHOLDS.length - 1, area))];
            } else {
                return;
            }
        } else {
            final float side = mNaturalWidth * .253f;
            final float top = mNaturalHeight * .253f;
            if (mode == android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT) {
                mDownZoomRect.set(side, top, mNaturalWidth, mNaturalHeight);
            } else {
                mDownZoomRect.set(0f, top, mNaturalWidth - side, mNaturalHeight);
            }
            // The factory listener classifies every down inside the shrunken main display as an
            // outward-corner candidate. Requiring an 80x70 inner-corner band makes the reverse
            // gesture needlessly hard and lets the ordinary system-bar detector steal attempts.
            if (mode == android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                    && mDownZoomRect.contains(x, y)) {
                mGesture = GESTURE_EXIT_LEFT;
            } else if (mode == android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT
                    && mDownZoomRect.contains(x, y)) {
                mGesture = GESTURE_EXIT_RIGHT;
            } else if (settingEnabled("thumb_push_down", false)) {
                mGesture = GESTURE_THUMB_EXIT;
                final int area = Settings.Global.getInt(mContext.getContentResolver(),
                        "thumb_trigger_area", 2);
                mThumbThreshold = THUMB_SIZE_THRESHOLDS[Math.max(0,
                        Math.min(THUMB_SIZE_THRESHOLDS.length - 1, area))];
            } else {
                return;
            }
        }
        mDownX = x;
        mDownY = y;
        mDownTime = event.getEventTime();
        mFireable = true;
        mLastResult = "tracking " + gestureName(mGesture);
    }

    private void update(MotionEvent event) {
        if (!mFireable || event.getPointerCount() != 1) return;
        final float[] natural = toNatural(event.getX(), event.getY());
        final float dx = natural[0] - mDownX;
        final float dy = natural[1] - mDownY;
        final long elapsed = event.getEventTime() - mDownTime;
        final float scale = mNaturalWidth / REFERENCE_WIDTH;
        final float cornerDistance = 160f * scale;
        final boolean triggered;
        final int requestedMode;
        final int reason;

        switch (mGesture) {
            case GESTURE_ENTER_LEFT:
                triggered = elapsed < CORNER_TIMEOUT_MS
                        && dx > cornerDistance && dy > cornerDistance;
                requestedMode = android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT;
                reason = REASON_CORNER_ENTER;
                break;
            case GESTURE_ENTER_RIGHT:
                triggered = elapsed < CORNER_TIMEOUT_MS
                        && dx < -cornerDistance && dy > cornerDistance;
                requestedMode = android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT;
                reason = REASON_CORNER_ENTER;
                break;
            case GESTURE_EXIT_LEFT:
                triggered = elapsed < CORNER_TIMEOUT_MS
                        && dx < -cornerDistance && dy < -cornerDistance
                        && natural[0] < mDownZoomRect.left
                        && natural[1] < mDownZoomRect.top;
                requestedMode = android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID;
                reason = REASON_CORNER_EXIT;
                break;
            case GESTURE_EXIT_RIGHT:
                triggered = elapsed < CORNER_TIMEOUT_MS
                        && dx > cornerDistance && dy < -cornerDistance
                        && natural[0] > mDownZoomRect.right
                        && natural[1] < mDownZoomRect.top;
                requestedMode = android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID;
                reason = REASON_CORNER_EXIT;
                break;
            case GESTURE_THUMB_ENTER:
                triggered = elapsed < THUMB_TIMEOUT_MS && event.getSize() > mThumbThreshold
                        && dy > 35f * scale && dy > Math.abs(dx) * 1.7f;
                requestedMode = mDownX < mNaturalWidth / 2f
                        ? android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT
                        : android.view.MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT;
                reason = REASON_THUMB_ENTER;
                break;
            case GESTURE_THUMB_EXIT:
                triggered = elapsed < THUMB_TIMEOUT_MS && event.getSize() > mThumbThreshold
                        && dy < -300f * scale && -dy > Math.abs(dx) * 1.7f;
                requestedMode = android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID;
                reason = REASON_THUMB_EXIT;
                break;
            default:
                return;
        }

        final long timeout = isCornerGesture(mGesture) ? CORNER_TIMEOUT_MS : THUMB_TIMEOUT_MS;
        if (!triggered && elapsed >= timeout) {
            cancel("timeout");
            return;
        }
        if (!triggered) return;
        if (requestedMode != android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID
                && !mCallbacks.canEnter()) {
            cancel("entry conditions");
            return;
        }
        final int generation = ++mGeneration;
        final int firedGesture = mGesture;
        mFireable = false;
        mGesture = GESTURE_NONE;
        mLastResult = "fired " + gestureName(firedGesture) + " reason=" + reason;
        mCallbacks.cancelCurrentTouch();
        final Runnable fire = () -> {
            if (generation != mGeneration) return;
            mPendingFire = null;
            final int committedMode = mCallbacks.getCommittedMode();
            final int sceneGeneration = mCallbacks.getSceneGeneration();
            final boolean entering = reason == REASON_CORNER_ENTER
                    || reason == REASON_THUMB_ENTER;
            final boolean modeStillMatches = entering
                    ? mDownMode == android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID
                            && committedMode
                                    == android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    : mDownMode != android.view.MagnificationSpecSmt.TYPE_ZOOM_INVALID
                            && committedMode == mDownMode;
            if (!modeStillMatches || sceneGeneration != mDownSceneGeneration) {
                mLastResult = "rejected: mode changed down=" + mDownMode
                        + " committed=" + committedMode + " generation="
                        + mDownSceneGeneration + "/" + sceneGeneration;
                return;
            }
            mCallbacks.requestMode(requestedMode, reason);
        };
        mPendingFire = fire;
        if (reason == REASON_CORNER_ENTER || reason == REASON_CORNER_EXIT) {
            mHandler.postDelayed(fire, CORNER_CALLBACK_DELAY_MS);
        } else {
            mHandler.post(fire);
        }
    }

    boolean isTrackingCornerGesture() {
        return mFireable && (mGesture == GESTURE_ENTER_LEFT || mGesture == GESTURE_ENTER_RIGHT
                || mGesture == GESTURE_EXIT_LEFT || mGesture == GESTURE_EXIT_RIGHT);
    }

    void cancelForSystemBar(String edge) {
        cancel("system bar " + edge);
    }

    private void cancel(String reason) {
        // Lifting the finger after a recognized corner gesture must not cancel its factory 65 ms
        // callback delay. A new stream, display change or system-bar win still invalidates it.
        if (mPendingFire != null && !"up/cancel".equals(reason)) {
            mHandler.removeCallbacks(mPendingFire);
            mPendingFire = null;
        }
        if (mFireable) mLastResult = "cancelled: " + reason;
        mFireable = false;
        mGesture = GESTURE_NONE;
        if (mPendingFire == null) ++mGeneration;
    }

    private boolean settingEnabled(String key, boolean defaultValue) {
        final String value = Settings.Global.getString(mContext.getContentResolver(), key);
        if (value == null) return defaultValue;
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    private float[] toNatural(float x, float y) {
        switch (mRotation) {
            case Surface.ROTATION_90:
                return new float[] {y, mLogicalWidth - x};
            case Surface.ROTATION_180:
                return new float[] {mNaturalWidth - x, mNaturalHeight - y};
            case Surface.ROTATION_270:
                return new float[] {mLogicalHeight - y, x};
            default:
                return new float[] {x, y};
        }
    }

    private static boolean isCornerGesture(int gesture) {
        return gesture == GESTURE_ENTER_LEFT || gesture == GESTURE_ENTER_RIGHT
                || gesture == GESTURE_EXIT_LEFT || gesture == GESTURE_EXIT_RIGHT;
    }

    private static String gestureName(int gesture) {
        switch (gesture) {
            case GESTURE_ENTER_LEFT: return "corner-enter-left";
            case GESTURE_ENTER_RIGHT: return "corner-enter-right";
            case GESTURE_EXIT_LEFT: return "corner-exit-left";
            case GESTURE_EXIT_RIGHT: return "corner-exit-right";
            case GESTURE_THUMB_ENTER: return "thumb-enter";
            case GESTURE_THUMB_EXIT: return "thumb-exit";
            default: return "none";
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "OneStepGestureDetector:");
        pw.println(prefix + "  display=" + mNaturalWidth + "x" + mNaturalHeight
                + " rotation=" + mRotation);
        pw.println(prefix + "  fireable=" + mFireable + " gesture=" + gestureName(mGesture));
        pw.println(prefix + "  downMode=" + mDownMode + " sceneGeneration="
                + mDownSceneGeneration + " zoomRect=" + mDownZoomRect);
        pw.println(prefix + "  lastResult=" + mLastResult);
    }
}
