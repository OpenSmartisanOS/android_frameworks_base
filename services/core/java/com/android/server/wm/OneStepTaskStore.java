/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.wm;

import android.content.ComponentName;
import android.graphics.Rect;

import com.android.internal.sidebar.OneStepPanelSpec;
import com.android.internal.sidebar.OneStepTaskInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** Small deterministic store for OneStep's three committed task slots. */
final class OneStepTaskStore {
    private final OneStepTaskInfo[] mSlots = new OneStepTaskInfo[OneStepPanelSpec.SLOT_COUNT];

    OneStepTaskInfo find(int taskId) {
        for (OneStepTaskInfo task : mSlots) {
            if (task == null) continue;
            if (task.taskId == taskId) return task;
        }
        return null;
    }

    /** Exact component first, then a package match only when it identifies one task. */
    OneStepTaskInfo findForLaunch(ComponentName component, String packageName, int userId) {
        OneStepTaskInfo packageMatch = null;
        for (OneStepTaskInfo task : mSlots) {
            if (task == null || task.userId != userId || task.topActivity == null) continue;
            if (component != null && component.equals(task.topActivity)) return task;
            final String candidatePackage = component != null
                    ? component.getPackageName() : packageName;
            if (candidatePackage == null
                    || !candidatePackage.equals(task.topActivity.getPackageName())) continue;
            if (packageMatch != null) return null;
            packageMatch = task;
        }
        return packageMatch;
    }

    int chooseSlot(int preferredSlot) {
        if (preferredSlot >= 0 && preferredSlot < mSlots.length
                && mSlots[preferredSlot] == null) {
            return preferredSlot;
        }
        for (int slot = 0; slot < mSlots.length; slot++) {
            if (mSlots[slot] == null) return slot;
        }
        return mSlots.length - 1;
    }

    // Kept for the existing wmtests and callers that do not specify a preferred slot.
    int chooseSlot() {
        return chooseSlot(-1);
    }

    int evictionCandidate() {
        for (OneStepTaskInfo task : mSlots) {
            if (task == null) return -1;
        }
        return mSlots[0].taskId;
    }

    /** Commits an adopted/launched task and returns the task evicted from logical slot zero. */
    int commitAdd(OneStepTaskInfo info, int targetSlot, IntFunction<Rect> boundsForSlot) {
        if (info == null || find(info.taskId) != null) return -1;
        final int evictedTaskId = evictionCandidate();
        if (evictedTaskId >= 0) {
            // Full store policy is fixed and atomic: evict 0, shift 1 -> 0 and 2 -> 1,
            // and reserve slot 2 for the new task.
            mSlots[0] = withSlot(mSlots[1], 0, boundsForSlot);
            mSlots[1] = withSlot(mSlots[2], 1, boundsForSlot);
            mSlots[2] = null;
            targetSlot = mSlots.length - 1;
        } else if (targetSlot < 0 || targetSlot >= mSlots.length
                || mSlots[targetSlot] != null) {
            targetSlot = chooseSlot(-1);
        }
        mSlots[targetSlot] = info.withSlot(targetSlot, boundsForSlot.apply(targetSlot));
        return evictedTaskId;
    }

    int commitAdd(OneStepTaskInfo info, IntFunction<Rect> boundsForSlot) {
        return commitAdd(info, chooseSlot(-1), boundsForSlot);
    }

    boolean remove(int taskId, IntFunction<Rect> boundsForSlot) {
        for (int i = 0; i < mSlots.length; i++) {
            if (mSlots[i] != null && mSlots[i].taskId == taskId) {
                mSlots[i] = null;
                compact(boundsForSlot);
                return true;
            }
        }
        return false;
    }

    /** Clears all fixed slots and returns the task ids that were retained by OneStep. */
    List<Integer> clear() {
        final ArrayList<Integer> removed = new ArrayList<>(mSlots.length);
        for (int slot = 0; slot < mSlots.length; slot++) {
            if (mSlots[slot] != null) removed.add(mSlots[slot].taskId);
            mSlots[slot] = null;
        }
        return removed;
    }

    /** Replaces a task in-place, preserving its logical slot. A null replacement removes it. */
    boolean replace(int taskId, OneStepTaskInfo replacement,
            IntFunction<Rect> boundsForSlot) {
        for (int i = 0; i < mSlots.length; i++) {
            final OneStepTaskInfo current = mSlots[i];
            if (current == null) continue;
            if (current.taskId != taskId) continue;
            if (replacement == null) {
                mSlots[i] = null;
                compact(boundsForSlot);
            } else {
                mSlots[i] = replacement.withSlot(i, boundsForSlot.apply(i));
            }
            return true;
        }
        return false;
    }

    List<OneStepTaskInfo> snapshot(boolean visible, IntFunction<Rect> boundsForSlot) {
        final ArrayList<OneStepTaskInfo> copy = new ArrayList<>(mSlots.length);
        for (int slot = 0; slot < mSlots.length; slot++) {
            if (mSlots[slot] == null) continue;
            final OneStepTaskInfo task = mSlots[slot].withSlot(slot,
                    boundsForSlot.apply(slot));
            copy.add(task.withState(visible ? OneStepTaskInfo.STATE_EMBEDDED
                    : OneStepTaskInfo.STATE_HIDDEN, visible));
        }
        return copy;
    }

    private void compact(IntFunction<Rect> boundsForSlot) {
        int next = 0;
        for (int slot = 0; slot < mSlots.length; slot++) {
            if (mSlots[slot] == null) continue;
            final OneStepTaskInfo task = mSlots[slot];
            mSlots[next] = task.withSlot(next, boundsForSlot.apply(next));
            if (next != slot) mSlots[slot] = null;
            next++;
        }
    }

    private static OneStepTaskInfo withSlot(OneStepTaskInfo task, int slot,
            IntFunction<Rect> boundsForSlot) {
        return task != null ? task.withSlot(slot, boundsForSlot.apply(slot)) : null;
    }
}
