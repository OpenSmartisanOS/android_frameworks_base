/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/** Base compatibility extension attached lazily to every {@link View}. @hide */
public abstract class ViewSmtBase {
    protected final View mView;

    public interface OnForceTouchListener {
        void onForceTouch();
    }

    public interface OnLongClickAndMoveListener {
        boolean onLongClickAndMove(View view);
    }

    public interface OnShowContextMenuListener {
        boolean OnShowContextMenu(View view, float x, float y);
    }

    public ViewSmtBase(View view) {
        mView = view;
    }

    public Bitmap createSnapshot(Bitmap.Config config, int backgroundColor,
            boolean skipChildren, float scale) {
        final int width = Math.max(1, Math.round(mView.getWidth() * scale));
        final int height = Math.max(1, Math.round(mView.getHeight() * scale));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, config);
        if ((backgroundColor & 0xff000000) != 0) bitmap.eraseColor(backgroundColor);
        final Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        canvas.translate(-mView.getScrollX(), -mView.getScrollY());
        mView.draw(canvas);
        return bitmap;
    }
}
