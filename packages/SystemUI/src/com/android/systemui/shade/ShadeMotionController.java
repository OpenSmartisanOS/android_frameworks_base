/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade;

import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.annotation.VisibleForTesting;

/** Owns direction locking and release decisions for the R2 shade. */
final class ShadeMotionController {
    enum State {
        IDLE,
        VERTICAL_TRACKING,
        VERTICAL_SETTLING,
        HORIZONTAL_PENDING,
        HORIZONTAL_SWITCHING,
    }

    interface Target {
        float getExpandedHeight();
        float getMaxExpandedHeight();
        float getPanelWidth();
        float getMinFlingVelocity();
        boolean isFullyCollapsed();
        boolean isFullyExpanded();
        boolean isQuickSettingsPage();
        boolean canStartHorizontalPageSwitch(float initialX, float initialY);
        boolean isFalseTouch(float x, float y, boolean expanding);
        void onVerticalTrackingStarted(int pointerCount);
        void setExpandedHeight(float height);
        void settleExpandedHeight(boolean expand, float velocity);
        void setQuickSettingsPageFromGesture(boolean quickSettings, boolean animate);
        void cancelHeightAnimation();
    }

    private static final float DESIGN_WIDTH = 1080f;
    private static final float MAX_CONTENT_WIDTH_DP = 480f;
    private static final float DIRECTION_LOCK_DESIGN_PX = 70f;
    private static final float PAGE_SWITCH_DESIGN_PX = 150f;

    private final Target mTarget;
    private final float mDensity;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();

    private State mState = State.IDLE;
    private int mTrackingPointer = -1;
    private int mMaxPointerCount = 1;
    private float mInitialX;
    private float mInitialY;
    private float mInitialHeight;
    private long mDownTime = -1L;
    private long mLastTrackedEventTime = -1L;
    private int mLastTrackedAction = -1;

    ShadeMotionController(Target target, float density) {
        mTarget = target;
        mDensity = density;
    }

