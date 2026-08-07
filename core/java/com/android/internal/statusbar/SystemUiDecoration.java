/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.internal.statusbar;

import android.os.Parcel;
import android.os.Parcelable;

/** Smartisan system-bar color metadata carried in window layout parameters. @hide */
public class SystemUiDecoration implements Parcelable {
    public static int USER_CUSTOMIZED = 0;
    public static int COLOR_PICKER_CUSTOMIZED = 1;
    public static int COLOR_PICKER_CHANGE = 2;
    public static int WAITING_CUSTOMIZED = 3;

    public String mPackageName;
    public int mIconAndTextColor;
    public int mNotificationNumberColor;
    public int mStatusBarBackground;
    public int mNavigationBarBackground;
    public int mCustomMode = USER_CUSTOMIZED;

    public SystemUiDecoration(Parcel parcel) {
        mPackageName = parcel.readString();
        mIconAndTextColor = parcel.readInt();
        mNotificationNumberColor = parcel.readInt();
        mStatusBarBackground = parcel.readInt();
        mNavigationBarBackground = parcel.readInt();
        mCustomMode = parcel.readInt();
    }

    public SystemUiDecoration(String packageName, int iconAndTextColor,
            int notificationNumberColor, int statusBarBackground,
            int navigationBarBackground) {
        this(packageName, iconAndTextColor, notificationNumberColor, statusBarBackground,
                navigationBarBackground, USER_CUSTOMIZED);
    }

    public SystemUiDecoration(String packageName, int iconAndTextColor,
            int notificationNumberColor, int statusBarBackground,
            int navigationBarBackground, int customMode) {
        mPackageName = packageName;
        mIconAndTextColor = iconAndTextColor;
        mNotificationNumberColor = notificationNumberColor;
        mStatusBarBackground = statusBarBackground;
        mNavigationBarBackground = navigationBarBackground;
        mCustomMode = customMode;
    }

    public static String toModeString(int mode) {
        if (mode == USER_CUSTOMIZED) return "USER_CUSTOMIZED";
        if (mode == COLOR_PICKER_CUSTOMIZED) return "COLOR_PICKER_CUSTOMIZED";
        if (mode == COLOR_PICKER_CHANGE) return "COLOR_PICKER_CHANGE";
        if (mode == WAITING_CUSTOMIZED) return "WAITING_CUSTOMIZED";
        return "null";
    }

    @Override
    public SystemUiDecoration clone() {
        return new SystemUiDecoration(mPackageName, mIconAndTextColor,
                mNotificationNumberColor, mStatusBarBackground, mNavigationBarBackground,
                mCustomMode);
    }

    /** Alias retained for decompilers and vendor code generated against renamed metadata. */
    public SystemUiDecoration m64clone() {
        return clone();
    }

    public boolean equals(SystemUiDecoration other) {
        return other != null
                && mIconAndTextColor == other.mIconAndTextColor
                && mNotificationNumberColor == other.mNotificationNumberColor
                && mStatusBarBackground == other.mStatusBarBackground
                && mNavigationBarBackground == other.mNavigationBarBackground
                && mCustomMode == other.mCustomMode;
    }

    public void readFromParcel(Parcel parcel) {
        mPackageName = parcel.readString();
        mIconAndTextColor = parcel.readInt();
        mNotificationNumberColor = parcel.readInt();
        mStatusBarBackground = parcel.readInt();
        mNavigationBarBackground = parcel.readInt();
        mCustomMode = parcel.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeInt(mIconAndTextColor);
        parcel.writeInt(mNotificationNumberColor);
        parcel.writeInt(mStatusBarBackground);
        parcel.writeInt(mNavigationBarBackground);
        parcel.writeInt(mCustomMode);
    }

    @Override
    public String toString() {
        return "(SystemUiDecoration (pkg = " + mPackageName
                + " icon and text color = 0x" + Integer.toHexString(mIconAndTextColor)
                + " notification number color = 0x"
                + Integer.toHexString(mNotificationNumberColor)
                + " status bar background id = 0x"
                + Integer.toHexString(mStatusBarBackground)
                + " navigation bar background id = 0x"
                + Integer.toHexString(mNavigationBarBackground)
                + " decoration mode:" + toModeString(mCustomMode) + ")";
    }

    public static final Parcelable.Creator<SystemUiDecoration> CREATOR =
            new Parcelable.Creator<SystemUiDecoration>() {
                @Override
                public SystemUiDecoration createFromParcel(Parcel parcel) {
                    return new SystemUiDecoration(parcel);
                }

                @Override
                public SystemUiDecoration[] newArray(int size) {
                    return new SystemUiDecoration[size];
                }
            };
}
