/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.taskview;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.window.flags.Flags.enableHandlersDebuggingMode;
import static com.android.wm.shell.bubbles.util.BubbleUtils.getExitBubbleTransaction;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BUBBLES;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BUBBLES_NOISY;
import static com.android.wm.shell.transition.TransitionDispatchState.CAPTURED_CHANGE_IN_WRONG_TRANSITION;
import static com.android.wm.shell.transition.TransitionDispatchState.CAPTURED_UNRELATED_CHANGE;
import static com.android.wm.shell.transition.TransitionDispatchState.LOST_RELEVANT_CHANGE;
import static com.android.wm.shell.transition.Transitions.transitTypeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.ProtoLog;
import com.android.wm.shell.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.shared.bubbles.BubbleAnythingFlagHelper;
import com.android.wm.shell.transition.TransitionDispatchState;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Handles Shell Transitions that involve TaskView tasks.
 */
public class TaskViewTransitions implements Transitions.TransitionHandler, TaskViewController {
    static final String TAG = "TaskViewTransitions";

    private final TaskViewRepository mTaskViewRepo;
    private final ArrayList<PendingTransition> mPending = new ArrayList<>();
    private final Transitions mTransitions;
    private final boolean[] mRegistered = new boolean[]{false};
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Executor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;

    /** A temp transaction used for quick things. */
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    /**
     * A display change transition that also involves a TaskView. The display animation is deferred
     * until the TaskView's bounds are updated. This object holds the logic to dispatch the display
     * transition once the TaskView is ready.
     */
    private PendingRedirectTransition mPendingRedirectTransition;

    /**
     * TaskView makes heavy use of startTransition. Only one shell-initiated transition can be
     * in-flight (collecting) at a time (because otherwise, the operations could get merged into
     * a single transition). So, keep a queue here until we add a queue in server-side.
     */
    @VisibleForTesting
    static class PendingTransition {
        final @WindowManager.TransitionType int mType;
        final WindowContainerTransaction mWct;
        final @NonNull TaskViewTaskController mTaskView;
        ExternalTransition mExternalTransition;
        IBinder mClaimed;
        int mAdoptedTaskId = -1;
        int mPromotedTaskId = -1;
        int mReplacementTaskId = -1;
        int mOriginalOneStepRotation = Surface.ROTATION_0;
        boolean mNotifyTaskRemovalAfterTransition;
        int mFullscreenTaskId = -1;
        boolean mFullscreenTaskMatched;

        /**
         * This is needed because arbitrary activity launches can still "intrude" into any
         * transition since `startActivity` is a synchronous call. Once that is solved, we can
         * remove this.
         */
        final IBinder mLaunchCookie;

