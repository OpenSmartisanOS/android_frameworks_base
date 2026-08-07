/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.util;

import android.annotation.NonNull;
import android.os.Parcel;
import android.view.MagnificationSpecSmt;

/** Smartisan extensions carried with a merged window configuration. @hide */
public class MergedConfigurationSmtEx {
    public boolean mHasImeFocus = true;
    public final MagnificationSpecSmt mMagnificationSpec = MagnificationSpecSmt.obtain();

    public void apply(MagnificationSpecSmt spec) {
        mMagnificationSpec.setTo(spec);
    }

    public void setTo(@NonNull MergedConfigurationSmtEx other) {
        mHasImeFocus = other.mHasImeFocus;
        mMagnificationSpec.setTo(other.mMagnificationSpec);
    }

    public void reset() {
        mHasImeFocus = true;
        mMagnificationSpec.clear();
    }

    public void readFromParcel(@NonNull Parcel source) {
        final MagnificationSpecSmt spec = source.readParcelable(
                MagnificationSpecSmt.class.getClassLoader(), MagnificationSpecSmt.class);
        mMagnificationSpec.setTo(spec);
        if (spec != null) spec.recycle();
        mHasImeFocus = source.readInt() == 1;
    }

    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mMagnificationSpec, flags);
        dest.writeInt(mHasImeFocus ? 1 : 0);
    }
}
