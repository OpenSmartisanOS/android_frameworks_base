/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensmartisanos.fakecall;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Lightweight lock-screen-safe incoming call surface for the fake-call feature. */
public final class FakeCallActivity extends Activity {
    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_CALLER_NUMBER = "caller_number";
    public static final String EXTRA_ANSWERED = "answered";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private View mAnswer;
    private long mConnectedAt;
    private boolean mAnswered;
    private boolean mCancelSent;

    private final Runnable mDurationTicker = new Runnable() {
        @Override
        public void run() {
            final long seconds = Math.max(0, (System.currentTimeMillis() - mConnectedAt) / 1000);
            mStatus.setText(String.format(
                    Locale.getDefault(),
                    "%02d:%02d",
                    seconds / 60,
                    seconds % 60));
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(createContentView());
        if (getIntent().getBooleanExtra(EXTRA_ANSWERED, false)) {
            showAnsweredState();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_ANSWERED, false)) {
            showAnsweredState();
        }
    }

    private View createContentView() {
        final int horizontalPadding = dp(28);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(horizontalPadding, dp(72), horizontalPadding, dp(54));
        root.setBackground(verticalGradient(0xff353942, 0xff111318));

        final String callerName = valueOrDefault(
                getIntent().getStringExtra(EXTRA_CALLER_NAME),
                R.string.default_caller_name);
        final String callerNumber = valueOrDefault(
                getIntent().getStringExtra(EXTRA_CALLER_NUMBER),
                R.string.default_caller_number);

        final TextView avatar = text(callerName.substring(0, 1), 36, Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        avatar.setBackground(circle(0xff737985));
        root.addView(avatar, new LinearLayout.LayoutParams(dp(96), dp(96)));

        final TextView name = text(callerName, 30, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams nameParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(28);
        root.addView(name, nameParams);

        final TextView number = text(callerNumber, 16, 0xffc9ccd2);
        number.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams numberParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        numberParams.topMargin = dp(8);
        root.addView(number, numberParams);

        mStatus = text(getString(R.string.incoming_call), 15, 0xffc9ccd2);
        mStatus.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(14);
        root.addView(mStatus, statusParams);

        final View spacer = new View(this);
        root.addView(
                spacer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        final LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(
                actions,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        final Button decline = callButton(getString(R.string.decline), 0xffdf4d4d);
        decline.setOnClickListener(view -> finishAndCancel());
        actions.addView(decline, new LinearLayout.LayoutParams(0, dp(64), 1f));

        final View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(28), 1));

        final Button answer = callButton(getString(R.string.answer), 0xff36a86b);
        answer.setOnClickListener(view -> answerCall());
        actions.addView(answer, new LinearLayout.LayoutParams(0, dp(64), 1f));
        mAnswer = answer;

        return root;
    }

    private void answerCall() {
        startService(
                new Intent(this, FakeCallService.class)
                        .setAction(FakeCallService.ACTION_ANSWER));
        showAnsweredState();
    }

    private void finishAndCancel() {
        sendCancel();
        finishAndRemoveTask();
    }

    private void showAnsweredState() {
        if (mAnswered) {
            return;
        }
        mAnswered = true;
        mAnswer.setVisibility(View.INVISIBLE);
        mStatus.setText(R.string.connected);
        mConnectedAt = System.currentTimeMillis();
        mHandler.postDelayed(mDurationTicker, 1000);
    }

    private void sendCancel() {
        if (!mCancelSent) {
            mCancelSent = true;
            startService(
                    new Intent(this, FakeCallService.class)
                            .setAction(FakeCallService.ACTION_CANCEL));
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (isFinishing()) {
            sendCancel();
        }
        super.onDestroy();
    }

    private Button callButton(String label, int color) {
        final Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setBackground(roundedRect(color, dp(32)));
        return button;
    }

    private TextView text(String value, float sizeSp, int color) {
        final TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        return textView;
    }

    private String valueOrDefault(String value, int fallbackRes) {
        return TextUtils.isEmpty(value) ? getString(fallbackRes) : value;
    }

    private GradientDrawable circle(int color) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable roundedRect(int color, float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable verticalGradient(int startColor, int endColor) {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {startColor, endColor});
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
