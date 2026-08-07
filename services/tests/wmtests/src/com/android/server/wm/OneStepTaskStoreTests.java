/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.wm;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;

import androidx.test.filters.SmallTest;

import com.android.internal.sidebar.OneStepTaskInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import androidx.test.runner.AndroidJUnit4;

import java.util.List;

/** Unit coverage for the committed state that survives panel and SystemUI lifecycles. */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class OneStepTaskStoreTests {
    private static Rect bounds(int slot) {
        return new Rect(slot * 10, 0, slot * 10 + 10, 100);
    }

    private static OneStepTaskInfo task(int id) {
        return new OneStepTaskInfo(id, 0, -1, null, "task-" + id, new Rect(), 0,
                OneStepTaskInfo.STATE_EMBEDDED, true);
    }

    @Test
    public void commitAdd_usesFirstEmptySlotAndDoesNotDuplicate() {
        final OneStepTaskStore store = new OneStepTaskStore();

        assertThat(store.chooseSlot()).isEqualTo(0);
        assertThat(store.commitAdd(task(10), OneStepTaskStoreTests::bounds)).isEqualTo(-1);
        assertThat(store.chooseSlot()).isEqualTo(1);
        assertThat(store.commitAdd(task(10), OneStepTaskStoreTests::bounds)).isEqualTo(-1);

        final List<OneStepTaskInfo> tasks = store.snapshot(true, OneStepTaskStoreTests::bounds);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).taskId).isEqualTo(10);
        assertThat(tasks.get(0).slot).isEqualTo(0);
    }

    @Test
    public void fourthTask_evictsOldestAndCompactsSlots() {
        final OneStepTaskStore store = new OneStepTaskStore();
        store.commitAdd(task(10), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(11), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(12), OneStepTaskStoreTests::bounds);

        assertThat(store.evictionCandidate()).isEqualTo(10);
        assertThat(store.commitAdd(task(13), OneStepTaskStoreTests::bounds)).isEqualTo(10);

        final List<OneStepTaskInfo> tasks = store.snapshot(true, OneStepTaskStoreTests::bounds);
        assertThat(tasks).hasSize(3);
        assertThat(tasks.get(0).taskId).isEqualTo(11);
        assertThat(tasks.get(1).taskId).isEqualTo(12);
        assertThat(tasks.get(2).taskId).isEqualTo(13);
        assertThat(tasks.get(0).slot).isEqualTo(0);
        assertThat(tasks.get(1).slot).isEqualTo(1);
        assertThat(tasks.get(2).slot).isEqualTo(2);
    }

    @Test
    public void failedAdopt_doesNotMutateCommittedState() {
        final OneStepTaskStore store = new OneStepTaskStore();
        store.commitAdd(task(10), OneStepTaskStoreTests::bounds);
        final int plannedSlot = store.chooseSlot();
        final int plannedEviction = store.evictionCandidate();

        // A failed host operation never calls commitAdd: this is the atomic rollback boundary.
        assertThat(plannedSlot).isEqualTo(1);
        assertThat(plannedEviction).isEqualTo(-1);
        assertThat(store.snapshot(true, OneStepTaskStoreTests::bounds)).hasSize(1);
    }

    @Test
    public void hostDeathAndPanelExit_retainTasksButChangeVisibility() {
        final OneStepTaskStore store = new OneStepTaskStore();
        store.commitAdd(task(10), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(11), OneStepTaskStoreTests::bounds);

        final List<OneStepTaskInfo> hidden = store.snapshot(false, OneStepTaskStoreTests::bounds);
        assertThat(hidden).hasSize(2);
        assertThat(hidden.get(0).state).isEqualTo(OneStepTaskInfo.STATE_HIDDEN);
        assertThat(hidden.get(0).visible).isFalse();

        final List<OneStepTaskInfo> restored = store.snapshot(true, OneStepTaskStoreTests::bounds);
        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).state).isEqualTo(OneStepTaskInfo.STATE_EMBEDDED);
        assertThat(restored.get(0).visible).isTrue();
    }

    @Test
    public void taskDeath_removesAndCompactsRemainingTasks() {
        final OneStepTaskStore store = new OneStepTaskStore();
        store.commitAdd(task(10), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(11), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(12), OneStepTaskStoreTests::bounds);

        assertThat(store.remove(11, OneStepTaskStoreTests::bounds)).isTrue();
        final List<OneStepTaskInfo> tasks = store.snapshot(true, OneStepTaskStoreTests::bounds);
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).taskId).isEqualTo(10);
        assertThat(tasks.get(1).taskId).isEqualTo(12);
        assertThat(tasks.get(1).slot).isEqualTo(1);
    }

    @Test
    public void swap_replacesTaskInSameSlot() {
        final OneStepTaskStore store = new OneStepTaskStore();
        store.commitAdd(task(10), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(11), OneStepTaskStoreTests::bounds);

        assertThat(store.replace(10, task(20), OneStepTaskStoreTests::bounds)).isTrue();

        final List<OneStepTaskInfo> tasks = store.snapshot(true, OneStepTaskStoreTests::bounds);
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).taskId).isEqualTo(20);
        assertThat(tasks.get(0).slot).isEqualTo(0);
        assertThat(tasks.get(1).taskId).isEqualTo(11);
        assertThat(tasks.get(1).slot).isEqualTo(1);
    }

    @Test
    public void swapWithoutReplacement_removesAndCompactsSlot() {
        final OneStepTaskStore store = new OneStepTaskStore();
        store.commitAdd(task(10), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(11), OneStepTaskStoreTests::bounds);
        store.commitAdd(task(12), OneStepTaskStoreTests::bounds);

        assertThat(store.replace(11, null, OneStepTaskStoreTests::bounds)).isTrue();

        final List<OneStepTaskInfo> tasks = store.snapshot(true, OneStepTaskStoreTests::bounds);
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).taskId).isEqualTo(10);
        assertThat(tasks.get(1).taskId).isEqualTo(12);
        assertThat(tasks.get(1).slot).isEqualTo(1);
    }
}
