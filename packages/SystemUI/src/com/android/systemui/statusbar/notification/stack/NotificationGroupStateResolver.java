/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.statusbar.notification.stack;

import com.android.systemui.statusbar.notification.row.NotificationCardBackgroundKind;
import com.android.systemui.statusbar.notification.row.NotificationContentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Smartisan notification group presentation calculation. */
public final class NotificationGroupStateResolver {
    public enum Mode { EMPTY, STANDARD_STACK, CUSTOM_HEADER }

    public static final class ChildInput {
        public final NotificationContentType kind;
        public final int contractedHeight;
        public final int fullHeight;
        public final boolean visible;

        public ChildInput(NotificationContentType kind, int contractedHeight, int fullHeight,
                boolean visible) {
            this.kind = kind;
            this.contractedHeight = Math.max(0, contractedHeight);
            this.fullHeight = Math.max(0, fullHeight);
            this.visible = visible;
        }
    }

    public static final class ChildState {
        public final int y;
        public final int height;
        public final float scaleX;
        public final float alpha;
        public final boolean hidden;
        public final NotificationCardBackgroundKind backgroundKind;

        ChildState(int y, int height, float scaleX, float alpha, boolean hidden,
                NotificationCardBackgroundKind backgroundKind) {
            this.y = y;
            this.height = height;
            this.scaleX = scaleX;
            this.alpha = alpha;
            this.hidden = hidden;
            this.backgroundKind = backgroundKind;
        }
    }

    public static final class Result {
        public final Mode mode;
        public final NotificationContentType lastVisibleKind;
        public final int intrinsicHeight;
        public final boolean headerVisible;
        public final boolean tailVisible;
        public final int tailY;
        public final List<ChildState> children;

        Result(Mode mode, NotificationContentType lastVisibleKind, int intrinsicHeight,
                boolean headerVisible, boolean tailVisible, int tailY,
                List<ChildState> children) {
            this.mode = mode;
            this.lastVisibleKind = lastVisibleKind;
            this.intrinsicHeight = intrinsicHeight;
            this.headerVisible = headerVisible;
            this.tailVisible = tailVisible;
            this.tailY = tailY;
            this.children = Collections.unmodifiableList(children);
        }
    }

    private NotificationGroupStateResolver() {}

