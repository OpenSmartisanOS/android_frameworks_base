/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/** Point payload used by Smartisan Foo/word lookup. @hide */
public class FooPointInfo implements Parcelable {
    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_DISMISS = 1;

    public int type;
    public float x;
    public float y;
    public int displayId;

    public FooPointInfo() {}

    public FooPointInfo(float x, float y) {
        this(TYPE_DEFAULT, x, y, 0);
    }

    public FooPointInfo(float x, float y, int displayId) {
        this(TYPE_DEFAULT, x, y, displayId);
    }

    public FooPointInfo(int type, float x, float y) {
        this(type, x, y, 0);
    }

    public FooPointInfo(int type, float x, float y, int displayId) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.displayId = displayId;
    }

    public void readFromParcel(@NonNull Parcel source) {
        type = source.readInt();
        x = source.readFloat();
        y = source.readFloat();
        displayId = source.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeFloat(x);
        dest.writeFloat(y);
        // Preserve Smartisan's original (quirky) wire layout: written as float, read as int.
        dest.writeFloat(displayId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<FooPointInfo> CREATOR = new Creator<FooPointInfo>() {
        @Override
        public FooPointInfo createFromParcel(Parcel source) {
            final FooPointInfo info = new FooPointInfo();
            info.readFromParcel(source);
            return info;
        }

        @Override
        public FooPointInfo[] newArray(int size) {
            return new FooPointInfo[size];
        }
    };
}
