/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.HandlerExecutor;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.ScreenCaptureInternal;
import android.window.TaskSnapshot;
import android.window.TaskSnapshotManager;

/**
 * Owns the stable task image shown underneath the transparent R2 keyguard.
 *
 * <p>The controller deliberately has no Binder surface. It prepares the image while WindowManager
 * still owns the task surfaces, then leaves SystemUI to animate only its transparent curtain. The
 * prepared pixels always live in one secure, read-only buffer layer named {@code
 * keyguard_background}. Keeping one stable composition path is important: retaining only Home's
 * Task/Activity parents looked valid to WindowManager but its child surfaces could still be hidden,
 * which produced a black exposed area during the unlock gesture.</p>
 */
final class KeyguardBackgroundController {
    private static final String TAG = "KeyguardBackground";
    /** Watchdog only; the normal handoff removes the frozen layer on a committed real-task frame. */
    private static final long HANDOFF_TIMEOUT_MS = 600L;
    private static final long WAIT_FOR_KEYGUARD_MS = 800L;
    private static final long RETRY_DELAY_MS = 120L;
    private static final long SLOW_RETRY_DELAY_MS = 2_000L;
    private static final int FAST_PREPARE_RETRIES = 8;
    private static final int LAYER_BELOW_ANCHOR = -1;
    private static final int LAYER_ABOVE_TASK_AREA = 1;

    private final WindowManagerService mService;

    @Nullable private SurfaceControl mBackgroundSurface;
    /** The concrete NotificationShade surface currently used as the relative-layer anchor. */
    @Nullable private SurfaceControl mNotificationShadeAnchor;
    @Nullable private Task mSourceTask;
    private int mSourceTaskId = -1;
    private int mSourceUserId = -1;
    private int mCaptureRotation = -1;
    private boolean mBlackFallback;
    private boolean mKeyguardShowing;
    private boolean mWaitingForKeyguard;
    private boolean mHandoffPending;
    private boolean mHandoffBarrierScheduled;
    private long mHandoffGeneration;
    private int mPrepareRetryCount;
    private PrepareFailure mPrepareFailure = PrepareFailure.NONE;
    private int mUnsafeEligibilitySignature;

    private enum PrepareFailure {
        NONE,
        UNSAFE,
        TRANSIENT,
    }

    enum CaptureResult {
        SUCCESS,
        UNSAFE,
        TRANSIENT,
    }

    private final Runnable mRemoveAfterHandoff;
    private final Runnable mAbortIfKeyguardDidNotShow;
    private final Runnable mRetryPrepare;

    KeyguardBackgroundController(WindowManagerService service) {
        mService = service;
        mRemoveAfterHandoff = () -> {
            synchronized (mService.mGlobalLock) {
                if (!mHandoffPending || mKeyguardShowing) {
                    return;
                }
                removeBackgroundLocked("handoff watchdog");
            }
        };
        mAbortIfKeyguardDidNotShow = () -> {
            synchronized (mService.mGlobalLock) {
                if (!mWaitingForKeyguard || mKeyguardShowing) {
                    return;
                }
                mWaitingForKeyguard = false;
                removeBackgroundLocked("keyguard did not show");
            }
        };
        mRetryPrepare = () -> {
            synchronized (mService.mGlobalLock) {
                if (!mKeyguardShowing || !mBlackFallback
                        || mPrepareFailure != PrepareFailure.TRANSIENT) {
                    return;
                }
                mPrepareRetryCount++;
                if (!prepareLocked("retry " + mPrepareRetryCount, false /* installFallback */)) {
                    schedulePrepareRetryLocked();
                }
            }
        };
    }

    /** Called before app task surfaces are hidden for a new keyguard session. */
    void onScreenTurningOffLocked(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        cancelCallbacksLocked();
        removeBackgroundLocked("new screen-off session");
        mPrepareRetryCount = 0;
        mWaitingForKeyguard = true;
        prepareLocked("screen turning off", true /* installFallback */);
        mService.mH.postDelayed(mAbortIfKeyguardDidNotShow, WAIT_FOR_KEYGUARD_MS);
    }

