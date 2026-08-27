package com.android.systemui.statusbar.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.res.R;

/** SOS status-bar notification count with digits punched through the icon background. */
public class NotificationCountView extends ImageView {
    private final Paint mNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mCount;
    private int mTint;
    private Bitmap mBitmap;
    private Bitmap mSourceBitmap;
    private int mBackgroundRes;
    private int mRenderedCount = -1;
    private int mRenderedTint = Integer.MIN_VALUE;
    private final boolean mUseColoredBackground;
    private final float mMaxOffsetX;
    private final float mTextOffsetY;
    private CountChangeListener mCountChangeListener;

    public NotificationCountView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mNumberPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mNumberPaint.setTextAlign(Paint.Align.CENTER);
        mNumberPaint.setTypeface(Typeface.DEFAULT_BOLD);
        mNumberPaint.setFakeBoldText(true);
        mNumberPaint.setTextSize(getResources().getDimension(
                R.dimen.status_bar_notification_count_text_size));
        mMaxOffsetX = getResources().getDimension(
                R.dimen.status_bar_notification_count_max_offset_x);
        mTextOffsetY = getResources().getDimension(
                R.dimen.status_bar_notification_count_text_offset_y);
        mUseColoredBackground = getResources().getBoolean(
                R.bool.config_colored_notification_count);
    }

    public void setCountChangeListener(CountChangeListener listener) {
        mCountChangeListener = listener;
    }

    public void setCount(int count) {
        mCount = Math.max(count, 0);
        setVisibility(mCount == 0 ? GONE : VISIBLE);
        updateBitmap();
        requestLayout();
        if (getParent() instanceof android.view.View) {
            ((android.view.View) getParent()).requestLayout();
        }
        if (mCountChangeListener != null) {
            mCountChangeListener.onCountChanged(mCount);
        }
    }

    public int getCount() {
        return mCount;
    }

    /** Notified when the status-bar hidden-notification glyph changes. */
    public interface CountChangeListener {
        void onCountChanged(int count);
    }

    public void setIconTint(int tint) {
        mTint = tint;
        updateBitmap();
    }

    private void updateBitmap() {
        if (mCount == 0) return;
        final int background = backgroundResourceForCount(mCount, mUseColoredBackground);
        if (mBackgroundRes != background || mSourceBitmap == null || mBitmap == null) {
            if (mSourceBitmap != null && !mSourceBitmap.isRecycled()) mSourceBitmap.recycle();
            if (mBitmap != null && !mBitmap.isRecycled()) mBitmap.recycle();
            mSourceBitmap = BitmapFactory.decodeResource(getResources(), background);
            if (mSourceBitmap == null) return;
            mBitmap = mSourceBitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (mBitmap == null) return;
            mBackgroundRes = background;
            mRenderedCount = -1;
            mRenderedTint = Integer.MIN_VALUE;
            setImageBitmap(mBitmap);
        }
        if (mRenderedCount == mCount && mRenderedTint == mTint) return;

        final Canvas canvas = new Canvas(mBitmap);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (!mUseColoredBackground && mTint != 0) {
            backgroundPaint.setColorFilter(new PorterDuffColorFilter(mTint, PorterDuff.Mode.SRC_IN));
        }
        canvas.drawBitmap(mSourceBitmap, 0f, 0f, backgroundPaint);
        final Paint.FontMetrics metrics = mNumberPaint.getFontMetrics();
        final float baseline = mBitmap.getHeight() / 2f
                - (metrics.ascent + metrics.descent) / 2f + mTextOffsetY;
        final float centerX = mBitmap.getWidth() / 2f
                - (mCount > 99 ? mMaxOffsetX : 0) - 0.5f;
        canvas.drawText(displayTextForCount(mCount), centerX,
                baseline, mNumberPaint);
        mRenderedCount = mCount;
        mRenderedTint = mTint;
        invalidate();
    }

    @VisibleForTesting
    @DrawableRes
    static int backgroundResourceForCount(int count, boolean useColoredBackground) {
        if (count < 10) {
            return useColoredBackground
                    ? R.drawable.colored_smartisan_bg_notification_count_single
                    : R.drawable.smartisan_bg_notification_count_single;
        }
        if (count <= 99) {
            return useColoredBackground
                    ? R.drawable.colored_smartisan_bg_notification_count_double
                    : R.drawable.smartisan_bg_notification_count_double;
        }
        return useColoredBackground
                ? R.drawable.colored_smartisan_bg_notification_count_max
                : R.drawable.smartisan_bg_notification_count_max;
    }

    @VisibleForTesting
    static String displayTextForCount(int count) {
        return Integer.toString(Math.min(Math.max(count, 0), 99));
    }
}
