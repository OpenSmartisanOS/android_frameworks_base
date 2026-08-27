/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.onestep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.ViewRootImpl;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.window.TaskSnapshot;

import androidx.annotation.Keep;

import com.android.internal.sidebar.IOneStepTaskHost;
import com.android.internal.sidebar.ISidebarService;
import com.android.internal.sidebar.OneStepPanelSpec;
import com.android.internal.sidebar.OneStepTaskInfo;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.wm.shell.taskview.TaskView;
import com.android.wm.shell.taskview.TaskViewFactory;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Owns the live TaskViews displayed in the phone OneStep side strip. */
@SysUISingleton
public final class OneStepTaskHost implements CoreStartable {
    private static final String TAG = "OneStepTaskHost";

    interface Delegate {
        void start();

        void requestTaskFromLauncher(int taskId, Rect sourceBounds);

        void dump(PrintWriter pw, String[] args);
    }

    private final Delegate mDelegate;

    @Inject
    public OneStepTaskHost(Context context, Optional<TaskViewFactory> taskViewFactory,
            @Main Executor mainExecutor) {
        mDelegate = createDelegate(context, taskViewFactory, mainExecutor);
    }

    private Delegate createDelegate(Context context, Optional<TaskViewFactory> taskViewFactory,
            Executor mainExecutor) {
        final ClassLoader classLoader = OneStepTaskHost.class.getClassLoader();
        try {
            final Class<?> taskHostClass = Class.forName(
                    "com.android.internal.sidebar.IOneStepTaskHost", false, classLoader);
            final Class<?> sidebarServiceClass = Class.forName(
                    "com.android.internal.sidebar.ISidebarService", false, classLoader);
            final Class<?> taskInfoClass = Class.forName(
                    "com.android.internal.sidebar.OneStepTaskInfo", false, classLoader);
            Class.forName("com.android.internal.sidebar.OneStepPanelSpec", false, classLoader);
            sidebarServiceClass.getMethod("registerOneStepTaskHost", taskHostClass);
            sidebarServiceClass.getMethod("requestAdoptOneStepTask", int.class, int.class,
                    Rect.class, int.class);
            sidebarServiceClass.getMethod("reportOneStepTaskResult", long.class, int.class,
                    int.class, taskInfoClass, String.class);

            final Class<?> delegateClass = Class.forName(
                    "com.android.systemui.onestep.OneStepTaskHostV2", true, classLoader);
            return (Delegate) delegateClass
                    .getDeclaredConstructor(Context.class, Optional.class, Executor.class)
                    .newInstance(context, taskViewFactory, mainExecutor);
        } catch (ReflectiveOperationException | LinkageError e) {
            Log.i(TAG, "OneStep task host protocol unavailable; using legacy sidebar runtime");
            return null;
        }
    }

    @Override
    public void start() {
        if (mDelegate != null) mDelegate.start();
    }

    public void requestTaskFromLauncher(int taskId, Rect sourceBounds) {
        if (mDelegate != null) mDelegate.requestTaskFromLauncher(taskId, sourceBounds);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        if (mDelegate != null) {
            mDelegate.dump(pw, args);
        } else {
            pw.println("OneStepTaskHost: legacy sidebar runtime (task host unavailable)");
        }
    }
}

@Keep
final class OneStepTaskHostV2 implements OneStepTaskHost.Delegate {
    private static final String TAG = "OneStepTaskHost";
    private static final long REUSE_DELAY_MS = 250;
    private static final long TASK_OPERATION_TIMEOUT_MS = 6_000;
    private static final long ORIGINAL_CARD_TRANSITION_MS = 200;
    private static final long ORIGINAL_INSERT_SPRING_MS = 400;
    private static final long ORIGINAL_REMOVE_MS = 200;
    private static final long ORIGINAL_SLIDE_GUARD_MS = 800;
    private static final float ORIGINAL_RECENTS_CORNER_RADIUS_PX = 120f;
    private static final int ORIGINAL_BACKGROUND_TINT = 0xbf000000;
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Optional<TaskViewFactory> mTaskViewFactory;
    private final Executor mMainExecutor;
    private final Handler mMainHandler;
    private final OneStepTopAreaController mTopArea;
    private final Slot[] mSlots = new Slot[OneStepPanelSpec.SLOT_COUNT];
    private final BroadcastReceiver mWallpaperReceiver;
    private Bitmap mWallpaperBitmap;
    private Bitmap mSharedBackgroundBitmap;

    private final IOneStepTaskHost mBinder = new IOneStepTaskHost.Stub() {
        @Override
        public void applyState(OneStepPanelSpec spec, List<OneStepTaskInfo> tasks, long revision) {
            mMainExecutor.execute(() -> applyStateOnMain(spec, tasks, revision));
        }

        @Override
        public void adoptTask(long requestId, int taskId, int slot, int evictedTaskId,
                Rect sourceBounds) {
            final Rect source = sourceBounds != null ? new Rect(sourceBounds) : new Rect();
            mMainExecutor.execute(() -> adoptTaskOnMain(
                    requestId, taskId, slot, evictedTaskId, source));
        }

        @Override
        public void launchTask(long requestId, Intent intent, int userId, int slot,
                int evictedTaskId) {
            mMainExecutor.execute(() -> launchTaskOnMain(requestId, intent, userId, slot,
                    evictedTaskId));
        }

        @Override
        public void activateTask(long requestId, int taskId) {
            mMainExecutor.execute(() -> activateTaskOnMain(requestId, taskId));
        }

        @Override
        public void restoreTask(long requestId, int taskId, boolean toFront) {
            mMainExecutor.execute(() -> restoreTaskOnMain(requestId, taskId, toFront));
        }

        @Override
        public void closeTask(long requestId, int taskId) {
            mMainExecutor.execute(() -> closeTaskOnMain(requestId, taskId));
        }

        @Override
        public void swapTask(long requestId, int promotedTaskId, int replacementTaskId,
                int slot) {
            mMainExecutor.execute(() -> swapTaskOnMain(
                    requestId, promotedTaskId, replacementTaskId, slot));
        }

        @Override
        public void updateOngoing(ComponentName component, int uid, int pid, CharSequence text,
                int state) {
            mMainExecutor.execute(() -> mTopArea.updateOngoing(
                    component, uid, pid, text, state));
        }

        @Override
        public void showGlobalShare(Intent intent) {
            final Intent copy = intent != null ? new Intent(intent) : null;
            mMainExecutor.execute(() -> mTopArea.showGlobalShare(copy));
        }

        @Override
        public void handleSidebarShareList() {
            mMainExecutor.execute(mTopArea::handleSidebarShareList);
        }
    };

    private FrameLayout mRoot;
    private FrameLayout mContentLayer;
    private SideAreaBackgroundView mSideBackground;
    private WindowManager.LayoutParams mLayoutParams;
    private final Rect mSceneBounds = new Rect();
    private OneStepPanelSpec mPanelSpec = OneStepPanelSpec.hidden(0);
    private long mRevision = -1;
    private final ArrayList<OneStepTaskInfo> mAuthoritativeTasks = new ArrayList<>();
    private boolean mRepairInProgress;
    private int mReconcileGeneration;
    private String mLastReconcileResult = "none";
    private String mLastStaleCallback = "none";
    private int mPendingLauncherTaskId = -1;
    private final Rect mPendingLauncherSourceBounds = new Rect();
    private int mLauncherDropGeneration;
    private long mLauncherDropDeadline;
    private Runnable mLauncherDropTimeout;
    private String mLastLauncherDropResult = "none";
    private boolean mAttached;
    private boolean mWindowAdded;
    private boolean mSceneInputEnabled;
    private int mWindowGeneration;
    private boolean mHostRegisteredForCurrentWindows;
    private boolean mHostRegistrationRejected;
    private FrameLayout mSwapOverlay;
    private final ArrayList<Bitmap> mSwapBitmaps = new ArrayList<>();
    private int mSwapVisualGeneration;
    private boolean mSwapVisualRunning;
    private Slot mDeferredSwapSlot;
    private TaskView mDeferredSwapTaskView;
    private long mDeferredSwapRequestId;
    private int mDeferredSwapReplacementTaskId = -1;
    private int mDeferredSwapTargetMode = OneStepPanelSpec.MODE_HIDDEN;
    private int mDeferredSwapTaskViewGeneration = -1;
    private int mDeferredSwapWindowGeneration = -1;
    private String mLastSwapCommit = "none";
    private FrameLayout mReopenOverlay;
    private final ArrayList<Bitmap> mReopenBitmaps = new ArrayList<>();
    private Slot mReopenSlot;
    private int mReopenVisualGeneration;
    private boolean mReopenVisualRunning;
    private int mLastExternalReopenTaskId = -1;
    private String mLastExternalReopenResult = "none";
    private String mLastFlingResult = "none";