    public static Result resolve(List<ChildInput> children, boolean expanded, boolean userLocked,
            float expandFraction, int maxCollapsedChildren, int maxExpandedChildren,
            int headerHeight, int collapsedPadding, int expandedInterval, int tailHeight) {
        int firstVisible = -1;
        NotificationContentType lastKind = NotificationContentType.STANDARD;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).visible) {
                if (firstVisible < 0) firstVisible = i;
                lastKind = children.get(i).kind;
            }
        }
        if (firstVisible < 0) {
            return new Result(Mode.EMPTY, lastKind, 0, false, false, 0,
                    emptyStates(children.size()));
        }

        final Mode mode = children.get(firstVisible).kind == NotificationContentType.CUSTOM
                ? Mode.CUSTOM_HEADER : Mode.STANDARD_STACK;
        final float fraction = userLocked ? clamp(expandFraction) : expanded ? 1f : 0f;
        final Layout collapsed = layout(children, mode, false, maxCollapsedChildren,
                headerHeight, collapsedPadding, expandedInterval, tailHeight);
        final Layout full = layout(children, mode, true, maxExpandedChildren,
                headerHeight, collapsedPadding, expandedInterval, tailHeight);
        final ArrayList<ChildState> states = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            final MutableChildState from = collapsed.children.get(i);
            final MutableChildState to = full.children.get(i);
            final boolean hidden = fraction <= 0f ? from.hidden
                    : fraction >= 1f ? to.hidden : from.hidden && to.hidden;
            states.add(new ChildState(
                    lerp(from.y, to.y, fraction),
                    lerp(from.height, to.height, fraction),
                    lerp(from.scaleX, to.scaleX, fraction),
                    lerp(from.alpha, to.alpha, fraction),
                    hidden,
                    fraction >= 1f ? to.backgroundKind : from.backgroundKind));
        }
        return new Result(
                mode,
                lastKind,
                lerp(collapsed.height, full.height, fraction),
                mode == Mode.CUSTOM_HEADER,
                mode == Mode.CUSTOM_HEADER && fraction >= 1f,
                lerp(collapsed.tailY, full.tailY, fraction),
                states);
    }

    private static Layout layout(List<ChildInput> inputs, Mode mode, boolean expanded,
            int maxVisible, int headerHeight, int collapsedPadding, int expandedInterval,
            int tailHeight) {
        final ArrayList<MutableChildState> result = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) result.add(new MutableChildState());
        int visibleOrdinal = 0;
        int y = mode == Mode.CUSTOM_HEADER ? headerHeight : 0;
        int firstContractedHeight = 0;
        for (ChildInput input : inputs) {
            if (input.visible) {
                firstContractedHeight = input.contractedHeight;
                break;
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            final ChildInput input = inputs.get(i);
            final MutableChildState state = result.get(i);
            if (!input.visible) {
                state.hidden = true;
                continue;
            }
            final boolean allowed = visibleOrdinal < Math.max(0, maxVisible);
            if (!allowed) {
                state.alpha = 0f;
                state.hidden = true;
                visibleOrdinal++;
                continue;
            }
            if (expanded) {
                if (visibleOrdinal > 0) y += expandedInterval;
                state.y = y;
                state.height = input.fullHeight;
                state.scaleX = 1f;
                state.backgroundKind = mode == Mode.CUSTOM_HEADER
                        ? NotificationCardBackgroundKind.CHILD_NORMAL
                        : NotificationCardBackgroundKind.TOP_NORMAL;
                y += input.fullHeight;
            } else if (mode == Mode.STANDARD_STACK) {
                state.y = visibleOrdinal == 0
                        ? 0 : firstContractedHeight + visibleOrdinal * collapsedPadding;
                state.height = input.contractedHeight;
                state.scaleX = Math.max(.98f, 1f - visibleOrdinal * .01f);
                state.backgroundKind = visibleOrdinal == 0
                        ? NotificationCardBackgroundKind.TOP_NORMAL
                        : NotificationCardBackgroundKind.CHILD_GREY;
                y = Math.max(y, firstContractedHeight
                        + Math.min(visibleOrdinal, 2) * collapsedPadding);
            } else {
                if (visibleOrdinal > 0) y += collapsedPadding;
                state.y = y;
                state.height = input.contractedHeight;
                state.scaleX = 1f;
                state.backgroundKind = NotificationCardBackgroundKind.CHILD_NORMAL;
                y += input.contractedHeight;
            }
            state.alpha = 1f;
            state.hidden = false;
            visibleOrdinal++;
        }
        final int tailY = y;
        if (expanded && mode == Mode.CUSTOM_HEADER && visibleOrdinal > 0) {
            y += tailHeight;
        }
        return new Layout(y, tailY, result);
    }

    private static ArrayList<ChildState> emptyStates(int size) {
        final ArrayList<ChildState> states = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            states.add(new ChildState(0, 0, 1f, 0f, true,
                    NotificationCardBackgroundKind.TOP_NORMAL));
        }
        return states;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int lerp(int start, int end, float fraction) {
        return Math.round(start + (end - start) * fraction);
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private static final class MutableChildState {
        int y;
        int height;
        float scaleX = 1f;
        float alpha;
        boolean hidden;
        NotificationCardBackgroundKind backgroundKind = NotificationCardBackgroundKind.TOP_NORMAL;
    }

    private static final class Layout {
        final int height;
        final int tailY;
        final ArrayList<MutableChildState> children;

        Layout(int height, int tailY, ArrayList<MutableChildState> children) {
            this.height = height;
            this.tailY = tailY;
            this.children = children;
        }
    }
}