    void onKeyguardShownChangedLocked(int displayId, boolean showing, boolean aodShowing) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mKeyguardShowing = showing;
        if (showing) {
            mHandoffGeneration++;
            mHandoffPending = false;
            mHandoffBarrierScheduled = false;
            mWaitingForKeyguard = false;
            cancelCallbacksLocked();
            if (mBackgroundSurface == null || mBlackFallback) {
                prepareLocked("keyguard shown", true /* installFallback */);
            }
            placeBackgroundBelowNotificationShadeLocked();
            if (mBlackFallback) {
                schedulePrepareRetryLocked();
            }
            return;
        }

        mWaitingForKeyguard = false;
        cancelCallbacksLocked();
        if (mBackgroundSurface != null && !mBlackFallback
                && isFrozenCaptureSessionSafeLocked()) {
            // Keep the prepared image above the task area until the real task has completed its
            // first post-keyguard composition.  The task identity is intentionally not part of
            // this check: the frozen pixels were validated when captured and Launcher commonly
            // replaces its task during cold boot while Keyguard remains visible.
            placeBackgroundAboveTaskAreaLocked();
            mHandoffGeneration++;
            mHandoffPending = true;
            mHandoffBarrierScheduled = false;
            // Request a post-keyguard placement. Once the real app window has been placed and drawn,
            // a SurfaceFlinger commit barrier removes the frozen layer. The timeout is only a
            // fail-safe for a broken/missing app surface; it is not the normal visual handoff.
            mService.mWindowPlacerLocked.requestTraversal();
            mService.mH.postDelayed(mRemoveAfterHandoff, HANDOFF_TIMEOUT_MS);
        } else {
            removeBackgroundLocked("keyguard hidden without safe handoff");
        }
    }

    void onSurfacePlacementLocked() {
        if (!mKeyguardShowing) {
            scheduleCommittedHandoffIfReadyLocked();
            return;
        }
        final DisplayContent dc = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (dc == null) {
            removeBackgroundLocked("default display removed");
            return;
        }
        if (mBlackFallback) {
            if (mPrepareFailure == PrepareFailure.UNSAFE) {
                final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
                final int signature = calculateEligibilitySignatureLocked(tda);
                if (signature != mUnsafeEligibilitySignature) {
                    mPrepareRetryCount = 0;
                    if (!prepareLocked("task safety changed", false /* installFallback */)) {
                        schedulePrepareRetryLocked();
                    }
                }
                return;
            }
            schedulePrepareRetryLocked();
            return;
        }
        if (mBackgroundSurface == null) {
            return;
        }
        if (!isFrozenCaptureSessionSafeLocked()) {
            installBlackFallbackLocked(dc, "frozen capture session became unsafe");
            markUnsafePrepareFailureLocked(dc.getDefaultTaskDisplayArea());
            schedulePrepareRetryLocked();
            return;
        }
        if (mCaptureRotation != dc.getRotation()) {
            // A stale-orientation buffer is never stretched into place. Rebuild from the current
            // cache/capture if possible, otherwise use the secure black fallback.
            removeBackgroundLocked("display rotated");
            prepareLocked("display rotated", true /* installFallback */);
            if (mBlackFallback) {
                schedulePrepareRetryLocked();
            }
            return;
        }

        // During cold boot Keyguard can be reported before SystemUI has registered its
        // NotificationShade WindowToken. The initial surface then uses TaskDisplayArea as a safe
        // temporary parent. As soon as Shade exists, move the already prepared buffer directly
        // underneath it; otherwise the temporary parent may be rebuilt and leave the buffer in
        // SurfaceFlinger's Offscreen Hierarchy even though capture itself succeeded.
        final WindowState shade = getNotificationShadeWithSurface(dc);
        if (shade != null && (mNotificationShadeAnchor == null
                || !mNotificationShadeAnchor.isSameSurface(shade.getSurfaceControl()))) {
            placeBackgroundBelowNotificationShadeLocked();
        }
    }

    /**
     * Removes the frozen layer only after SurfaceFlinger has accepted a transaction submitted
     * behind a fully drawn, visible real task. This prevents both a black gap and the old fixed
     * 600ms snapshot-to-live jump.
     */
    private void scheduleCommittedHandoffIfReadyLocked() {
        if (!mHandoffPending || mHandoffBarrierScheduled || mBackgroundSurface == null
                || !mBackgroundSurface.isValid() || !isRealTaskReadyForHandoffLocked()) {
            return;
        }

        final SurfaceControl expectedSurface = mBackgroundSurface;
        final long expectedGeneration = mHandoffGeneration;
        mHandoffBarrierScheduled = true;
        final SurfaceControl.Transaction barrier = mService.mTransactionFactory.get();
        // Touch the retained surface so this listener is ordered after the task placement that was
        // submitted immediately before this method from RootWindowContainer.
        barrier.show(expectedSurface);
        barrier.addTransactionCommittedListener(new HandlerExecutor(mService.mH), () -> {
            synchronized (mService.mGlobalLock) {
                if (!mHandoffPending || mKeyguardShowing
                        || mBackgroundSurface != expectedSurface
                        || mHandoffGeneration != expectedGeneration) {
                    return;
                }
                mService.mH.removeCallbacks(mRemoveAfterHandoff);
                removeBackgroundLocked("real task frame committed");
            }
        });
        barrier.apply();
        barrier.close();
    }

    private boolean isRealTaskReadyForHandoffLocked() {
        final DisplayContent dc = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (dc == null) {
            return false;
        }
        final Task topTask = findTopVisibleLeafTaskLocked(dc.getDefaultTaskDisplayArea());
        if (topTask == null || !topTask.isVisibleRequested()) {
            return false;
        }
        final WindowState mainWindow = topTask.getTopVisibleAppMainWindow();
        return mainWindow != null && mainWindow.isVisibleNow() && mainWindow.isDisplayed()
                && mainWindow.mWinAnimator.hasSurface() && mainWindow.mWinAnimator.mLastAlpha > 0f;
    }

    void onUserSwitchedLocked() {
        mKeyguardShowing = false;
        mWaitingForKeyguard = false;
        cancelCallbacksLocked();
        removeBackgroundLocked("user switched");
    }

    void onDisplayRemovedLocked(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mKeyguardShowing = false;
        mWaitingForKeyguard = false;
        cancelCallbacksLocked();
        removeBackgroundLocked("display removed");
    }

    /**
     * Prepares a real task-area image. Returns false when only the existing/fresh black fallback is
     * available. The retry path deliberately leaves an existing black layer in place until a real
     * buffer has been committed, so it can never expose stale or uninitialised pixels.
     */
    private boolean prepareLocked(String reason, boolean installFallback) {
        final DisplayContent dc = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (dc == null) {
            markTransientPrepareFailureLocked();
            return false;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        final Task topTask = findTopVisibleLeafTaskLocked(tda);
        if (topTask == null) {
            if (installFallback) {
                clearSourceLocked();
                installBlackFallbackLocked(dc, reason + ": no safe current task");
            }
            markTransientPrepareFailureLocked();
            return false;
        }
        if (!isTaskAllowedLocked(topTask)) {
            if (installFallback) {
                clearSourceLocked();
                installBlackFallbackLocked(dc, reason + ": no safe current task");
            }
            markUnsafePrepareFailureLocked(tda);
            return false;
        }

        rememberSourceLocked(topTask, dc.getRotation());
        if (!areAllVisibleTasksSafeLocked(tda)) {
            if (installFallback) {
                installBlackFallbackLocked(dc, reason + ": protected visible task");
            }
            markUnsafePrepareFailureLocked(tda);
            return false;
        }

        final Rect captureBounds = new Rect(tda.getBounds());
        captureBounds.offsetTo(0, 0);
        if (topTask.isVisible()) {
            final CaptureResult captureResult =
                    captureTaskDisplayAreaLocked(dc, tda, captureBounds);
            if (captureResult == CaptureResult.SUCCESS) {
                Slog.i(TAG, "Prepared keyguard_background from task-area capture, task="
                        + topTask.mTaskId + " reason=" + reason);
                markPrepareSucceededLocked();
                return true;
            }
            if (captureResult == CaptureResult.UNSAFE) {
                if (installFallback) {
                    installBlackFallbackLocked(dc, reason + ": protected capture buffer");
                }
                markUnsafePrepareFailureLocked(tda);
                return false;
            }
        }
        if (installCachedSnapshotLocked(dc, tda, topTask)) {
            Slog.i(TAG, "Prepared keyguard_background from cached snapshot, task="
                    + topTask.mTaskId + " reason=" + reason);
            markPrepareSucceededLocked();
            return true;
        }
        if (installFallback) {
            installBlackFallbackLocked(dc, reason + ": capture failed");
        }
        markTransientPrepareFailureLocked();
        return false;
    }

    private CaptureResult captureTaskDisplayAreaLocked(DisplayContent dc, TaskDisplayArea tda,
            Rect captureBounds) {
        final ScreenCaptureInternal.LayerCaptureArgs.Builder builder =
                new ScreenCaptureInternal.LayerCaptureArgs.Builder(tda.getSurfaceControl())
                        .setSourceCrop(captureBounds)
                        .setPreserveDisplayColors(true);
        // Retry capture while the secure black fallback is installed, but never capture that
        // fallback into the replacement buffer.  This makes the retry useful even when no cached
        // TaskSnapshot exists and avoids promoting an all-black capture as a successful result.
        if (mBackgroundSurface != null && mBackgroundSurface.isValid()) {
            builder.setExcludeLayers(new SurfaceControl[] {mBackgroundSurface});
        }
        final ScreenCaptureInternal.LayerCaptureArgs args = builder.build();
        final ScreenCaptureInternal.ScreenshotHardwareBuffer screenshot =
                ScreenCaptureInternal.captureLayers(args);
        final HardwareBuffer buffer = screenshot == null ? null : screenshot.getHardwareBuffer();
        final CaptureResult initialResult = classifyCaptureResultProperties(
                screenshot != null,
                screenshot != null && screenshot.containsSecureLayers(),
                buffer != null && !buffer.isClosed() && buffer.getWidth() > 1
                        && buffer.getHeight() > 1,
                buffer != null && !buffer.isClosed()
                        && (buffer.getUsage() & HardwareBuffer.USAGE_PROTECTED_CONTENT) != 0);
        if (initialResult != CaptureResult.SUCCESS) {
            closeScreenshotBuffer(screenshot);
            return initialResult;
        }

        final SurfaceControl surface = buildBufferSurfaceLocked(dc, tda, buffer, "capture");
        if (surface == null) {
            closeScreenshotBuffer(screenshot);
            return CaptureResult.TRANSIENT;
        }
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        t.setBuffer(surface, buffer);
        t.setColorSpace(surface, screenshot.getColorSpace());
        configureFullTaskAreaSurface(t, surface, tda, buffer.getWidth(), buffer.getHeight());
        final SurfaceControl shadeAnchor = placeBelowNotificationShade(t, surface, dc, tda);
        t.show(surface);
        t.apply();
        t.close();
        buffer.close();
        replaceBackgroundSurfaceLocked(surface, false /* blackFallback */);
        mNotificationShadeAnchor = shadeAnchor;
        return CaptureResult.SUCCESS;
    }

    private boolean installCachedSnapshotLocked(DisplayContent dc, TaskDisplayArea tda,
            Task sourceTask) {
        final TaskSnapshot snapshot = mService.mTaskSnapshotController.getSnapshot(
                sourceTask.mTaskId, TaskSnapshotManager.RESOLUTION_HIGH,
                TaskSnapshot.REFERENCE_NONE);
        if (snapshot == null || !snapshot.isBufferValid() || !snapshot.isRealSnapshot()
                || snapshot.hasProtectedContent() || snapshot.getRotation() != dc.getRotation()) {
            return false;
        }
        final ActivityRecord activity = sourceTask.getTopNonFinishingActivity();
        if (activity == null || snapshot.getTopActivityComponent() == null
                || !snapshot.getTopActivityComponent().equals(activity.mActivityComponent)) {
            return false;
        }
        final HardwareBuffer buffer = snapshot.getHardwareBuffer();
        if (!isUsableUnprotectedBuffer(buffer)) {
            return false;
        }
        final SurfaceControl surface = buildBufferSurfaceLocked(dc, tda, buffer, "task snapshot");
        if (surface == null) {
            return false;
        }
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        snapshot.setBufferToSurface(t, surface);
        t.setColorSpace(surface, snapshot.getColorSpace());
        configureFullTaskAreaSurface(t, surface, tda, buffer.getWidth(), buffer.getHeight());
        final SurfaceControl shadeAnchor = placeBelowNotificationShade(t, surface, dc, tda);
        t.show(surface);
        t.apply();
        t.close();
        replaceBackgroundSurfaceLocked(surface, false /* blackFallback */);
        mNotificationShadeAnchor = shadeAnchor;
        mCaptureRotation = snapshot.getRotation();
        return true;
    }

    private void installBlackFallbackLocked(DisplayContent dc, String reason) {
        if (mBackgroundSurface != null && mBlackFallback && mBackgroundSurface.isValid()
                && mCaptureRotation == dc.getRotation()) {
            return;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        final Rect bounds = tda.getBounds();
        final SurfaceControl surface;
        try {
            surface = mService.makeSurfaceBuilder()
                    .setName("keyguard_background")
                    .setParent(getKeyguardBackgroundParentSurface(dc, tda))
                    .setColorLayer()
                    .setOpaque(true)
                    .setSecure(true)
                    .setCallsite("KeyguardBackgroundController.black")
                    .build();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to create secure keyguard background", e);
            return;
        }
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        t.setColor(surface, new float[] {0f, 0f, 0f});
        t.setWindowCrop(surface, bounds.width(), bounds.height());
        final SurfaceControl shadeAnchor = placeBelowNotificationShade(t, surface, dc, tda);
        t.show(surface);
        t.apply();
        t.close();
        replaceBackgroundSurfaceLocked(surface, true /* blackFallback */);
        mNotificationShadeAnchor = shadeAnchor;
        mCaptureRotation = dc.getRotation();
        Slog.w(TAG, "Using black keyguard_background: " + reason);
    }

    @Nullable
    private SurfaceControl buildBufferSurfaceLocked(DisplayContent dc, TaskDisplayArea tda,
            HardwareBuffer buffer, String source) {
        try {
            return mService.makeSurfaceBuilder()
                    .setName("keyguard_background")
                    .setParent(getKeyguardBackgroundParentSurface(dc, tda))
                    .setBLASTLayer()
                    .setFormat(buffer.getFormat())
                    .setOpaque(true)
                    .setSecure(true)
                    .setCallsite("KeyguardBackgroundController." + source)
                    .build();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to create keyguard background from " + source, e);
            return null;
        }
    }

    private static void configureFullTaskAreaSurface(SurfaceControl.Transaction t,
            SurfaceControl surface, TaskDisplayArea tda, int bufferWidth, int bufferHeight) {
        final Rect bounds = tda.getBounds();
        final float sx = bounds.width() / (float) bufferWidth;
        final float sy = bounds.height() / (float) bufferHeight;
        t.setMatrix(surface, sx, 0f, 0f, sy);
        t.setWindowCrop(surface, bufferWidth, bufferHeight);
    }

    /**
     * Returns the parent used while Keyguard is visible.
     *
     * <p>The original R2 background is immediately underneath the Keyguard window. Putting the
     * frozen pixels merely below TaskDisplayArea still leaves Android's wallpaper/display-area
     * layers between the pixels and NotificationShade; when those layers are hidden for Keyguard,
     * the transparent curtain exposes black. Prefer NotificationShade's WindowToken so the
     * background and shade are siblings with no intervening policy layer.</p>
     */
    private static SurfaceControl getKeyguardBackgroundParentSurface(DisplayContent dc,
            TaskDisplayArea tda) {
        final WindowState shade = getNotificationShadeWithSurface(dc);
        final WindowContainer<?> parent = shade != null ? shade.getParent() : tda.getParent();
        if (parent == null || parent.getSurfaceControl() == null
                || !parent.getSurfaceControl().isValid()) {
            throw new IllegalStateException("Keyguard has no stable surface parent");
        }
        return parent.getSurfaceControl();
    }

    @Nullable
    private static WindowState getNotificationShadeWithSurface(DisplayContent dc) {
        final WindowState shade = dc.getDisplayPolicy().getNotificationShade();
        return shade != null && shade.getSurfaceControl() != null
                && shade.getSurfaceControl().isValid() && shade.getParent() != null
                && shade.getParent().getSurfaceControl() != null
                && shade.getParent().getSurfaceControl().isValid() ? shade : null;
    }

    private static void setPositionInParent(SurfaceControl.Transaction t, SurfaceControl surface,
            TaskDisplayArea tda, WindowContainer<?> parent) {
        final Rect bounds = tda.getBounds();
        final Rect parentBounds = parent == null ? new Rect() : parent.getBounds();
        t.setPosition(surface, bounds.left - parentBounds.left,
                bounds.top - parentBounds.top);
    }

    @Nullable
    private static SurfaceControl placeBelowNotificationShade(SurfaceControl.Transaction t,
            SurfaceControl surface, DisplayContent dc, TaskDisplayArea tda) {
        final WindowState shade = getNotificationShadeWithSurface(dc);
        if (shade != null) {
            final WindowContainer<?> parent = shade.getParent();
            t.reparent(surface, parent.getSurfaceControl());
            setPositionInParent(t, surface, tda, parent);
            t.setRelativeLayer(surface, shade.getSurfaceControl(), LAYER_BELOW_ANCHOR);
            return shade.getSurfaceControl();
        }
        placeRelativeToTaskArea(t, surface, tda, LAYER_BELOW_ANCHOR);
        return null;
    }

    private static void placeRelativeToTaskArea(SurfaceControl.Transaction t,
            SurfaceControl surface, TaskDisplayArea tda, int relativeLayer) {
        final SurfaceControl taskAreaSurface = tda.getSurfaceControl();
        final WindowContainer<?> parent = tda.getParent();
        if (parent == null || parent.getSurfaceControl() == null
                || !parent.getSurfaceControl().isValid()
                || taskAreaSurface == null || !taskAreaSurface.isValid()) {
            throw new IllegalStateException("TaskDisplayArea surface is unavailable");
        }
        t.reparent(surface, parent.getSurfaceControl());
        setPositionInParent(t, surface, tda, parent);
        t.setRelativeLayer(surface, taskAreaSurface, relativeLayer);
    }

    @Nullable
    private Task findTopVisibleLeafTaskLocked(TaskDisplayArea tda) {
        final Task[] result = new Task[1];
        tda.forAllLeafTasks(task -> {
            if (result[0] == null && task.isVisible()) {
                result[0] = task;
            }
        }, true /* traverseTopToBottom */);
        if (result[0] != null) {
            return result[0];
        }
        final Task topRootTask = tda.getTopRootTask();
        return topRootTask == null ? null : topRootTask.getTopLeafTask();
    }

    private boolean areAllVisibleTasksSafeLocked(TaskDisplayArea tda) {
        final boolean[] safe = {true};
        tda.forAllLeafTasks(task -> {
            // isVisibleRequested() closes the placement-frame gap before the new task's surfaces
            // become visibly committed. A protected task must revoke the frozen buffer first.
            if (safe[0] && (task.isVisible() || task.isVisibleRequested())
                    && !isTaskAllowedLocked(task)) {
                safe[0] = false;
            }
        }, true /* traverseTopToBottom */);
        return safe[0];
    }

    private boolean isTaskAllowedLocked(Task task) {
        if (task.getDisplayId() != DEFAULT_DISPLAY
                || task.mUserId != mService.mAtmService.getCurrentUserId()
                || task.isSecure()) {
            return false;
        }
        final int activityType = task.getActivityType();
        if (activityType != ACTIVITY_TYPE_HOME && activityType != ACTIVITY_TYPE_STANDARD) {
            return false;
        }
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        if (activity == null || activity.shouldUseAppThemeSnapshot()) {
            return false;
        }

        // A translucent top activity can expose another visible activity in the same task. Check
        // every visible activity instead of trusting only the top record, otherwise a FLAG_SECURE
        // window underneath it could be copied into keyguard_background.
        final boolean[] safe = {true};
        task.forAllActivities(candidate -> {
            if (safe[0] && candidate.isVisibleRequested()
                    && candidate.shouldUseAppThemeSnapshot()) {
                safe[0] = false;
            }
        }, true /* traverseTopToBottom */);
        return safe[0];
    }

    /**
     * Validates the immutable pixels captured for this lockscreen session.
     *
     * <p>A task being removed or replaced after capture is not a security failure: the buffer no
     * longer references that task's live surfaces.  Treating task identity as a continuing
     * invariant made the successful cold-boot Home capture turn black as soon as Launcher
     * recreated its task.  User, rotation and a newly-secure original task remain fail-closed
     * boundaries.</p>
     */
    private boolean isFrozenCaptureSessionSafeLocked() {
        if (mSourceUserId < 0
                || mSourceUserId != mService.mAtmService.getCurrentUserId()) {
            return false;
        }
        final DisplayContent dc = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (dc == null || dc.getRotation() != mCaptureRotation) {
            return false;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        // A secure task becoming visible after the original capture revokes the entire frozen
        // layer. Never leave an older non-secure frame underneath a newly protected activity.
        if (!areAllVisibleTasksSafeLocked(tda)) {
            return false;
        }
        if (mSourceTask == null || !mSourceTask.isAttached()
                || mSourceTask.mTaskId != mSourceTaskId
                || mSourceTask.mUserId != mSourceUserId) {
            // The captured pixels are immutable and were fully checked at capture time. Launcher
            // commonly replaces its Home task during cold boot, so identity loss alone is safe.
            return true;
        }
        // Re-run the complete per-Activity policy, not only Task.isSecure(). This covers a window
        // adding FLAG_SECURE or disabling recents screenshots while Keyguard remains visible.
        return isTaskAllowedLocked(mSourceTask);
    }

    private void rememberSourceLocked(Task task, int rotation) {
        mSourceTask = task;
        mSourceTaskId = task.mTaskId;
        mSourceUserId = task.mUserId;
        mCaptureRotation = rotation;
    }

    private void replaceBackgroundSurfaceLocked(SurfaceControl surface, boolean blackFallback) {
        removeSurfaceLocked();
        mBackgroundSurface = surface;
        mBlackFallback = blackFallback;
    }

    private void placeBackgroundBelowNotificationShadeLocked() {
        if (mBackgroundSurface == null || !mBackgroundSurface.isValid()) {
            return;
        }
        final DisplayContent dc = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (dc == null) {
            removeBackgroundLocked("default display removed while layering background");
            return;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        final SurfaceControl shadeAnchor =
                placeBelowNotificationShade(t, mBackgroundSurface, dc, tda);
        t.show(mBackgroundSurface);
        t.apply();
        t.close();
        mNotificationShadeAnchor = shadeAnchor;
    }

    private void placeBackgroundAboveTaskAreaLocked() {
        if (mBackgroundSurface == null || !mBackgroundSurface.isValid()) {
            return;
        }
        final DisplayContent dc = mService.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        if (dc == null) {
            removeBackgroundLocked("default display removed while promoting background");
            return;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        placeRelativeToTaskArea(t, mBackgroundSurface, tda, LAYER_ABOVE_TASK_AREA);
        t.show(mBackgroundSurface);
        t.apply();
        t.close();
        mNotificationShadeAnchor = null;
    }

    private void removeBackgroundLocked(String reason) {
        if (mBackgroundSurface != null) {
            Slog.i(TAG, "Removing keyguard_background: " + reason);
        }
        removeSurfaceLocked();
        clearSourceLocked();
    }

    private void removeSurfaceLocked() {
        mHandoffGeneration++;
        mHandoffPending = false;
        mHandoffBarrierScheduled = false;
        if (mBackgroundSurface != null) {
            final SurfaceControl surface = mBackgroundSurface;
            mBackgroundSurface = null;
            if (surface.isValid()) {
                final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
                t.remove(surface);
                t.apply();
                t.close();
            }
            surface.release();
        }
        mBlackFallback = false;
        mNotificationShadeAnchor = null;
        mPrepareRetryCount = 0;
        mPrepareFailure = PrepareFailure.NONE;
        mUnsafeEligibilitySignature = 0;
    }

    private void clearSourceLocked() {
        mSourceTask = null;
        mSourceTaskId = -1;
        mSourceUserId = -1;
        mCaptureRotation = -1;
    }

    private void schedulePrepareRetryLocked() {
        if (!mKeyguardShowing || !mBlackFallback
                || mPrepareFailure != PrepareFailure.TRANSIENT
                || mService.mH.hasCallbacks(mRetryPrepare)) {
            return;
        }
        final long delay = retryDelayForAttempt(mPrepareRetryCount);
        mService.mH.postDelayed(mRetryPrepare, delay);
    }

    static long retryDelayForAttempt(int attempt) {
        return attempt < FAST_PREPARE_RETRIES ? RETRY_DELAY_MS : SLOW_RETRY_DELAY_MS;
    }

    static CaptureResult classifyCaptureResultProperties(boolean screenshotPresent,
            boolean containsSecureLayers, boolean bufferValid, boolean protectedBuffer) {
        // A capture can report security metadata even when its buffer is concurrently revoked or
        // invalidated. Security rejection must win over the transient-buffer classification so we
        // do not poll a protected task until its task/window eligibility actually changes.
        if (containsSecureLayers || protectedBuffer) {
            return CaptureResult.UNSAFE;
        }
        if (!screenshotPresent || !bufferValid) {
            return CaptureResult.TRANSIENT;
        }
        return CaptureResult.SUCCESS;
    }

    private void markPrepareSucceededLocked() {
        mService.mH.removeCallbacks(mRetryPrepare);
        mPrepareRetryCount = 0;
        mPrepareFailure = PrepareFailure.NONE;
        mUnsafeEligibilitySignature = 0;
    }

    private void markTransientPrepareFailureLocked() {
        mPrepareFailure = PrepareFailure.TRANSIENT;
        mUnsafeEligibilitySignature = 0;
    }

    private void markUnsafePrepareFailureLocked(TaskDisplayArea tda) {
        mService.mH.removeCallbacks(mRetryPrepare);
        mPrepareFailure = PrepareFailure.UNSAFE;
        mUnsafeEligibilitySignature = calculateEligibilitySignatureLocked(tda);
    }

    private int calculateEligibilitySignatureLocked(TaskDisplayArea tda) {
        final Task topTask = findTopVisibleLeafTaskLocked(tda);
        int result = mService.mAtmService.getCurrentUserId();
        result = 31 * result + tda.getDisplayId();
        if (topTask == null) {
            return 31 * result - 1;
        }
        result = 31 * result + topTask.mTaskId;
        result = 31 * result + topTask.mUserId;
        result = 31 * result + (isTaskAllowedLocked(topTask) ? 1 : 0);
        result = 31 * result + (areAllVisibleTasksSafeLocked(tda) ? 1 : 0);
        return result;
    }

    private void cancelCallbacksLocked() {
        mService.mH.removeCallbacks(mRemoveAfterHandoff);
        mService.mH.removeCallbacks(mAbortIfKeyguardDidNotShow);
        mService.mH.removeCallbacks(mRetryPrepare);
    }

    private static boolean isUsableUnprotectedBuffer(@Nullable HardwareBuffer buffer) {
        return buffer != null && !buffer.isClosed() && buffer.getWidth() > 1
                && buffer.getHeight() > 1
                && (buffer.getUsage() & HardwareBuffer.USAGE_PROTECTED_CONTENT) == 0;
    }

    private static void closeScreenshotBuffer(
            @Nullable ScreenCaptureInternal.ScreenshotHardwareBuffer screenshot) {
        if (screenshot == null) {
            return;
        }
        final HardwareBuffer buffer = screenshot.getHardwareBuffer();
        if (buffer != null && !buffer.isClosed()) {
            buffer.close();
        }
    }
}