    public OneStepTaskHostV2(Context context, Optional<TaskViewFactory> taskViewFactory,
            @Main Executor mainExecutor) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mTaskViewFactory = taskViewFactory;
        mMainExecutor = mainExecutor;
        mMainHandler = new Handler(context.getMainLooper());
        mTopArea = new OneStepTopAreaController(context, this::getSharedSidebarBackground,
                this::onTrustedWindowStateChanged);
        mWallpaperReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                mMainExecutor.execute(() -> {
                    mWallpaperBitmap = null;
                    mSharedBackgroundBitmap = null;
                    updatePanelBackground();
                    mTopArea.refreshBackground();
                    publishSidebarBackground();
                });
            }
        };
        for (int i = 0; i < mSlots.length; i++) mSlots[i] = new Slot(i);
    }

    @Override
    public void start() {
        mContext.registerReceiver(mWallpaperReceiver,
                new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        mTopArea.start();
        ensureWindow();
        registerWithSidebarService();
    }

    public void requestTaskFromLauncher(int taskId, Rect sourceBounds) {
        final Rect copiedBounds = sourceBounds != null ? new Rect(sourceBounds) : new Rect();
        mMainExecutor.execute(() -> beginLauncherDrop(taskId, copiedBounds));
    }

    private void beginLauncherDrop(int taskId, Rect sourceBounds) {
        cancelLauncherDrop("superseded");
        if (!isValidLauncherTask(taskId)) {
            mLastLauncherDropResult = "rejected: task unavailable";
            return;
        }
        final ISidebarService service = getSidebarService();
        if (service == null) {
            mLastLauncherDropResult = "rejected: sidebar service unavailable";
            return;
        }
        try {
            if (service.isInSidebarMode()) {
                service.requestAdoptOneStepTask(taskId, -1, sourceBounds,
                        OneStepTaskInfo.SOURCE_LAUNCHER_RECENTS);
                mLastLauncherDropResult = "adopt requested immediately task=" + taskId;
                return;
            }
            if (!service.canEnterSidebarMode()) {
                mLastLauncherDropResult = "rejected: entry conditions";
                return;
            }
            mPendingLauncherTaskId = taskId;
            mPendingLauncherSourceBounds.set(sourceBounds);
            mLauncherDropDeadline = SystemClock.uptimeMillis() + 2_000;
            final int generation = ++mLauncherDropGeneration;
            mLauncherDropTimeout = () -> {
                if (generation != mLauncherDropGeneration) return;
                cancelLauncherDrop("timed out waiting for visible scene");
            };
            mMainHandler.postDelayed(mLauncherDropTimeout, 2_000);
            mLastLauncherDropResult = "waiting for scene task=" + taskId;
            service.requestEnterLastMode();
        } catch (RemoteException | SecurityException e) {
            cancelLauncherDrop("binder failure");
            Log.w(TAG, "Unable to send Launcher task to OneStep", e);
        }
    }

    private void maybeCompleteLauncherDrop() {
        if (mPendingLauncherTaskId < 0 || !mPanelSpec.visible) return;
        final int taskId = mPendingLauncherTaskId;
        final Rect sourceBounds = new Rect(mPendingLauncherSourceBounds);
        if (SystemClock.uptimeMillis() >= mLauncherDropDeadline || !isValidLauncherTask(taskId)) {
            cancelLauncherDrop("task/scene invalid before adopt");
            return;
        }
        final ISidebarService service = getSidebarService();
        try {
            if (service == null || !service.isInSidebarMode()) {
                cancelLauncherDrop("scene state disagrees with service");
                return;
            }
            // Clear before Binder so a synchronous state callback cannot submit it twice.
            clearLauncherDropState();
            service.requestAdoptOneStepTask(taskId, -1, sourceBounds,
                    OneStepTaskInfo.SOURCE_LAUNCHER_RECENTS);
            mLastLauncherDropResult = "adopt requested after enter task=" + taskId;
        } catch (RemoteException | SecurityException e) {
            cancelLauncherDrop("adopt binder failure");
        }
    }

    private boolean isValidLauncherTask(int taskId) {
        if (taskId < 0) return false;
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        if (display.width() >= display.height()) return false;
        try {
            for (ActivityManager.RunningTaskInfo task
                    : ActivityTaskManager.getInstance().getTasks(
                            100, false, false, android.view.Display.DEFAULT_DISPLAY)) {
                if (task.taskId == taskId && task.userId == ActivityManager.getCurrentUser()
                        && task.displayId == android.view.Display.DEFAULT_DISPLAY) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to validate Launcher OneStep task", e);
        }
        return false;
    }

    private void cancelLauncherDrop(String reason) {
        if (mPendingLauncherTaskId >= 0) mLastLauncherDropResult = "cancelled: " + reason;
        clearLauncherDropState();
    }

    private void clearLauncherDropState() {
        ++mLauncherDropGeneration;
        if (mLauncherDropTimeout != null) {
            mMainHandler.removeCallbacks(mLauncherDropTimeout);
            mLauncherDropTimeout = null;
        }
        mPendingLauncherTaskId = -1;
        mPendingLauncherSourceBounds.setEmpty();
        mLauncherDropDeadline = 0;
    }

    private void registerWithSidebarService() {
        if (mHostRegistrationRejected) return;
        if (!mTopArea.ensureWindow()) {
            mMainHandler.postDelayed(this::registerWithSidebarService, 500);
            return;
        }
        ensureWindow();
        if (!trustedWindowsReady()) {
            mMainHandler.postDelayed(this::registerWithSidebarService, 500);
            return;
        }
        if (mHostRegisteredForCurrentWindows) return;
        final ISidebarService service = getSidebarService();
        if (service == null) {
            mMainHandler.postDelayed(this::registerWithSidebarService, 1000);
            return;
        }
        try {
            service.registerOneStepTaskHost(mBinder);
            mHostRegisteredForCurrentWindows = true;
            publishSidebarBackground();
        } catch (SecurityException e) {
            mHostRegistrationRejected = true;
            Log.e(TAG, "OneStep host registration permanently rejected", e);
        } catch (RemoteException e) {
            mMainHandler.postDelayed(this::registerWithSidebarService, 1000);
        }
    }

    private void onTrustedWindowStateChanged() {
        if (!trustedWindowsReady()) {
            cancelLauncherDrop("trusted OneStep window detached");
            unregisterTaskHostForWindowLoss();
            return;
        }
        if (!mHostRegisteredForCurrentWindows) {
            registerWithSidebarService();
        }
    }

    private void unregisterTaskHostForWindowLoss() {
        if (!mHostRegisteredForCurrentWindows) return;
        mHostRegisteredForCurrentWindows = false;
        final ISidebarService service = getSidebarService();
        try {
            if (service != null) service.registerOneStepTaskHost(null);
        } catch (SecurityException e) {
            mHostRegistrationRejected = true;
            Log.e(TAG, "OneStep host unregister permanently rejected", e);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to unregister detached OneStep host", e);
        }
    }

    private boolean trustedWindowsReady() {
        if (!mTopArea.isSurfaceReady() || !mAttached || mRoot == null
                || !mRoot.isAttachedToWindow()) {
            return false;
        }
        final ViewRootImpl viewRoot = mRoot.getViewRootImpl();
        return viewRoot != null && viewRoot.getSurfaceControl() != null
                && viewRoot.getSurfaceControl().isValid();
    }

    private ISidebarService getSidebarService() {
        return ISidebarService.Stub.asInterface(ServiceManager.getService("sidebar"));
    }

    private void applyStateOnMain(OneStepPanelSpec spec, List<OneStepTaskInfo> tasks,
            long revision) {
        if (spec == null || revision < mRevision) return;
        final boolean wasVisible = mPanelSpec.visible;
        final boolean modeChanged = mPanelSpec.mode != spec.mode;
        final boolean taskStateChanged = revision != mRevision
                || !sameAuthoritativeTaskMapping(tasks, mAuthoritativeTasks);
        mRevision = revision;
        mPanelSpec = spec;
        if (!spec.visible || modeChanged) {
            for (Slot slot : mSlots) slot.cancelGestureState();
        }
        if (!spec.visible) {
            finishContentReopenedAnimation(false /* revealLiveCard */);
            for (Slot slot : mSlots) {
                cancelTransientTaskCorner(slot);
            }
        }
        final int taskCount = tasks != null ? tasks.size() : 0;
        final boolean topReady = mTopArea.ensureWindow();
        if (spec.sideBounds.isEmpty()) {
            setSceneInputEnabled(false);
            mTopArea.applyState(spec, taskCount);
            if (mRoot != null) mRoot.setAlpha(0f);
            setPanelWindowVisible(false);
            return;
        }
        ensureWindow();
        if (mRoot == null || !mAttached || !topReady || !mTopArea.isAttached()) {
            setSceneInputEnabled(false);
            mTopArea.hideImmediately();
            if (mRoot != null) mRoot.setAlpha(0f);
            setPanelWindowVisible(false);
            if (spec.visible) {
                Log.e(TAG, "Refusing partial OneStep scene; trusted windows are not ready");
                final ISidebarService service = getSidebarService();
                try {
                    if (service != null) service.resetWindowForTemp();
                } catch (RemoteException | SecurityException ignored) {
                }
            }
            return;
        }
        mTopArea.applyState(spec, taskCount);
        updateWindowBounds(spec);
        updatePanelBackground();
        if (taskStateChanged) {
            reconcileSlots(tasks != null ? tasks : new ArrayList<>());
        } else {
            rememberAuthoritativeTasks(tasks);
        }
        maybeCommitDeferredSwap(spec);
        if (spec.visible) {
            showPanelForWmAnimation();
            maybeCompleteLauncherDrop();
        } else if (wasVisible) {
            hidePanelAfterWmAnimation();
        } else {
            cancelPanelAnimation();
            setSceneInputEnabled(false);
            mRoot.setAlpha(0f);
            setPanelWindowVisible(false);
        }
        if (mSwapVisualRunning) {
            // The in-scene transition layer owns the redraw while the real TaskViews exchange.
            mContentLayer.setAlpha(0f);
        }
    }

    private static boolean sameAuthoritativeTaskMapping(List<OneStepTaskInfo> incoming,
            List<OneStepTaskInfo> current) {
        final int incomingSize = incoming != null ? incoming.size() : 0;
        if (incomingSize != current.size()) return false;
        if (incoming == null) return true;
        for (OneStepTaskInfo task : incoming) {
            boolean found = false;
            for (OneStepTaskInfo existing : current) {
                if (task.taskId == existing.taskId && task.slot == existing.slot) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private void rememberAuthoritativeTasks(List<OneStepTaskInfo> tasks) {
        mAuthoritativeTasks.clear();
        if (tasks != null) mAuthoritativeTasks.addAll(tasks);
    }

    private void ensureWindow() {
        if (mRoot != null && mAttached && mRoot.isAttachedToWindow()) return;
        // Do not create a second 2051 token while the first addView is waiting for attach.
        if (mRoot != null && mWindowAdded) return;
        if (mRoot != null) removeStaleWindow();
        final int generation = ++mWindowGeneration;
        mHostRegisteredForCurrentWindows = false;

        final FrameLayout root = new FrameLayout(mContext);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackgroundColor(Color.TRANSPARENT);
        // Keep the trusted host and its SurfaceViews attached across ordinary OneStep exits.
        // WMS leaves the 2051 token in the factory off-screen reset pose; the client only removes
        // its input region while hidden.
        root.setVisibility(View.VISIBLE);
        root.setAlpha(mPanelSpec.visible ? 1f : 0f);
        mRoot = root;
        mContentLayer = new FrameLayout(mContext);
        mContentLayer.setClipChildren(false);
        mContentLayer.setClipToPadding(false);
        root.addView(mContentLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSideBackground = new SideAreaBackgroundView(mContext);
        mContentLayer.addView(mSideBackground, new FrameLayout.LayoutParams(1, 1));
        for (Slot slot : mSlots) {
            if (slot.container.getParent() instanceof ViewGroup) {
                ((ViewGroup) slot.container.getParent()).removeView(slot.container);
            }
            mContentLayer.addView(slot.container, new FrameLayout.LayoutParams(1, 1));
            slot.updateVisualState();
        }
        root.getViewTreeObserver().addOnComputeInternalInsetsListener(info -> {
            info.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            final Rect side = mPanelSpec.sideBounds;
            if (!mSceneInputEnabled || side.isEmpty() || mSceneBounds.isEmpty()) {
                info.touchableRegion.setEmpty();
            } else {
                info.touchableRegion.set(side.left - mSceneBounds.left,
                        side.top - mSceneBounds.top, side.right - mSceneBounds.left,
                        side.bottom - mSceneBounds.top);
            }
        });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (mRoot != root || generation != mWindowGeneration) return;
                mAttached = true;
                onTrustedWindowStateChanged();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (mRoot != root || generation != mWindowGeneration) return;
                mAttached = false;
                onTrustedWindowStateChanged();
                if (mPanelSpec.visible) {
                    mMainHandler.postDelayed(() -> {
                        if (mRoot == root && generation == mWindowGeneration
                                && !root.isAttachedToWindow()
                                && mPanelSpec.visible) {
                            removeStaleWindow();
                            ensureWindow();
                            if (mRoot != null) {
                                updateWindowBounds(mPanelSpec);
                                updatePanelBackground();
                            }
                        }
                    }, 100);
                }
            }
        });
        mLayoutParams = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SIDEBAR_TOOLS_SIDE_AREA,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        mLayoutParams.setTitle("OneStepTaskPanel");
        if (!mPanelSpec.visible) {
            mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        mLayoutParams.alpha = mPanelSpec.visible ? 1f : 0f;
        // The TaskView punches a hole in this SystemUI window so app input can reach the
        // embedded activity. Mark the surrounding panel as trusted; otherwise InputDispatcher
        // treats it as an obscuring overlay and drops touches inside the punched-out region.
        mLayoutParams.setTrustedOverlay();
        try {
            mWindowManager.addView(root, mLayoutParams);
            mWindowAdded = true;
            mAttached = root.isAttachedToWindow();
            if (!mAttached) {
                mMainHandler.postDelayed(() -> {
                    if (mRoot != root || generation != mWindowGeneration || mAttached
                            || root.isAttachedToWindow()) return;
                    Log.w(TAG, "Timed out waiting for OneStep task panel attach; rebuilding");
                    removeStaleWindow();
                    ensureWindow();
                }, 500);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to attach OneStep task panel", e);
            if (mRoot == root) removeStaleWindow();
            mAttached = false;
        }
    }

    private void removeStaleWindow() {
        final FrameLayout root = mRoot;
        final boolean windowAdded = mWindowAdded;
        unregisterTaskHostForWindowLoss();
        finishOriginalSwapAnimation(false /* revealLiveStrip */);
        finishContentReopenedAnimation(false /* revealLiveCard */);
        for (Slot slot : mSlots) {
            cancelTransientTaskCorner(slot);
            slot.cancelGestureState();
        }
        mRoot = null;
        mContentLayer = null;
        mSideBackground = null;
        mAttached = false;
        mWindowAdded = false;
        mSceneInputEnabled = false;
        mHostRegisteredForCurrentWindows = false;
        cancelLauncherDrop("task panel window rebuilt");
        ++mWindowGeneration;
        if (root == null || !windowAdded) return;
        try {
            mWindowManager.removeViewImmediate(root);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateWindowBounds(OneStepPanelSpec spec) {
        if (!mAttached || mRoot == null) return;
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        final int sceneTop = !spec.topBounds.isEmpty()
                ? spec.topBounds.bottom : spec.sideBounds.top;
        // The factory 2051 window is a stable full-width scene. Only its sideBounds children draw;
        // changing the WindowState origin during a gesture invalidates both TaskView input and the
        // original WMS counter-transform.
        mSceneBounds.set(display.left, sceneTop, display.right, display.bottom);
        final int width = Math.max(1, mSceneBounds.width());
        final int height = Math.max(1, mSceneBounds.height());
        final boolean windowBoundsChanged = mLayoutParams.width != width
                || mLayoutParams.height != height || mLayoutParams.x != mSceneBounds.left
                || mLayoutParams.y != mSceneBounds.top;
        mLayoutParams.width = width;
        mLayoutParams.height = height;
        mLayoutParams.x = mSceneBounds.left;
        mLayoutParams.y = mSceneBounds.top;
        updateSlotLayout(spec);
        if (!windowBoundsChanged) {
            mRoot.requestLayout();
            return;
        }
        try {
            mWindowManager.updateViewLayout(mRoot, mLayoutParams);
            mRoot.requestLayout();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resize OneStep task panel", e);
            removeStaleWindow();
            if (mPanelSpec.visible) mMainHandler.postDelayed(this::ensureWindow, 100);
        }
    }

    private void updatePanelBackground() {
        if (mSideBackground == null || mPanelSpec.sideBounds.isEmpty()) return;
        final Bitmap wallpaper = getSharedSidebarBackground();
        if (wallpaper == null || wallpaper.isRecycled()) {
            mSideBackground.setBackgroundResource(R.drawable.onestep_sidebar_background);
            return;
        }
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        mSideBackground.setBackground(new WallpaperSliceDrawable(
                wallpaper, mPanelSpec.sideBounds, display));
    }

    private Bitmap getWallpaperBitmap() {
        if (mWallpaperBitmap != null && !mWallpaperBitmap.isRecycled()) return mWallpaperBitmap;
        try {
            final WallpaperManager wallpaperManager =
                    mContext.getSystemService(WallpaperManager.class);
            final Drawable wallpaper = wallpaperManager != null ? wallpaperManager.getDrawable()
                    : null;
            if (wallpaper == null) return null;
            final Rect displayBounds = mWindowManager.getMaximumWindowMetrics().getBounds();
            final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, displayBounds.width()),
                    Math.max(1, displayBounds.height()), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            wallpaper.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            wallpaper.draw(canvas);
            mWallpaperBitmap = bitmap;
            return bitmap;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to render OneStep side wallpaper", e);
            return null;
        }
    }

    /** Publishes the same small blurred/tinted cache used by the original BackgroundManager. */
    private void publishSidebarBackground() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        final Bitmap background = getSharedSidebarBackground();
        if (background == null) return;
        try {
            service.updateOneStepBackground(background);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Unable to publish OneStep wallpaper cache", e);
        }
    }

    private Bitmap getSharedSidebarBackground() {
        if (mSharedBackgroundBitmap != null && !mSharedBackgroundBitmap.isRecycled()) {
            return mSharedBackgroundBitmap;
        }
        final Bitmap wallpaper = getWallpaperBitmap();
        if (wallpaper == null || wallpaper.isRecycled()) return null;
        final int width = Math.max(1, wallpaper.getWidth() / 8);
        final int height = Math.max(1, wallpaper.getHeight() / 8);
        Bitmap scaled = Bitmap.createScaledBitmap(wallpaper, width, height, true);
        if (!scaled.isMutable()) {
            final Bitmap mutable = scaled.copy(Bitmap.Config.ARGB_8888, true);
            if (scaled != wallpaper) scaled.recycle();
            scaled = mutable;
        }
        blurBitmap(scaled, 5, 2);
        new Canvas(scaled).drawColor(ORIGINAL_BACKGROUND_TINT);
        mSharedBackgroundBitmap = scaled;
        return scaled;
    }

    /** Small two-pass box blur; the 1/8-size cache keeps this comfortably below Binder limits. */
    private static void blurBitmap(Bitmap bitmap, int radius, int passes) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (radius <= 0 || width <= 1 || height <= 1) return;
        int[] source = new int[width * height];
        int[] target = new int[source.length];
        bitmap.getPixels(source, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < passes; pass++) {
            for (int y = 0; y < height; y++) {
                final int row = y * width;
                for (int x = 0; x < width; x++) {
                    int a = 0, r = 0, g = 0, b = 0, count = 0;
                    for (int sample = Math.max(0, x - radius);
                            sample <= Math.min(width - 1, x + radius); sample++) {
                        final int color = source[row + sample];
                        a += Color.alpha(color);
                        r += Color.red(color);
                        g += Color.green(color);
                        b += Color.blue(color);
                        count++;
                    }
                    target[row + x] = Color.argb(a / count, r / count, g / count, b / count);
                }
            }
            final int[] horizontal = source;
            source = target;
            target = horizontal;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int a = 0, r = 0, g = 0, b = 0, count = 0;
                    for (int sample = Math.max(0, y - radius);
                            sample <= Math.min(height - 1, y + radius); sample++) {
                        final int color = source[sample * width + x];
                        a += Color.alpha(color);
                        r += Color.red(color);
                        g += Color.green(color);
                        b += Color.blue(color);
                        count++;
                    }
                    target[y * width + x] = Color.argb(
                            a / count, r / count, g / count, b / count);
                }
            }
            final int[] vertical = source;
            source = target;
            target = vertical;
        }
        bitmap.setPixels(source, 0, width, 0, 0, width, height);
    }

    /** Draws the narrow margin and inter-card gap layers from the original SideAreaBgView. */
    private static final class SideAreaBackgroundView extends View {
        private final ArrayList<Rect> mCards = new ArrayList<>();
        private final Paint mLightGapPaint = new Paint();
        private final Paint mDarkGapPaint = new Paint();
        private boolean mLeftMode;

        SideAreaBackgroundView(Context context) {
            super(context);
            mLightGapPaint.setColor(0x1affffff);
            mDarkGapPaint.setColor(0x1a000000);
            setWillNotDraw(false);
        }

        void setGeometry(Rect side, List<Rect> slots) {
            mLeftMode = side.left == 0;
            mCards.clear();
            for (Rect slot : slots) {
                final Rect local = new Rect(slot);
                local.offset(-side.left, -side.top);
                mCards.add(local);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int i = 0; i < mCards.size(); i++) {
                final Rect card = mCards.get(i);
                if (mLeftMode && card.right < getWidth()) {
                    drawGap(canvas, card.right, card.top, getWidth(), card.bottom);
                } else if (!mLeftMode && card.left > 0) {
                    drawGap(canvas, 0, card.top, card.left, card.bottom);
                }
                final int nextTop = i + 1 < mCards.size()
                        ? mCards.get(i + 1).top : getHeight();
                if (card.bottom < nextTop) {
                    drawGap(canvas, 0, card.bottom, getWidth(), nextTop);
                }
            }
        }

        private void drawGap(Canvas canvas, int left, int top, int right, int bottom) {
            canvas.drawRect(left, top, right, bottom, mLightGapPaint);
            canvas.drawRect(left, top, right, bottom, mDarkGapPaint);
        }
    }

    /** Draws the normalized blurred wallpaper slice used by the original BackgroundManager. */
    private static final class WallpaperSliceDrawable extends Drawable {
        private final Bitmap mBitmap;
        private final Rect mSource;
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        WallpaperSliceDrawable(Bitmap bitmap, Rect source, Rect display) {
            mBitmap = bitmap;
            final float scaleX = bitmap.getWidth() / (float) Math.max(1, display.width());
            final float scaleY = bitmap.getHeight() / (float) Math.max(1, display.height());
            mSource = new Rect(
                    Math.round((source.left - display.left) * scaleX),
                    Math.round((source.top - display.top) * scaleY),
                    Math.round((source.right - display.left) * scaleX),
                    Math.round((source.bottom - display.top) * scaleY));
            mSource.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(mBitmap, mSource, getBounds(), mPaint);
            canvas.drawColor(0x1affffff);
            canvas.drawColor(0x1a000000);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Uses the authoritative original-aspect card bounds without deriving them a second time. */
    private void updateSlotLayout(OneStepPanelSpec spec) {
        if (mRoot == null || mContentLayer == null || mSceneBounds.isEmpty()) return;
        final Rect side = spec.sideBounds;
        if (mSideBackground != null) {
            final FrameLayout.LayoutParams background = new FrameLayout.LayoutParams(
                    Math.max(1, side.width()), Math.max(1, side.height()));
            background.leftMargin = side.left - mSceneBounds.left;
            background.topMargin = side.top - mSceneBounds.top;
            mSideBackground.setLayoutParams(background);
            mSideBackground.setGeometry(side, spec.slotBounds);
        }
        for (int i = 0; i < mSlots.length; i++) {
            final Rect slotBounds = spec.getSlotBounds(i);
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.max(1, slotBounds.width()), Math.max(1, slotBounds.height()));
            params.leftMargin = slotBounds.left - mSceneBounds.left;
            params.topMargin = slotBounds.top - mSceneBounds.top;
            mSlots[i].container.setLayoutParams(params);
            mSlots[i].updateVisualState();
            configureTaskPresentation(mSlots[i]);
        }
    }

    private void configureTaskPresentation(Slot slot) {
        if (slot.taskView == null) return;
        final Rect displayBounds = mWindowManager.getMaximumWindowMetrics().getBounds();
        slot.taskView.setTaskBoundsOverride(new Rect(0, 0,
                displayBounds.width(), displayBounds.height()));
        updateTaskTouchRegion(slot);
    }

    private void animateTaskInserted(Slot slot, Rect sourceBounds) {
        final View card = slot.container;
        cancelTransientTaskCorner(slot);
        card.animate().cancel();
        card.setAlpha(1f);
        card.setTranslationX(0f);
        if (sourceBounds != null && !sourceBounds.isEmpty()) {
            final int cornerGeneration = ++slot.cornerAnimationGeneration;
            final TaskView animatedTaskView = slot.taskView;
            if (animatedTaskView != null) {
                animatedTaskView.setTaskCornerRadius(ORIGINAL_RECENTS_CORNER_RADIUS_PX);
            }
            final float originalPxScale = mWindowManager.getMaximumWindowMetrics()
                    .getBounds().width() / 1080f;
            card.setScaleX(.62f);
            card.setScaleY(.62f);
            card.setTranslationY(-110f * originalPxScale);
            card.animate().scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(ORIGINAL_CARD_TRANSITION_MS)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (slot.cornerAnimationGeneration == cornerGeneration
                                && slot.taskView == animatedTaskView
                                && animatedTaskView != null) {
                            animatedTaskView.setTaskCornerRadius(0f);
                        }
                    }).start();
        } else {
            card.setScaleX(.2f);
            card.setScaleY(.2f);
            card.setTranslationY(0f);
            card.animate().scaleX(1f).scaleY(1f)
                    .setDuration(ORIGINAL_INSERT_SPRING_MS)
                    .setInterpolator(new OvershootInterpolator(.72f)).start();
        }
    }

    private void cancelTransientTaskCorner(Slot slot) {
        ++slot.cornerAnimationGeneration;
        if (slot.taskView != null) slot.taskView.setTaskCornerRadius(0f);
    }

    private void updateTaskTouchRegion(Slot slot) {
        if (slot.taskView == null || slot.taskView.getWidth() <= 0
                || slot.taskView.getHeight() <= 0) return;
        final int[] location = new int[2];
        slot.taskView.getLocationInWindow(location);
        slot.taskView.setObscuredTouchRect(new Rect(location[0], location[1],
                location[0] + slot.taskView.getWidth(),
                location[1] + slot.taskView.getHeight()));
    }

    private void showPanelForWmAnimation() {
        cancelPanelAnimation();
        if (mRoot == null) return;
        setPanelWindowVisible(true);
        setSceneInputEnabled(true);
        mRoot.setAlpha(1f);
        mRoot.setTranslationX(0f);
        mRoot.setVisibility(View.VISIBLE);
        if (!mSwapVisualRunning && mContentLayer != null) mContentLayer.setAlpha(1f);
    }

    private void hidePanelAfterWmAnimation() {
        // WMS has committed the last reverse-animation frame and keeps the 2051 token off-screen.
        // Leave the host VISIBLE so its three SurfaceViews remain attached, but hide the window
        // surface itself. View alpha alone does not reliably affect embedded SurfaceView layers.
        setSceneInputEnabled(false);
        if (mRoot != null) mRoot.setAlpha(0f);
        setPanelWindowVisible(false);
    }

    private void setPanelWindowVisible(boolean visible) {
        if (mLayoutParams == null) return;
        final float alpha = visible ? 1f : 0f;
        final int oldFlags = mLayoutParams.flags;
        if (visible) {
            mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        final boolean changed = mLayoutParams.alpha != alpha || oldFlags != mLayoutParams.flags;
        mLayoutParams.alpha = alpha;
        if (!changed || !mAttached || mRoot == null) return;
        try {
            mWindowManager.updateViewLayout(mRoot, mLayoutParams);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to update OneStep task panel visibility", e);
        }
    }

    private void setSceneInputEnabled(boolean enabled) {
        mSceneInputEnabled = enabled;
        for (Slot slot : mSlots) {
            // Factory ActivityStackView cards are display-only. The 2051 gesture overlay owns
            // click/fling input even while the scene is visible.
            if (slot.taskView != null) slot.taskView.setTaskInputEnabled(false);
        }
        if (mRoot != null) mRoot.requestLayout();
    }

    private void cancelPanelAnimation() {
        // WMS owns the only scene animation. There is no independent panel animator to cancel.
    }

    private void reconcileSlots(List<OneStepTaskInfo> tasks) {
        rememberAuthoritativeTasks(tasks);
        runAuthoritativeReconcile("state revision=" + mRevision);
    }

    private void runAuthoritativeReconcile(String reason) {
        if (mRepairInProgress || hasPendingOperation()) {
            mLastReconcileResult = "deferred: " + reason;
            return;
        }
        alignSlotsToState(mAuthoritativeTasks);
        for (int i = 0; i < mSlots.length; i++) {
            final Slot slot = mSlots[i];
            final OneStepTaskInfo expected = authoritativeTaskAt(i);
            final int expectedTaskId = expected != null ? expected.taskId : -1;
            if (slot.taskId >= 0 && slot.taskId != expectedTaskId) {
                beginStaleSlotRepair(slot, expectedTaskId, reason);
                return;
            }
        }
        for (OneStepTaskInfo task : mAuthoritativeTasks) {
            if (task.slot < 0 || task.slot >= mSlots.length) continue;
            Slot current = findSlot(task.taskId);
            if (current == null) {
                final Slot target = mSlots[task.slot];
                if (target.taskId < 0 && target.pendingRequestId == 0) {
                    ensureTaskView(target, () -> {
                        target.rebuilding = true;
                        target.expectedTaskId = task.taskId;
                        target.taskView.adoptTask(task.taskId);
                    });
                    mLastReconcileResult = "rebuilding task=" + task.taskId
                            + " slot=" + task.slot + " reason=" + reason;
                    return;
                }
            }
        }
        mLastReconcileResult = "consistent: " + reason;
    }

    private OneStepTaskInfo authoritativeTaskAt(int slot) {
        for (OneStepTaskInfo task : mAuthoritativeTasks) {
            if (task.slot == slot) return task;
        }
        return null;
    }

    private OneStepTaskInfo authoritativeTaskForId(int taskId) {
        for (OneStepTaskInfo task : mAuthoritativeTasks) {
            if (task.taskId == taskId) return task;
        }
        return null;
    }

    private void beginStaleSlotRepair(Slot slot, int expectedTaskId, String reason) {
        if (slot.taskView == null || slot.taskId < 0) return;
        mRepairInProgress = true;
        slot.repairing = true;
        slot.repairTargetTaskId = expectedTaskId;
        final int staleTaskId = slot.taskId;
        final int generation = ++mReconcileGeneration;
        mLastReconcileResult = "restoring stale task=" + staleTaskId + " slot=" + slot.index
                + " expected=" + expectedTaskId + " reason=" + reason;
        slot.taskView.moveToFullscreen(false /* toFront */);
        mMainHandler.postDelayed(() -> {
            if (generation != mReconcileGeneration || !slot.repairing) return;
            if (isTaskActuallyFullscreen(slot)) {
                finishStaleSlotRepair(slot, staleTaskId);
                return;
            }
            slot.repairing = false;
            slot.repairTargetTaskId = -1;
            mRepairInProgress = false;
            mLastReconcileResult = "repair timeout; preserved embedded task=" + staleTaskId;
        }, TASK_OPERATION_TIMEOUT_MS);
    }

    private void finishStaleSlotRepair(Slot slot, int removedTaskId) {
        final int targetTaskId = slot.repairTargetTaskId;
        ++mReconcileGeneration;
        slot.repairing = false;
        slot.repairTargetTaskId = -1;
        releaseTaskView(slot);
        mRepairInProgress = false;
        mLastReconcileResult = "released stale task=" + removedTaskId;
        if (targetTaskId >= 0) {
            ensureTaskView(slot, () -> {
                slot.rebuilding = true;
                slot.expectedTaskId = targetTaskId;
                slot.taskView.adoptTask(targetTaskId);
            });
        } else {
            mMainHandler.post(() -> runAuthoritativeReconcile("stale slot released"));
        }
    }

    private void adoptTaskOnMain(long requestId, int taskId, int logicalSlot,
            int evictedTaskId, Rect sourceBounds) {
        if (!isHostReady(requestId, taskId)) return;
        final Slot target = evictedTaskId >= 0 ? findSlot(evictedTaskId)
                : firstReusableSlot(logicalSlot);
        if (target == null) {
            report(requestId, taskId, OneStepTaskInfo.RESULT_REJECTED, null,
                    "No free OneStep task slot");
            return;
        }
        reuseSlotIfNeeded(target, requestId, taskId, evictedTaskId,
                () -> ensureTaskView(target, () -> {
            target.expectedTaskId = taskId;
            target.pendingSourceBounds.set(sourceBounds);
            beginPendingOperation(target, requestId, Slot.OP_ADOPT);
            target.taskView.adoptTask(taskId);
        }));
    }

    private void launchTaskOnMain(long requestId, Intent intent, int userId, int logicalSlot,
            int evictedTaskId) {
        if (intent == null || !isHostReady(requestId, -1)) return;
        if (userId != ActivityManager.getCurrentUser()) {
            report(requestId, -1, OneStepTaskInfo.RESULT_REJECTED, null,
                    "OneStep launch user is no longer current");
            return;
        }
        final Slot target = evictedTaskId >= 0 ? findSlot(evictedTaskId)
                : firstReusableSlot(logicalSlot);
        if (target == null) {
            report(requestId, -1, OneStepTaskInfo.RESULT_REJECTED, null,
                    "No free OneStep task slot");
            return;
        }
        reuseSlotIfNeeded(target, requestId, -1, evictedTaskId,
                () -> ensureTaskView(target, () -> {
            target.expectedTaskId = -1;
            target.pendingSourceBounds.setEmpty();
            beginPendingOperation(target, requestId, Slot.OP_LAUNCH);
            final Intent launchIntent = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ActivityOptions creatorOptions = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(mContext,
                    (int) (requestId & 0x7fffffff), launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    creatorOptions.toBundle(),
                    UserHandle.of(userId));
            final ActivityOptions senderOptions = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
            final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
            final Rect logicalBounds = new Rect(0, 0, display.width(), display.height());
            try {
                target.taskView.startActivity(pendingIntent, null, senderOptions,
                        logicalBounds);
                mTopArea.onTaskLaunchDispatched(requestId);
            } catch (RuntimeException e) {
                clearPendingOperation(target);
                mTopArea.onTaskOperationFinished(requestId);
                report(requestId, -1, OneStepTaskInfo.RESULT_REJECTED, null,
                        "Unable to dispatch OneStep launch");
                Log.w(TAG, "Unable to dispatch OneStep task launch", e);
            }
        }));
    }

    private boolean isHostReady(long requestId, int taskId) {
        if (!mTaskViewFactory.isPresent() || !mHostRegisteredForCurrentWindows
                || !trustedWindowsReady() || !mPanelSpec.visible) {
            report(requestId, taskId, OneStepTaskInfo.RESULT_UNAVAILABLE, null,
                    "TaskViewFactory or OneStep panel unavailable");
            return false;
        }
        return true;
    }

    private void reuseSlotIfNeeded(Slot target, long requestId, int requestedTaskId,
            int evictedTaskId, Runnable ready) {
        if (evictedTaskId < 0 || target.taskView == null || target.taskId < 0) {
            ready.run();
            return;
        }
        target.expectedTaskId = requestedTaskId;
        target.rollbackTaskId = evictedTaskId;
        target.evictionReady = ready;
        beginPendingOperation(target, requestId, Slot.OP_EVICT);
        target.taskView.moveToFullscreen(false /* toFront */);
    }

    private boolean hasPendingOperation() {
        for (Slot slot : mSlots) {
            if (slot.pendingRequestId != 0 || slot.rebuilding) return true;
        }
        return false;
    }

    private void alignSlotsToState(List<OneStepTaskInfo> tasks) {
        final Slot[] ordered = new Slot[mSlots.length];
        final boolean[] used = new boolean[mSlots.length];
        for (OneStepTaskInfo task : tasks) {
            if (task.slot < 0 || task.slot >= ordered.length) continue;
            for (int i = 0; i < mSlots.length; i++) {
                if (!used[i] && mSlots[i].taskId == task.taskId) {
                    ordered[task.slot] = mSlots[i];
                    used[i] = true;
                    break;
                }
            }
        }
        int nextUnused = 0;
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] != null) continue;
            while (nextUnused < used.length && used[nextUnused]) nextUnused++;
            if (nextUnused < used.length) {
                ordered[i] = mSlots[nextUnused];
                used[nextUnused++] = true;
            }
        }
        applySlotOrder(ordered);
    }

    private void moveSlotToEnd(Slot recycled) {
        final Slot[] ordered = new Slot[mSlots.length];
        int out = 0;
        for (Slot slot : mSlots) {
            if (slot != recycled) ordered[out++] = slot;
        }
        ordered[out] = recycled;
        applySlotOrder(ordered);
    }

    private void applySlotOrder(Slot[] ordered) {
        boolean changed = false;
        for (int i = 0; i < mSlots.length; i++) changed |= mSlots[i] != ordered[i];
        if (!changed) return;
        final Slot[] previous = mSlots.clone();
        final int[] previousTops = new int[previous.length];
        if (mContentLayer != null) {
            for (Slot slot : mSlots) {
                previousTops[indexOf(previous, slot)] = slot.container.getTop();
            }
        }
        for (int i = 0; i < mSlots.length; i++) {
            mSlots[i] = ordered[i];
            mSlots[i].index = i;
        }
        if (mContentLayer != null) {
            updateSlotLayout(mPanelSpec);
            mContentLayer.post(() -> {
                for (Slot slot : mSlots) {
                    final int oldIndex = indexOf(previous, slot);
                    final float delta = oldIndex >= 0
                            ? previousTops[oldIndex] - slot.container.getTop() : 0f;
                    if (delta != 0f && slot.taskId >= 0) {
                        slot.container.setTranslationY(delta);
                        slot.container.animate().translationY(0f)
                                .setDuration(ORIGINAL_CARD_TRANSITION_MS)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    }
                }
            });
        }
    }

    private static int indexOf(Slot[] slots, Slot target) {
        for (int i = 0; i < slots.length; i++) if (slots[i] == target) return i;
        return -1;
    }

    private void activateTaskOnMain(long requestId, int taskId) {
        final Slot slot = findSlot(taskId);
        if (slot == null || slot.taskView == null) {
            report(requestId, taskId, OneStepTaskInfo.RESULT_NOT_FOUND, null,
                    "OneStep task not found");
            return;
        }
        slot.taskView.bringTaskToFront();
        report(requestId, taskId, OneStepTaskInfo.RESULT_OK, slot.snapshot(), "activated");
    }

    private void restoreTaskOnMain(long requestId, int taskId, boolean toFront) {
        final Slot slot = findSlot(taskId);
        if (slot == null || slot.taskView == null || slot.pendingRequestId != 0) {
            report(requestId, taskId, OneStepTaskInfo.RESULT_NOT_FOUND, null,
                    "OneStep task is unavailable for restore");
            return;
        }
        slot.lockGesture();
        cancelTransientTaskCorner(slot);
        slot.externalReopenPending = toFront;
        beginPendingOperation(slot, requestId, Slot.OP_RESTORE);
        if (toFront) startContentReopenedAnimation(slot);
        slot.taskView.moveToFullscreen(toFront);
    }

    private void closeTaskOnMain(long requestId, int taskId) {
        final Slot slot = findSlot(taskId);
        if (slot == null || slot.taskView == null) {
            report(requestId, taskId, OneStepTaskInfo.RESULT_NOT_FOUND, null,
                    "OneStep task not found");
            return;
        }
        cancelTransientTaskCorner(slot);
        beginPendingOperation(slot, requestId, Slot.OP_CLOSE);
        slot.taskView.removeTask();
    }

    private void swapTaskOnMain(long requestId, int promotedTaskId, int replacementTaskId,
            int logicalSlot) {
        final Slot slot = findSlot(promotedTaskId);
        if (slot == null || slot.taskView == null || slot.pendingRequestId != 0
                || (logicalSlot >= 0 && slot.index != logicalSlot)) {
            if (slot != null) slot.unlockGesture();
            report(requestId, promotedTaskId, OneStepTaskInfo.RESULT_NOT_FOUND, null,
                    "OneStep task cannot be swapped");
            return;
        }
        slot.expectedTaskId = replacementTaskId;
        cancelTransientTaskCorner(slot);
        final Runnable startSwap = () -> {
            if (slot.pendingRequestId != 0 && slot.pendingRequestId != requestId) return;
            beginPendingOperation(slot, requestId, Slot.OP_SWAP);
            startOriginalSwapAnimation(slot, replacementTaskId);
        };
        if (!slot.taskView.isSurfaceReady()) {
            beginPendingOperation(slot, requestId, Slot.OP_SWAP);
            slot.onInitialized = startSwap;
        } else {
            startSwap.run();
        }
    }

    /**
     * Reproduces the R2 four-role visual handoff. The two snapshots are only a redraw bridge: the
     * promoted and replacement tasks remain real tasks, and the actual listener/windowing-mode
     * migration is delayed until the original 300 ms display animation has completed.
     */
    private void startOriginalSwapAnimation(Slot slot, int replacementTaskId) {
        cancelTransientTaskCorner(slot);
        finishOriginalSwapAnimation(false /* revealLiveStrip */);
        if (slot.taskView == null) return;
        final int generation = ++mSwapVisualGeneration;
        mSwapVisualRunning = true;
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        final Rect sideStart = slot.boundsOnScreen();
        final Rect mainStart = mainBoundsForMode(mPanelSpec.mode, display);
        final int targetMode = mPanelSpec.mode == OneStepPanelSpec.MODE_LEFT
                ? OneStepPanelSpec.MODE_RIGHT : OneStepPanelSpec.MODE_LEFT;
        final Rect sideEnd = mirrorHorizontally(sideStart, display);
        final Rect mainEnd = mainBoundsForMode(targetMode, display);

        if (mRoot != null && mContentLayer != null && !mSceneBounds.isEmpty()) {
            mSwapOverlay = new FrameLayout(mContext);
            mSwapOverlay.setClipChildren(false);
            mSwapOverlay.setClipToPadding(false);
            final ImageView oldMain = createSnapshotView(replacementTaskId,
                    slot.lastInfo != null && slot.lastInfo.taskDescription != null
                            ? slot.lastInfo.taskDescription.getBackgroundColor() : Color.BLACK,
                    mSwapBitmaps, false /* requireSnapshot */);
            final ImageView promoted = createSnapshotView(slot.taskId,
                    slot.lastInfo != null && slot.lastInfo.taskDescription != null
                            ? slot.lastInfo.taskDescription.getBackgroundColor() : Color.BLACK,
                    mSwapBitmaps, false /* requireSnapshot */);
            addSnapshotAt(mSwapOverlay, oldMain, mainStart);
            addSnapshotAt(mSwapOverlay, promoted, sideStart);
            mRoot.addView(mSwapOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mSwapOverlay.bringToFront();
            mContentLayer.setAlpha(0f);
            animateSnapshot(oldMain, mainStart, sideEnd);
            animateSnapshot(promoted, sideStart, mainEnd);
        }

        mDeferredSwapSlot = slot;
        mDeferredSwapTaskView = slot.taskView;
        mDeferredSwapRequestId = slot.pendingRequestId;
        mDeferredSwapReplacementTaskId = replacementTaskId;
        mDeferredSwapTargetMode = targetMode;
        mDeferredSwapTaskViewGeneration = slot.taskViewGeneration;
        mDeferredSwapWindowGeneration = mWindowGeneration;
        mLastSwapCommit = "waiting request=" + mDeferredSwapRequestId
                + " targetMode=" + targetMode;

        // The original starts the DisplayArea side-change with the card animation, but does not
        // unbind/promote the task until the 300 ms WMS callback.
        final ISidebarService service = getSidebarService();
        try {
            if (service != null) service.requestEnterSidebarMode(targetMode);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Unable to switch OneStep side during task swap", e);
        }
        // A Shell transition failure must not leave an input-blocking or black bridge behind.
        mMainHandler.postDelayed(() -> {
            if (generation == mSwapVisualGeneration && mSwapVisualRunning) {
                finishOriginalSwapAnimation(true /* revealLiveStrip */);
            }
        }, TASK_OPERATION_TIMEOUT_MS);
    }

    private void maybeCommitDeferredSwap(OneStepPanelSpec spec) {
        final Slot slot = mDeferredSwapSlot;
        if (slot == null || spec == null || !spec.visible
                || spec.mode != mDeferredSwapTargetMode) return;
        final TaskView taskView = mDeferredSwapTaskView;
        if (!mSwapVisualRunning || taskView == null || slot.taskView != taskView
                || slot.pendingOperation != Slot.OP_SWAP
                || slot.pendingRequestId != mDeferredSwapRequestId
                || slot.taskViewGeneration != mDeferredSwapTaskViewGeneration
                || mWindowGeneration != mDeferredSwapWindowGeneration) {
            mLastSwapCommit = "rejected stale final state request=" + mDeferredSwapRequestId;
            finishOriginalSwapAnimation(true /* revealLiveStrip */);
            return;
        }
        final int replacementTaskId = mDeferredSwapReplacementTaskId;
        mLastSwapCommit = "committed request=" + mDeferredSwapRequestId
                + " mode=" + spec.mode;
        clearDeferredSwapState();
        taskView.swapTaskToFullscreen(replacementTaskId);
    }

    private void clearDeferredSwapState() {
        mDeferredSwapSlot = null;
        mDeferredSwapTaskView = null;
        mDeferredSwapRequestId = 0;
        mDeferredSwapReplacementTaskId = -1;
        mDeferredSwapTargetMode = OneStepPanelSpec.MODE_HIDDEN;
        mDeferredSwapTaskViewGeneration = -1;
        mDeferredSwapWindowGeneration = -1;
    }

    private Rect mainBoundsForMode(int mode, Rect display) {
        final int top = mPanelSpec.topBounds.isEmpty()
                ? display.top : mPanelSpec.topBounds.bottom;
        final int sideWidth = mPanelSpec.sideBounds.isEmpty()
                ? Math.round(display.width() * .253f) : mPanelSpec.sideBounds.width();
        return mode == OneStepPanelSpec.MODE_LEFT
                ? new Rect(display.left + sideWidth, top, display.right, display.bottom)
                : new Rect(display.left, top, display.right - sideWidth, display.bottom);
    }

    private static Rect mirrorHorizontally(Rect source, Rect display) {
        final int left = display.left + display.right - source.right;
        return new Rect(left, source.top, left + source.width(), source.bottom);
    }

    private ImageView createSnapshotView(int taskId, int fallbackColor,
            ArrayList<Bitmap> ownedBitmaps, boolean requireSnapshot) {
        final ImageView view = new ImageView(mContext);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setBackgroundColor(fallbackColor != 0 ? fallbackColor : Color.BLACK);
        if (taskId < 0) return requireSnapshot ? null : view;
        try {
            final TaskSnapshot snapshot = ActivityTaskManager.getService()
                    .takeTaskSnapshot(taskId, false /* updateCache */);
            if (snapshot != null && snapshot.getHardwareBuffer() != null) {
                final Bitmap bitmap = Bitmap.wrapHardwareBuffer(snapshot.getHardwareBuffer(),
                        snapshot.getColorSpace());
                if (bitmap != null) {
                    ownedBitmaps.add(bitmap);
                    view.setImageBitmap(bitmap);
                    return view;
                }
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Unable to capture OneStep transition task " + taskId, e);
        }
        return requireSnapshot ? null : view;
    }

    private void addSnapshotAt(FrameLayout overlay, ImageView view, Rect bounds) {
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, bounds.width()), Math.max(1, bounds.height()));
        params.leftMargin = bounds.left - mSceneBounds.left;
        params.topMargin = bounds.top - mSceneBounds.top;
        overlay.addView(view, params);
    }

    private void animateSnapshot(View view, Rect from, Rect to) {
        animateSnapshot(view, from, to, null);
    }

    private void animateSnapshot(View view, Rect from, Rect to, Runnable endAction) {
        view.setPivotX(0f);
        view.setPivotY(0f);
        final AnimatorSet animator = new AnimatorSet();
        animator.playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, to.left - from.left),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, to.top - from.top),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f,
                        to.width() / (float) Math.max(1, from.width())),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f,
                        to.height() / (float) Math.max(1, from.height())));
        animator.setDuration(ORIGINAL_CARD_TRANSITION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        if (endAction != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) endAction.run();
                }
            });
        }
        animator.start();
    }

    /** Factory onContentReopenedByOthers: a straight card-to-main redraw bridge. */
    private void startContentReopenedAnimation(Slot slot) {
        finishContentReopenedAnimation(true /* revealLiveCard */);
        mLastExternalReopenTaskId = slot.taskId;
        if (!mPanelSpec.visible || !mAttached || mRoot == null || mContentLayer == null
                || mSceneBounds.isEmpty() || slot.taskView == null
                || !slot.taskView.isSurfaceReady()) {
            mLastExternalReopenResult = "snapshot skipped: OneStep surface unavailable";
            return;
        }
        final int fallbackColor = slot.lastInfo != null
                && slot.lastInfo.taskDescription != null
                ? slot.lastInfo.taskDescription.getBackgroundColor() : Color.BLACK;
        final ImageView snapshot = createSnapshotView(slot.taskId, fallbackColor,
                mReopenBitmaps, true /* requireSnapshot */);
        if (snapshot == null) {
            mLastExternalReopenResult = "snapshot skipped: capture failed";
            return;
        }
        final Rect from = slot.boundsOnScreen();
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        final Rect to = mainBoundsForMode(mPanelSpec.mode, display);
        if (from.isEmpty() || to.isEmpty()) {
            recycleBitmaps(mReopenBitmaps);
            mLastExternalReopenResult = "snapshot skipped: invalid bounds";
            return;
        }

        final int generation = ++mReopenVisualGeneration;
        mReopenVisualRunning = true;
        mReopenSlot = slot;
        mReopenOverlay = new FrameLayout(mContext);
        mReopenOverlay.setClipChildren(false);
        mReopenOverlay.setClipToPadding(false);
        addSnapshotAt(mReopenOverlay, snapshot, from);
        mRoot.addView(mReopenOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mReopenOverlay.bringToFront();
        slot.container.animate().cancel();
        slot.container.setAlpha(0f);
        mLastExternalReopenResult = "animating generation=" + generation;
        animateSnapshot(snapshot, from, to, () -> {
            if (generation == mReopenVisualGeneration && mReopenVisualRunning) {
                mLastExternalReopenResult =
                        "visual complete; waiting for fullscreen transition";
            }
        });
    }

    private void finishContentReopenedAnimation(boolean revealLiveCard) {
        ++mReopenVisualGeneration;
        mReopenVisualRunning = false;
        final FrameLayout overlay = mReopenOverlay;
        final Slot slot = mReopenSlot;
        mReopenOverlay = null;
        mReopenSlot = null;
        if (overlay != null) {
            overlay.animate().cancel();
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        }
        recycleBitmaps(mReopenBitmaps);
        if (slot != null) cancelTransientTaskCorner(slot);
        if (revealLiveCard && slot != null && slot.container != null) {
            slot.container.animate().cancel();
            slot.container.setAlpha(1f);
            slot.container.setTranslationX(0f);
            slot.container.setTranslationY(0f);
            slot.container.setScaleX(1f);
            slot.container.setScaleY(1f);
        }
    }

    private static void recycleBitmaps(ArrayList<Bitmap> bitmaps) {
        for (int i = 0; i < bitmaps.size(); i++) {
            final Bitmap bitmap = bitmaps.get(i);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        bitmaps.clear();
    }

    private void finishOriginalSwapAnimation(boolean revealLiveStrip) {
        ++mSwapVisualGeneration;
        mSwapVisualRunning = false;
        clearDeferredSwapState();
        final FrameLayout overlay = mSwapOverlay;
        mSwapOverlay = null;
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        recycleBitmaps(mSwapBitmaps);
        for (Slot slot : mSlots) cancelTransientTaskCorner(slot);
        if (revealLiveStrip && mContentLayer != null && mPanelSpec.visible) {
            mRoot.setVisibility(View.VISIBLE);
            mContentLayer.setAlpha(1f);
        }
    }

    private Slot firstReusableSlot(int requestedSlot) {
        if (requestedSlot >= 0 && requestedSlot < mSlots.length
                && mSlots[requestedSlot].taskId < 0) {
            return mSlots[requestedSlot];
        }
        for (Slot slot : mSlots) {
            if (slot.taskId < 0 && slot.pendingRequestId == 0) return slot;
        }
        return null;
    }

    private Slot findSlot(int taskId) {
        if (taskId < 0) return null;
        for (Slot slot : mSlots) if (slot.taskId == taskId) return slot;
        return null;
    }

    private void ensureTaskView(Slot slot, Runnable ready) {
        if (slot.taskView != null) {
            if (slot.taskView.isInitialized() && slot.taskView.isSurfaceReady()) ready.run();
            else slot.onInitialized = ready;
            return;
        }
        slot.onInitialized = ready;
        final int generation = ++slot.taskViewGeneration;
        mTaskViewFactory.get().create(mContext, mMainExecutor, taskView -> {
            if (generation != slot.taskViewGeneration || slot.taskView != null) {
                taskView.release();
                return;
            }
            slot.taskView = taskView;
            taskView.setOneStepTaskView(true);
            taskView.setTaskInputEnabled(false);
            taskView.getController().setHideTaskWithSurface(true);
            configureTaskPresentation(slot);
            taskView.setListener(mMainExecutor, slot.guardedListener(generation, taskView));
            taskView.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                configureTaskPresentation(slot);
            });
            slot.container.addView(taskView, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            slot.gestureLayer.bringToFront();
        });
    }

    private void releaseTaskView(Slot slot) {
        ++slot.taskViewGeneration;
        cancelTransientTaskCorner(slot);
        if (mReopenSlot == slot) {
            finishContentReopenedAnimation(false /* revealLiveCard */);
        }
        clearPendingOperation(slot);
        final TaskView taskView = slot.taskView;
        if (taskView != null) {
            // TaskView.release() unregisters its SurfaceHolder callback before the view is
            // detached. Detaching first invokes surfaceDestroyed() while the controller still
            // owns the task, which queues a stale TO_BACK/hidden transaction after a successful
            // moveToFullscreen transition.
            taskView.release();
            slot.container.removeView(taskView);
        }
        slot.taskView = null;
        slot.taskId = -1;
        slot.expectedTaskId = -1;
        slot.lastInfo = null;
        slot.component = null;
        slot.onInitialized = null;
        slot.evictionReady = null;
        slot.pendingSourceBounds.setEmpty();
        slot.container.animate().cancel();
        slot.container.setAlpha(1f);
        slot.container.setScaleX(1f);
        slot.container.setScaleY(1f);
        slot.container.setTranslationX(0f);
        slot.container.setTranslationY(0f);
        slot.updateGestureState();
        slot.unlockGesture();
        slot.pendingRequestId = 0;
        slot.pendingOperation = Slot.OP_NONE;
        slot.unlockGesture();
        slot.rebuilding = false;
        slot.suppressRemovalCallback = false;
        slot.externalReopenPending = false;
        slot.repairing = false;
        slot.repairTargetTaskId = -1;
        slot.boundTaskViewGeneration = -1;
        slot.rollbackTaskId = -1;
    }

    private void beginPendingOperation(Slot slot, long requestId, int operation) {
        final long now = SystemClock.uptimeMillis();
        final long deadline = slot.pendingRequestId == requestId
                && slot.pendingDeadlineUptime > now
                ? slot.pendingDeadlineUptime : now + TASK_OPERATION_TIMEOUT_MS;
        clearPendingOperation(slot);
        slot.pendingRequestId = requestId;
        slot.pendingOperation = operation;
        slot.pendingDeadlineUptime = deadline;
        slot.updateVisualState();
        slot.pendingTimeout = () -> handlePendingOperationTimeout(slot, requestId);
        mMainHandler.postDelayed(slot.pendingTimeout, Math.max(1, deadline - now));
    }

    private void clearPendingOperation(Slot slot) {
        if (slot.pendingTimeout != null) {
            mMainHandler.removeCallbacks(slot.pendingTimeout);
            slot.pendingTimeout = null;
        }
        slot.pendingRequestId = 0;
        slot.pendingOperation = Slot.OP_NONE;
        slot.pendingDeadlineUptime = 0;
        slot.updateVisualState();
    }

    private void handlePendingOperationTimeout(Slot slot, long requestId) {
        if (slot.pendingRequestId != requestId) return;
        final int operation = slot.pendingOperation;
        final int taskId = operation == Slot.OP_EVICT
                ? slot.expectedTaskId
                : (slot.taskId >= 0 ? slot.taskId : slot.expectedTaskId);
        if (operation == Slot.OP_RESTORE && isTaskActuallyFullscreen(slot)) {
            final boolean externalReopen = slot.externalReopenPending;
            slot.externalReopenPending = false;
            clearPendingOperation(slot);
            cancelTransientTaskCorner(slot);
            finishContentReopenedAnimation(false /* revealLiveCard */);
            slot.unlockGesture();
            if (externalReopen) {
                mLastExternalReopenResult = "completed after callback timeout";
            }
            releaseTaskView(slot);
            report(requestId, taskId, OneStepTaskInfo.RESULT_OK, null,
                    "restored (fullscreen state confirmed)");
            return;
        }
        if (operation == Slot.OP_SWAP) {
            finishOriginalSwapAnimation(true /* revealLiveStrip */);
        } else if (operation == Slot.OP_RESTORE) {
            final boolean externalReopen = slot.externalReopenPending;
            slot.externalReopenPending = false;
            finishContentReopenedAnimation(true /* revealLiveCard */);
            if (externalReopen) {
                mLastExternalReopenResult = "failed: fullscreen transition timed out";
            }
        }
        cancelTransientTaskCorner(slot);
        clearPendingOperation(slot);
        report(requestId, taskId, OneStepTaskInfo.RESULT_UNAVAILABLE, null,
                "Timed out waiting for OneStep task transition");
        if (operation == Slot.OP_EVICT) {
            final int rollbackTaskId = slot.rollbackTaskId;
            slot.evictionReady = null;
            slot.expectedTaskId = -1;
            slot.rollbackTaskId = -1;
            if (isTaskActuallyFullscreen(slot)) {
                releaseTaskView(slot);
                mMainHandler.post(() -> runAuthoritativeReconcile(
                        "eviction timeout rollback task=" + rollbackTaskId));
            } else {
                slot.unlockGesture();
            }
        } else if (operation == Slot.OP_ADOPT || operation == Slot.OP_LAUNCH) {
            final int rollbackTaskId = slot.rollbackTaskId;
            if (slot.taskView != null && slot.taskId >= 0) {
                slot.suppressRemovalCallback = true;
                slot.taskView.moveToFullscreen(false /* toFront */);
                mMainHandler.postDelayed(() -> {
                    releaseTaskView(slot);
                    if (rollbackTaskId >= 0) {
                        runAuthoritativeReconcile("adopt timeout rollback task="
                                + rollbackTaskId);
                    }
                }, REUSE_DELAY_MS);
            } else {
                releaseTaskView(slot);
                if (rollbackTaskId >= 0) {
                    mMainHandler.post(() -> runAuthoritativeReconcile(
                            "adopt timeout rollback task=" + rollbackTaskId));
                }
            }
        } else if (operation == Slot.OP_SWAP) {
            slot.expectedTaskId = -1;
            slot.unlockGesture();
        } else if (operation == Slot.OP_RESTORE || operation == Slot.OP_CLOSE) {
            slot.unlockGesture();
        }
    }

    private static boolean isTaskActuallyFullscreen(Slot slot) {
        return slot != null && slot.lastInfo != null
                && slot.lastInfo.getWindowingMode()
                == android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
    }

    private void report(long requestId, int taskId, int result, OneStepTaskInfo info,
            String message) {
        mTopArea.onTaskOperationFinished(requestId);
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.reportOneStepTaskResult(requestId, taskId, result, info, message);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Unable to report OneStep task result", e);
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("OneStepTaskHost:");
        pw.println("  factory=" + mTaskViewFactory.isPresent() + " attached=" + mAttached
                + " surfaceReady=" + trustedWindowsReady()
                + " hostRegistered=" + mHostRegisteredForCurrentWindows
                + " windowGeneration=" + mWindowGeneration
                + " revision=" + mRevision + " spec=" + mPanelSpec);
        mTopArea.dump(pw);
        pw.println("  sceneBounds=" + mSceneBounds
                + " inputEnabled=" + mSceneInputEnabled
                + " hostVisible=" + (mRoot != null && mRoot.getVisibility() == View.VISIBLE)
                + " hostAlpha=" + (mRoot != null ? mRoot.getAlpha() : -1f));
        pw.println("  externalReopenTask=" + mLastExternalReopenTaskId
                + " visualGeneration=" + mReopenVisualGeneration
                + " running=" + mReopenVisualRunning
                + " result=" + mLastExternalReopenResult);
        pw.println("  reconcileGeneration=" + mReconcileGeneration
                + " running=" + mRepairInProgress + " result=" + mLastReconcileResult);
        pw.println("  lastStaleCallback=" + mLastStaleCallback);
        pw.println("  swapVisualRunning=" + mSwapVisualRunning
                + " deferredRequest=" + mDeferredSwapRequestId
                + " targetMode=" + mDeferredSwapTargetMode
                + " result=" + mLastSwapCommit);
        pw.println("  launcherDropTask=" + mPendingLauncherTaskId
                + " generation=" + mLauncherDropGeneration
                + " deadline=" + mLauncherDropDeadline
                + " result=" + mLastLauncherDropResult);
        pw.println("  lastFling=" + mLastFlingResult);
        for (Slot slot : mSlots) pw.println("  " + slot);
    }

    private final class Slot {
        static final int OP_NONE = 0;
        static final int OP_ADOPT = 1;
        static final int OP_LAUNCH = 2;
        static final int OP_RESTORE = 3;
        static final int OP_CLOSE = 4;
        static final int OP_SWAP = 5;
        static final int OP_EVICT = 6;

        final int bindingId;
        int index;
        final FrameLayout container;
        final View gestureLayer;
        final GestureDetector gestureDetector;
        TaskView taskView;
        int taskViewGeneration;
        int boundTaskViewGeneration = -1;
        int taskId = -1;
        int expectedTaskId = -1;
        long pendingRequestId;
        int pendingOperation;
        boolean rebuilding;
        boolean suppressRemovalCallback;
        Runnable onInitialized;
        Runnable evictionReady;
        Runnable pendingTimeout;
        long pendingDeadlineUptime;
        Runnable gestureUnlock;
        final Rect pendingSourceBounds = new Rect();
        float gestureDownX;
        float gestureDownY;
        int gestureDownMode = OneStepPanelSpec.MODE_HIDDEN;
        int gestureDownTaskId = -1;
        int gestureDownTaskViewGeneration = -1;
        int gestureDownWindowGeneration = -1;
        boolean gestureCanFling;
        boolean gestureLocked;
        boolean slideRemovalInFlight;
        int cornerAnimationGeneration;
        boolean externalReopenPending;
        boolean repairing;
        int repairTargetTaskId = -1;
        int rollbackTaskId = -1;
        ActivityManager.RunningTaskInfo lastInfo;
        ComponentName component;

        final TaskView.Listener listenerCallbacks = new TaskView.Listener() {
            @Override
            public void onInitialized() {
                runSurfaceReadyAction();
            }

            @Override
            public void onSurfaceAlreadyCreated() {
                runSurfaceReadyAction();
            }

            @Override
            public void onTaskCreated(int createdTaskId, android.content.ComponentName name) {
                if (expectedTaskId >= 0 && expectedTaskId != createdTaskId) {
                    mLastStaleCallback = "onTaskCreated expected=" + expectedTaskId
                            + " actual=" + createdTaskId + " slot=" + index;
                    if (taskView != null) taskView.moveToFullscreen(false /* toFront */);
                    if (!rebuilding && pendingRequestId != 0) {
                        final long failedRequest = pendingRequestId;
                        clearPendingOperation(Slot.this);
                        report(failedRequest, createdTaskId, OneStepTaskInfo.RESULT_REJECTED,
                                null, "TaskView returned an unexpected task");
                    }
                    return;
                }
                if (!rebuilding && pendingRequestId == 0) {
                    mLastStaleCallback = "onTaskCreated without operation task=" + createdTaskId
                            + " slot=" + index;
                    if (taskView != null) taskView.moveToFullscreen(false /* toFront */);
                    return;
                }
                final int completedOperation = pendingOperation;
                final boolean completedSwap = pendingOperation == OP_SWAP;
                final Rect sourceBounds = new Rect(pendingSourceBounds);
                pendingSourceBounds.setEmpty();
                taskId = createdTaskId;
                component = name;
                boundTaskViewGeneration = taskViewGeneration;
                rollbackTaskId = -1;
                expectedTaskId = -1;
                updateGestureState();
                unlockGesture();
                if (rebuilding) {
                    rebuilding = false;
                    mMainHandler.post(() -> runAuthoritativeReconcile("rebuild completed"));
                    return;
                }
                final long requestId = pendingRequestId;
                clearPendingOperation(Slot.this);
                if (completedSwap) {
                    finishOriginalSwapAnimation(true /* revealLiveStrip */);
                } else if (completedOperation == OP_ADOPT || completedOperation == OP_LAUNCH) {
                    animateTaskInserted(Slot.this, sourceBounds);
                }
                report(requestId, createdTaskId, OneStepTaskInfo.RESULT_OK, snapshot(), "embedded");
            }

            @Override
            public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
                final int acceptedTaskId = expectedTaskId >= 0 ? expectedTaskId : taskId;
                if (taskInfo == null || acceptedTaskId < 0
                        || taskInfo.taskId != acceptedTaskId
                        || boundTaskViewGeneration != taskViewGeneration) {
                    mLastStaleCallback = "onTaskInfoChanged ignored task="
                            + (taskInfo != null ? taskInfo.taskId : -1)
                            + " accepted=" + acceptedTaskId + " slot=" + index
                            + " generation=" + boundTaskViewGeneration + "/"
                            + taskViewGeneration;
                    return;
                }
                if (taskView != null) {
                    taskView.setOneStepContentRotation(
                            taskInfo.configuration.windowConfiguration.getRotation());
                }
                lastInfo = taskInfo;
                if (taskInfo.topActivity != null) component = taskInfo.topActivity;
                updateGestureState();
            }

            @Override
            public void onTaskRemovalStarted(int removedTaskId) {
                if (repairing) {
                    if (removedTaskId != taskId) {
                        mLastStaleCallback = "repair removal ignored task=" + removedTaskId
                                + " current=" + taskId + " slot=" + index;
                        return;
                    }
                    finishStaleSlotRepair(Slot.this, removedTaskId);
                    return;
                }
                if (taskId >= 0 && removedTaskId != taskId) {
                    mLastStaleCallback = "onTaskRemovalStarted ignored task=" + removedTaskId
                            + " current=" + taskId + " slot=" + index;
                    return;
                }
                if (suppressRemovalCallback) {
                    suppressRemovalCallback = false;
                    return;
                }
                final long requestId = pendingRequestId;
                final int operation = pendingOperation;
                final boolean completedSlideRemoval = operation == OP_RESTORE
                        && slideRemovalInFlight;
                if (operation == OP_EVICT) {
                    final long deadline = pendingDeadlineUptime;
                    final int requestedTaskId = expectedTaskId;
                    final int rollbackTask = removedTaskId;
                    final Runnable ready = evictionReady;
                    evictionReady = null;
                    releaseTaskView(Slot.this);
                    moveSlotToEnd(Slot.this);
                    if (ready == null || SystemClock.uptimeMillis() >= deadline) {
                        report(requestId, requestedTaskId,
                                OneStepTaskInfo.RESULT_UNAVAILABLE, null,
                                "Timed out evicting old OneStep task");
                        return;
                    }
                    pendingRequestId = requestId;
                    pendingOperation = OP_EVICT;
                    pendingDeadlineUptime = deadline;
                    expectedTaskId = requestedTaskId;
                    rollbackTaskId = rollbackTask;
                    pendingTimeout = () -> handlePendingOperationTimeout(
                            Slot.this, requestId);
                    mMainHandler.postDelayed(pendingTimeout,
                            Math.max(1, deadline - SystemClock.uptimeMillis()));
                    ready.run();
                    return;
                }
                if (operation == OP_SWAP && expectedTaskId >= 0) {
                    // The promoted task has left, but the same TaskView is about to receive the
                    // replacement. Keep the request/binding alive until onTaskCreated confirms
                    // the replacement instead of releasing the new task on a delayed callback.
                    taskId = -1;
                    boundTaskViewGeneration = -1;
                    lastInfo = null;
                    component = null;
                    updateGestureState();
                    return;
                }
                if (operation == OP_SWAP) {
                    finishOriginalSwapAnimation(true /* revealLiveStrip */);
                } else if (operation == OP_RESTORE) {
                    final boolean externalReopen = externalReopenPending;
                    externalReopenPending = false;
                    finishContentReopenedAnimation(false /* revealLiveCard */);
                    if (externalReopen) {
                        mLastExternalReopenResult = "completed request=" + requestId;
                    }
                }
                cancelTransientTaskCorner(Slot.this);
                unlockGesture(!completedSlideRemoval /* restoreVisual */);
                // onTaskRemovalStarted is the authoritative point at which the listener migration
                // has completed. Release the empty TaskView before reporting the new store state;
                // otherwise applyState can see the old task for another 250 ms and start a false
                // stale-slot repair.
                releaseTaskView(Slot.this);
                if (operation == OP_RESTORE || operation == OP_CLOSE || operation == OP_SWAP) {
                    report(requestId, removedTaskId, OneStepTaskInfo.RESULT_OK, null,
                            operation == OP_CLOSE ? "closed"
                                    : operation == OP_SWAP ? "swapped" : "restored");
                } else if (requestId != 0) {
                    report(requestId, removedTaskId, OneStepTaskInfo.RESULT_UNAVAILABLE, null,
                            "Task disappeared during OneStep operation");
                } else {
                    report(0, removedTaskId, OneStepTaskInfo.RESULT_TASK_REMOVED, null,
                            "task removed");
                }
            }

            @Override
            public void onTaskAdoptionFailed(int failedTaskId) {
                final long requestId = pendingRequestId;
                final boolean wasRebuilding = rebuilding;
                final int rollbackTask = rollbackTaskId;
                clearPendingOperation(Slot.this);
                rebuilding = false;
                unlockGesture();
                releaseTaskView(Slot.this);
                report(wasRebuilding ? 0 : requestId, failedTaskId,
                        wasRebuilding ? OneStepTaskInfo.RESULT_TASK_REMOVED
                                : OneStepTaskInfo.RESULT_NOT_FOUND,
                        null, "running task unavailable");
                if (rollbackTask >= 0) {
                    mMainHandler.post(() -> runAuthoritativeReconcile(
                            "adopt failure rollback task=" + rollbackTask));
                }
            }

            @Override
            public void onTaskSwapFailed(int promotedTaskId, int replacementTaskId) {
                final long requestId = pendingRequestId;
                clearPendingOperation(Slot.this);
                expectedTaskId = -1;
                taskId = promotedTaskId;
                boundTaskViewGeneration = taskViewGeneration;
                unlockGesture();
                finishOriginalSwapAnimation(true /* revealLiveStrip */);
                configureTaskPresentation(Slot.this);
                updateGestureState();
                report(requestId, promotedTaskId, OneStepTaskInfo.RESULT_REJECTED, null,
                        "Unable to swap OneStep task");
                final ActivityManager.RunningTaskInfo live = taskView != null
                        ? taskView.getTaskInfo() : null;
                if (live == null || live.taskId != promotedTaskId
                        || authoritativeTaskForId(promotedTaskId) == null) {
                    mMainHandler.post(() -> runAuthoritativeReconcile("swap rollback mismatch"));
                }
            }

            @Override
            public void onTaskMoveToFullscreenFailed(int failedTaskId) {
                if (repairing) {
                    ++mReconcileGeneration;
                    repairing = false;
                    repairTargetTaskId = -1;
                    mRepairInProgress = false;
                    mLastReconcileResult = "repair rejected; preserved task=" + failedTaskId;
                    return;
                }
                final long requestId = pendingRequestId;
                final int operation = pendingOperation;
                final int resultTaskId = operation == OP_EVICT
                        ? expectedTaskId : (taskId >= 0 ? taskId : failedTaskId);
                if (operation != OP_RESTORE && operation != OP_EVICT) return;
                if (operation == OP_RESTORE && isTaskActuallyFullscreen(Slot.this)) {
                    final boolean externalReopen = externalReopenPending;
                    externalReopenPending = false;
                    clearPendingOperation(Slot.this);
                    cancelTransientTaskCorner(Slot.this);
                    finishContentReopenedAnimation(false /* revealLiveCard */);
                    unlockGesture();
                    if (externalReopen) {
                        mLastExternalReopenResult =
                                "completed after Shell state reconciliation";
                    }
                    releaseTaskView(Slot.this);
                    report(requestId, resultTaskId, OneStepTaskInfo.RESULT_OK, null,
                            "restored (fullscreen state confirmed)");
                    return;
                }
                clearPendingOperation(Slot.this);
                cancelTransientTaskCorner(Slot.this);
                if (operation == OP_RESTORE) {
                    final boolean externalReopen = externalReopenPending;
                    externalReopenPending = false;
                    finishContentReopenedAnimation(true /* revealLiveCard */);
                    if (externalReopen) {
                        mLastExternalReopenResult = "failed: Shell rolled task back";
                    }
                } else {
                    evictionReady = null;
                    expectedTaskId = -1;
                    rollbackTaskId = -1;
                }
                unlockGesture();
                report(requestId, resultTaskId,
                        OneStepTaskInfo.RESULT_REJECTED, null,
                        "Unable to move OneStep task to fullscreen");
            }
        };

        TaskView.Listener guardedListener(int generation, TaskView source) {
            return new TaskView.Listener() {
                private boolean current(String callback, int callbackTaskId) {
                    if (taskView == source && taskViewGeneration == generation) return true;
                    mLastStaleCallback = callback + " ignored task=" + callbackTaskId
                            + " slot=" + index + " generation=" + generation + "/"
                            + taskViewGeneration;
                    return false;
                }

                @Override
                public void onInitialized() {
                    if (current("onInitialized", -1)) listenerCallbacks.onInitialized();
                }

                @Override
                public void onSurfaceAlreadyCreated() {
                    if (current("onSurfaceAlreadyCreated", -1)) {
                        listenerCallbacks.onSurfaceAlreadyCreated();
                    }
                }

                @Override
                public void onTaskCreated(int createdTaskId, ComponentName name) {
                    if (current("onTaskCreated", createdTaskId)) {
                        listenerCallbacks.onTaskCreated(createdTaskId, name);
                    }
                }

                @Override
                public void onTaskVisibilityChanged(int changedTaskId, boolean visible) {
                    if (current("onTaskVisibilityChanged", changedTaskId)) {
                        listenerCallbacks.onTaskVisibilityChanged(changedTaskId, visible);
                    }
                }

                @Override
                public void onTaskRemovalStarted(int removedTaskId) {
                    if (current("onTaskRemovalStarted", removedTaskId)) {
                        listenerCallbacks.onTaskRemovalStarted(removedTaskId);
                    }
                }

                @Override
                public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
                    final int changedTaskId = taskInfo != null ? taskInfo.taskId : -1;
                    if (current("onTaskInfoChanged", changedTaskId)) {
                        listenerCallbacks.onTaskInfoChanged(taskInfo);
                    }
                }

                @Override
                public void onTaskAdoptionFailed(int failedTaskId) {
                    if (current("onTaskAdoptionFailed", failedTaskId)) {
                        listenerCallbacks.onTaskAdoptionFailed(failedTaskId);
                    }
                }

                @Override
                public void onTaskSwapFailed(int promotedTaskId, int replacementTaskId) {
                    if (current("onTaskSwapFailed", promotedTaskId)) {
                        listenerCallbacks.onTaskSwapFailed(promotedTaskId, replacementTaskId);
                    }
                }

                @Override
                public void onTaskMoveToFullscreenFailed(int failedTaskId) {
                    if (current("onTaskMoveToFullscreenFailed", failedTaskId)) {
                        listenerCallbacks.onTaskMoveToFullscreenFailed(failedTaskId);
                    }
                }

                @Override
                public void onBackPressedOnTaskRoot(int backTaskId) {
                    if (current("onBackPressedOnTaskRoot", backTaskId)) {
                        listenerCallbacks.onBackPressedOnTaskRoot(backTaskId);
                    }
                }
            };
        }

        private void runSurfaceReadyAction() {
            if (onInitialized == null || taskView == null || !taskView.isSurfaceReady()) return;
            final Runnable action = onInitialized;
            onInitialized = null;
            action.run();
        }

        Slot(int index) {
            bindingId = index;
            this.index = index;
            container = new FrameLayout(mContext);
            container.setClipToOutline(false);

            gestureLayer = new View(mContext);
            gestureLayer.setClickable(true);
            gestureLayer.setFocusable(true);
            gestureLayer.setContentDescription("OneStep task card");
            gestureDetector = new GestureDetector(mContext,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent event) {
                            return true;
                        }

                        @Override
                        public boolean onSingleTapUp(MotionEvent event) {
                            if (!validGestureSnapshot()) {
                                mLastFlingResult = "tap rejected: stale gesture slot=" + index;
                                return true;
                            }
                            gestureLayer.performClick();
                            requestSwapOrAdoptFromGesture();
                            return true;
                        }

                        @Override
                        public boolean onFling(MotionEvent down, MotionEvent up,
                                float velocityX, float velocityY) {
                            if (!validGestureSnapshot() || !gestureCanFling
                                    || down == null || up == null) {
                                mLastFlingResult = "rejected: stale/cancelled slot=" + index;
                                return true;
                            }
                            final float dx = up.getRawX() - down.getRawX();
                            if (Math.abs(velocityX) <= Math.abs(velocityY)
                                    || Math.abs(dx) < 30f) {
                                mLastFlingResult = "rejected: not horizontal dx=" + dx
                                        + " vx=" + velocityX + " vy=" + velocityY;
                                return true;
                            }
                            final boolean outward = gestureDownMode
                                    == OneStepPanelSpec.MODE_LEFT ? dx < 0f : dx > 0f;
                            if (!outward || gestureDownTaskId < 0) {
                                mLastFlingResult = "rejected: direction/task dx=" + dx
                                        + " mode=" + gestureDownMode;
                                return true;
                            }
                            gestureCanFling = false;
                            mLastFlingResult = "accepted task=" + gestureDownTaskId
                                    + " slot=" + index + " dx=" + dx;
                            requestRestoreFromGesture();
                            return true;
                        }
                    });
            gestureDetector.setIsLongpressEnabled(false);
            gestureLayer.setOnTouchListener((view, event) -> onCardTouch(event));
            container.addView(gestureLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            updateVisualState();
        }

        boolean onCardTouch(MotionEvent event) {
            if (gestureLocked) return true;
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    gestureDownX = event.getRawX();
                    gestureDownY = event.getRawY();
                    gestureDownMode = mPanelSpec.mode;
                    gestureDownTaskId = taskId;
                    gestureDownTaskViewGeneration = taskViewGeneration;
                    gestureDownWindowGeneration = mWindowGeneration;
                    gestureCanFling = mSceneInputEnabled && mPanelSpec.visible
                            && mPanelSpec.sideBounds.contains(Math.round(gestureDownX),
                                    Math.round(gestureDownY));
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    gestureCanFling = false;
                    mLastFlingResult = "rejected: multi-touch slot=" + index;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isWithinSideFlingCorridor(event)) {
                        gestureCanFling = false;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    gestureCanFling = false;
                    break;
            }
            gestureDetector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                clearGestureSnapshot();
            }
            return true;
        }

        private boolean isWithinSideFlingCorridor(MotionEvent event) {
            final Rect side = mPanelSpec.sideBounds;
            if (side.isEmpty() || event.getRawY() < side.top || event.getRawY() > side.bottom) {
                return false;
            }
            return gestureDownMode == OneStepPanelSpec.MODE_LEFT
                    ? event.getRawX() <= side.right : event.getRawX() >= side.left;
        }

        private boolean validGestureSnapshot() {
            return mSceneInputEnabled && mPanelSpec.visible && !gestureLocked
                    && gestureDownMode == mPanelSpec.mode
                    && gestureDownTaskId == taskId
                    && gestureDownTaskViewGeneration == taskViewGeneration
                    && gestureDownWindowGeneration == mWindowGeneration
                    && pendingRequestId == 0;
        }

        private void clearGestureSnapshot() {
            gestureCanFling = false;
            gestureDownMode = OneStepPanelSpec.MODE_HIDDEN;
            gestureDownTaskId = -1;
            gestureDownTaskViewGeneration = -1;
            gestureDownWindowGeneration = -1;
        }

        void requestSwapOrAdoptFromGesture() {
            final ISidebarService service = getSidebarService();
            if (service == null || pendingRequestId != 0) return;
            lockGesture();
            try {
                if (taskId >= 0) {
                    service.requestSwapOneStepTask(taskId);
                } else {
                    service.requestAdoptCurrentOneStepTask();
                }
            } catch (RemoteException | SecurityException e) {
                unlockGesture();
            }
        }

        void requestRestoreFromGesture() {
            final ISidebarService service = getSidebarService();
            if (service == null || taskId < 0 || pendingRequestId != 0) return;
            lockGesture();
            slideRemovalInFlight = true;
            cancelTransientTaskCorner(this);
            final int restoringTaskId = taskId;
            final int restoringGeneration = taskViewGeneration;
            final int restoringWindowGeneration = mWindowGeneration;
            final int restoringMode = mPanelSpec.mode;
            final float outside = restoringMode == OneStepPanelSpec.MODE_LEFT
                    ? -Math.max(1, container.getWidth()) : Math.max(1, container.getWidth());
            container.animate().cancel();
            container.animate().translationX(outside).alpha(0f)
                    .setDuration(ORIGINAL_REMOVE_MS)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (taskId != restoringTaskId
                                || taskViewGeneration != restoringGeneration
                                || mWindowGeneration != restoringWindowGeneration
                                || mPanelSpec.mode != restoringMode || !mPanelSpec.visible) {
                            mLastFlingResult = "cancelled before restore task="
                                    + restoringTaskId;
                            cancelGestureState();
                            return;
                        }
                        try {
                            service.requestRestoreOneStepTask(restoringTaskId, false /* toFront */);
                        } catch (RemoteException | SecurityException e) {
                            unlockGesture();
                        }
                    }).start();
        }

        void lockGesture() {
            gestureLocked = true;
            if (gestureUnlock != null) mMainHandler.removeCallbacks(gestureUnlock);
            gestureUnlock = this::unlockGesture;
            mMainHandler.postDelayed(gestureUnlock, ORIGINAL_SLIDE_GUARD_MS);
            updateGestureState();
        }

        void unlockGesture() {
            unlockGesture(true /* restoreVisual */);
        }

        void unlockGesture(boolean restoreVisual) {
            if (gestureUnlock != null) {
                mMainHandler.removeCallbacks(gestureUnlock);
                gestureUnlock = null;
            }
            gestureLocked = false;
            final boolean animateRollback = restoreVisual && slideRemovalInFlight
                    && taskId >= 0
                    && (container.getAlpha() != 1f || container.getTranslationX() != 0f);
            slideRemovalInFlight = false;
            clearGestureSnapshot();
            if (restoreVisual && taskId >= 0
                    && (container.getAlpha() != 1f || container.getTranslationX() != 0f)) {
                container.animate().cancel();
                if (animateRollback) {
                    container.animate().alpha(1f).translationX(0f)
                            .setDuration(ORIGINAL_CARD_TRANSITION_MS)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    container.setAlpha(1f);
                    container.setTranslationX(0f);
                }
            }
            updateGestureState();
        }

        void cancelGestureState() {
            if (gestureUnlock != null) {
                mMainHandler.removeCallbacks(gestureUnlock);
                gestureUnlock = null;
            }
            container.animate().cancel();
            gestureLocked = false;
            slideRemovalInFlight = false;
            clearGestureSnapshot();
            container.setAlpha(1f);
            container.setTranslationX(0f);
            updateGestureState();
        }

        void updateGestureState() {
            gestureLayer.setEnabled(!gestureLocked);
            gestureLayer.setAlpha(gestureLocked ? .72f : 1f);
            updateVisualState();
        }

        void updateVisualState() {
            final boolean activeDrop = pendingRequestId != 0
                    && (pendingOperation == OP_ADOPT || pendingOperation == OP_LAUNCH);
            container.setBackgroundResource(activeDrop
                    ? R.drawable.onestep_window_empty_active
                    : R.drawable.onestep_task_view_bg);
        }

        Rect boundsOnScreen() {
            final Rect bounds = new Rect();
            final int[] location = new int[2];
            container.getLocationOnScreen(location);
            bounds.set(location[0], location[1], location[0] + container.getWidth(),
                    location[1] + container.getHeight());
            return bounds;
        }

        OneStepTaskInfo snapshot() {
            final ActivityManager.RunningTaskInfo info = lastInfo;
            final String label = info != null && info.taskDescription != null
                    ? info.taskDescription.getLabel() : null;
            final int color = info != null && info.taskDescription != null
                    ? info.taskDescription.getBackgroundColor() : Color.BLACK;
            return new OneStepTaskInfo(taskId, info != null ? info.userId
                    : ActivityManager.getCurrentUser(),
                    index, info != null && info.topActivity != null ? info.topActivity : component,
                    label, boundsOnScreen(), color,
                    OneStepTaskInfo.STATE_EMBEDDED, mPanelSpec.visible);
        }

        @Override
        public String toString() {
            final Rect logicalBounds = lastInfo != null
                    ? lastInfo.configuration.windowConfiguration.getBounds() : new Rect();
            final Rect localBounds = new Rect(container.getLeft(), container.getTop(),
                    container.getRight(), container.getBottom());
            return "Slot{" + index + " binding=" + bindingId + " taskId=" + taskId
                    + " expected=" + expectedTaskId
                    + " request=" + pendingRequestId + " op=" + pendingOperation
                    + " generation=" + boundTaskViewGeneration + "/" + taskViewGeneration
                    + " logical=" + logicalBounds + " local=" + localBounds + "}";
        }
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }
}
