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

package com.android.systemui.statusbar.notification.row;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

/** Reproduces the original Smartisan InCall heads-up answer/hang-up pulse. */
final class InCallHeadsUpAnimator {
    private static final String INCALL_PACKAGE = "com.android.incallui";
    private static final long INITIAL_SCALE_DURATION_MS = 875L;
    private static final long ALTERNATE_DURATION_MS = 1750L;
    private static final long START_DELAY_MS = 1000L;

    private static boolean sChange;
    private static long sChangeTime;
    private static float sAnswerScaleHistory;

    private final Context mContext;

    @Nullable private AnimatorSet mAnimator;
    @Nullable private View mHangup;
    @Nullable private View mAnswer;

    InCallHeadsUpAnimator(Context context) {
        mContext = context;
    }

    void setRunning(boolean running, @Nullable StatusBarNotification sbn,
            @Nullable NotificationContentView layout) {
        if (!running) {
            stop();
            return;
        }
        if (sbn == null || !INCALL_PACKAGE.equals(sbn.getPackageName()) || layout == null) {
            stop();
            return;
        }
        final View headsUpChild = layout.getHeadsUpChild();
        if (headsUpChild == null) {
            stop();
            return;
        }
        final Context packageContext = sbn.getPackageContext(mContext);
        final int hangupId = packageContext.getResources().getIdentifier(
                "hangup_iv", "id", packageContext.getPackageName());
        final int answerId = packageContext.getResources().getIdentifier(
                "answer_iv", "id", packageContext.getPackageName());
        final View hangup = headsUpChild.findViewById(hangupId);
        final View answer = headsUpChild.findViewById(answerId);
        if (hangup == null || answer == null) {
            stop();
            return;
        }

        stop();
        mHangup = hangup;
        mAnswer = answer;
        hangup.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        answer.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        final AccelerateDecelerateInterpolator interpolator =
                new AccelerateDecelerateInterpolator();
        final ObjectAnimator hangupInitial = ObjectAnimator.ofPropertyValuesHolder(hangup,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, .8f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, .8f));
        hangupInitial.setDuration(INITIAL_SCALE_DURATION_MS);
        hangupInitial.setInterpolator(interpolator);
        final ObjectAnimator answerInitial = ObjectAnimator.ofPropertyValuesHolder(answer,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f));
        answerInitial.setDuration(INITIAL_SCALE_DURATION_MS);
        answerInitial.setInterpolator(interpolator);

        final ValueAnimator alternating = ValueAnimator.ofFloat(1f, 0f);
        alternating.setDuration(ALTERNATE_DURATION_MS);
        alternating.setStartDelay(INITIAL_SCALE_DURATION_MS);
        alternating.setInterpolator(interpolator);
        alternating.setRepeatCount(ValueAnimator.INFINITE);
        alternating.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                sChange = false;
                sChangeTime = System.currentTimeMillis();
                sAnswerScaleHistory = 1.2f;
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
                final long now = System.currentTimeMillis();
                if (now - sChangeTime > 1000L) {
                    sChange = !sChange;
                    sChangeTime = now;
                }
            }
        });
        alternating.addUpdateListener(animation -> {
            final float value = (float) animation.getAnimatedValue();
            final float hangupScale = (sChange ? value * .4f : (1f - value) * .4f) + .8f;
            final float answerScale = (sChange ? (1f - value) * .4f : value * .4f) + .8f;
            // Preserve the OEM discontinuity guard so a late frame never swaps button scales.
            if (Math.abs(sAnswerScaleHistory - answerScale) < .3f) {
                setScale(hangup, hangupScale);
                setScale(answer, answerScale);
                sAnswerScaleHistory = answerScale;
            }
        });

        final AnimatorSet animator = new AnimatorSet();
        animator.playTogether(hangupInitial, answerInitial, alternating);
        animator.setStartDelay(START_DELAY_MS);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                reset(hangup, answer);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                reset(hangup, answer);
            }
        });
        mAnimator = animator;

        final View.OnTouchListener touchListener = (view, event) -> {
            final AnimatorSet current = mAnimator;
            if (current == null) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                current.pause();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                current.resume();
            }
            return false;
        };
        hangup.setOnTouchListener(touchListener);
        answer.setOnTouchListener(touchListener);
        animator.start();
    }

    void stop() {
        final AnimatorSet animator = mAnimator;
        mAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        final View hangup = mHangup;
        final View answer = mAnswer;
        mHangup = null;
        mAnswer = null;
        if (hangup != null && answer != null) {
            reset(hangup, answer);
            hangup.setLayerType(View.LAYER_TYPE_NONE, null);
            answer.setLayerType(View.LAYER_TYPE_NONE, null);
            hangup.setOnTouchListener(null);
            answer.setOnTouchListener(null);
        }
    }

    private static void reset(View hangup, View answer) {
        setScale(hangup, 1f);
        setScale(answer, 1f);
    }

    private static void setScale(View view, float scale) {
        view.setScaleX(scale);
        view.setScaleY(scale);
    }
}
