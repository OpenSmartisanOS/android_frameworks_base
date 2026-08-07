/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.internal.sidebar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/** Immutable snapshot of one task hosted by the source-built OneStep panel. @hide */
public final class OneStepTaskInfo implements Parcelable {
    public static final int RESULT_OK = 0;
    public static final int RESULT_UNAVAILABLE = 1;
    public static final int RESULT_NOT_FOUND = 2;
    public static final int RESULT_REJECTED = 3;
    public static final int RESULT_FAILED = 4;
    public static final int RESULT_TASK_REMOVED = 5;

    public static final int SOURCE_UNKNOWN = 0;
    public static final int SOURCE_LAUNCHER_RECENTS = 1;
    public static final int SOURCE_SIDEBAR_APP = 2;
    public static final int SOURCE_FOCUSED_TASK = 3;
    public static final int SOURCE_EXTERNAL_REOPEN = 4;

    public static final int STATE_PENDING = 0;
    public static final int STATE_EMBEDDED = 1;
    public static final int STATE_HIDDEN = 2;
    public static final int STATE_RESTORING = 3;
    public static final int STATE_FAILED = 4;

    public final int taskId;
    public final int userId;
    public final int slot;
    @Nullable public final ComponentName topActivity;
    @Nullable public final String label;
    @NonNull public final Rect bounds;
    public final int backgroundColor;
    public final int state;
    public final boolean visible;

    public OneStepTaskInfo(int taskId, int userId, int slot,
            @Nullable ComponentName topActivity, @Nullable String label, @Nullable Rect bounds,
            int backgroundColor, int state, boolean visible) {
        this.taskId = taskId;
        this.userId = userId;
        this.slot = slot;
        this.topActivity = topActivity;
        this.label = label;
        this.bounds = bounds != null ? new Rect(bounds) : new Rect();
        this.backgroundColor = backgroundColor;
        this.state = state;
        this.visible = visible;
    }

    private OneStepTaskInfo(Parcel source) {
        taskId = source.readInt();
        userId = source.readInt();
        slot = source.readInt();
        topActivity = source.readTypedObject(ComponentName.CREATOR);
        label = source.readString8();
        final Rect parcelBounds = source.readTypedObject(Rect.CREATOR);
        bounds = parcelBounds != null ? parcelBounds : new Rect();
        backgroundColor = source.readInt();
        state = source.readInt();
        visible = source.readBoolean();
    }

    @NonNull
    public OneStepTaskInfo withSlot(int newSlot, @Nullable Rect newBounds) {
        return new OneStepTaskInfo(taskId, userId, newSlot, topActivity, label, newBounds,
                backgroundColor, state, visible);
    }

    @NonNull
    public OneStepTaskInfo withState(int newState, boolean newVisible) {
        return new OneStepTaskInfo(taskId, userId, slot, topActivity, label, bounds,
                backgroundColor, newState, newVisible);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeInt(userId);
        dest.writeInt(slot);
        dest.writeTypedObject(topActivity, flags);
        dest.writeString8(label);
        dest.writeTypedObject(bounds, flags);
        dest.writeInt(backgroundColor);
        dest.writeInt(state);
        dest.writeBoolean(visible);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "OneStepTaskInfo{taskId=" + taskId + ", userId=" + userId + ", slot=" + slot
                + ", topActivity=" + topActivity + ", state=" + state + ", visible=" + visible
                + ", bounds=" + bounds + "}";
    }

    public static final @NonNull Creator<OneStepTaskInfo> CREATOR =
            new Creator<OneStepTaskInfo>() {
                @Override
                public OneStepTaskInfo createFromParcel(Parcel source) {
                    return new OneStepTaskInfo(source);
                }

                @Override
                public OneStepTaskInfo[] newArray(int size) {
                    return new OneStepTaskInfo[size];
                }
            };
}
