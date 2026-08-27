package com.android.systemui.volume;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;
import androidx.annotation.DrawableRes;

import com.android.systemui.res.R;

/**
 * Android 16 compatible implementation of the vertical slider used by the R2 volume panel.
 *
 * <p>The old widget mixed rendering, settings writes and parent scaling. This compatibility view
 * deliberately owns only the original drawing and touch contract; volume and brightness policy is
 * kept in {@link VolumeDialogImpl} and the modern platform controllers.</p>
 */
public class VerticalSeekBar extends View {
    /** Listener matching the original Smartisan widget contract. */
    public interface OnProgressChangedListener {
        void onProgressChanged(VerticalSeekBar seekBar, int progress, boolean fromUser);
        void onStartTrackingTouch(VerticalSeekBar seekBar);
        void onStopTrackingTouch(VerticalSeekBar seekBar);
        default void onCancelTrackingTouch(VerticalSeekBar seekBar) {
            onStopTrackingTouch(seekBar);
        }
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private float mMin;
    private float mMax = 100f;
    private float mProgress;
    private float mTrackWidth;
    private float mActiveTrackWidth;
    private float mThumbRadius;
    private float mDraggingThumbRadius;
    private int mSections = 10;
    private boolean mShowSections = true;
    private boolean mTouchToSeek = true;
    private boolean mTracking;
    private Drawable mInactiveMarker;
    private Drawable mActiveMarker;
    private boolean mActiveMarkerStyle;
    private boolean mMarkerStyleInitialized;
    private int mMarkerWidth;
    private int mMarkerHeight;
    private OnProgressChangedListener mListener;

    public VerticalSeekBar(Context context) {
        this(context, null);
    }

