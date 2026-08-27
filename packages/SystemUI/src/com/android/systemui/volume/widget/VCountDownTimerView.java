package com.android.systemui.volume.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.android.systemui.res.R;

import java.util.Locale;

/**
 * R2 twelve-position timed-mute selector and active countdown renderer.
 *
 * <p>The View owns only drawing, touch and its single main-thread display tick. Settings,
 * AlarmManager and audio ownership remain in Android 16 controllers.</p>
 */
public class VCountDownTimerView extends View {
    public interface VCountDownStatusListener {
        void onCancel();
        void onFinish();
        void onStart(int seconds);
    }

    private enum Mode { SELECTOR, COUNTDOWN }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mActiveMarker;
    private final Drawable mInactiveMarker;
    private final Drawable mProgressBridge;
    private final Drawable mProgressFullBridge;
    private final Runnable mTick = this::tick;

    private String[] mLabels;
    private int[] mSeconds;
    private int mSelected;
    private Mode mMode = Mode.SELECTOR;
    private long mDeadlineMillis;
    private long mDurationMillis;
    private boolean mFinishDispatched;
    private boolean mTracking;
    private VCountDownStatusListener mListener;

    public VCountDownTimerView(Context context) {
        this(context, null);
    }

    public VCountDownTimerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VCountDownTimerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mLabels = getResources().getStringArray(R.array.volume_dialog_mute_time_labels);
        mSeconds = getResources().getIntArray(R.array.volume_dialog_mute_time_seconds);
        mActiveMarker = context.getDrawable(R.drawable.mute_time_tick_mark_track_blue);
        mInactiveMarker = context.getDrawable(R.drawable.mute_time_tick_mark_track_gray);
        mProgressBridge = context.getDrawable(R.drawable.mute_timer_progress_bridge);
        mProgressFullBridge = context.getDrawable(R.drawable.mute_timer_progress_full_bridge);
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateContentDescription();
    }

    public void setCountDownSeconds(int[] seconds) {
        if (seconds == null || seconds.length < 2) {
            throw new IllegalArgumentException("seconds array must contain at least two entries");
        }
        mSeconds = seconds.clone();
        mSelected = Math.min(mSelected, mSeconds.length - 1);
        invalidate();
    }

    public void setCountDownListener(@Nullable VCountDownStatusListener listener) {
        mListener = listener;
    }

    public int getSelectedSeconds() {
        return mSeconds[Math.min(mSelected, mSeconds.length - 1)];
    }

    public void setSelectedSeconds(int seconds) {
        mSelected = nearestIndex(seconds);
        updateContentDescription();
        invalidate();
    }

    public void showSelector() {
        stopTicking();
        mMode = Mode.SELECTOR;
        mFinishDispatched = false;
        setEnabled(true);
        updateContentDescription();
        invalidate();
    }

    public void showCountdown(long deadlineMillis) {
        showCountdown(deadlineMillis, 0);
    }

    public void showCountdown(long deadlineMillis, int durationSeconds) {
        if (mMode == Mode.COUNTDOWN && mDeadlineMillis == deadlineMillis) {
            tick();
            return;
        }
        long remaining = Math.max(0L, deadlineMillis - System.currentTimeMillis());
        mSelected = durationSeconds > 0
                ? nearestIndex(durationSeconds)
                : ceilingIndex((int) Math.min(Integer.MAX_VALUE,
                        (remaining + 999L) / 1000L));
        mDurationMillis = Math.max(1L,
                (durationSeconds > 0 ? durationSeconds : mSeconds[mSelected]) * 1000L);
        mDeadlineMillis = deadlineMillis;
        mMode = Mode.COUNTDOWN;
        mFinishDispatched = false;
        setEnabled(false);
        updateContentDescription();
        tick();
    }

    public void stop() {
        stopTicking();
        mTracking = false;
        mFinishDispatched = false;
    }

    private int nearestIndex(int seconds) {
        int best = 0;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < mSeconds.length; i++) {
            int d = Math.abs(mSeconds[i] - seconds);
            if (d < distance) {
                best = i;
                distance = d;
            }
        }
        return best;
    }

    private int ceilingIndex(int seconds) {
        for (int i = 0; i < mSeconds.length; i++) {
            if (mSeconds[i] >= seconds) return i;
        }
        return mSeconds.length - 1;
    }

    private void tick() {
        removeCallbacks(mTick);
        if (mMode != Mode.COUNTDOWN || !isAttachedToWindow()) return;
        long remaining = Math.max(0L, mDeadlineMillis - System.currentTimeMillis());
        updateContentDescription();
        invalidate();
        if (remaining == 0L) {
            if (!mFinishDispatched) {
                mFinishDispatched = true;
                if (mListener != null) mListener.onFinish();
            }
            return;
        }
        long nextSecond = 1000L - (System.currentTimeMillis() % 1000L);
        postDelayed(mTick, Math.max(16L, nextSecond));
    }

    private void stopTicking() {
        removeCallbacks(mTick);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mMode == Mode.COUNTDOWN) tick();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTicking();
        mTracking = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mMode == Mode.COUNTDOWN) {
            drawCountdown(canvas);
        } else {
            drawSelector(canvas);
        }
    }

    private void drawSelector(Canvas canvas) {
        final float scale = Math.max(.01f, getWidth() / 180f);
        final float cx = getWidth() / 2f;
        final float top = 24f * scale;
        final float bottom = getHeight() - 24f * scale;
        final float step = (bottom - top) / Math.max(1, mLabels.length - 1);
        final int markerWidth = Math.max(1, Math.round(9f * scale));
        final int markerHeight = Math.max(1, Math.round(9f * scale));
        mPaint.setTextSize(30f * scale);
        mPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < mLabels.length; i++) {
            // The original maps index 0 (15 min) to the bottom and 8 h to the top.
            float y = bottom - step * i;
            Drawable marker = i <= mSelected ? mActiveMarker : mInactiveMarker;
            int markerLeft = Math.round(cx - markerWidth / 2f);
            int markerTop = Math.round(y - markerHeight / 2f);
            marker.setBounds(markerLeft, markerTop, markerLeft + markerWidth,
                    markerTop + markerHeight);
            marker.draw(canvas);
            mPaint.setColor(i == mSelected ? 0xff3d7ff2 : 0x4c000000);
            mPaint.setTypeface(Typeface.defaultFromStyle(i == mSelected
                    ? Typeface.BOLD : Typeface.NORMAL));
            canvas.drawText(mLabels[i], cx,
                    y - (mPaint.ascent() + mPaint.descent()) / 2f, mPaint);
        }
    }

    private void drawCountdown(Canvas canvas) {
        final long remaining = Math.max(0L, mDeadlineMillis - System.currentTimeMillis());
        final float fraction = Math.max(0f, Math.min(1f,
                remaining / (float) Math.max(1L, mDurationMillis)));
        final float scale = Math.max(.01f, getWidth() / 180f);
        final float top = 0f;
        final float bottom = getHeight();
        final float selectedStart = bottom - (bottom - 129f * scale)
                * mSelected / Math.max(1f, mSeconds.length - 1f);
        final float progressTop = selectedStart + (bottom - selectedStart) * (1f - fraction);
        Drawable bridge = progressTop <= 1f ? mProgressFullBridge : mProgressBridge;
        bridge.setBounds(0, Math.max(0, Math.round(progressTop)), getWidth(), Math.round(bottom));
        bridge.draw(canvas);

        mPaint.setColor(0xff3d7ff2);
        mPaint.setTextSize(30f * scale);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setTypeface(Typeface.DEFAULT_BOLD);
        String text = formatTime((remaining + 999L) / 1000L);
        float baseline = getHeight() * .46f - (mPaint.ascent() + mPaint.descent()) / 2f;
        canvas.drawText(text, getWidth() / 2f, baseline, mPaint);
    }

    private static String formatTime(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds / 60L) % 60L;
        long secs = seconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || mMode != Mode.SELECTOR) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracking = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                updateSelection(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateSelection(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                updateSelection(event.getY());
                mTracking = false;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                return true;
            default:
                return false;
        }
    }

    private void updateSelection(float y) {
        float scale = Math.max(.01f, getWidth() / 180f);
        float top = 24f * scale;
        float bottom = getHeight() - 24f * scale;
        float clamped = Math.max(top, Math.min(bottom, y));
        int next = Math.round((bottom - clamped) / Math.max(1f, bottom - top)
                * (mLabels.length - 1));
        if (next != mSelected) {
            mSelected = next;
            updateContentDescription();
            invalidate();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (mMode == Mode.SELECTOR && mListener != null) {
            mListener.onStart(getSelectedSeconds());
        }
        return true;
    }

    private void updateContentDescription() {
        if (mMode == Mode.COUNTDOWN) {
            long remaining = Math.max(0L, mDeadlineMillis - System.currentTimeMillis());
            setContentDescription(getResources().getString(
                    R.string.volume_dialog_mute_remaining, formatTime((remaining + 999L) / 1000L)));
        } else {
            setContentDescription(mLabels[Math.min(mSelected, mLabels.length - 1)]);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mMode == Mode.SELECTOR) {
            info.setClassName(android.widget.SeekBar.class.getName());
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, mSeconds.length - 1,
                    mSelected));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (mMode == Mode.SELECTOR && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            int delta = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1;
            int next = Math.max(0, Math.min(mSeconds.length - 1, mSelected + delta));
            if (next != mSelected) {
                mSelected = next;
                updateContentDescription();
                invalidate();
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            }
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