    boolean onInterceptTouchEvent(MotionEvent event, boolean mayExpand, boolean mayCollapse) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            beginMotion(event);
            return false;
        }
        if (mDownTime != event.getDownTime()) {
            return false;
        }
        trackMovementOnce(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            mMaxPointerCount = Math.max(mMaxPointerCount, event.getPointerCount());
            return mState == State.VERTICAL_TRACKING;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            switchTrackingPointer(event);
            return mState == State.VERTICAL_TRACKING || mState == State.HORIZONTAL_PENDING;
        }
        if (action == MotionEvent.ACTION_MOVE && mState == State.IDLE) {
            return lockDirectionIfNeeded(event, mayExpand, mayCollapse);
        }
        return mState == State.VERTICAL_TRACKING || mState == State.HORIZONTAL_PENDING;
    }

    boolean onTouchEvent(MotionEvent event, boolean mayExpand, boolean mayCollapse) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (mDownTime != event.getDownTime()) {
                beginMotion(event);
            }
            return true;
        }
        if (mDownTime != event.getDownTime()) {
            return false;
        }
        trackMovementOnce(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            mMaxPointerCount = Math.max(mMaxPointerCount, event.getPointerCount());
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            switchTrackingPointer(event);
            return true;
        }

        final int pointerIndex = findTrackingPointerIndex(event);
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        if (action == MotionEvent.ACTION_MOVE) {
            if (mState == State.IDLE) {
                lockDirectionIfNeeded(event, mayExpand, mayCollapse);
            }
            if (mState == State.VERTICAL_TRACKING) {
                final float maxHeight = Math.max(0f, mTarget.getMaxExpandedHeight());
                mTarget.setExpandedHeight(clamp(mInitialHeight + y - mInitialY, 0f, maxHeight));
                return true;
            }
            return mState == State.HORIZONTAL_PENDING;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            if (mState == State.VERTICAL_TRACKING) {
                final float maxHeight = Math.max(1f, mTarget.getMaxExpandedHeight());
                final boolean restoreExpanded = mInitialHeight > maxHeight * 0.5f;
                mState = State.VERTICAL_SETTLING;
                mTarget.settleExpandedHeight(restoreExpanded, 0f);
                recycleMotionTracking();
                return true;
            }
            resetMotion();
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (mState == State.HORIZONTAL_PENDING) {
                final float deltaX = x - mInitialX;
                if (Math.abs(deltaX) > getPageSwitchThreshold()) {
                    mState = State.HORIZONTAL_SWITCHING;
                    mTarget.setQuickSettingsPageFromGesture(deltaX < 0f, true);
                } else {
                    resetMotion();
                }
                return true;
            }
            if (mState == State.VERTICAL_TRACKING) {
                mVelocityTracker.computeCurrentVelocity(1000);
                final float velocityY = mVelocityTracker.getYVelocity(mTrackingPointer);
                final float velocityX = mVelocityTracker.getXVelocity(mTrackingPointer);
                final float vectorVelocity = (float) Math.hypot(velocityX, velocityY);
                final float maxHeight = Math.max(1f, mTarget.getMaxExpandedHeight());
                final boolean expandFromMotion =
                        vectorVelocity < mTarget.getMinFlingVelocity()
                                ? mTarget.getExpandedHeight() / maxHeight > 0.5f
                                : velocityY > 0f;
                // Opening and closing have opposite valid directions in BrightLine falsing.
                // Classify the intended endpoint first; a rejected close safely restores the
                // expanded shade, while a rejected open already has that same safe endpoint.
                final boolean expand = mTarget.isFalseTouch(x, y, expandFromMotion)
                        || expandFromMotion;
                mState = State.VERTICAL_SETTLING;
                mTarget.settleExpandedHeight(expand, velocityY);
                recycleMotionTracking();
                return true;
            }
            resetMotion();
        }
        return false;
    }

    void onVerticalSettled() {
        if (mState == State.VERTICAL_SETTLING) {
            mState = State.IDLE;
        }
    }

    void onPageSwitchFinished() {
        if (mState == State.HORIZONTAL_SWITCHING) {
            resetMotion();
        }
    }

    void cancel() {
        mTarget.cancelHeightAnimation();
        resetMotion();
    }

    void destroy() {
        mTarget.cancelHeightAnimation();
        resetMotion();
        mVelocityTracker.recycle();
    }

    @VisibleForTesting
    State getState() {
        return mState;
    }

    @VisibleForTesting
    float getDirectionLockThreshold() {
        return DIRECTION_LOCK_DESIGN_PX * getWidthScale();
    }

    @VisibleForTesting
    float getPageSwitchThreshold() {
        return PAGE_SWITCH_DESIGN_PX * getWidthScale();
    }

    private boolean lockDirectionIfNeeded(
            MotionEvent event, boolean mayExpand, boolean mayCollapse) {
        final int pointerIndex = findTrackingPointerIndex(event);
        final float deltaX = event.getX(pointerIndex) - mInitialX;
        final float deltaY = event.getY(pointerIndex) - mInitialY;
        final float threshold = getDirectionLockThreshold();
        if (mTarget.isFullyExpanded()
                && mTarget.canStartHorizontalPageSwitch(mInitialX, mInitialY)
                && Math.abs(deltaX) > threshold
                && Math.abs(deltaX) > Math.abs(deltaY)) {
            mState = State.HORIZONTAL_PENDING;
            return true;
        }
        final boolean directionAllowed = deltaY > 0f ? mayExpand : mayCollapse;
        if (directionAllowed
                && Math.abs(deltaY) > threshold
                && Math.abs(deltaY) > Math.abs(deltaX)) {
            mState = State.VERTICAL_TRACKING;
            mTarget.onVerticalTrackingStarted(mMaxPointerCount);
            return true;
        }
        return false;
    }

    private void beginMotion(MotionEvent event) {
        mTarget.cancelHeightAnimation();
        recycleMotionTracking();
        mDownTime = event.getDownTime();
        mTrackingPointer = event.getPointerId(0);
        mMaxPointerCount = event.getPointerCount();
        mInitialX = event.getX(0);
        mInitialY = event.getY(0);
        mInitialHeight = mTarget.getExpandedHeight();
        mState = State.IDLE;
        trackMovementOnce(event);
    }

    private void switchTrackingPointer(MotionEvent event) {
        final int upPointer = event.getPointerId(event.getActionIndex());
        if (upPointer != mTrackingPointer || event.getPointerCount() <= 1) {
            return;
        }
        final int newIndex = event.getActionIndex() == 0 ? 1 : 0;
        mTrackingPointer = event.getPointerId(newIndex);
        mInitialX = event.getX(newIndex);
        mInitialY = event.getY(newIndex);
        mInitialHeight = mTarget.getExpandedHeight();
    }

    private int findTrackingPointerIndex(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        return pointerIndex;
    }

    private float getWidthScale() {
        final float physicalWidth = Math.max(1f, mTarget.getPanelWidth());
        return Math.min(physicalWidth, MAX_CONTENT_WIDTH_DP * mDensity) / DESIGN_WIDTH;
    }

    private void trackMovementOnce(MotionEvent event) {
        if (event.getEventTime() == mLastTrackedEventTime
                && event.getActionMasked() == mLastTrackedAction) {
            return;
        }
        mVelocityTracker.addMovement(event);
        mLastTrackedEventTime = event.getEventTime();
        mLastTrackedAction = event.getActionMasked();
    }

    private void recycleMotionTracking() {
        mVelocityTracker.clear();
        mLastTrackedEventTime = -1L;
        mLastTrackedAction = -1;
    }

    private void resetMotion() {
        recycleMotionTracking();
        mState = State.IDLE;
        mTrackingPointer = -1;
        mMaxPointerCount = 1;
        mDownTime = -1L;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
