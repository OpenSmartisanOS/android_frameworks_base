package com.android.systemui.volume;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.transition.Transition;
import android.transition.TransitionValues;
import android.view.ViewGroup;

/** R2 background-color half of the 300 ms single/triple-column transition. */
public class CustomColorTransition extends Transition {
    private static final String PROPERTY = "sos:r2Volume:backgroundColor";

    private void capture(TransitionValues values) {
        if (values.view.getBackground() instanceof GradientDrawable) {
            ColorStateList color = ((GradientDrawable) values.view.getBackground()).getColor();
            if (color != null) values.values.put(PROPERTY, color.getDefaultColor());
        }
    }

    @Override public void captureStartValues(TransitionValues values) { capture(values); }
    @Override public void captureEndValues(TransitionValues values) { capture(values); }

    @Override
    public Animator createAnimator(ViewGroup sceneRoot, TransitionValues start,
            TransitionValues end) {
        if (start == null || end == null) return null;
        Integer from = (Integer) start.values.get(PROPERTY);
        Integer to = (Integer) end.values.get(PROPERTY);
        if (from == null || to == null || from.equals(to)
                || !(end.view.getBackground() instanceof GradientDrawable)) return null;
        GradientDrawable target = (GradientDrawable) end.view.getBackground();
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.addUpdateListener(value -> target.setColor((Integer) value.getAnimatedValue()));
        return animator;
    }
}