        PendingTransition(@WindowManager.TransitionType int type,
                @Nullable WindowContainerTransaction wct,
                @NonNull TaskViewTaskController taskView,
                @Nullable IBinder launchCookie) {
            mType = type;
            mWct = wct;
            mTaskView = taskView;
            mLaunchCookie = launchCookie;
        }
        /** Dumps PendingTransition state. */
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.println("Pending transition:");
            pw.print(prefix); pw.println("  task view: " + mTaskView);
            pw.print(prefix); pw.println("  transition type: " + mType);
            pw.print(prefix); pw.println("  external transition: " + mExternalTransition);
            pw.print(prefix); pw.println("  claim token: " + mClaimed);
            pw.print(prefix); pw.println("  notify removal after transition: "
                    + mNotifyTaskRemovalAfterTransition);
            pw.print(prefix); pw.println("  fullscreen task: " + mFullscreenTaskId
                    + " matched=" + mFullscreenTaskMatched);
            pw.print(prefix); pw.println("  swap: " + mPromotedTaskId + " -> "
                    + mReplacementTaskId);
        }
    }

    public TaskViewTransitions(Transitions transitions, TaskViewRepository repository,
            ShellTaskOrganizer taskOrganizer, SyncTransactionQueue syncQueue) {
        mTransitions = transitions;
        mTaskOrganizer = taskOrganizer;
        mShellExecutor = taskOrganizer.getExecutor();
        mSyncQueue = syncQueue;
        mTaskViewRepo = repository;
        // Defer registration until the first TaskView because we want this to be the "first" in
        // priority when handling requests.
        // TODO(210041388): register here once we have an explicit ordering mechanism.
    }

    public TaskViewRepository getRepository() {
        return mTaskViewRepo;
    }

    @Override
    public void registerTaskView(TaskViewTaskController tv) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.registerTaskView(): taskView=%d",
                tv.hashCode());
        synchronized (mRegistered) {
            if (!mRegistered[0]) {
                mRegistered[0] = true;
                mTransitions.addHandler(this);
            }
        }
        mTaskViewRepo.add(tv);
    }

    @Override
    public void unregisterTaskView(TaskViewTaskController tv) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.unregisterTaskView: taskView=%d",
                tv.hashCode());
        mTaskViewRepo.remove(tv);
        // Note: Don't unregister handler since this is a singleton with lifetime bound to Shell
    }

    /**
     * Starts a transition outside of the handler associated with {@link TaskViewTransitions}.
     */
    public void startInstantTransition(@WindowManager.TransitionType int type,
            WindowContainerTransaction wct) {
        mTransitions.startTransition(type, wct, null);
    }

    /**
     * Starts or queues an "external" runnable into the pending queue. This means it will run
     * in order relative to the local transitions.
     *
     * The external operation *must* call {@link #onExternalDone} once it has finished.
     *
     * In practice, the external is usually another transition on a different handler.
     */
    public void enqueueExternal(@NonNull TaskViewTaskController taskView, ExternalTransition ext) {
        ProtoLog.d(WM_SHELL_BUBBLES,
                "TaskViewTransitions.enqueueExternal(): transition=%s taskView=%d pending=%d",
                ext, taskView.hashCode(), mPending.size());
        final PendingTransition pending = new PendingTransition(
                TRANSIT_NONE, null /* wct */, taskView, null /* cookie */);
        pending.mExternalTransition = ext;
        mPending.add(pending);
        startNextTransition();
    }

    /**
     * Add an already running external transition into the pending queue.
     * This transition has to be started externally. And it will block any new transitions from
     * starting in the pending queue.
     *
     * The external operation *must* call {@link #onExternalDone(IBinder)} once it has finished.
     */
    public void enqueueRunningExternal(@NonNull TaskViewTaskController taskView,
            IBinder transition) {
        ProtoLog.d(WM_SHELL_BUBBLES,
                "TaskViewTransitions.enqueueRunningExternal(): "
                    + "transition=%s taskView=%d pending=%d",
                transition, taskView.hashCode(), mPending.size());
        final PendingTransition pending = new PendingTransition(
                TRANSIT_NONE, null /* wct */, taskView, null /* cookie */);
        pending.mExternalTransition = () -> transition;
        pending.mClaimed = transition;
        mPending.add(pending);
    }

    /**
     * An external transition run in this "queue" is required to call this once it becomes ready.
     */
    public void onExternalDone(IBinder key) {
        final PendingTransition pending = findPending(key);
        if (pending == null) {
            ProtoLog.w(WM_SHELL_BUBBLES_NOISY,
                    "Transitions.onExternalDone(): unknown transition=%s", key);
            return;
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.onExternalDone(): taskView=%d "
                + "transition=%s", pending.mTaskView.hashCode(), key);
        mPending.remove(pending);
        startNextTransition();
    }

    /**
     * Looks through the pending transitions for a opening transaction that matches the provided
     * `taskView`.
     *
     * @param taskView the pending transition should be for this.
     */
    @VisibleForTesting
    PendingTransition findPendingOpeningTransition(TaskViewTaskController taskView) {
        for (int i = mPending.size() - 1; i >= 0; --i) {
            if (mPending.get(i).mTaskView != taskView) continue;
            if (mPending.get(i).mExternalTransition != null) continue;
            if (TransitionUtil.isOpeningType(mPending.get(i).mType)) {
                return mPending.get(i);
            }
        }
        return null;
    }

    /**
     * Looks through the pending transitions for one matching `taskView`.
     *
     * @param taskView the pending transition should be for this.
     * @param type     the type of transition it's looking for
     */
    PendingTransition findPending(TaskViewTaskController taskView, int type) {
        for (int i = mPending.size() - 1; i >= 0; --i) {
            if (mPending.get(i).mTaskView != taskView) continue;
            if (mPending.get(i).mExternalTransition != null) continue;
            if (mPending.get(i).mType == type) {
                return mPending.get(i);
            }
        }
        return null;
    }

    /** Looks through the pending transitions for one matching {@param claimed} */
    @VisibleForTesting
    public PendingTransition findPending(IBinder claimed) {
        for (int i = 0; i < mPending.size(); ++i) {
            if (mPending.get(i).mClaimed != claimed) continue;
            return mPending.get(i);
        }
        return null;
    }

    /** @return whether there are pending transitions on TaskViews. */
    public boolean hasPending() {
        return !mPending.isEmpty();
    }

    /** Removes all pending transitions for the given {@code taskView}. */
    public void removePendingTransitions(TaskViewTaskController taskView) {
        for (int i = mPending.size() - 1; i >= 0; --i) {
            if (mPending.get(i).mTaskView != taskView) continue;
            if (mPending.get(i).mExternalTransition != null) continue;
            mPending.remove(i);
        }
    }

    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        final ActivityManager.RunningTaskInfo triggerTask = request.getTriggerTask();
        if (triggerTask == null) {
            return null;
        }
        final TaskViewTaskController taskView = findTaskView(triggerTask);
        if (taskView == null) return null;

        // Opening types should all be initiated by shell
        if (!TransitionUtil.isClosingType(request.getType())) {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.handleRequest(): taskView=%d "
                    + "skipping transition=%d", taskView.hashCode(), transition.hashCode());
            return null;
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.handleRequest(): taskView=%d "
                        + "handling transition=%d", taskView.hashCode(), transition.hashCode());
        PendingTransition pending = new PendingTransition(request.getType(), null,
                taskView, null /* cookie */);
        pending.mClaimed = transition;
        mPending.add(pending);
        return new WindowContainerTransaction();
    }

    private TaskViewTaskController findTaskView(ActivityManager.RunningTaskInfo taskInfo) {
        final TaskViewRepository.TaskViewState state = mTaskViewRepo.byToken(taskInfo.token);
        return state != null ? state.getTaskView() : null;
    }

    /** Returns true if the given {@code taskInfo} belongs to a task view. */
    public boolean isTaskViewTask(ActivityManager.RunningTaskInfo taskInfo) {
        return findTaskView(taskInfo) != null;
    }

    private void prepareActivityOptions(ActivityOptions options, Rect launchBounds,
            @NonNull TaskViewTaskController destination) {
        final Binder launchCookie = new Binder();
        mShellExecutor.execute(() -> {
            mTaskOrganizer.setPendingLaunchCookieListener(launchCookie, destination);
        });
        final Rect taskBounds = destination.getTaskBounds();
        options.setLaunchBounds(taskBounds != null && destination.hasTaskBoundsOverride()
                ? taskBounds : launchBounds);
        options.setLaunchCookie(launchCookie);
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        options.setRemoveWithTaskOrganizer(true);
    }

    @Override
    public void startShortcutActivity(@NonNull TaskViewTaskController destination,
            @NonNull ShortcutInfo shortcut, @NonNull ActivityOptions options,
            @Nullable Rect launchBounds) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startShortcutActivity(): taskView=%d "
                        + "shortcut=%s bounds=%s", destination.hashCode(), shortcut, launchBounds);
        prepareActivityOptions(options, launchBounds, destination);
        final Context context = destination.getContext();
        mShellExecutor.execute(() -> {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.startShortcut(context.getPackageName(), shortcut, options.toBundle());
            startTaskView(wct, destination, options.getLaunchCookie());
        });
    }

    @Override
    public void startActivity(@NonNull TaskViewTaskController destination,
            @NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options, @Nullable Rect launchBounds) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startActivity(): taskView=%d intent=%s",
                destination.hashCode(), pendingIntent.getIntent());
        prepareActivityOptions(options, launchBounds, destination);
        mShellExecutor.execute(() -> {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.sendPendingIntent(pendingIntent, fillInIntent, options.toBundle());
            startTaskView(wct, destination, options.getLaunchCookie());
        });
    }

    @Override
    public void startRootTask(@NonNull TaskViewTaskController destination,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            @Nullable WindowContainerTransaction wct) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startRootTask(): taskView=%d task=%s",
                destination.hashCode(), taskInfo);
        if (wct == null) {
            wct = new WindowContainerTransaction();
        }
        // This method skips the regular flow where an activity task is launched as part of a new
        // transition in taskview and then transition is intercepted using the launchcookie.
        // The task here is already created and running, it just needs to be reparented, resized
        // and tracked correctly inside taskview. Which is done by calling
        // prepareOpenAnimation() and then manually enqueuing the resulting window container
        // transaction.
        prepareOpenAnimation(destination, true /* newTask */, mTransaction /* startTransaction */,
                null /* finishTransaction */, taskInfo, leash, wct);
        mTransaction.apply();
        mTransitions.startTransition(TRANSIT_CHANGE, wct, null);
    }

    @VisibleForTesting
    void startTaskView(@NonNull WindowContainerTransaction wct,
            @NonNull TaskViewTaskController taskView, @NonNull IBinder launchCookie) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startTaskView(): taskView=%d",
                taskView.hashCode());
        updateVisibilityState(taskView, true /* visible */);
        mPending.add(new PendingTransition(TRANSIT_OPEN, wct, taskView, launchCookie));
        startNextTransition();
    }

    @Override
    public void adoptTask(@NonNull TaskViewTaskController destination, int taskId) {
        mShellExecutor.execute(() -> {
            final ActivityManager.RunningTaskInfo taskInfo =
                    mTaskOrganizer.getRunningTaskInfo(taskId);
            if (taskInfo != null && destination.isOneStepTaskView()) {
                destination.setOneStepContentRotation(
                        taskInfo.configuration.windowConfiguration.getRotation());
            }
            final Rect bounds = destination.getTaskBounds();
            if (taskInfo == null || bounds == null || bounds.isEmpty()
                    || destination.getTaskToken() != null || !destination.isSurfaceCreated()) {
                destination.notifyTaskAdoptionFailed(taskId);
                return;
            }
            try {
                mTaskOrganizer.addListenerForTaskId(destination, taskId);
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.setWindowingMode(taskInfo.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
                wct.setBounds(taskInfo.token, bounds);
                wct.setHidden(taskInfo.token, false);
                if (destination.isOneStepTaskView()) {
                    // Original ActivityStackView stacks stay above ordinary fullscreen stacks,
                    // while never becoming the display focus or controlling system decor.
                    wct.setAlwaysOnTop(taskInfo.token, true);
                    wct.setFocusable(taskInfo.token, false);
                }
                wct.reorder(taskInfo.token, true /* onTop */);
                wct.setInterceptBackPressedOnTaskRoot(taskInfo.token, true);
                wct.setTaskTrimmableFromRecents(taskInfo.token, false);
                updateVisibilityState(destination, true /* visible */);
                final PendingTransition pending = new PendingTransition(TRANSIT_CHANGE, wct,
                        destination, null /* cookie */);
                pending.mAdoptedTaskId = taskId;
                mPending.add(pending);
                startNextTransition();
            } catch (RuntimeException e) {
                mPending.removeIf(pending -> pending.mTaskView == destination
                        && pending.mAdoptedTaskId == taskId);
                updateVisibilityState(destination, false /* visible */);
                mTaskOrganizer.removeListener(destination);
                final WindowContainerTransaction rollback = new WindowContainerTransaction();
                rollback.setWindowingMode(taskInfo.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED);
                rollback.setBounds(taskInfo.token, new Rect());
                rollback.setInterceptBackPressedOnTaskRoot(taskInfo.token, false);
                rollback.setTaskTrimmableFromRecents(taskInfo.token, true);
                if (destination.isOneStepTaskView()) {
                    rollback.setAlwaysOnTop(taskInfo.token, false);
                    rollback.setFocusable(taskInfo.token, true);
                }
                mTaskOrganizer.applyTransaction(rollback);
                destination.notifyTaskAdoptionFailed(taskId);
            }
        });
    }

    @Override
    public void removeTaskView(@NonNull TaskViewTaskController taskView,
            @Nullable WindowContainerToken taskToken) {
        final WindowContainerToken token = taskToken != null ? taskToken : taskView.getTaskToken();
        if (token == null) {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.removeTaskView(): taskView=%d no token",
                    taskView.hashCode());
            // We don't have a task yet, so just clean up records
            unregisterTaskView(taskView);
            return;
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.removeTaskView(): taskView=%d",
                taskView.hashCode());
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.removeTask(token);
        updateVisibilityState(taskView, false /* visible */);
        mShellExecutor.execute(() -> {
            mPending.add(new PendingTransition(TRANSIT_CLOSE, wct, taskView, null /* cookie */));
            startNextTransition();
        });
    }

    @Override
    public void moveTaskViewToFullscreen(@NonNull TaskViewTaskController taskView) {
        moveTaskViewToFullscreen(taskView, true /* toFront */);
    }

    @Override
    public void moveTaskViewToFullscreen(@NonNull TaskViewTaskController taskView,
            boolean toFront) {
        final WindowContainerToken taskToken = taskView.getTaskToken();
        final ActivityManager.RunningTaskInfo taskInfo = taskView.getTaskInfo();
        if (taskToken == null || taskInfo == null) {
            taskView.notifyTaskMoveToFullscreenFailed(taskInfo != null ? taskInfo.taskId : -1);
            return;
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.moveTaskViewToFullscreen(): taskView=%d",
                taskView.hashCode());
        taskView.setTaskCornerRadius(0f);
        final WindowContainerTransaction wct =
                getExitBubbleTransaction(taskToken, taskView.getCaptionInsetsOwner());
        wct.setWindowingMode(taskToken,
                android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED);
        wct.setBounds(taskToken, new Rect());
        wct.setInterceptBackPressedOnTaskRoot(taskToken, false);
        wct.setTaskTrimmableFromRecents(taskToken, true);
        wct.setAlwaysOnTop(taskToken, false);
        if (taskView.isOneStepTaskView()) {
            wct.setFocusable(taskToken, true);
        }
        wct.reorder(taskToken, toFront);
        mShellExecutor.execute(() -> {
            final PendingTransition pending = new PendingTransition(
                    TRANSIT_CHANGE, wct, taskView, null /* cookie */);
            pending.mNotifyTaskRemovalAfterTransition = true;
            pending.mFullscreenTaskId = taskInfo.taskId;
            mPending.add(pending);
            startNextTransition();
        });
    }

    @Override
    public void swapTaskViewToFullscreen(@NonNull TaskViewTaskController taskView,
            int replacementTaskId) {
        if (replacementTaskId < 0) {
            moveTaskViewToFullscreen(taskView, true /* toFront */);
            return;
        }
        mShellExecutor.execute(() -> {
            final ActivityManager.RunningTaskInfo promoted = taskView.getTaskInfo();
            final ActivityManager.RunningTaskInfo replacement =
                    mTaskOrganizer.getRunningTaskInfo(replacementTaskId);
            final int previousRotation = taskView.getOneStepContentRotation();
            if (promoted == null || replacement == null || promoted.taskId == replacementTaskId
                    || !taskView.isSurfaceCreated()) {
                taskView.notifyTaskSwapFailed(
                        promoted != null ? promoted.taskId : -1, replacementTaskId);
                return;
            }
            if (taskView.isOneStepTaskView()) {
                taskView.setOneStepContentRotation(
                        replacement.configuration.windowConfiguration.getRotation());
            }
            final Rect bounds = taskView.getTaskBounds();
            if (bounds == null || bounds.isEmpty()) {
                taskView.setOneStepContentRotation(previousRotation);
                taskView.notifyTaskSwapFailed(promoted.taskId, replacementTaskId);
                return;
            }
            try {
                mTaskOrganizer.addListenerForTaskId(taskView, replacementTaskId);
                final WindowContainerTransaction wct = getExitBubbleTransaction(
                        promoted.token, taskView.getCaptionInsetsOwner());
                wct.setWindowingMode(promoted.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED);
                wct.setBounds(promoted.token, new Rect());
                wct.setInterceptBackPressedOnTaskRoot(promoted.token, false);
                wct.setTaskTrimmableFromRecents(promoted.token, true);
                wct.setAlwaysOnTop(promoted.token, false);
                wct.setFocusable(promoted.token, true);

                wct.setWindowingMode(replacement.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
                wct.setBounds(replacement.token, bounds);
                wct.setHidden(replacement.token, false);
                if (taskView.isOneStepTaskView()) {
                    wct.setAlwaysOnTop(replacement.token, true);
                    wct.setFocusable(replacement.token, false);
                }
                wct.setInterceptBackPressedOnTaskRoot(replacement.token, true);
                wct.setTaskTrimmableFromRecents(replacement.token, false);
                wct.reorder(replacement.token, true /* onTop */);
                // Apply the fullscreen promotion last so it remains the focused main task.
                wct.reorder(promoted.token, true /* onTop */);

                updateVisibilityState(taskView, true /* visible */);
                final PendingTransition pending = new PendingTransition(
                        TRANSIT_CHANGE, wct, taskView, null /* cookie */);
                pending.mAdoptedTaskId = replacementTaskId;
                pending.mPromotedTaskId = promoted.taskId;
                pending.mReplacementTaskId = replacementTaskId;
                pending.mOriginalOneStepRotation = previousRotation;
                mPending.add(pending);
                startNextTransition();
            } catch (RuntimeException e) {
                taskView.setOneStepContentRotation(previousRotation);
                mTaskOrganizer.removeListenerForTaskId(taskView, replacementTaskId);
                updateVisibilityState(taskView, true /* visible */);
                taskView.notifyTaskSwapFailed(promoted.taskId, replacementTaskId);
            }
        });
    }

    private void notifyTaskRemovalAfterTransition(PendingTransition pending) {
        if (pending == null || !pending.mNotifyTaskRemovalAfterTransition) return;
        pending.mNotifyTaskRemovalAfterTransition = false;
        final int taskId = pending.mFullscreenTaskId;
        pending.mFullscreenTaskId = -1;
        ActivityManager.RunningTaskInfo taskInfo = pending.mTaskView.getTaskInfo();
        if (taskInfo == null || taskInfo.taskId != taskId) {
            taskInfo = mTaskOrganizer.getRunningTaskInfo(taskId);
        }
        pending.mTaskView.setTaskCornerRadius(0f);
        if (taskInfo != null) {
            pending.mTaskView.notifyTaskRemovalStarted(taskInfo);
        } else {
            pending.mTaskView.notifyTaskMoveToFullscreenFailed(taskId);
        }
    }

    @Override
    public void setTaskViewVisible(TaskViewTaskController taskView, boolean visible) {
        setTaskViewVisible(taskView, visible, false /* reorder */);
    }

    @Override
    public void bringTaskViewToFront(TaskViewTaskController taskView) {
        setTaskViewVisible(taskView, true /* visible */, true /* reorder */);
    }

    /** See {@link #setTaskViewVisible(TaskViewTaskController, boolean, boolean, boolean)}. */
    public void setTaskViewVisible(TaskViewTaskController taskView, boolean visible,
            boolean reorder) {
        setTaskViewVisible(taskView, visible, reorder,
                true /* syncHiddenWithVisibilityOnReorder */);
    }

    /**
     * See {@link #setTaskViewVisible(TaskViewTaskController, boolean, boolean, boolean, boolean,
     * WindowContainerTransaction)}.
     */
    public void setTaskViewVisible(TaskViewTaskController taskView, boolean visible,
            boolean reorder, boolean syncHiddenWithVisibilityOnReorder) {
        setTaskViewVisible(taskView, visible, reorder, syncHiddenWithVisibilityOnReorder,
                false /* nonBlockingIfPossible */, null /* overrideTransaction */);
    }

    /**
     * Starts a new transition to make the given {@code taskView} visible and optionally
     * reordering it.
     *
     * @param reorder  Whether to reorder the task or not. If this is {@code true}, the task will
     *                 be reordered as per the given {@code visible}. For {@code visible = true},
     *                 task will be reordered to top. For {@code visible = false}, task will be
     *                 reordered to the bottom
     * @param syncHiddenWithVisibilityOnReorder Whether to also synchronize the hidden state of
     *                                          the task with the target visibility when
     *                                          reordering. This only takes effect if {@code
     *                                          reorder} is {@code true}.
     * @param nonBlockingIfPossible If true, the wct will be executed in a non-blocking way when
     *                              possible. It is possible if {@link #mShellExecutor} is an
     *                              instance of {@link ShellExecutor} that supports posting a
     *                              Runnable after the current execution.
     * @param overrideTransaction The transaction that already contains a set of task hierarchy
     *                            operations. If this is non-null, this method won't apply any
     *                            hierarchy related operations to avoid conflicts.
     * @throws IllegalStateException If the flag {@link FLAG_ENABLE_CREATE_ANY_BUBBLE} is not
     *                               enabled.
     */
    public void setTaskViewVisible(TaskViewTaskController taskView, boolean visible,
            boolean reorder, boolean syncHiddenWithVisibilityOnReorder,
            boolean nonBlockingIfPossible, WindowContainerTransaction overrideTransaction) {
        final TaskViewRepository.TaskViewState state = mTaskViewRepo.byTaskView(taskView);
        if (state == null) return;
        // A TaskView SurfaceView can be destroyed and recreated while the embedded task is
        // retained (OneStep hides its side window on exit).  In that race the repository may
        // already say visible=true even though WM still has a queued hidden transaction and the
        // task leash is parented to the old surface.  A deliberate bring-to-front must therefore
        // be allowed to run again: its transition clears hidden and prepareOpenAnimation reparents
        // the leash to the newly-created TaskView surface.  Plain duplicate visibility updates
        // remain no-ops.
        if (state.mVisible == visible && !(visible && reorder)) return;
        if (taskView.getTaskInfo() == null) {
            // Nothing to update, task is not yet available
            return;
        }
        state.mVisible = visible;

        final WindowContainerTransaction wct;
        if (overrideTransaction != null) {
            wct = overrideTransaction;
        } else {
            wct = new WindowContainerTransaction();
            wct.setBounds(taskView.getTaskInfo().token, state.mBounds);
            if (reorder && !syncHiddenWithVisibilityOnReorder) {
                // Reset hidden state to fix corner case where surface was destroyed before task
                // appeared in #prepareOpenAnimation.
                wct.setHidden(taskView.getTaskInfo().token, false /* hidden */);
                // Order of #setAlwaysOnTop and #reorder matters; hierarchy ops apply sequentially.
                wct.setAlwaysOnTop(taskView.getTaskInfo().token, visible /* alwaysOnTop */);
            } else {
                wct.setHidden(taskView.getTaskInfo().token, !visible /* hidden */);
            }
            if (taskView.isOneStepTaskView()) {
                wct.setAlwaysOnTop(taskView.getTaskInfo().token, visible);
                wct.setFocusable(taskView.getTaskInfo().token, false);
            }
            if (reorder) {
                wct.reorder(taskView.getTaskInfo().token, visible /* onTop */);
            }
        }

        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.setTaskViewVisible(): taskView=%d "
                + "visible=%b", taskView.hashCode(), visible);
        final PendingTransition pending = new PendingTransition(
                visible ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK, wct, taskView, null /* cookie */);
        mPending.add(pending);
        if (nonBlockingIfPossible && mShellExecutor instanceof ShellExecutor executor) {
            executor.executeDelayed(this::startNextTransition, 0);
        } else {
            startNextTransition();
        }
        // visibility is reported in transition.
    }

    /** Starts a new transition to reorder the given {@code taskView}'s task. */
    public void reorderTaskViewTask(TaskViewTaskController taskView, boolean onTop) {
        final TaskViewRepository.TaskViewState state = mTaskViewRepo.byTaskView(taskView);
        if (state == null) return;
        if (taskView.getTaskInfo() == null) {
            // Nothing to update, task is not yet available
            return;
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.reorderTaskViewTask(): taskView=%d "
                        + "onTop=%b", taskView.hashCode(), onTop);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reorder(taskView.getTaskInfo().token, onTop /* onTop */);
        PendingTransition pending = new PendingTransition(
                onTop ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK, wct, taskView, null /* cookie */);
        mPending.add(pending);
        startNextTransition();
        // visibility is reported in transition.
    }

    /** Updates the bounds state for the given task view. */
    public void updateBoundsState(TaskViewTaskController taskView, Rect boundsOnScreen) {
        final TaskViewRepository.TaskViewState state = mTaskViewRepo.byTaskView(taskView);
        if (state == null) return;
        final Rect stableBounds = stableTaskBounds(taskView, boundsOnScreen);
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY,
                "Transitions.updateBoundsState(): taskView=%d bounds=%s",
                taskView.hashCode(), stableBounds);
        state.mBounds.set(stableBounds);
    }

    void updateVisibilityState(TaskViewTaskController taskView, boolean visible) {
        final TaskViewRepository.TaskViewState state = mTaskViewRepo.byTaskView(taskView);
        if (state == null) return;
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.updateVisibilityState(): taskView=%d "
                        + "visible=%b", taskView.hashCode(), visible);
        state.mVisible = visible;
    }

    @Override
    public void setTaskBounds(TaskViewTaskController taskView, Rect boundsOnScreen) {
        if (taskView.getTaskToken() == null) {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.setTaskBounds(): null token");
            return;
        }

        mShellExecutor.execute(() -> {
            // Sync Transactions can't operate simultaneously with shell transition collection.
            setTaskBoundsInTransition(taskView, boundsOnScreen);
        });
    }

    @Override
    public void updateTaskViewPresentation(TaskViewTaskController taskView) {
        if (!taskView.isOneStepTaskView()) return;
        mShellExecutor.execute(() -> {
            final SurfaceControl leash = taskView.getTaskLeash();
            final SurfaceControl parent = taskView.getSurfaceControl();
            if (leash == null || !leash.isValid() || parent == null || !parent.isValid()) return;
            mSyncQueue.runInSync(t -> applyTaskSurfacePresentation(t, leash, taskView,
                    1 /* fallbackWidth */, 1 /* fallbackHeight */, true /* show */));
        });
    }

    private void setTaskBoundsInTransition(TaskViewTaskController taskView, Rect boundsOnScreen) {
        final TaskViewRepository.TaskViewState state = mTaskViewRepo.byTaskView(taskView);
        final Rect stableBounds = stableTaskBounds(taskView, boundsOnScreen);
        if (state == null || Objects.equals(stableBounds, state.mBounds)) {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.setTaskBoundsInTransition(): "
                    + "Skipping, same bounds");
            return;
        }
        state.mBounds.set(stableBounds);
        if (!state.mVisible) {
            // Task view isn't visible, the bounds will next visibility update.
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.setTaskBoundsInTransition(): "
                    + "Skipping, not visible");
            return;
        }
        if (hasPending()) {
            // There is already a transition in-flight, the window bounds will be set in
            // prepareOpenAnimation.
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.setTaskBoundsInTransition(): "
                    + "Skipping, pending transition");
            return;
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.setTaskBoundsInTransition(): taskView=%d "
                        + "bounds=%s", taskView.hashCode(), stableBounds);
        // If there is a pending redirect transition, it may have a WCT with other operations.
        final WindowContainerTransaction wct = mPendingRedirectTransition != null
                ? mPendingRedirectTransition.takePendingWct() : new WindowContainerTransaction();
        wct.setBounds(taskView.getTaskInfo().token, stableBounds);
        mPending.add(new PendingTransition(TRANSIT_CHANGE, wct, taskView, null /* cookie */));
        startNextTransition();
    }

    private static Rect stableTaskBounds(TaskViewTaskController taskView, Rect requestedBounds) {
        if (taskView.isOneStepTaskView()) {
            final Rect logicalBounds = taskView.getTaskBounds();
            if (logicalBounds != null && !logicalBounds.isEmpty()) return logicalBounds;
        }
        return requestedBounds;
    }

    private void startNextTransition() {
        if (mPending.isEmpty()) {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startNextTransition(): None pending");
            return;
        }
        final PendingTransition pending = mPending.get(0);
        if (pending.mClaimed != null) {
            // Wait for this to start animating.
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startNextTransition(): "
                    + "taskView=%d pending type=%s transition=%s", pending.mTaskView.hashCode(),
                    transitTypeToString(pending.mType), pending.mClaimed);
            return;
        }
        if (pending.mExternalTransition != null) {
            pending.mClaimed = pending.mExternalTransition.start();
            if (pending.mClaimed == null) {
                ProtoLog.w(WM_SHELL_BUBBLES_NOISY, "TaskViewTransitions.startNextTransition(): "
                        + "taskView=%d starting the external transition returned a null claim "
                        + "token. it may have already finished. removing it so that it does not "
                        + "block other transitions.", pending.mTaskView.hashCode());
                rollbackPendingTransition(pending);
                mPending.remove(pending);
                startNextTransition();
                return;
            }
        } else {
            pending.mClaimed = mTransitions.startTransition(pending.mType, pending.mWct, this);
        }
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startNextTransition(): "
                + "taskView=%d starting type=%s transition=%s", pending.mTaskView.hashCode(),
                transitTypeToString(pending.mType), pending.mClaimed);
    }

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        if (!Flags.fixTaskViewRotationAnimation() && !aborted) return;
        final PendingTransition pending = findPending(transition);
        if (pending == null) return;
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.onTransitionConsumed(): taskView=%d "
                + "consumed type=%s transition=%s aborted=%b", pending.mTaskView.hashCode(),
                transitTypeToString(pending.mType), transition, aborted);
        if (aborted) rollbackPendingTransition(pending);
        mPending.remove(pending);
        startNextTransition();
    }

    private void rollbackPendingTransition(PendingTransition pending) {
        if (pending == null) return;
        if (pending.mNotifyTaskRemovalAfterTransition) {
            rollbackFullscreen(pending);
        } else {
            rollbackAdoption(pending);
        }
    }

    /** Restores the exact TaskView ownership and WM policy after a failed fullscreen move. */
    private void rollbackFullscreen(PendingTransition pending) {
        if (pending == null || !pending.mNotifyTaskRemovalAfterTransition) return;
        pending.mNotifyTaskRemovalAfterTransition = false;
        final int taskId = pending.mFullscreenTaskId;
        pending.mFullscreenTaskId = -1;
        pending.mFullscreenTaskMatched = false;
        final TaskViewTaskController taskView = pending.mTaskView;
        final ActivityManager.RunningTaskInfo taskInfo =
                mTaskOrganizer.getRunningTaskInfo(taskId);
        if (taskInfo != null && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            updateVisibilityState(taskView, false /* visible */);
            taskView.setTaskCornerRadius(0f);
            taskView.notifyTaskRemovalStarted(taskInfo);
            return;
        }
        try {
            if (taskInfo != null) {
                mTaskOrganizer.addListenerForTaskId(taskView, taskId);
                final Rect bounds = taskView.getTaskBounds();
                final WindowContainerTransaction rollback = new WindowContainerTransaction();
                rollback.setWindowingMode(taskInfo.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
                if (bounds != null && !bounds.isEmpty()) {
                    rollback.setBounds(taskInfo.token, bounds);
                }
                rollback.setHidden(taskInfo.token, false);
                rollback.setInterceptBackPressedOnTaskRoot(taskInfo.token, true);
                rollback.setTaskTrimmableFromRecents(taskInfo.token, false);
                if (taskView.isOneStepTaskView()) {
                    rollback.setAlwaysOnTop(taskInfo.token, true);
                    rollback.setFocusable(taskInfo.token, false);
                }
                rollback.reorder(taskInfo.token, true /* onTop */);
                updateVisibilityState(taskView, true /* visible */);
                mSyncQueue.queue(rollback);
                mSyncQueue.runInSync(t -> {
                    final SurfaceControl leash = taskView.getTaskLeash();
                    final SurfaceControl parent = taskView.getSurfaceControl();
                    if (leash == null || !leash.isValid() || parent == null
                            || !parent.isValid()) {
                        return;
                    }
                    final Rect taskBounds = taskInfo.configuration.windowConfiguration.getBounds();
                    applyTaskSurfacePresentation(t, leash, taskView,
                            Math.max(1, taskBounds.width()), Math.max(1, taskBounds.height()),
                            true /* show */);
                });
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to roll back fullscreen OneStep task " + taskId, e);
        }
        taskView.notifyTaskMoveToFullscreenFailed(taskId);
    }

    private void rollbackAdoption(PendingTransition pending) {
        if (pending == null || pending.mAdoptedTaskId < 0) return;
        if (pending.mPromotedTaskId >= 0) {
            rollbackSwap(pending);
            return;
        }
        final int taskId = pending.mAdoptedTaskId;
        pending.mAdoptedTaskId = -1;
        updateVisibilityState(pending.mTaskView, false /* visible */);
        final ActivityManager.RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(taskId);
        try {
            mTaskOrganizer.removeListener(pending.mTaskView);
            if (taskInfo != null) {
                final WindowContainerTransaction rollback = new WindowContainerTransaction();
                rollback.setWindowingMode(taskInfo.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED);
                rollback.setBounds(taskInfo.token, new Rect());
                rollback.setInterceptBackPressedOnTaskRoot(taskInfo.token, false);
                rollback.setTaskTrimmableFromRecents(taskInfo.token, true);
                if (pending.mTaskView.isOneStepTaskView()) {
                    rollback.setAlwaysOnTop(taskInfo.token, false);
                    rollback.setFocusable(taskInfo.token, true);
                }
                mTaskOrganizer.applyTransaction(rollback);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to fully roll back OneStep task adoption " + taskId, e);
        }
        pending.mTaskView.notifyTaskAdoptionFailed(taskId);
    }

    private void rollbackSwap(PendingTransition pending) {
        final int promotedTaskId = pending.mPromotedTaskId;
        final int replacementTaskId = pending.mReplacementTaskId;
        pending.mTaskView.setOneStepContentRotation(pending.mOriginalOneStepRotation);
        pending.mAdoptedTaskId = -1;
        pending.mPromotedTaskId = -1;
        pending.mReplacementTaskId = -1;
        try {
            mTaskOrganizer.removeListenerForTaskId(pending.mTaskView, replacementTaskId);
            final ActivityManager.RunningTaskInfo promoted =
                    mTaskOrganizer.getRunningTaskInfo(promotedTaskId);
            final ActivityManager.RunningTaskInfo replacement =
                    mTaskOrganizer.getRunningTaskInfo(replacementTaskId);
            final WindowContainerTransaction rollback = new WindowContainerTransaction();
            if (promoted != null) {
                final Rect bounds = pending.mTaskView.getTaskBounds();
                rollback.setWindowingMode(promoted.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
                if (bounds != null && !bounds.isEmpty()) rollback.setBounds(promoted.token, bounds);
                rollback.setHidden(promoted.token, false);
                rollback.setInterceptBackPressedOnTaskRoot(promoted.token, true);
                rollback.setTaskTrimmableFromRecents(promoted.token, false);
                if (pending.mTaskView.isOneStepTaskView()) {
                    rollback.setAlwaysOnTop(promoted.token, true);
                    rollback.setFocusable(promoted.token, false);
                }
            }
            if (replacement != null) {
                rollback.setWindowingMode(replacement.token,
                        android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED);
                rollback.setBounds(replacement.token, new Rect());
                rollback.setInterceptBackPressedOnTaskRoot(replacement.token, false);
                rollback.setTaskTrimmableFromRecents(replacement.token, true);
                if (pending.mTaskView.isOneStepTaskView()) {
                    rollback.setAlwaysOnTop(replacement.token, false);
                    rollback.setFocusable(replacement.token, true);
                }
            }
            mTaskOrganizer.applyTransaction(rollback);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to fully roll back OneStep task swap", e);
        }
        pending.mTaskView.notifyTaskSwapFailed(promotedTaskId, replacementTaskId);
    }

    private void finishSwap(PendingTransition pending) {
        if (pending == null || pending.mPromotedTaskId < 0) return;
        final int promotedTaskId = pending.mPromotedTaskId;
        pending.mPromotedTaskId = -1;
        pending.mReplacementTaskId = -1;
        pending.mAdoptedTaskId = -1;
        mTaskOrganizer.removeListenerForTaskId(pending.mTaskView, promotedTaskId);
    }

    /**
     * @param change the change to examine
     * @param pending the pending tansition
     * @return whether this is a TaskView that this handler will be able to handle
     */
    private boolean isValidTaskView(TransitionInfo.Change change, PendingTransition pending) {
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (taskInfo == null) {
            // Not a task, so ignore
            return false;
        }

        if (change.getMode() == TRANSIT_OPEN) {
            // Ignore tasks that are launched in the wrong transition
            return pending != null && taskInfo.containsLaunchCookie(pending.mLaunchCookie);
        }
        if (isTaskViewTask(taskInfo)) {
            return true;
        }
        if (isTaskToTaskView(change, pending)) {
            return true;
        }

        // In some cases, findTaskView returns null but the change is still a task view:
        if (change.getMode() == TRANSIT_CLOSE) {
            // TaskView can be null when closing
            return true;
        }
        if (change.getMode() == TRANSIT_TO_FRONT && pending != null) {
            // Accept if an existing task, not currently in TaskView, is
            // brought to the front to be moved into TaskView
            return isTaskToTaskView(change, pending);
        }
        return false;
    }

    /**
     * @return if an existing task, not currently in TaskView, is brought to the front to be moved
     * into TaskView (e.g task being moved into a bubble)
     */
    private boolean isTaskToTaskView(TransitionInfo.Change change, PendingTransition pending) {
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        return pending != null && taskInfo != null
                && (isAdoptedTask(change, pending)
                        || (BubbleAnythingFlagHelper.enableCreateAnyBubble()
                        && change.getMode() == TRANSIT_TO_FRONT
                        && pending.mTaskView.getPendingInfo() != null
                        && pending.mTaskView.getPendingInfo().taskId == taskInfo.taskId))
                ;
    }

    private boolean isAdoptedTask(TransitionInfo.Change change, PendingTransition pending) {
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        return pending != null && pending.mAdoptedTaskId >= 0 && taskInfo != null
                && pending.mAdoptedTaskId == taskInfo.taskId;
    }

    private boolean isMovingTaskViewToFullscreen(ActivityManager.RunningTaskInfo taskInfo,
            TaskViewTaskController taskView, PendingTransition pending) {
        if (taskInfo == null || taskView == null || pending == null
                || (!pending.mNotifyTaskRemovalAfterTransition
                        && pending.mPromotedTaskId != taskInfo.taskId)
                || pending.mTaskView != taskView) {
            return false;
        }
        if (pending.mPromotedTaskId == taskInfo.taskId) return true;
        final ActivityManager.RunningTaskInfo currentInfo = taskView.getTaskInfo();
        return currentInfo != null && currentInfo.taskId == taskInfo.taskId;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
                                  @NonNull TransitionInfo info,
                                  @NonNull SurfaceControl.Transaction startTransaction,
                                  @NonNull SurfaceControl.Transaction finishTransaction,
                                  @NonNull Transitions.TransitionFinishCallback finishCallback) {
        return startAnimation(transition, info, TransitionDispatchState.getDummyInstance(),
                startTransaction, finishTransaction, finishCallback);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
                                  @Nullable TransitionInfo transitionInfo,
                                  @NonNull TransitionDispatchState dispatchState,
                                  @NonNull SurfaceControl.Transaction startTransaction,
                                  @NonNull SurfaceControl.Transaction finishTransaction,
                                  @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (transitionInfo == null || transitionInfo.getChanges().isEmpty()) {
            PendingTransition pending = findPending(transition);
            if (pending != null) {
                ProtoLog.e(WM_SHELL_BUBBLES, "Transitions.startAnimation(): found a transition with"
                                + "no changes that is managed by TaskViewTransitions. taskView=%d "
                                + "type=%s transition=%s", pending.mTaskView.hashCode(),
                        transitTypeToString(pending.mType), transition);
                rollbackPendingTransition(pending);
                mPending.remove(pending);
                startNextTransition();
            }
            return false;
        }

        if (!Flags.taskViewTransitionsRefactor() && !enableHandlersDebuggingMode()) {
            return startAnimationLegacy(transition, transitionInfo, startTransaction,
                    finishTransaction, finishCallback);
        }
        final boolean inDataCollectionModeOnly =
                enableHandlersDebuggingMode() && transitionInfo == null;
        final TransitionInfo info = inDataCollectionModeOnly ? dispatchState.mInfo : transitionInfo;

        final PendingTransition pending = findPending(transition);
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startAnimation(): taskView=%d "
                    + "type=%s transition=%s", pending != null ? pending.mTaskView.hashCode() : -1,
                    pending != null ? transitTypeToString(pending.mType) : "unknown", transition);
        if (pending != null) {
            mPending.remove(pending);
            pending.mFullscreenTaskMatched = false;
        }
        if (mTaskViewRepo.isEmpty()) {
            if (pending != null) {
                Slog.e(TAG, "Pending taskview transition but no task-views");
                rollbackPendingTransition(pending);
            }
            return false;
        }
        boolean stillNeedsMatchingLaunch = pending != null && pending.mLaunchCookie != null;
        boolean adoptedTaskHandled = false;
        int changingDisplayId = -1;
        WindowContainerTransaction wct = null;

        // Collect all the tasks views that this handler can handle
        ArrayList<TransitionInfo.Change> taskViews = new ArrayList<>();
        ArrayList<TransitionInfo.Change> alienChanges = new ArrayList<>();
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = info.getChanges().get(i);
            if (isValidTaskView(chg, pending)) {
                taskViews.add(chg);
                if (inDataCollectionModeOnly) {
                    dispatchState.addError(this, chg, LOST_RELEVANT_CHANGE);
                }
            } else {
                alienChanges.add(chg);
                if (Flags.fixTaskViewRotationAnimation()
                        && chg.hasFlags(TransitionInfo.FLAG_IS_DISPLAY)
                        && chg.getMode() == TRANSIT_CHANGE && mPendingRedirectTransition == null) {
                    changingDisplayId = chg.getEndDisplayId();
                }
            }
        }
        if (inDataCollectionModeOnly) {
            return false;
        }

        // Prepare taskViews for animation
        boolean isReadyForAnimation = true;
        for (int i = 0; i < taskViews.size(); ++i) {
            final TransitionInfo.Change task = taskViews.get(i);
            final ActivityManager.RunningTaskInfo taskInfo = task.getTaskInfo();
            final SurfaceControl leash = task.getLeash();
            final TaskViewTaskController infoTv = findTaskView(taskInfo);

            switch (task.getMode()) {
                case TRANSIT_TO_BACK:
                    if (pending != null && pending.mType == TRANSIT_TO_BACK
                            && !infoTv.isOneStepTaskView()) {
                        // TO_BACK is only used when setting the task view visibility immediately,
                        // so in that case we can also hide the surface immediately
                        startTransaction.hide(leash);
                    }
                    infoTv.prepareHideAnimation(finishTransaction);
                    break;
                case TRANSIT_CLOSE:
                    // TaskView can be null when closing
                    if (infoTv != null) {
                        infoTv.prepareCloseAnimation();
                    }
                    break;
                case TRANSIT_OPEN:
                    stillNeedsMatchingLaunch = false;
                    if (wct == null) wct = new WindowContainerTransaction();
                    prepareOpenAnimation(pending.mTaskView, true /* isNewInTaskView */,
                            startTransaction, finishTransaction, taskInfo, leash, wct);
                    break;
                case TRANSIT_TO_FRONT:
                    if (wct == null) wct = new WindowContainerTransaction();
                    final boolean adoptedToFront = isAdoptedTask(task, pending);
                    if (infoTv == null && pending != null && isTaskToTaskView(task, pending)) {
                        // The task is being moved into taskView, so it is still "new" from
                        // TaskView's perspective (e.g. task being moved into a bubble)
                        stillNeedsMatchingLaunch = false;
                        isReadyForAnimation &= prepareOpenAnimation(pending.mTaskView,
                                true /* isNewInTaskView */, startTransaction, finishTransaction,
                                taskInfo, leash, wct);
                        adoptedTaskHandled |= adoptedToFront;
                    } else {
                        isReadyForAnimation &= prepareOpenAnimation(infoTv,
                                adoptedToFront /* isNewInTaskView */,
                                startTransaction, finishTransaction,
                                taskInfo, leash, wct);
                        adoptedTaskHandled |= adoptedToFront;
                    }
                    break;
                case TRANSIT_CHANGE:
                    final TaskViewTaskController changeTv = infoTv == null
                            && isTaskToTaskView(task, pending) ? pending.mTaskView : infoTv;
                    if (changeTv == null) break;
                    if (isMovingTaskViewToFullscreen(taskInfo, changeTv, pending)) {
                        // The transition finish transaction already reparents the task leash back
                        // to its fullscreen hierarchy. Reparenting it to the TaskView here leaves
                        // the fullscreen task under a surface that is about to be destroyed.
                        updateVisibilityState(changeTv, false /* visible */);
                        pending.mFullscreenTaskMatched = true;
                        startTransaction.setCornerRadius(leash, 0f);
                        finishTransaction.setCornerRadius(leash, 0f);
                        break;
                    }
                    final boolean adopted = isAdoptedTask(task, pending);
                    final Rect boundsOnScreen = changeTv.prepareOpen(task.getTaskInfo(), leash);
                    if (boundsOnScreen != null) {
                        if (wct == null) wct = new WindowContainerTransaction();
                        updateBounds(changeTv, boundsOnScreen, startTransaction, finishTransaction,
                                taskInfo, leash, wct);
                        if (changingDisplayId == task.getEndDisplayId()) {
                            ProtoLog.d(WM_SHELL_BUBBLES, "Transitions.startAnimation(): "
                                    + "display change, taskView=%d", changeTv.hashCode());
                            // Remove the change from TransitionInfo to avoid the transition from
                            // being handled by another TaskViewTransitions instance.
                            info.getChanges().remove(task);
                        }
                    } else {
                        startTransaction.reparent(leash, changeTv.getSurfaceControl());
                        finishTransaction.reparent(leash, changeTv.getSurfaceControl())
                                .setPosition(leash, 0, 0);
                    }
                    if (adopted) {
                        adoptedTaskHandled = true;
                        changeTv.notifyAppeared(true /* newTask */);
                    }
                    break;
                default:
                    break;
            }
        }

        // Check for unexpected changes in transition
        for (int i = 0; i < alienChanges.size(); ++i) {
            final TransitionInfo.Change change = alienChanges.get(i);
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null) {
                // Silently ignore non-tasks
                continue;
            }
            if (change.getMode() == TRANSIT_OPEN
                    && (pending == null || !taskInfo.containsLaunchCookie(pending.mLaunchCookie))) {
                Slog.e(TAG, "Found a launching TaskView in the wrong transition. All "
                        + "TaskView launches should be initiated by shell and in their "
                        + "own transition: " + taskInfo.taskId);
                dispatchState.addError(this, change, CAPTURED_CHANGE_IN_WRONG_TRANSITION);
            } else {
                Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. This "
                        + "shouldn't happen, so there may be a visual artifact: "
                        + taskInfo.taskId);
                dispatchState.addError(this, change, CAPTURED_UNRELATED_CHANGE);
            }
        }

        if (pending != null && pending.mNotifyTaskRemovalAfterTransition
                && !pending.mFullscreenTaskMatched) {
            Slog.w(TAG, "Expected fullscreen OneStep task " + pending.mFullscreenTaskId
                    + " in transition, rolling it back");
            rollbackFullscreen(pending);
            startNextTransition();
            return false;
        } else if (pending != null && pending.mAdoptedTaskId >= 0 && !adoptedTaskHandled) {
            Slog.w(TAG, "Expected adopted task " + pending.mAdoptedTaskId
                    + " in transition, rolling it back");
            rollbackAdoption(pending);
        } else if (stillNeedsMatchingLaunch) {
            Slog.w(TAG, "Expected a TaskView launch in this transition but didn't get one, "
                    + "cleaning up the task view");
            // Didn't find a task so the task must have never launched
            pending.mTaskView.setTaskNotFound();
        } else if (wct == null && pending == null && taskViews.size() != info.getChanges().size()) {
            // Just some house-keeping, let another handler animate.
            return false;
        } else if (!isReadyForAnimation) {
            // Animation could not be fully prepared. The surface for one or more TaskViews was
            // destroyed after the WCT had started. Roll back the listener/windowing migration
            // before another handler consumes the transition, otherwise the task escapes
            // fullscreen while the TaskView repository still claims it is embedded.
            Slog.w(TAG, "Animation not ready for all TaskViews; rolling back OneStep state.");
            if (pending != null) {
                if (pending.mAdoptedTaskId >= 0) {
                    rollbackAdoption(pending);
                } else if (pending.mLaunchCookie != null) {
                    pending.mTaskView.setTaskNotFound();
                    updateVisibilityState(pending.mTaskView, false /* visible */);
                }
            }
            startNextTransition();
            return false;
        }
        if (changingDisplayId > -1) {
            // Wait for setTaskBoundsInTransition -> mergeAnimation to let DefaultTransitionHandler
            // run display level animation after the new bounds of TaskView is set.
            mPendingRedirectTransition = new PendingRedirectTransition(() ->
                    mTransitions.dispatchTransition(transition, info, startTransaction,
                            finishTransaction, finishCallback, this), wct);
            mTransitions.getMainExecutor().executeDelayed(() -> {
                if (mPendingRedirectTransition != null) {
                    Slog.w(TAG, "Timed out to wait for transition of setTaskBounds");
                    executePendingRedirectTransition();
                }
            }, PendingRedirectTransition.TIMEOUT_MS);
            return true;
        }
        // No animation, just show it immediately.
        startTransaction.apply();
        finishCallback.onTransitionFinished(wct);
        notifyTaskRemovalAfterTransition(pending);
        finishSwap(pending);
        startNextTransition();
        return true;
    }

    private boolean startAnimationLegacy(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final PendingTransition pending = findPending(transition);
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.startAnimation(): taskView=%d "
                + "type=%s transition=%s", pending != null ? pending.mTaskView.hashCode() : -1,
                pending != null ? transitTypeToString(pending.mType) : "unknown", transition);
        if (pending != null) {
            mPending.remove(pending);
            pending.mFullscreenTaskMatched = false;
        }
        if (mTaskViewRepo.isEmpty()) {
            if (pending != null) {
                Slog.e(TAG, "Pending taskview transition but no task-views");
                rollbackPendingTransition(pending);
            }
            return false;
        }
        boolean stillNeedsMatchingLaunch = pending != null && pending.mLaunchCookie != null;
        boolean adoptedTaskHandled = false;
        ArrayList<TransitionInfo.Change> taskViewChanges = null;
        int changingDisplayId = -1;
        int changesHandled = 0;
        WindowContainerTransaction wct = null;
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = info.getChanges().get(i);
            if (Flags.fixTaskViewRotationAnimation() && chg.hasFlags(TransitionInfo.FLAG_IS_DISPLAY)
                    && chg.getMode() == TRANSIT_CHANGE && mPendingRedirectTransition == null) {
                changingDisplayId = chg.getEndDisplayId();
                continue;
            }
            final ActivityManager.RunningTaskInfo taskInfo = chg.getTaskInfo();
            if (taskInfo == null) continue;
            if (TransitionUtil.isClosingType(chg.getMode())) {
                final boolean isHide = chg.getMode() == TRANSIT_TO_BACK;
                TaskViewTaskController tv = findTaskView(taskInfo);
                if (tv == null && !isHide) {
                    // TaskView can be null when closing
                    changesHandled++;
                    continue;
                }
                if (tv == null) {
                    if (pending != null) {
                        Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. This "
                                + "shouldn't happen, so there may be a visual artifact: "
                                + taskInfo.taskId);
                    }
                    continue;
                }
                if (isHide) {
                    if (pending != null && pending.mType == TRANSIT_TO_BACK
                            && !tv.isOneStepTaskView()) {
                        // TO_BACK is only used when setting the task view visibility immediately,
                        // so in that case we can also hide the surface immediately
                        startTransaction.hide(chg.getLeash());
                    }
                    tv.prepareHideAnimation(finishTransaction);
                } else {
                    tv.prepareCloseAnimation();
                }
                changesHandled++;
            } else if (TransitionUtil.isOpeningType(chg.getMode())) {
                boolean isNewInTaskView = false;
                final boolean adopted = isAdoptedTask(chg, pending);
                TaskViewTaskController tv;
                if (chg.getMode() == TRANSIT_OPEN) {
                    isNewInTaskView = true;
                    if (pending == null || !taskInfo.containsLaunchCookie(pending.mLaunchCookie)) {
                        Slog.e(TAG, "Found a launching TaskView in the wrong transition. All "
                                + "TaskView launches should be initiated by shell and in their "
                                + "own transition: " + taskInfo.taskId);
                        continue;
                    }
                    stillNeedsMatchingLaunch = false;
                    tv = pending.mTaskView;
                } else {
                    tv = findTaskView(taskInfo);
                    if (tv == null && pending != null) {
                        if (isTaskToTaskView(chg, pending)) {
                            // In this case an existing task, not currently in TaskView, is
                            // brought to the front to be moved into TaskView. This is still
                            // "new" from TaskView's perspective. (e.g. task being moved into a
                            // bubble)
                            isNewInTaskView = true;
                            stillNeedsMatchingLaunch = false;
                            tv = pending.mTaskView;
                            adoptedTaskHandled |= adopted;
                        } else {
                            Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. "
                                    + "This shouldn't happen, so there may be a visual "
                                    + "artifact: " + taskInfo.taskId);
                        }
                    }
                    if (tv == null) continue;
                    if (adopted) {
                        isNewInTaskView = true;
                        stillNeedsMatchingLaunch = false;
                        adoptedTaskHandled = true;
                    }
                }
                if (wct == null) wct = new WindowContainerTransaction();
                prepareOpenAnimation(tv, isNewInTaskView, startTransaction, finishTransaction,
                        taskInfo, chg.getLeash(), wct);
                changesHandled++;
            } else if (chg.getMode() == TRANSIT_CHANGE) {
                TaskViewTaskController tv = findTaskView(taskInfo);
                final boolean adopted = isAdoptedTask(chg, pending);
                if (tv == null && adopted) tv = pending.mTaskView;
                if (tv == null) {
                    if (pending != null) {
                        Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. This "
                                + "shouldn't happen, so there may be a visual artifact: "
                                + taskInfo.taskId);
                    }
                    continue;
                }
                if (isMovingTaskViewToFullscreen(taskInfo, tv, pending)) {
                    updateVisibilityState(tv, false /* visible */);
                    pending.mFullscreenTaskMatched = true;
                    startTransaction.setCornerRadius(chg.getLeash(), 0f);
                    finishTransaction.setCornerRadius(chg.getLeash(), 0f);
                    changesHandled++;
                    continue;
                }
                if (taskViewChanges == null) {
                    taskViewChanges = new ArrayList<>();
                }
                taskViewChanges.add(chg);
                final Rect boundsOnScreen = tv.prepareOpen(chg.getTaskInfo(), chg.getLeash());
                if (boundsOnScreen != null) {
                    if (wct == null) wct = new WindowContainerTransaction();
                    updateBounds(tv, boundsOnScreen, startTransaction, finishTransaction,
                            chg.getTaskInfo(), chg.getLeash(), wct);
                } else {
                    startTransaction.reparent(chg.getLeash(), tv.getSurfaceControl());
                    finishTransaction.reparent(chg.getLeash(), tv.getSurfaceControl())
                            .setPosition(chg.getLeash(), 0, 0);
                }
                if (adopted) {
                    adoptedTaskHandled = true;
                    tv.notifyAppeared(true /* newTask */);
                }
                changesHandled++;
            }
        }
        if (pending != null && pending.mNotifyTaskRemovalAfterTransition
                && !pending.mFullscreenTaskMatched) {
            Slog.w(TAG, "Expected fullscreen OneStep task " + pending.mFullscreenTaskId
                    + " in legacy transition, rolling it back");
            rollbackFullscreen(pending);
            startNextTransition();
            return false;
        } else if (pending != null && pending.mAdoptedTaskId >= 0 && !adoptedTaskHandled) {
            Slog.w(TAG, "Expected adopted task " + pending.mAdoptedTaskId
                    + " in legacy transition, rolling it back");
            rollbackAdoption(pending);
        } else if (stillNeedsMatchingLaunch) {
            Slog.w(TAG, "Expected a TaskView launch in this transition but didn't get one, "
                    + "cleaning up the task view");
            // Didn't find a task so the task must have never launched
            pending.mTaskView.setTaskNotFound();
        } else if (wct == null && pending == null && changesHandled != info.getChanges().size()) {
            // Just some house-keeping, let another handler animate.
            return false;
        }
        if (changingDisplayId > -1 && taskViewChanges != null) {
            ProtoLog.d(WM_SHELL_BUBBLES, "Transitions.startAnimationLegacy(): "
                    + "handle display change");
            // Remove the change from TransitionInfo to avoid being handled by
            // another TaskViewTransitions instance.
            info.getChanges().removeAll(taskViewChanges);
            // Wait for setTaskBoundsInTransition -> mergeAnimation to let DefaultTransitionHandler
            // run display level animation after the new bounds of TaskView is set.
            mPendingRedirectTransition = new PendingRedirectTransition(() ->
                    mTransitions.dispatchTransition(transition, info, startTransaction,
                            finishTransaction, finishCallback, this), wct);
            mTransitions.getMainExecutor().executeDelayed(() -> {
                if (mPendingRedirectTransition != null) {
                    Slog.w(TAG, "Timed out to wait for transition of setTaskBounds");
                    executePendingRedirectTransition();
                }
            }, PendingRedirectTransition.TIMEOUT_MS);
            return true;
        }
        // No animation, just show it immediately.
        startTransaction.apply();
        finishCallback.onTransitionFinished(wct);
        notifyTaskRemovalAfterTransition(pending);
        finishSwap(pending);
        startNextTransition();
        return true;
    }

    /**
     * Prepares the TaskView for an open animation.
     *
     * @param taskView the {@link TaskViewTaskController} for the TaskView being opened.
     * @param newTask whether the task is considered new within this {@link TaskView}.
     * @param startTransaction the transaction to apply before the animation starts.
     * @param finishTransaction the transaction to apply after the animation finishes.
     * @param taskInfo information about the running task to animate.
     * @param leash the surface leash representing the task's surface.
     * @param wct a {@link WindowContainerTransaction} to apply changes.
     * @return {@code true} if the TaskView's surface is created and ready for animation,
     * {@code false} if the surface was destroyed and the animation should be deferred.
     */
    @VisibleForTesting
    public boolean prepareOpenAnimation(TaskViewTaskController taskView,
            final boolean newTask,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            WindowContainerTransaction wct) {
        final Rect boundsOnScreen = taskView.prepareOpen(taskInfo, leash);
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.prepareOpenAnimation(): taskView=%d "
                        + "newTask=%b bounds=%s", taskView.hashCode(), newTask, boundsOnScreen);
        final boolean isSurfaceCreated = boundsOnScreen != null;
        if (isSurfaceCreated) {
            updateBounds(taskView, boundsOnScreen, startTransaction, finishTransaction, taskInfo,
                    leash, wct);
        } else if (taskView.isOneStepTaskView()) {
            // Preserve the task and listener, but keep the activity hidden until the trusted
            // sidebar SurfaceView is recreated. A visible orphan task can steal focus and system
            // bar control even though its 2051 host window is gone.
            wct.setHidden(taskInfo.token, true /* hidden */);
            wct.setAlwaysOnTop(taskInfo.token, false);
            wct.setFocusable(taskInfo.token, false);
            updateVisibilityState(taskView, false /* visible */);
        } else {
            // The surface has already been destroyed before the task has appeared,
            // so go ahead and hide the task entirely
            wct.setHidden(taskInfo.token, true /* hidden */);
            updateVisibilityState(taskView, false /* visible */);
            // listener callback is below
        }
        if (newTask) {
            wct.setInterceptBackPressedOnTaskRoot(taskInfo.token, true /* intercept */);
        }
        if (taskView.isOneStepTaskView()) {
            wct.setAlwaysOnTop(taskInfo.token, true);
            wct.setFocusable(taskInfo.token, false);
        }

        if (taskInfo.taskDescription != null) {
            int backgroundColor = taskInfo.taskDescription.getBackgroundColor();
            taskView.setResizeBgColor(startTransaction, backgroundColor);
        }

        // After the embedded task has appeared, set it to non-trimmable. This is important
        // to prevent recents from trimming and removing the embedded task.
        wct.setTaskTrimmableFromRecents(taskInfo.token, false /* isTrimmableFromRecents */);

        taskView.notifyAppeared(newTask);
        return isSurfaceCreated;
    }

    /**
     * Updates bounds for the task view during an unfold transition.
     *
     * @return true if the task was found and a transition for this task is pending. false
     * otherwise.
     */
    public boolean updateBoundsForUnfold(Rect bounds, SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        final TaskViewTaskController taskView = findTaskView(taskInfo);
        if (taskView == null) {
            return false;
        }

        final PendingTransition pendingTransition = findPending(taskView, TRANSIT_CHANGE);
        if (pendingTransition == null) {
            return false;
        }

        mPending.remove(pendingTransition);

        updateSurface(leash, startTransaction, finishTransaction, taskView,
                bounds.width(), bounds.height());
        updateBoundsState(taskView, bounds);
        return true;
    }

    /** Updates the surface properties for a TaskView's task leash. */
    private void updateSurface(@NonNull SurfaceControl leash,
            @NonNull SurfaceControl.Transaction startT, @NonNull SurfaceControl.Transaction finishT,
            @NonNull TaskViewTaskController taskView, int width, int height) {
        // Reparent the task under the task view surface and set the bounds on it.
        applyTaskSurfacePresentation(startT, leash, taskView, width, height, true /* show */);
        // The finish transaction would reparent the task back to the window hierarchy parent, so
        // reparent it to the task view surface.
        applyTaskSurfacePresentation(finishT, leash, taskView, width, height, false /* show */);
    }

    /**
     * Maps fixed OneStep content into the TaskView's local Surface parent. Screen coordinates
     * never enter this transaction; the trusted 2051 window and its View layout own placement.
     */
    private static void applyTaskSurfacePresentation(SurfaceControl.Transaction transaction,
            SurfaceControl leash, TaskViewTaskController taskView, int fallbackWidth,
            int fallbackHeight, boolean show) {
        final Rect logicalBounds = taskView.getTaskBounds();
        final int cropWidth = logicalBounds != null && !logicalBounds.isEmpty()
                ? logicalBounds.width() : Math.max(1, fallbackWidth);
        final int cropHeight = logicalBounds != null && !logicalBounds.isEmpty()
                ? logicalBounds.height() : Math.max(1, fallbackHeight);
        final float scale = taskView.getTaskSurfaceScale();
        float dsdx = scale;
        float dtdx = 0f;
        float dtdy = 0f;
        float dsdy = scale;
        float x = 0f;
        float y = 0f;
        if (taskView.isOneStepTaskView()) {
            switch (taskView.getOneStepContentRotation()) {
                case Surface.ROTATION_90:
                    dsdx = 0f;
                    dtdx = -scale;
                    dtdy = scale;
                    dsdy = 0f;
                    x = cropHeight * scale;
                    break;
                case Surface.ROTATION_180:
                    dsdx = -scale;
                    dsdy = -scale;
                    x = cropWidth * scale;
                    y = cropHeight * scale;
                    break;
                case Surface.ROTATION_270:
                    dsdx = 0f;
                    dtdx = scale;
                    dtdy = -scale;
                    dsdy = 0f;
                    y = cropWidth * scale;
                    break;
                default:
                    break;
            }
        }
        transaction.reparent(leash, taskView.getSurfaceControl())
                .setPosition(leash, x, y)
                .setMatrix(leash, dsdx, dtdx, dtdy, dsdy)
                .setWindowCrop(leash, cropWidth, cropHeight)
                .setCornerRadius(leash, taskView.getTaskCornerRadius());
        if (show) transaction.show(leash);
    }

    private void updateBounds(TaskViewTaskController taskView, Rect boundsOnScreen,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            WindowContainerTransaction wct) {
        ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Transitions.updateBounds(): taskView=%d bounds=%s",
                taskView.hashCode(), boundsOnScreen);
        final int width = boundsOnScreen.width();
        final int height = boundsOnScreen.height();
        // Adoption starts from a fullscreen leash that already has screen-space position and crop.
        // Reset both in the start transaction as well as the finish transaction, otherwise the
        // first frame remains offset under the TaskView parent (and may stay there if another
        // transition merges before the finish transaction is applied).
        applyTaskSurfacePresentation(startTransaction, leash, taskView, width, height,
                true /* show */);
        // Also reparent on finishTransaction since the finishTransaction will reparent back
        // to its "original" parent by default.
        if (finishTransaction != null) {
            applyTaskSurfacePresentation(finishTransaction, leash, taskView, width, height,
                    false /* show */);
        }
        updateBoundsState(taskView, boundsOnScreen);
        updateVisibilityState(taskView, true /* visible */);
        wct.setBounds(taskInfo.token, boundsOnScreen);
        taskView.applyCaptionInsetsIfNeeded();
    }

    private void executePendingRedirectTransition() {
        if (mPendingRedirectTransition != null) {
            mPendingRedirectTransition.dispatchTransition();
            mPendingRedirectTransition = null;
        }
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT, @NonNull SurfaceControl.Transaction finishT,
            @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (!Flags.fixTaskViewRotationAnimation()) return;
        final PendingTransition pending = findPending(transition);
        if (pending != null) {
            mPending.remove(pending);
        }
        executePendingRedirectTransition();
        boolean hasHandledTaskView = false;
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null) continue;
            final TaskViewTaskController taskView = findTaskView(taskInfo);
            if (taskView == null) continue;
            final SurfaceControl leash = change.getLeash();
            final Rect endBounds = change.getEndAbsBounds();
            updateSurface(leash, startT, finishT, taskView, endBounds.width(), endBounds.height());
            hasHandledTaskView = true;
        }
        ProtoLog.d(WM_SHELL_BUBBLES, "mergeAnimation(): matchedPending=%b hasHandledTaskView=%b",
                pending != null, hasHandledTaskView);
        if (hasHandledTaskView) {
            startT.apply();
            finishCallback.onTransitionFinished(null /* wct */);
        }
    }

    /** Dumps TaskViewTransitions state. */
    public void dump(PrintWriter pw) {
        pw.println("TaskViewTransitions state:");
        pw.println("  Pending transitions count: " + mPending.size());
        for (PendingTransition pendingTransition : mPending) {
            pendingTransition.dump(pw, "    ");
        }
        mTaskViewRepo.dump(pw, "  ");
    }

    /**
     * This holds a transition that is deferred, for example a display-change that also affects a
     * TaskView. The transition is dispatched once the TaskView reports its new bounds (i.e.
     * {@link #setTaskBounds}), which happens via a {@link #mergeAnimation} call, or on timeout.
     */
    private static class PendingRedirectTransition {
        static final long TIMEOUT_MS = 500;
        private final Runnable mDispatchTransition;
        private WindowContainerTransaction mWct;

        PendingRedirectTransition(@NonNull Runnable dispatch,
                @Nullable WindowContainerTransaction wct) {
            mDispatchTransition = dispatch;
            mWct = wct;
        }

        @NonNull
        WindowContainerTransaction takePendingWct() {
            final WindowContainerTransaction wct = mWct;
            mWct = null;
            return wct != null ? wct : new WindowContainerTransaction();
        }

        void dispatchTransition() {
            mDispatchTransition.run();
        }
    }

    /** Interface for running an external transition in this object's pending queue. */
    public interface ExternalTransition {
        /** Starts a transition and returns an identifying key for lookup. */
        IBinder start();
    }
}
