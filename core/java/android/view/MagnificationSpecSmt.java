/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pools;

/**
 * Smartisan window magnification state used by OneStep.
 *
 * <p>The parcel layout is ABI-compatible with Smartisan OS 8.5.3. Do not reorder fields.</p>
 *
 * @hide
 */
public class MagnificationSpecSmt implements Parcelable {
    public static final int TYPE_ZOOM_INVALID = -1;
    public static final int TYPE_ZOOM_DISPLAY = 0;
    public static final int TYPE_ZOOM_SIDEBAR_IN_LEFT = 1;
    public static final int TYPE_ZOOM_SIDEBAR_IN_RIGHT = 2;
    public static final int TYPE_ZOOM_PINNED = 3;

    public static final int ANIM_UNSET = 0;
    public static final int ANIM_START = 1;
    public static final int ANIM_END = 2;
    public static final int ANIM_CANCEL = 3;
    public static final String SPEC_ANIM_KEY = "spec_anim_key";

    private static final Pools.SynchronizedPool<MagnificationSpecSmt> sPool =
            new Pools.SynchronizedPool<>(20);

    public int type = TYPE_ZOOM_INVALID;
    public float scaleX = 1.0f;
    public float scaleY = 1.0f;
    public float offsetX;
    public float offsetY;
    public boolean anim;
    public long duration;
    @Nullable public Rect cropRect;
    @Nullable public IRemoteCallback animCallback;

    private MagnificationSpecSmt() {}

    @NonNull
    public static MagnificationSpecSmt obtain() {
        final MagnificationSpecSmt spec = sPool.acquire();
        return spec != null ? spec : new MagnificationSpecSmt();
    }

    @NonNull
    public static MagnificationSpecSmt obtain(@Nullable MagnificationSpecSmt other) {
        final MagnificationSpecSmt spec = obtain();
        spec.setTo(other);
        return spec;
    }

    public void initialize(int type, float scaleX, float scaleY, float offsetX, float offsetY) {
        this.type = type;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public void setTo(@Nullable MagnificationSpecSmt other) {
        if (other == null) {
            clear();
            return;
        }
        type = other.type;
        scaleX = other.scaleX;
        scaleY = other.scaleY;
        offsetX = other.offsetX;
        offsetY = other.offsetY;
        anim = other.anim;
        duration = other.duration;
        cropRect = other.cropRect != null ? new Rect(other.cropRect) : null;
        animCallback = other.animCallback;
    }

    public void clear() {
        type = TYPE_ZOOM_INVALID;
        scaleX = 1.0f;
        scaleY = 1.0f;
        offsetX = 0.0f;
        offsetY = 0.0f;
        anim = false;
        duration = 0;
        cropRect = null;
        animCallback = null;
    }

    public void recycle() {
        clear();
        sPool.release(this);
    }

    public boolean isNop() {
        return type == TYPE_ZOOM_INVALID
                || (scaleX == 1.0f && scaleY == 1.0f && offsetX == 0.0f && offsetY == 0.0f);
    }

    public MagnificationSpecSmt type(int value) {
        type = value;
        return this;
    }

    public MagnificationSpecSmt scale(float value) {
        scaleX = value;
        scaleY = value;
        return this;
    }

    public MagnificationSpecSmt offsetXY(float x, float y) {
        offsetX = x;
        offsetY = y;
        return this;
    }

    public MagnificationSpecSmt anim(boolean enabled, long animationDuration) {
        anim = enabled;
        duration = animationDuration;
        return this;
    }

    public MagnificationSpecSmt cropRect(@Nullable Rect value) {
        cropRect = value;
        return this;
    }

    public MagnificationSpecSmt animCallback(@Nullable IRemoteCallback value) {
        animCallback = value;
        return this;
    }

    public static boolean same(@Nullable MagnificationSpecSmt first,
            @Nullable MagnificationSpecSmt second) {
        return first == null ? second == null : first.equals(second);
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) return true;
        if (!(object instanceof MagnificationSpecSmt)) return false;
        final MagnificationSpecSmt other = (MagnificationSpecSmt) object;
        return type == other.type
                && Float.compare(scaleX, other.scaleX) == 0
                && Float.compare(scaleY, other.scaleY) == 0
                && Float.compare(offsetX, other.offsetX) == 0
                && Float.compare(offsetY, other.offsetY) == 0;
    }

    @Override
    public int hashCode() {
        int result = type;
        result = 31 * result + Float.floatToIntBits(scaleX);
        result = 31 * result + Float.floatToIntBits(scaleY);
        result = 31 * result + Float.floatToIntBits(offsetX);
        result = 31 * result + Float.floatToIntBits(offsetY);
        return result;
    }

    @Override
    public String toString() {
        return "<type:" + type + ",scaleX:" + scaleX + ",scaleY:" + scaleY
                + ",offsetX:" + offsetX + ",offsetY:" + offsetY + ",anim:" + anim
                + ",duration:" + duration + ",cropRect:" + cropRect + ">";
    }

    private void initFromParcel(@NonNull Parcel source) {
        type = source.readInt();
        scaleX = source.readFloat();
        scaleY = source.readFloat();
        offsetX = source.readFloat();
        offsetY = source.readFloat();
        anim = source.readByte() != 0;
        duration = source.readLong();
        cropRect = source.readParcelable(Rect.class.getClassLoader(), Rect.class);
        animCallback = IRemoteCallback.Stub.asInterface(source.readStrongBinder());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeFloat(scaleX);
        dest.writeFloat(scaleY);
        dest.writeFloat(offsetX);
        dest.writeFloat(offsetY);
        dest.writeByte(anim ? (byte) 1 : (byte) 0);
        dest.writeLong(duration);
        dest.writeParcelable(cropRect, flags);
        dest.writeStrongBinder(animCallback != null ? animCallback.asBinder() : null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<MagnificationSpecSmt> CREATOR =
            new Creator<MagnificationSpecSmt>() {
                @Override
                public MagnificationSpecSmt createFromParcel(Parcel source) {
                    final MagnificationSpecSmt spec = obtain();
                    spec.initFromParcel(source);
                    return spec;
                }

                @Override
                public MagnificationSpecSmt[] newArray(int size) {
                    return new MagnificationSpecSmt[size];
                }
            };
}