    public VerticalSeekBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VerticalSeekBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        final float density = getResources().getDisplayMetrics().density;
        mTrackWidth = 2f * density;
        mActiveTrackWidth = 4f * density;
        mThumbRadius = 4f * density;
        mDraggingThumbRadius = 7f * density;
        mMarkerWidth = Math.round(24f * density / 2.5f);
        mMarkerHeight = Math.round(21f * density / 2.5f);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.VerticalSeekBar,
                    defStyleAttr, 0);
            mMin = a.getFloat(R.styleable.VerticalSeekBar_vsb_min, mMin);
            mMax = a.getFloat(R.styleable.VerticalSeekBar_vsb_max, mMax);
            mProgress = a.getFloat(R.styleable.VerticalSeekBar_vsb_progress, mProgress);
            mTrackWidth = a.getDimension(R.styleable.VerticalSeekBar_vsb_track_size, mTrackWidth);
            mActiveTrackWidth = a.getDimension(
                    R.styleable.VerticalSeekBar_vsb_second_track_size, mActiveTrackWidth);
            mThumbRadius = a.getDimension(
                    R.styleable.VerticalSeekBar_vsb_thumb_radius, mThumbRadius);
            mDraggingThumbRadius = a.getDimension(
                    R.styleable.VerticalSeekBar_vsb_thumb_radius_on_dragging,
                    mDraggingThumbRadius);
            mSections = Math.max(1, a.getInt(
                    R.styleable.VerticalSeekBar_vsb_section_count, mSections));
            mShowSections = a.getBoolean(
                    R.styleable.VerticalSeekBar_vsb_show_section_mark, mShowSections);
            mTouchToSeek = a.getBoolean(
                    R.styleable.VerticalSeekBar_vsb_touch_to_seek, mTouchToSeek);
            a.recycle();
        }
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setRange(float min, float max) {
        if (max <= min) return;
        mMin = min;
        mMax = max;
        setProgress(mProgress, false, false);
    }

    public float getMax() {
        return mMax;
    }

    public float getMin() {
        return mMin;
    }

    public int getProgress() {
        return Math.round(mProgress);
    }

    public void setProgress(float progress) {
        setProgress(progress, false, false);
    }

    public void setProgress(float progress, boolean animate, boolean notify) {
        float clamped = Math.max(mMin, Math.min(mMax, progress));
        if (mProgress == clamped) return;
        mProgress = clamped;
        invalidate();
        if (notify && mListener != null) {
            mListener.onProgressChanged(this, getProgress(), false);
        }
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
    }

    public void setOnProgressChangedListener(@Nullable OnProgressChangedListener listener) {
        mListener = listener;
    }

    /** Selects the exact R2 active or inactive 9-patch marker family. */
    public void setMarkerStyle(boolean active) {
        if (mMarkerStyleInitialized && mActiveMarkerStyle == active) return;
        mMarkerStyleInitialized = true;
        mActiveMarkerStyle = active;
        setMarkerDrawables(
                active ? R.drawable.ic_smartisan_volume_panel_open_blue_mark_normal
                        : R.drawable.ic_smartisan_volume_panel_close_mark_normal,
                active ? R.drawable.ic_smartisan_volume_panel_open_blue_mark
                        : R.drawable.ic_smartisan_volume_panel_close_mark);
    }

    public void setMarkerDrawables(@DrawableRes int inactive, @DrawableRes int active) {
        mInactiveMarker = getContext().getDrawable(inactive);
        mActiveMarker = getContext().getDrawable(active);
        invalidate();
    }

    public void setMarkerSize(int width, int height) {
        mMarkerWidth = Math.max(1, width);
        mMarkerHeight = Math.max(1, height);
        invalidate();
    }

    public void setSectionCount(int sections) {
        mSections = Math.max(1, sections);
        invalidate();
    }

    /** Ends a gesture whose backing stream/session was invalidated without writing a value. */
    public void abortTracking() {
        if (!mTracking) return;
        mTracking = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mShowSections && mInactiveMarker != null && mActiveMarker != null) {
            drawR2Markers(canvas);
            return;
        }
        final float cx = getWidth() / 2f;
        final float top = getPaddingTop() + mDraggingThumbRadius;
        final float bottom = getHeight() - getPaddingBottom() - mDraggingThumbRadius;
        final float fraction = (mProgress - mMin) / (mMax - mMin);
        final float thumbY = bottom - fraction * (bottom - top);

        mPaint.setColor(getResources().getColor(R.color.volume_dialog_panel_track, getContext().getTheme()));
        mRect.set(cx - mTrackWidth / 2f, top, cx + mTrackWidth / 2f, bottom);
        canvas.drawRoundRect(mRect, mTrackWidth, mTrackWidth, mPaint);

        if (mShowSections) {
            mPaint.setStrokeWidth(Math.max(1f, mTrackWidth / 2f));
            for (int i = 0; i <= mSections; i++) {
                float y = top + (bottom - top) * i / mSections;
                canvas.drawLine(cx - mActiveTrackWidth * 1.5f, y,
                        cx + mActiveTrackWidth * 1.5f, y, mPaint);
            }
        }

        mPaint.setColor(getResources().getColor(
                R.color.volume_dialog_panel_progress, getContext().getTheme()));
        mRect.set(cx - mActiveTrackWidth / 2f, thumbY,
                cx + mActiveTrackWidth / 2f, bottom);
        canvas.drawRoundRect(mRect, mActiveTrackWidth, mActiveTrackWidth, mPaint);
        canvas.drawCircle(cx, thumbY, mTracking ? mDraggingThumbRadius : mThumbRadius, mPaint);
    }

    private void drawR2Markers(Canvas canvas) {
        final float top = getPaddingTop();
        final float bottom = getHeight() - getPaddingBottom();
        final float available = Math.max(0f, bottom - top - mMarkerHeight);
        final float step = mSections <= 1 ? 0f : available / (mSections - 1);
        final float fraction = (mProgress - mMin) / Math.max(1f, mMax - mMin);
        final int firstActive = Math.max(0, Math.min(mSections,
                (int) Math.floor((1f - fraction) * mSections)));
        final int left = Math.round((getWidth() - mMarkerWidth) / 2f);
        for (int i = 0; i < mSections; i++) {
            final int markerTop = Math.round(top + i * step);
            final Drawable marker = i < firstActive ? mInactiveMarker : mActiveMarker;
            marker.setBounds(left, markerTop, left + mMarkerWidth, markerTop + mMarkerHeight);
            marker.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!mTouchToSeek) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                mTracking = true;
                if (mListener != null) mListener.onStartTrackingTouch(this);
                updateFromTouch(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (mTracking) {
                    updateFromTouch(event.getY());
                    mTracking = false;
                    invalidate();
                    if (mListener != null) mListener.onStopTrackingTouch(this);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mTracking) {
                    mTracking = false;
                    invalidate();
                    if (mListener != null) mListener.onCancelTrackingTouch(this);
                }
                return true;
            default:
                return false;
        }
    }

    private void updateFromTouch(float y) {
        final float top = getPaddingTop() + mDraggingThumbRadius;
        final float bottom = getHeight() - getPaddingBottom() - mDraggingThumbRadius;
        final float fraction = 1f - Math.max(0f, Math.min(1f, (y - top) / (bottom - top)));
        mProgress = mMin + fraction * (mMax - mMin);
        invalidate();
        if (mListener != null) mListener.onProgressChanged(this, getProgress(), true);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, mMin, mMax, mProgress));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            float step = Math.max(1f, (mMax - mMin) / mSections);
            mProgress = Math.max(mMin, Math.min(mMax,
                    mProgress + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            ? step : -step)));
            invalidate();
            if (mListener != null) {
                mListener.onStartTrackingTouch(this);
                mListener.onProgressChanged(this, getProgress(), true);
                mListener.onStopTrackingTouch(this);
            }
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
