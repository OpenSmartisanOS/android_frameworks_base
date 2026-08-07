/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package android.view;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/** Result payload used by Smartisan Foo/word lookup. @hide */
public class FooDisplayResultInfo implements Parcelable {
    public static final String FOO_RESULT_DISPLAY_WINDOW_TITLE = "FooResultDisplay";
    public static final String FOO_RESULT_DISPLAY_WINDOW_TITLE_2 = "FooResultDisplay2";

    public CharSequence windowTitle;
    public boolean valid;
    public CharSequence result;
    public Rect rect;
    public boolean fromFooDisplay;
    public int displayId;
    public int pointX;
    public int pointY;

    public FooDisplayResultInfo() {}

    public void readFromParcel(@NonNull Parcel source) {
        windowTitle = source.readCharSequence();
        valid = source.readInt() == 1;
        result = source.readCharSequence();
        if (source.readInt() == 1) {
            rect = new Rect();
            rect.readFromParcel(source);
        } else {
            rect = null;
        }
        fromFooDisplay = source.readInt() == 1;
        displayId = source.readInt();
        pointX = source.readInt();
        pointY = source.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeCharSequence(windowTitle);
        dest.writeInt(valid ? 1 : 0);
        dest.writeCharSequence(result);
        if (rect != null) {
            dest.writeInt(1);
            rect.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(fromFooDisplay ? 1 : 0);
        dest.writeInt(displayId);
        dest.writeInt(pointX);
        dest.writeInt(pointY);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<FooDisplayResultInfo> CREATOR =
            new Creator<FooDisplayResultInfo>() {
                @Override
                public FooDisplayResultInfo createFromParcel(Parcel source) {
                    final FooDisplayResultInfo result = new FooDisplayResultInfo();
                    result.readFromParcel(source);
                    return result;
                }

                @Override
                public FooDisplayResultInfo[] newArray(int size) {
                    return new FooDisplayResultInfo[size];
                }
            };
}
