/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.internal.sidebar;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Geometry shared by system_server, SystemUI and Sidebar for the phone OneStep panel. @hide */
public final class OneStepPanelSpec implements Parcelable {
    public static final int MODE_HIDDEN = -1;
    public static final int MODE_LEFT = 1;
    public static final int MODE_RIGHT = 2;
    public static final int SLOT_COUNT = 3;

    public final int displayId;
    public final int mode;
    @NonNull public final Rect topBounds;
    @NonNull public final Rect sideBounds;
    @NonNull public final List<Rect> slotBounds;
    public final boolean visible;

    public OneStepPanelSpec(int displayId, int mode, @NonNull Rect topBounds,
            @NonNull Rect sideBounds, @NonNull List<Rect> slotBounds, boolean visible) {
        this.displayId = displayId;
        this.mode = mode;
        this.topBounds = new Rect(topBounds);
        this.sideBounds = new Rect(sideBounds);
        final ArrayList<Rect> slots = new ArrayList<>(slotBounds.size());
        for (Rect bounds : slotBounds) slots.add(new Rect(bounds));
        this.slotBounds = Collections.unmodifiableList(slots);
        this.visible = visible;
    }

    private OneStepPanelSpec(Parcel source) {
        displayId = source.readInt();
        mode = source.readInt();
        final Rect parcelTop = source.readTypedObject(Rect.CREATOR);
        topBounds = parcelTop != null ? parcelTop : new Rect();
        final Rect parcelSide = source.readTypedObject(Rect.CREATOR);
        sideBounds = parcelSide != null ? parcelSide : new Rect();
        final ArrayList<Rect> slots = new ArrayList<>();
        source.readTypedList(slots, Rect.CREATOR);
        slotBounds = Collections.unmodifiableList(slots);
        visible = source.readBoolean();
    }

    @NonNull
    public static OneStepPanelSpec hidden(int displayId) {
        return new OneStepPanelSpec(displayId, MODE_HIDDEN, new Rect(), new Rect(),
                Collections.emptyList(), false);
    }

    @NonNull
    public Rect getSlotBounds(int slot) {
        return slot >= 0 && slot < slotBounds.size() ? new Rect(slotBounds.get(slot)) : new Rect();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(displayId);
        dest.writeInt(mode);
        dest.writeTypedObject(topBounds, flags);
        dest.writeTypedObject(sideBounds, flags);
        dest.writeTypedList(slotBounds, flags);
        dest.writeBoolean(visible);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "OneStepPanelSpec{displayId=" + displayId + ", mode=" + mode
                + ", top=" + topBounds + ", side=" + sideBounds + ", slots=" + slotBounds
                + ", visible=" + visible + "}";
    }

    public static final @NonNull Creator<OneStepPanelSpec> CREATOR =
            new Creator<OneStepPanelSpec>() {
                @Override
                public OneStepPanelSpec createFromParcel(Parcel source) {
                    return new OneStepPanelSpec(source);
                }

                @Override
                public OneStepPanelSpec[] newArray(int size) {
                    return new OneStepPanelSpec[size];
                }
            };
}
