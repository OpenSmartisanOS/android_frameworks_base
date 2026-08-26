/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.notification.emptyshade.ui.view;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** The canonical empty-shade view, backed exclusively by the original R2 presentation. */
public class EmptyShadeView extends StackScrollerDecorView {
    private static final Uri WEATHER_URI =
            Uri.parse("content://app.smartisanweather.revived.lockscreen/current");
    private static final String METHOD_GET_CURRENT_WEATHER = "get_current";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private int mGeneration;
    private boolean mRegistered;
    private boolean mWeatherRequestInFlight;
    private boolean mWeatherRequestPending;

    private View mContentView;
    private View mDateWeatherContainer;
    private TextView mTimeView;
    private TextView mAmPmView;
    private TextView mDayView;
    private TextView mDateView;
    private TextView mWeatherView;
    private View mEmptyLogo;
    private TextView mEmptyText;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateDateTime();
            final String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)
                    || Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                requestWeather();
            }
        }
    };

    private final ContentObserver mWeatherObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            requestWeather();
        }
    };

    public EmptyShadeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContentView = requireViewById(R.id.empty_shade_content);
        mDateWeatherContainer = requireViewById(R.id.empty_shade_datetime_weather);
        mTimeView = requireViewById(R.id.empty_shade_time);
        mAmPmView = requireViewById(R.id.empty_shade_am_pm);
        mDayView = requireViewById(R.id.empty_shade_day);
        mDateView = requireViewById(R.id.empty_shade_date);
        mWeatherView = requireViewById(R.id.empty_shade_weather);
        mEmptyLogo = requireViewById(R.id.empty_shade_logo);
        mEmptyText = requireViewById(R.id.no_notifications);

        final Typeface clockTypeface =
                ResourcesCompat.getFont(getContext(), R.font.sos_android_clock);
        if (clockTypeface != null) {
            mTimeView.setTypeface(clockTypeface);
            mAmPmView.setTypeface(clockTypeface);
        }
        applyOriginalColors();
        updateDateTime();
        applyResponsiveGeometry(false);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.empty_shade_content);
    }

    @Override
    protected View findSecondaryView() {
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mRegistered) {
            return;
        }
        mRegistered = true;
        mGeneration++;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        getContext().registerReceiver(
                mReceiver, filter, null, mMainHandler, Context.RECEIVER_NOT_EXPORTED);
        try {
            getContext().getContentResolver().registerContentObserver(
                    WEATHER_URI, true, mWeatherObserver);
        } catch (RuntimeException ignored) {
            // Weather is optional; broadcasts and the initial query remain available.
        }
        updateDateTime();
        requestWeather();
    }

    @Override
    protected void onDetachedFromWindow() {
        mGeneration++;
        mWeatherRequestPending = false;
        if (mRegistered) {
            try {
                getContext().unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException ignored) {
                // A configuration teardown may already have removed the receiver.
            }
            try {
                getContext().getContentResolver().unregisterContentObserver(mWeatherObserver);
            } catch (RuntimeException ignored) {
                // Provider teardown must not prevent shade detach.
            }
            mRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDateTime();
        applyResponsiveGeometry(true);
        requestWeather();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            applyResponsiveGeometry(false);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // NSSL measures decor children with an UNSPECIFIED height. R2's empty page is exactly one
        // physical shade panel tall, so normalize that contract before the base class measures it.
        int panelHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (panelHeight <= 0) {
            panelHeight = getResources().getDisplayMetrics().heightPixels;
        }
        super.onMeasure(
                widthMeasureSpec, MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY));
    }

    /** Re-applies fixed R2 colors after Android's notification palette changes. */
    public void applyOriginalColors() {
        if (mTimeView == null) {
            return;
        }
        mTimeView.setTextColor(0x9a000000);
        mAmPmView.setTextColor(0x9a000000);
        mDayView.setTextColor(0x66000000);
        mDateView.setTextColor(0x66000000);
        mWeatherView.setTextColor(0x66000000);
        mEmptyText.setTextColor(0x26000000);
    }

    private void updateDateTime() {
        if (mTimeView == null) {
            return;
        }
        final Calendar calendar = Calendar.getInstance();
        final boolean use24Hour = DateFormat.is24HourFormat(getContext());
        String time = DateFormat.format(use24Hour ? "kk:mm" : "hh:mm", calendar).toString();
        if (!use24Hour && !time.isEmpty() && time.charAt(0) == '0') {
            time = time.substring(1);
        }
        mTimeView.setText(time);
        if (use24Hour) {
            mAmPmView.setVisibility(GONE);
        } else {
            mAmPmView.setText(calendar.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
            mAmPmView.setVisibility(VISIBLE);
        }
        mDayView.setText(DateFormat.format("E", calendar));
        mDateView.setText(formatOriginalDate(calendar));
    }

    private CharSequence formatOriginalDate(Calendar calendar) {
        final Locale locale = getResources().getConfiguration().getLocales().get(0);
        final int month = calendar.get(Calendar.MONTH) + 1;
        final int day = calendar.get(Calendar.DAY_OF_MONTH);
        if (Locale.CHINESE.getLanguage().equals(locale.getLanguage())) {
            return String.format(locale, "%02d.%02d", month, day);
        }
        return DateFormat.format("MMM", calendar) + "." + day;
    }

    private void requestWeather() {
        if (!isAttachedToWindow()) {
            return;
        }
        if (mWeatherRequestInFlight) {
            mWeatherRequestPending = true;
            return;
        }
        mWeatherRequestInFlight = true;
        final int generation = mGeneration;
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle result;
            try {
                result = getContext().getContentResolver().call(
                        WEATHER_URI, METHOD_GET_CURRENT_WEATHER, null, null);
            } catch (RuntimeException ignored) {
                mMainHandler.post(() -> finishWeatherRequest(generation, null));
                return;
            }
            mMainHandler.post(() -> finishWeatherRequest(generation, result));
        });
    }

    private void finishWeatherRequest(int generation, @Nullable Bundle result) {
        mWeatherRequestInFlight = false;
        publishWeather(generation, result);
        if (mWeatherRequestPending && isAttachedToWindow()) {
            mWeatherRequestPending = false;
            requestWeather();
        } else {
            mWeatherRequestPending = false;
        }
    }

    private void publishWeather(int generation, @Nullable Bundle result) {
        if (generation != mGeneration || !isAttachedToWindow() || mWeatherView == null) {
            return;
        }
        if (result == null || result.getBoolean("setupRequired", false)) {
            mWeatherView.setText(null);
            return;
        }
        final List<String> parts = new ArrayList<>();
        final String condition = result.getString("condition", "").trim();
        if (!condition.isEmpty()) {
            parts.add(condition);
        }
        final Object temperatureValue = result.get("temperature");
        if (temperatureValue != null) {
            String temperature = temperatureValue.toString();
            final int decimal = temperature.indexOf('.');
            if (decimal > 0) {
                temperature = temperature.substring(0, decimal);
            }
            final String unit = result.getString("unit", "C");
            parts.add(temperature + (unit.startsWith("°") ? " " + unit : " °" + unit));
        }
        final Object aqi = result.get("aqi");
        if (aqi != null && !"null".equals(aqi.toString())) {
            parts.add("AQI " + aqi);
        }
        mWeatherView.setText(String.join("   ", parts));
    }

    private void applyResponsiveGeometry(boolean animateLogo) {
        if (mContentView == null) {
            return;
        }
        final int width = getWidth() > 0
                ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        final float density = Math.max(0.01f, getResources().getDisplayMetrics().density);
        final float contentWidth = Math.min(width, 480f * density);
        final float scale = Math.max(0.01f, contentWidth / 1080f);

        setTextSizePx(mTimeView, 144f * scale);
        setTextSizePx(mAmPmView, 138f * scale);
        setTextSizePx(mDayView, 40.5f * scale);
        setTextSizePx(mDateView, 40.5f * scale);
        setTextSizePx(mWeatherView, 40.5f * scale);
        setTextSizePx(mEmptyText, 48f * scale);

        setHeight(requireViewById(R.id.empty_shade_time_row), Math.round(158f * scale));
        setTopMargin(mDateWeatherContainer, Math.round(140f * scale));
        setStartPadding(mAmPmView, Math.round(20f * scale));
        setStartPadding(mDateView, Math.round(20f * scale));
        setTopMargin(mWeatherView, Math.round(15f * scale));
        setSquareSize(mEmptyLogo, Math.round(180f * scale));
        mEmptyText.setPadding(
                mEmptyText.getPaddingLeft(), mEmptyText.getPaddingTop(),
                mEmptyText.getPaddingRight(), Math.round(227.5f * scale));
        final boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        final float logoScale = portrait ? 1f : 0.5f;
        final float logoTranslation = portrait ? 0f : 90f * scale;
        mEmptyLogo.animate().cancel();
        if (animateLogo) {
            mEmptyLogo.animate().scaleX(logoScale).scaleY(logoScale)
                    .translationY(logoTranslation).setDuration(300L).start();
        } else {
            mEmptyLogo.setScaleX(logoScale);
            mEmptyLogo.setScaleY(logoScale);
            mEmptyLogo.setTranslationY(logoTranslation);
        }
    }

    private static void setTextSizePx(TextView view, float size) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private static void setHeight(View view, int height) {
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        params.height = height;
        view.setLayoutParams(params);
    }

    private static void setSquareSize(View view, int size) {
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = size;
        params.height = size;
        view.setLayoutParams(params);
    }

    private static void setTopMargin(View view, int margin) {
        final ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.topMargin = margin;
        view.setLayoutParams(params);
    }

    private static void setStartPadding(View view, int padding) {
        view.setPaddingRelative(
                padding, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
    }
}
