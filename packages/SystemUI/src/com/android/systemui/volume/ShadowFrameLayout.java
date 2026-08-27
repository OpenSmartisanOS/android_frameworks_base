package com.android.systemui.volume;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Inflation-compatible R2 shadow host; the original shadow remains a 9-patch background. */
public class ShadowFrameLayout extends FrameLayout {
    public ShadowFrameLayout(Context context) { super(context); }
    public ShadowFrameLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    public ShadowFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
