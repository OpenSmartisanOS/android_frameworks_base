/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.statusbar.notification.stack;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.statusbar.notification.row.NotificationCardBackgroundKind;
import com.android.systemui.statusbar.notification.row.NotificationContentType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationGroupStateResolverTest {
    private static final int HEADER = 60;
    private static final int CHILD_PAD = 4;
    private static final int EXPANDED_GAP = 9;
    private static final int TAIL = 9;

    @Test
    public void standardCollapsed_usesFirstThreeRealContractedRowsAsStack() {
        NotificationGroupStateResolver.Result result = resolve(List.of(
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.STANDARD, true)), false, false, 0f);

        assertThat(result.mode).isEqualTo(NotificationGroupStateResolver.Mode.STANDARD_STACK);
        assertThat(result.intrinsicHeight).isEqualTo(68);
        assertThat(result.children.get(0).scaleX).isEqualTo(1f);
        assertThat(result.children.get(1).scaleX).isEqualTo(.99f);
        assertThat(result.children.get(2).scaleX).isEqualTo(.98f);
        assertThat(result.children.get(0).backgroundKind)
                .isEqualTo(NotificationCardBackgroundKind.TOP_NORMAL);
        assertThat(result.children.get(1).backgroundKind)
                .isEqualTo(NotificationCardBackgroundKind.CHILD_GREY);
        assertThat(result.children.get(3).hidden).isTrue();
    }

    @Test
    public void customFirstExpanded_hasHeaderAndTailButLastKindRemainsIndependent() {
        NotificationGroupStateResolver.Result result = resolve(List.of(
                child(NotificationContentType.CUSTOM, true),
                child(NotificationContentType.STANDARD, true)), true, false, 1f);

        assertThat(result.mode).isEqualTo(NotificationGroupStateResolver.Mode.CUSTOM_HEADER);
        assertThat(result.lastVisibleKind).isEqualTo(NotificationContentType.STANDARD);
        assertThat(result.headerVisible).isTrue();
        assertThat(result.tailVisible).isTrue();
        assertThat(result.children.get(0).y).isEqualTo(60);
        assertThat(result.children.get(1).y).isEqualTo(169);
        assertThat(result.children.get(0).backgroundKind)
                .isEqualTo(NotificationCardBackgroundKind.CHILD_NORMAL);
        assertThat(result.children.get(1).backgroundKind)
                .isEqualTo(NotificationCardBackgroundKind.CHILD_NORMAL);
        assertThat(result.tailY).isEqualTo(269);
        assertThat(result.intrinsicHeight).isEqualTo(278);
    }

    @Test
    public void invisibleEdges_doNotOwnFirstOrLastPresentationKind() {
        NotificationGroupStateResolver.Result result = resolve(List.of(
                child(NotificationContentType.CUSTOM, false),
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.CUSTOM, false)), false, false, 0f);

        assertThat(result.mode).isEqualTo(NotificationGroupStateResolver.Mode.STANDARD_STACK);
        assertThat(result.lastVisibleKind).isEqualTo(NotificationContentType.STANDARD);
    }

    @Test
    public void userLocked_midExpansion_revealsChildrenWithoutOneFrameJump() {
        NotificationGroupStateResolver.Result result = resolve(List.of(
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.STANDARD, true),
                child(NotificationContentType.STANDARD, true)), false, true, .5f);

        assertThat(result.children.get(3).hidden).isFalse();
        assertThat(result.children.get(3).alpha).isWithin(.001f).of(.5f);
        assertThat(result.tailVisible).isFalse();
    }

    private static NotificationGroupStateResolver.ChildInput child(
            NotificationContentType type, boolean visible) {
        return new NotificationGroupStateResolver.ChildInput(type, 60, 100, visible);
    }

    private static NotificationGroupStateResolver.Result resolve(
            List<NotificationGroupStateResolver.ChildInput> children, boolean expanded,
            boolean userLocked, float fraction) {
        return NotificationGroupStateResolver.resolve(children, expanded, userLocked, fraction,
                3, children.size(), HEADER, CHILD_PAD, EXPANDED_GAP, TAIL);
    }
}
