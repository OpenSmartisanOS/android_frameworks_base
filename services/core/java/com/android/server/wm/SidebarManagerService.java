/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static com.android.internal.inputmethod.SoftInputShowHideReason.HIDE_BUBBLES;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.view.FooDisplayResultInfo;
import android.view.MagnificationSpecSmt;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.sidebar.IIdeaPills;
import com.android.internal.sidebar.ILauncher;
import com.android.internal.sidebar.IOneStepTaskHost;
import com.android.internal.sidebar.IOneStepTaskListener;
import com.android.internal.sidebar.ISidebar;
import com.android.internal.sidebar.ISidebarService;
import com.android.internal.sidebar.OneStepPanelSpec;
import com.android.internal.sidebar.OneStepTaskInfo;
import com.android.internal.util.DumpUtils;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** System-server implementation of Smartisan's OneStep binder service. */
public final class SidebarManagerService extends ISidebarService.Stub {
    private static final String TAG = "SidebarManagerService";
    private static final String SIDEBAR_PACKAGE = "com.smartisanos.sidebar";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";
    private static final String REOPEN_EXCLUDED_PACKAGE = "com.smartisanos.securitycenter";
    private static final ComponentName SIDEBAR_SERVICE_COMPONENT = new ComponentName(
            SIDEBAR_PACKAGE, "com.smartisanos.sidebar.SidebarService");
    private static final String MAGISK_MODULE_PROPERTY = "ro.sos.onestep.magisk";
    private static final String SIDEBAR_SYSTEM_PATH =
            "/system_ext/priv-app/Sidebar/Sidebar.apk";
    private static final String LAUNCHER_SYSTEM_PATH =
            "/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk";
    private static final byte[] SIDEBAR_MODULE_CERT_SHA256 = hexToBytes(
            "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8");
    private static final byte[] LAUNCHER_CERT_SHA256 = hexToBytes(
            "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc");
    private static final long[] SIDEBAR_REBIND_DELAYS_MS = {1_000, 2_000, 4_000, 8_000,
            16_000, 30_000};
    private static final String ACTION_SIDEBAR_BACKGROUND_CHANGED =
            "com.smartisanos.sidebar.action.BACKGROUND_CHANGED";
    private static final String ACTION_TEXT_BOOM_TARGETS =
            "com.smartisanos.sidebar.action.TEXT_BOOM_TARGETS";
    private static final int MAX_TASKS = OneStepPanelSpec.SLOT_COUNT;
    private static final float SIDEBAR_FRACTION = 0.253f;
    private static final float ORIGINAL_DISPLAY_WIDTH = 1080f;
    private static final float ORIGINAL_TASK_WIDTH = 267f;
    private static final float ORIGINAL_TASK_GAP = 6f;
    private static final long TASK_OPERATION_TIMEOUT_MS = 10_000;

    private static final int OP_ADOPT = 1;
    private static final int OP_LAUNCH = 2;
    private static final int OP_ACTIVATE = 3;
    private static final int OP_RESTORE = 4;
    private static final int OP_CLOSE = 5;
    private static final int OP_SWAP = 6;
    private static final int OP_REOPEN_INTENT = 7;

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd-length certificate digest");
        }
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            final int high = Character.digit(hex.charAt(i * 2), 16);
            final int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid certificate digest");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private final Context mContext;
    private final WindowManagerService mWindowManager;
    private final Handler mHandler;
    private final Object mLock = new Object();

    private ISidebar mSidebar;
    private IBinder mSidebarBinder;
    private ServiceConnection mSidebarServiceConnection;
    private boolean mSidebarAppBound;
    private boolean mSidebarAppBinding;
    private int mSidebarBindGeneration;
    private int mSidebarBindAttempt;
    private ILauncher mLauncher;
    private IBinder mLauncherBinder;
    private int mLauncherUid = Process.INVALID_UID;
    private String mLastSecurityRejection;
    private IIdeaPills mIdeaPills;
    private IOneStepTaskHost mTaskHost;
    private IBinder mTaskHostBinder;
    private int mTaskHostUid = Process.INVALID_UID;
    private IOneStepTaskListener mTaskListener;
    private IBinder mTaskListenerBinder;
    private Bitmap mOneStepBackground;
    private final OneStepTaskStore mTaskStore = new OneStepTaskStore();
    private final ArrayDeque<PendingTaskOperation> mTaskOperationQueue = new ArrayDeque<>();
    private final ArraySet<Integer> mExternalReopenTasks = new ArraySet<>();
    private PendingTaskOperation mPendingTaskOperation;
    private long mNextTaskRequestId = 1;
    private long mTaskRevision;
    private int mLastExternalReopenTaskId = -1;
    private String mLastExternalReopenPackage;
    private String mLastExternalReopenResult = "none";
    private boolean mZoomTransitionPending;
    private int mZoomTransitionTarget = MagnificationSpecSmt.TYPE_ZOOM_INVALID;
    private boolean mReenterAfterHostRegistration;
    private volatile boolean mEnabled = true;
    private int mMode = MagnificationSpecSmt.TYPE_ZOOM_INVALID;
    private int mLastMode = MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT;
    private int mLastSystemGestureMode = MagnificationSpecSmt.TYPE_ZOOM_INVALID;
    private int mLastSystemGestureReason;
    private String mLastSystemGestureResult = "none";
    private final LinkedHashMap<String, OngoingItem> mOngoingItems = new LinkedHashMap<>();
    private Intent mGlobalShareIntent;
    private final ContentObserver mSettingsObserver;

    private final IBinder.DeathRecipient mSidebarDeath = this::handleSidebarDeath;
    private final IBinder.DeathRecipient mLauncherDeath;
    private final IBinder.DeathRecipient mTaskHostDeath;
    private final IBinder.DeathRecipient mTaskListenerDeath;

    private static final class PendingTaskOperation {
        final long requestId;
        final int operation;
        final int requestedTaskId;
        final int preferredSlot;
        final Intent intent;
        final int userId;
        final Rect sourceBounds;
        final int source;
        final int replacementTaskId;
        int slot = -1;
        int evictedTaskId = -1;

        PendingTaskOperation(long requestId, int operation, int requestedTaskId,
                int preferredSlot, Intent intent, int userId, Rect sourceBounds, int source,
                int replacementTaskId) {
            this.requestId = requestId;
            this.operation = operation;
            this.requestedTaskId = requestedTaskId;
            this.preferredSlot = preferredSlot;
            this.intent = intent != null ? new Intent(intent) : null;
            this.userId = userId;
            this.sourceBounds = sourceBounds != null ? new Rect(sourceBounds) : new Rect();
            this.source = source;
            this.replacementTaskId = replacementTaskId;
        }
    }

    private static final class OngoingItem {
        final ComponentName component;
        final int uid;
        final int pid;
        final CharSequence text;
        final int state;

        OngoingItem(ComponentName component, int uid, int pid, CharSequence text, int state) {
            this.component = component;
            this.uid = uid;
            this.pid = pid;
            this.text = text != null ? text.toString() : null;
            this.state = state;
        }
    }

    public SidebarManagerService(Context context, WindowManagerService windowManager) {
        mContext = context;
        mWindowManager = windowManager;
        mHandler = new Handler(context.getMainLooper());
        mTaskHostDeath = () -> mHandler.post(this::handleTaskHostDeath);
        mTaskListenerDeath = () -> mHandler.post(this::handleTaskListenerDeath);
        mLauncherDeath = () -> mHandler.post(this::handleLauncherDeath);
        mEnabled = Settings.Global.getInt(
                context.getContentResolver(), "side_bar_mode", 1) == 1;
        final int persistedMode = Settings.Global.getInt(context.getContentResolver(),
                "side_bar_zoom_type", MagnificationSpecSmt.TYPE_ZOOM_INVALID);
        if (persistedMode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                || persistedMode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT) {
            mLastMode = persistedMode;
        }
        Settings.Global.putInt(context.getContentResolver(), "side_bar_zoom_type",
                MagnificationSpecSmt.TYPE_ZOOM_INVALID);
        Settings.Global.putInt(context.getContentResolver(), "sidebar_switch_status", 0);
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                applyEnabled(Settings.Global.getInt(mContext.getContentResolver(),
                        "side_bar_mode", 1) == 1, false);
            }
        };
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("side_bar_mode"), false, mSettingsObserver);
    }

    private void handleSidebarDeath() {
        mHandler.post(() -> {
            synchronized (mLock) {
                mSidebar = null;
                mSidebarBinder = null;
            }
        });
    }

    private void handleLauncherDeath() {
        synchronized (mLock) {
            if (mLauncherBinder != null) {
                mLauncherBinder.unlinkToDeath(mLauncherDeath, 0);
            }
            mLauncher = null;
            mLauncherBinder = null;
            mLauncherUid = Process.INVALID_UID;
        }
    }

    /** Starts the bound-only Sidebar data service once package scanning is available. */
    void systemReady() {
        mHandler.post(this::bindSidebarService);
    }

    /**
     * Factory ActivityStackView calls this condition "content reopened by others". Android's
     * normal launch has already delivered the Intent (or requested the task move) by this point;
     * OneStep only needs to release the exact embedded task back to the main display area.
     */
    void onOneStepTaskReopenedByOthers(int taskId, String packageName) {
        if (taskId < 0 || REOPEN_EXCLUDED_PACKAGE.equals(packageName)) return;
        mHandler.post(() -> handleOneStepTaskReopened(taskId, packageName));
    }

    private void handleOneStepTaskReopened(int taskId, String packageName) {
        int userId = UserHandle.USER_NULL;
        int displayId = Display.INVALID_DISPLAY;
        boolean taskMissing = false;
        String resolvedPackage = packageName;
        synchronized (mWindowManager.mGlobalLock) {
            final Task task = mWindowManager.mRoot.anyTaskForId(taskId);
            if (task == null) {
                taskMissing = true;
            } else {
                userId = task.mUserId;
                displayId = task.getDisplayId();
                if (resolvedPackage == null) {
                    final ActivityRecord top = task.getTopNonFinishingActivity();
                    resolvedPackage = top != null ? top.packageName : null;
                }
            }
        }
        if (taskMissing) {
            recordExternalReopenIgnored(taskId, packageName, "task missing");
            return;
        }
        if (REOPEN_EXCLUDED_PACKAGE.equals(resolvedPackage)) return;
        if (userId != mWindowManager.mAtmService.getCurrentUserId()
                || displayId != Display.DEFAULT_DISPLAY) {
            recordExternalReopenIgnored(taskId, resolvedPackage,
                    "wrong user or display");
            return;
        }

        synchronized (mLock) {
            mLastExternalReopenTaskId = taskId;
            mLastExternalReopenPackage = resolvedPackage;
            if (findTaskLocked(taskId) == null) {
                if (isTaskBeingAddedLocked(taskId, resolvedPackage)) {
                    mExternalReopenTasks.add(taskId);
                    mLastExternalReopenResult = "deferred until embed completes";
                } else {
                    mLastExternalReopenResult = "ignored: task is not in OneStep";
                }
                return;
            }
            if (hasReleaseOperationLocked(taskId)) {
                mLastExternalReopenResult = "coalesced with existing release";
                return;
            }
            if (!mExternalReopenTasks.add(taskId)) {
                mLastExternalReopenResult = "coalesced duplicate";
                return;
            }
            enqueueExternalRestoreLocked(taskId, userId);
        }
    }

    private void recordExternalReopenIgnored(int taskId, String packageName, String reason) {
        synchronized (mLock) {
            mLastExternalReopenTaskId = taskId;
            mLastExternalReopenPackage = packageName;
            mLastExternalReopenResult = "ignored: " + reason;
        }
    }

    private boolean isTaskBeingAddedLocked(int taskId) {
        if (isAddingTaskLocked(mPendingTaskOperation, taskId, null)) return true;
        for (PendingTaskOperation operation : mTaskOperationQueue) {
            if (isAddingTaskLocked(operation, taskId, null)) return true;
        }
        return false;
    }

    private boolean isTaskBeingAddedLocked(int taskId, String packageName) {
        if (isAddingTaskLocked(mPendingTaskOperation, taskId, packageName)) return true;
        for (PendingTaskOperation operation : mTaskOperationQueue) {
            if (isAddingTaskLocked(operation, taskId, packageName)) return true;
        }
        return false;
    }

    private static boolean isAddingTaskLocked(PendingTaskOperation operation, int taskId,
            String packageName) {
        if (operation == null) return false;
        if (operation.operation == OP_ADOPT) {
            return operation.requestedTaskId == taskId;
        }
        if (operation.operation != OP_LAUNCH || packageName == null
                || operation.intent == null) {
            return false;
        }
        final ComponentName component = operation.intent.getComponent();
        return component != null ? packageName.equals(component.getPackageName())
                : packageName.equals(operation.intent.getPackage());
    }

    private boolean hasReleaseOperationLocked(int taskId) {
        if (isReleaseOperationForTask(mPendingTaskOperation, taskId)) return true;
        for (PendingTaskOperation operation : mTaskOperationQueue) {
            if (isReleaseOperationForTask(operation, taskId)) return true;
        }
        return false;
    }

    private static boolean isReleaseOperationForTask(PendingTaskOperation operation, int taskId) {
        if (operation == null || operation.requestedTaskId != taskId) return false;
        return operation.operation == OP_RESTORE || operation.operation == OP_CLOSE
                || operation.operation == OP_SWAP
                || operation.operation == OP_REOPEN_INTENT;
    }

    private void enqueueExternalRestoreLocked(int taskId, int userId) {
        final long requestId = nextTaskRequestIdLocked();
        mLastExternalReopenResult = "queued request=" + requestId;
        enqueueTaskOperationLocked(new PendingTaskOperation(requestId, OP_RESTORE, taskId,
                1 /* toFront */, null, userId, null,
                OneStepTaskInfo.SOURCE_EXTERNAL_REOPEN, -1));
    }

    private void drainDeferredExternalReopens() {
        synchronized (mLock) {
            for (int i = mExternalReopenTasks.size() - 1; i >= 0; i--) {
                final int taskId = mExternalReopenTasks.valueAt(i);
                if (findTaskLocked(taskId) != null) {
                    if (!hasReleaseOperationLocked(taskId)) {
                        enqueueExternalRestoreLocked(taskId,
                                mWindowManager.mAtmService.getCurrentUserId());
                    }
                } else if (!isTaskBeingAddedLocked(taskId)) {
                    mExternalReopenTasks.removeAt(i);
                    mLastExternalReopenResult = "discarded: embed did not complete";
                }
            }
        }
    }

    private void finishExternalReopenLocked(PendingTaskOperation operation, String result) {
        if (operation == null || operation.source != OneStepTaskInfo.SOURCE_EXTERNAL_REOPEN) {
            return;
        }
        mExternalReopenTasks.remove(operation.requestedTaskId);
        mLastExternalReopenTaskId = operation.requestedTaskId;
        mLastExternalReopenResult = result;
    }

    /** Rebinds the data service in the newly active user and invalidates stale callbacks. */
    void onUserSwitched() {
        mHandler.post(() -> {
            final ServiceConnection connection;
            final List<Integer> oldTasks;
            final PendingTaskOperation pending;
            final ArrayList<PendingTaskOperation> queued = new ArrayList<>();
            synchronized (mLock) {
                mHandler.removeCallbacks(mSidebarRebindRunnable);
                connection = mSidebarServiceConnection;
                mSidebarServiceConnection = null;
                mSidebarAppBound = false;
                mSidebarAppBinding = false;
                mSidebarBindAttempt = 0;
                mSidebarBindGeneration++;
                if (mSidebarBinder != null) {
                    mSidebarBinder.unlinkToDeath(mSidebarDeath, 0);
                }
                mSidebar = null;
                mSidebarBinder = null;
                pending = mPendingTaskOperation;
                mPendingTaskOperation = null;
                while (!mTaskOperationQueue.isEmpty()) {
                    queued.add(mTaskOperationQueue.removeFirst());
                }
                oldTasks = mTaskStore.clear();
                if (!oldTasks.isEmpty()) mTaskRevision++;
                mExternalReopenTasks.clear();
                mOngoingItems.clear();
                mGlobalShareIntent = null;
                mReenterAfterHostRegistration = false;
            }
            if (pending != null) {
                mWindowManager.finishOneStepTaskToFullscreenTransition(pending.requestId);
                rollbackEmbeddedMarker(pending);
                notifyTaskListener(pending.requestId, pending.requestedTaskId,
                        OneStepTaskInfo.RESULT_UNAVAILABLE, "OneStep user changed");
            }
            for (PendingTaskOperation operation : queued) {
                notifyTaskListener(operation.requestId, operation.requestedTaskId,
                        OneStepTaskInfo.RESULT_UNAVAILABLE, "OneStep user changed");
            }
            for (int taskId : oldTasks) {
                mWindowManager.setOneStepTaskEmbedded(taskId, false);
            }
            requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0,
                    true /* forceSafetyExit */);
            dispatchTaskState();
            if (connection != null) {
                try {
                    mContext.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                }
            }
            bindSidebarService();
        });
    }

    private void bindSidebarService() {
        final int generation;
        final ServiceConnection connection;
        synchronized (mLock) {
            if (mSidebarAppBound || mSidebarAppBinding) return;
            mSidebarAppBinding = true;
            generation = ++mSidebarBindGeneration;
            connection = new SidebarServiceConnection(generation);
            mSidebarServiceConnection = connection;
        }
        final boolean bound;
        try {
            bound = mContext.bindServiceAsUser(
                    new Intent().setComponent(SIDEBAR_SERVICE_COMPONENT), connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                    UserHandle.of(ActivityManager.getCurrentUser()));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to bind Sidebar data service", e);
            handleSidebarBindingFailure(connection, generation);
            return;
        }
        if (!bound) {
            Slog.w(TAG, "Sidebar data service bind returned false");
            handleSidebarBindingFailure(connection, generation);
        }
    }

    private void handleSidebarBindingFailure(ServiceConnection connection, int generation) {
        synchronized (mLock) {
            if (mSidebarServiceConnection != connection || mSidebarBindGeneration != generation) {
                return;
            }
            mSidebarServiceConnection = null;
            mSidebarAppBound = false;
            mSidebarAppBinding = false;
        }
        scheduleSidebarRebind();
    }

    private void disconnectSidebarService(ServiceConnection connection, int generation) {
        synchronized (mLock) {
            if (mSidebarServiceConnection != connection || mSidebarBindGeneration != generation) {
                return;
            }
            mSidebarServiceConnection = null;
            mSidebarAppBound = false;
            mSidebarAppBinding = false;
            if (mSidebarBinder != null) {
                mSidebarBinder.unlinkToDeath(mSidebarDeath, 0);
            }
            mSidebar = null;
            mSidebarBinder = null;
        }
        try {
            mContext.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        scheduleSidebarRebind();
    }

    private void scheduleSidebarRebind() {
        final long delay;
        synchronized (mLock) {
            final int index = Math.min(mSidebarBindAttempt,
                    SIDEBAR_REBIND_DELAYS_MS.length - 1);
            delay = SIDEBAR_REBIND_DELAYS_MS[index];
            if (mSidebarBindAttempt < SIDEBAR_REBIND_DELAYS_MS.length - 1) {
                mSidebarBindAttempt++;
            }
        }
        mHandler.removeCallbacks(mSidebarRebindRunnable);
        mHandler.postDelayed(mSidebarRebindRunnable, delay);
    }

    private final Runnable mSidebarRebindRunnable = this::bindSidebarService;

    private final class SidebarServiceConnection implements ServiceConnection {
        private final int mGeneration;

        SidebarServiceConnection(int generation) {
            mGeneration = generation;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mSidebarServiceConnection != this
                        || mSidebarBindGeneration != mGeneration) return;
                mSidebarAppBinding = false;
                mSidebarAppBound = true;
                mSidebarBindAttempt = 0;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnectSidebarService(this, mGeneration);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            disconnectSidebarService(this, mGeneration);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            disconnectSidebarService(this, mGeneration);
        }
    }

    private void handleTaskHostDeath() {
        PendingTaskOperation pending;
        final ArrayList<PendingTaskOperation> queued = new ArrayList<>();
        final boolean exitOneStep;
        synchronized (mLock) {
            exitOneStep = mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    || (mZoomTransitionPending
                            && mZoomTransitionTarget != MagnificationSpecSmt.TYPE_ZOOM_INVALID);
            if (exitOneStep) mReenterAfterHostRegistration = true;
            final IBinder oldHostBinder = mTaskHostBinder;
            mTaskHost = null;
            mTaskHostBinder = null;
            mTaskHostUid = Process.INVALID_UID;
            if (oldHostBinder != null) {
                oldHostBinder.unlinkToDeath(mTaskHostDeath, 0);
            }
            pending = mPendingTaskOperation;
            mPendingTaskOperation = null;
            while (!mTaskOperationQueue.isEmpty()) queued.add(mTaskOperationQueue.removeFirst());
            finishExternalReopenLocked(pending, "failed: task host died");
            for (PendingTaskOperation operation : queued) {
                finishExternalReopenLocked(operation, "failed: task host died");
            }
        }
        if (pending != null) {
            mWindowManager.finishOneStepTaskToFullscreenTransition(pending.requestId);
            rollbackEmbeddedMarker(pending);
            notifyTaskListener(pending.requestId, pending.requestedTaskId,
                    OneStepTaskInfo.RESULT_UNAVAILABLE, "SystemUI OneStep host died");
        }
        for (PendingTaskOperation operation : queued) {
            notifyTaskListener(operation.requestId, operation.requestedTaskId,
                    OneStepTaskInfo.RESULT_UNAVAILABLE, "SystemUI OneStep host died");
        }
        if (exitOneStep) {
            requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0,
                    true /* forceSafetyExit */);
        }
    }

    private void handleTaskListenerDeath() {
        synchronized (mLock) {
            mTaskListener = null;
            mTaskListenerBinder = null;
        }
    }

    private boolean isRootOrSystem(int uid) {
        return uid == Process.ROOT_UID || UserHandle.getAppId(uid) == Process.SYSTEM_UID;
    }

    private ApplicationInfo getOwnedApplicationInfo(int uid, String expectedPackage) {
        final PackageManager packageManager = mContext.getPackageManager();
        final String[] packages = packageManager.getPackagesForUid(uid);
        boolean ownsPackage = false;
        if (packages != null) {
            for (String packageName : packages) {
                if (expectedPackage.equals(packageName)) {
                    ownsPackage = true;
                    break;
                }
            }
        }
        if (!ownsPackage) return null;
        try {
            return packageManager.getApplicationInfoAsUser(
                    expectedPackage, 0, UserHandle.getUserId(uid));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private boolean isSystemPrivileged(ApplicationInfo info) {
        return info != null && (info.isSystemApp() || info.isUpdatedSystemApp())
                && info.isPrivilegedApp();
    }

    private boolean isPlatformSigned(int uid) {
        return mContext.getPackageManager().checkSignatures(uid, Process.SYSTEM_UID)
                == PackageManager.SIGNATURE_MATCH;
    }

    private boolean hasSigningCertificate(String packageName, byte[] certificate) {
        return mContext.getPackageManager().hasSigningCertificate(packageName, certificate,
                PackageManager.CERT_INPUT_SHA256);
    }

    /** Verifies the APK currently visible at a late bind-mounted system path. */
    private boolean hasApkSigningCertificate(String sourceDir, String expectedPackage,
            byte[] certificate) {
        final PackageInfo archiveInfo = mContext.getPackageManager().getPackageArchiveInfo(
                sourceDir, PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES));
        if (archiveInfo == null || !expectedPackage.equals(archiveInfo.packageName)) return false;
        final SigningInfo signingInfo = archiveInfo.signingInfo;
        if (signingInfo == null) return false;
        final Signature[] signers = signingInfo.getApkContentsSigners();
        if (signers == null) return false;
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signer : signers) {
                if (MessageDigest.isEqual(digest.digest(signer.toByteArray()), certificate)) {
                    return true;
                }
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
        return false;
    }

    private boolean isTrustedPlatformPackage(int uid, String packageName) {
        return isSystemPrivileged(getOwnedApplicationInfo(uid, packageName))
                && isPlatformSigned(uid);
    }

    private boolean isTrustedSystemUi(int uid) {
        return isTrustedPlatformPackage(uid, SYSTEM_UI_PACKAGE);
    }

    private boolean isTrustedSidebar(int uid) {
        final ApplicationInfo info = getOwnedApplicationInfo(uid, SIDEBAR_PACKAGE);
        if (!isSystemPrivileged(info)) return false;
        if (isPlatformSigned(uid)) return true;
        return SystemProperties.getBoolean(MAGISK_MODULE_PROPERTY, false)
                && !info.isUpdatedSystemApp()
                && SIDEBAR_SYSTEM_PATH.equals(info.sourceDir)
                && hasSigningCertificate(SIDEBAR_PACKAGE, SIDEBAR_MODULE_CERT_SHA256);
    }

    private boolean isTrustedLauncher(int uid) {
        final ApplicationInfo info = getOwnedApplicationInfo(uid, LAUNCHER_PACKAGE);
        return isSystemPrivileged(info)
                && !info.isUpdatedSystemApp()
                && LAUNCHER_SYSTEM_PATH.equals(info.sourceDir)
                && hasApkSigningCertificate(
                        info.sourceDir, LAUNCHER_PACKAGE, LAUNCHER_CERT_SHA256);
    }

    private void rejectCaller(String role, int uid) {
        final String rejection = role + " rejected uid=" + uid;
        synchronized (mLock) {
            mLastSecurityRejection = rejection;
        }
        Slog.w(TAG, rejection);
        throw new SecurityException("UID " + uid + " is not allowed to control OneStep as "
                + role);
    }

    void enforceSidebarPermission() {
        final int uid = Binder.getCallingUid();
        if (isRootOrSystem(uid) || isTrustedSidebar(uid) || isTrustedSystemUi(uid)
                || isTrustedPlatformPackage(uid, SETTINGS_PACKAGE)) return;
        rejectCaller("controller", uid);
    }

    private void enforceSidebarCaller() {
        final int uid = Binder.getCallingUid();
        if (isRootOrSystem(uid) || isTrustedSidebar(uid)) return;
        rejectCaller("Sidebar", uid);
    }

    private void enforceSystemUiCaller() {
        final int uid = Binder.getCallingUid();
        if (isRootOrSystem(uid) || isTrustedSystemUi(uid)) return;
        rejectCaller("SystemUI", uid);
    }

    private void enforceLauncherCaller() {
        final int uid = Binder.getCallingUid();
        if (isRootOrSystem(uid) || isTrustedLauncher(uid)) return;
        rejectCaller("Launcher", uid);
    }

    @Override
    public void registerSidebar(ISidebar sidebar) {
        enforceSidebarCaller();
        mHandler.post(() -> {
            final ILauncher launcher;
            synchronized (mLock) {
                if (mSidebarBinder != null) {
                    mSidebarBinder.unlinkToDeath(mSidebarDeath, 0);
                }
                mSidebar = sidebar;
                mSidebarBinder = sidebar != null ? sidebar.asBinder() : null;
                if (mSidebarBinder != null) {
                    try {
                        mSidebarBinder.linkToDeath(mSidebarDeath, 0);
                    } catch (RemoteException e) {
                        mSidebar = null;
                        mSidebarBinder = null;
                    }
                }
                launcher = mLauncher;
            }
            final ISidebar registeredSidebar = getSidebar();
            if (registeredSidebar != null) {
                try {
                    registeredSidebar.setEnabled(mEnabled);
                    final int replayMode;
                    synchronized (mLock) {
                        replayMode = mMode;
                    }
                    if (replayMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID) {
                        registeredSidebar.onEnterSidebarMode(replayMode, 0);
                    }
                    if (launcher != null) {
                        registeredSidebar.noticeSidebarIconFloat(
                                launcher, buildLauncherStateBundle());
                    }
                } catch (RemoteException ignored) {
                }
            }
        });
    }

    @Override
    public void resetWindow() {
        enforceSidebarPermission();
        requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0);
    }

    @Override
    public boolean isInSidebarMode() {
        return isInSidebarModeInternal();
    }

    private boolean isInSidebarModeInternal() {
        synchronized (mLock) {
            return mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID;
        }
    }

    int getModeForSystemGesture() {
        synchronized (mLock) {
            return mMode;
        }
    }

    void requestZoomFromSystemGesture(int mode, int reason) {
        final boolean valid = mode == MagnificationSpecSmt.TYPE_ZOOM_INVALID
                || mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                || mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT;
        // canEnterSidebarMode() takes the WM global lock; never call it while holding mLock.
        final boolean entryAllowed = mode == MagnificationSpecSmt.TYPE_ZOOM_INVALID
                || (valid && canEnterSidebarMode());
        synchronized (mLock) {
            mLastSystemGestureMode = mode;
            mLastSystemGestureReason = reason;
            final boolean entryGesture = reason == OneStepGestureDetector.REASON_CORNER_ENTER
                    || reason == OneStepGestureDetector.REASON_THUMB_ENTER;
            final boolean exitGesture = reason == OneStepGestureDetector.REASON_CORNER_EXIT
                    || reason == OneStepGestureDetector.REASON_THUMB_EXIT;
            if (!valid) {
                mLastSystemGestureResult = "rejected: invalid mode";
            } else if (entryGesture && mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID) {
                mLastSystemGestureResult = "rejected: stale entry gesture";
                return;
            } else if (exitGesture && mMode == MagnificationSpecSmt.TYPE_ZOOM_INVALID) {
                mLastSystemGestureResult = "rejected: stale exit gesture";
                return;
            } else if (mZoomTransitionPending) {
                mLastSystemGestureResult = "rejected: transition pending";
                return;
            } else if (!entryAllowed) {
                mLastSystemGestureResult = "rejected: entry conditions";
                return;
            } else {
                mLastSystemGestureResult = "queued";
            }
        }
        if (!valid) return;
        requestZoomInternal(mode, reason);
    }

    @Override
    public boolean canEnterSidebarMode() {
        if (!mEnabled || Settings.Global.getInt(
                mContext.getContentResolver(), "side_bar_mode", 1) != 1) {
            return false;
        }
        if (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
            return false;
        }
        final KeyguardManager keyguard = mContext.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        synchronized (mWindowManager.mGlobalLock) {
            final DisplayContent display = mWindowManager.mRoot.getDisplayContent(
                    Display.DEFAULT_DISPLAY);
            if (display == null) return false;
            final Rect bounds = display.getBounds();
            return !bounds.isEmpty() && bounds.height() > bounds.width()
                    && display.getConfiguration().orientation
                            != Configuration.ORIENTATION_LANDSCAPE;
        }
    }

    void onDefaultDisplayConfigurationChanged(int orientation, Rect bounds) {
        final boolean portrait = orientation != Configuration.ORIENTATION_LANDSCAPE
                && bounds != null && !bounds.isEmpty() && bounds.height() > bounds.width();
        if (portrait) return;
        mHandler.post(() -> {
            final boolean mustExit;
            synchronized (mLock) {
                mustExit = mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID
                        || (mZoomTransitionPending
                                && mZoomTransitionTarget
                                        != MagnificationSpecSmt.TYPE_ZOOM_INVALID);
            }
            if (mustExit) {
                requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0,
                        true /* forceSafetyExit */);
            }
        });
    }

    @Override
    public int getSidebarModeState() {
        synchronized (mLock) {
            return mMode;
        }
    }

    @Override
    public boolean isFocusedOnSidebar() {
        synchronized (mWindowManager.mGlobalLock) {
            final DisplayContent display = mWindowManager.mRoot.getTopFocusedDisplayContent();
            if (display == null || display.mCurrentFocus == null) return false;
            if ("com.smartisanos.sidebar".equals(display.mCurrentFocus.mAttrs.packageName)) {
                return true;
            }
            final Task task = display.mCurrentFocus.getTask();
            return task != null && mWindowManager.isOneStepTaskEmbedded(task.mTaskId);
        }
    }

    @Override
    public void resetWindowForTemp() {
        enforceSidebarPermission();
        requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0);
    }

    @Override
    public void requestEnterLastMode() {
        enforceSidebarPermission();
        final int mode;
        synchronized (mLock) {
            if (mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID || mZoomTransitionPending) {
                return;
            }
            mode = mLastMode;
        }
        requestZoomInternal(mode, 0);
    }

    @Override
    public void resumeSidebar() {
        enforceSidebarPermission();
        final ISidebar sidebar = getSidebar();
        if (sidebar != null) {
            try {
                sidebar.resumeSidebar();
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void updateOngoing(ComponentName component, int uid, int pid, CharSequence text,
            int state) {
        enforceSidebarPermission();
        if (component == null) return;
        final String key = component.flattenToShortString() + ':' + uid + ':' + pid;
        final IOneStepTaskHost host;
        synchronized (mLock) {
            if (state < 0) {
                mOngoingItems.remove(key);
            } else {
                mOngoingItems.put(key, new OngoingItem(component, uid, pid, text, state));
            }
            host = mTaskHost;
        }
        if (host != null) {
            try {
                host.updateOngoing(component, uid, pid, text, state);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void requestExitSidebarMode() {
        enforceSidebarPermission();
        requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0);
    }

    @Override
    public void requestEnterSidebarMode(int mode) {
        enforceSidebarPermission();
        if (mode != MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                && mode != MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT) {
            mode = MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT;
        }
        requestZoomInternal(mode, 0);
    }

    @Override
    public void setEnabled(boolean enabled) {
        enforceSidebarPermission();
        applyEnabled(enabled, true);
    }

    private void applyEnabled(boolean enabled, boolean writeSetting) {
        final boolean changed = mEnabled != enabled;
        mEnabled = enabled;
        if (writeSetting) {
            Settings.Global.putInt(
                    mContext.getContentResolver(), "side_bar_mode", enabled ? 1 : 0);
        }
        if (!changed && !writeSetting) return;
        final ISidebar sidebar = getSidebar();
        if (sidebar != null) {
            try {
                sidebar.setEnabled(enabled);
            } catch (RemoteException ignored) {
            }
        }
        if (!enabled) requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_INVALID, 0);
    }

    @Override
    public Bundle noticeSidebarIconFloat(ILauncher launcher, Bundle args) {
        enforceLauncherCaller();
        final int callingUid = Binder.getCallingUid();
        synchronized (mLock) {
            if (mLauncherBinder != null) {
                mLauncherBinder.unlinkToDeath(mLauncherDeath, 0);
            }
            mLauncher = launcher;
            mLauncherBinder = launcher != null ? launcher.asBinder() : null;
            mLauncherUid = launcher != null ? callingUid : Process.INVALID_UID;
            if (mLauncherBinder != null) {
                try {
                    mLauncherBinder.linkToDeath(mLauncherDeath, 0);
                } catch (RemoteException e) {
                    mLauncher = null;
                    mLauncherBinder = null;
                    mLauncherUid = Process.INVALID_UID;
                }
            }
        }
        final Bundle authoritativeState = buildLauncherStateBundle();
        final ISidebar sidebar = getSidebar();
        if (sidebar != null && launcher != null) {
            try {
                final Bundle sidebarState = sidebar.noticeSidebarIconFloat(
                        launcher, args != null ? new Bundle(args) : new Bundle());
                if (sidebarState != null) authoritativeState.putAll(sidebarState);
            } catch (RemoteException ignored) {
                // The callback remains cached and is replayed when Sidebar reconnects.
            }
        }
        return authoritativeState;
    }

    private Bundle buildLauncherStateBundle() {
        final boolean enabled;
        final boolean leftMode;
        synchronized (mLock) {
            enabled = mEnabled;
            leftMode = mMode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT;
        }
        final Bundle result = new Bundle();
        result.putBoolean("IS_SIDEBAR_ENABLE", enabled);
        result.putBoolean("IS_LEFT_MODE", leftMode);
        result.putInt("SIDE_VIEW_WIDTH", Math.round(
                mContext.getResources().getDisplayMetrics().widthPixels * SIDEBAR_FRACTION));
        return result;
    }

    @Override
    public void contentWindowOnTouch(int action, float[] points) {
        enforceSidebarPermission();
        final ISidebar sidebar = getSidebar();
        if (sidebar != null) {
            try {
                sidebar.contentWindowOnTouch(action, points);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void fooDisplay(FooDisplayResultInfo result) {
        enforceSidebarPermission();
        final ISidebar sidebar = getSidebar();
        if (sidebar != null) {
            try {
                sidebar.fooDisplay(result);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void onSidebarBackgroundChanged() {
        enforceSidebarPermission();
        synchronized (mLock) {
            mOneStepBackground = null;
        }
    }

    @Override
    public Bitmap getSidebarBackground() {
        synchronized (mLock) {
            if (mOneStepBackground != null && !mOneStepBackground.isRecycled()) {
                return mOneStepBackground;
            }
        }
        return null;
    }

    @Override
    public void updateOneStepBackground(Bitmap background) {
        enforceSystemUiCaller();
        synchronized (mLock) {
            mOneStepBackground = background;
        }
        final ISidebar sidebar = getSidebar();
        if (sidebar != null) {
            try {
                sidebar.onOneStepBackgroundChanged();
            } catch (RemoteException ignored) {
                // The package-targeted broadcast below remains the reconnect fallback.
            }
        }
        final Intent changed = new Intent(ACTION_SIDEBAR_BACKGROUND_CHANGED)
                .setPackage(SIDEBAR_PACKAGE);
        mContext.sendBroadcastAsUser(changed, UserHandle.SYSTEM);
    }

    @Override
    public void showGlobalShare(Intent intent) {
        enforceSidebarPermission();
        if (intent == null) return;
        final IOneStepTaskHost host;
        final boolean enter;
        synchronized (mLock) {
            mGlobalShareIntent = new Intent(intent);
            host = mTaskHost;
            // Text Boom preserves the factory 200 ms right-side entry sequencing in its
            // provider. Generic top-share and real global-share requests enter the last mode.
            enter = !ACTION_TEXT_BOOM_TARGETS.equals(intent.getAction())
                    && mMode == MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    && !mZoomTransitionPending;
        }
        if (host != null) {
            try {
                host.showGlobalShare(new Intent(intent));
            } catch (RemoteException ignored) {
            }
        }
        if (enter) {
            final int mode;
            synchronized (mLock) {
                mode = mLastMode;
            }
            requestZoomInternal(mode, 0);
        }
    }

    @Override
    public void registerIdeaPills(IIdeaPills ideaPills) {
        enforceSidebarPermission();
        synchronized (mLock) {
            mIdeaPills = ideaPills;
        }
    }

    @Override
    public Bundle callIdeaPills(String method, Bundle extras) {
        enforceSidebarPermission();
        final IIdeaPills ideaPills;
        synchronized (mLock) {
            ideaPills = mIdeaPills;
        }
        if (ideaPills == null) return null;
        try {
            return ideaPills.callIdeaPills(method, extras);
        } catch (RemoteException ignored) {
            return null;
        }
    }

    @Override
    public void handleSidebarShareList() {
        enforceSidebarPermission();
        final IOneStepTaskHost host;
        synchronized (mLock) {
            mGlobalShareIntent = null;
            host = mTaskHost;
        }
        if (host != null) {
            try {
                host.handleSidebarShareList();
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void dismissFooResultDisplay() {
        enforceSidebarPermission();
        final ISidebar sidebar = getSidebar();
        if (sidebar != null) {
            try {
                sidebar.dismissFooResultDisplay();
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void requestEnterSidebarModeWithFrom(int from) {
        enforceSidebarPermission();
        requestZoomInternal(MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT, from);
    }

    @Override
    public void requestDockWindow(int mode, boolean animate) {
        enforceSidebarPermission();
        // Dock/TNT is a separate desktop feature. Keep the ABI and route supported phone modes.
        if (mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                || mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT) {
            requestZoomInternal(mode, 0);
        }
    }

    @Override
    public void registerOneStepTaskHost(IOneStepTaskHost host) {
        enforceSystemUiCaller();
        if (host == null) {
            handleTaskHostDeath();
            return;
        }
        refreshCommittedTasksFromAtms();
        final int callingUid = Binder.getCallingUid();
        final OneStepPanelSpec spec;
        final List<OneStepTaskInfo> tasks;
        final long revision;
        final ArrayList<OngoingItem> ongoing;
        final Intent globalShare;
        final boolean reenter;
        synchronized (mLock) {
            if (mTaskHostBinder != null) {
                mTaskHostBinder.unlinkToDeath(mTaskHostDeath, 0);
            }
            mTaskHost = host;
            mTaskHostBinder = host != null ? host.asBinder() : null;
            mTaskHostUid = host != null ? callingUid : Process.INVALID_UID;
            if (mTaskHostBinder != null) {
                try {
                    mTaskHostBinder.linkToDeath(mTaskHostDeath, 0);
                } catch (RemoteException e) {
                    mTaskHost = null;
                    mTaskHostBinder = null;
                    mTaskHostUid = Process.INVALID_UID;
                }
            }
            spec = buildPanelSpecLocked();
            tasks = copyTasksLocked();
            revision = mTaskRevision;
            ongoing = new ArrayList<>(mOngoingItems.values());
            globalShare = mGlobalShareIntent != null ? new Intent(mGlobalShareIntent) : null;
            reenter = mReenterAfterHostRegistration && mTaskHost != null
                    && mMode == MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    && !mZoomTransitionPending;
        }
        final IOneStepTaskHost registeredHost = getTaskHost();
        if (registeredHost != null) {
            try {
                registeredHost.applyState(spec, tasks, revision);
                for (OngoingItem item : ongoing) {
                    registeredHost.updateOngoing(item.component, item.uid, item.pid,
                            item.text, item.state);
                }
                if (globalShare != null) {
                    registeredHost.showGlobalShare(globalShare);
                } else {
                    registeredHost.handleSidebarShareList();
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to initialize OneStep task host", e);
            }
        }
        if (reenter) mHandler.post(this::attemptHostReentry);
    }

    @Override
    public void registerOneStepTaskListener(IOneStepTaskListener listener) {
        enforceSidebarPermission();
        refreshCommittedTasksFromAtms();
        final OneStepPanelSpec spec;
        final List<OneStepTaskInfo> tasks;
        final long revision;
        synchronized (mLock) {
            if (mTaskListenerBinder != null) {
                mTaskListenerBinder.unlinkToDeath(mTaskListenerDeath, 0);
            }
            mTaskListener = listener;
            mTaskListenerBinder = listener != null ? listener.asBinder() : null;
            if (mTaskListenerBinder != null) {
                try {
                    mTaskListenerBinder.linkToDeath(mTaskListenerDeath, 0);
                } catch (RemoteException e) {
                    mTaskListener = null;
                    mTaskListenerBinder = null;
                }
            }
            spec = buildPanelSpecLocked();
            tasks = copyTasksLocked();
            revision = mTaskRevision;
        }
        if (listener != null) {
            try {
                listener.onOneStepTasksChanged(spec, tasks, revision);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public List<OneStepTaskInfo> getOneStepTasks() {
        refreshCommittedTasksFromAtms();
        synchronized (mLock) {
            return copyTasksLocked();
        }
    }

    @Override
    public long requestAdoptOneStepTask(int taskId, int preferredSlot, Rect sourceBounds,
            int source) {
        enforceSystemUiCaller();
        if (taskId < 0) return failedTaskRequest(taskId, "Invalid task id");
        synchronized (mLock) {
            final long requestId = nextTaskRequestIdLocked();
            final int operation = findTaskLocked(taskId) != null ? OP_ACTIVATE : OP_ADOPT;
            enqueueTaskOperationLocked(new PendingTaskOperation(requestId, operation, taskId,
                    preferredSlot, null, mWindowManager.mAtmService.getCurrentUserId(),
                    sourceBounds, source, -1));
            return requestId;
        }
    }

    @Override
    public long requestLaunchOneStepActivity(Intent intent, int userId, int preferredSlot) {
        enforceSystemUiCaller();
        if (intent == null) return failedTaskRequest(-1, "Missing launch intent");
        final int currentUserId = mWindowManager.mAtmService.getCurrentUserId();
        if (userId != currentUserId) {
            return failedTaskRequest(-1, "OneStep launch user is not current");
        }
        refreshCommittedTasksFromAtms();
        final Intent launchIntent = new Intent(intent);
        final boolean separateDocument = (launchIntent.getFlags()
                & (Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) != 0;
        ComponentName component = launchIntent.getComponent();
        if (component == null && !separateDocument) {
            try {
                final ResolveInfo resolved = mContext.getPackageManager().resolveActivityAsUser(
                        launchIntent, PackageManager.MATCH_DEFAULT_ONLY, userId);
                if (resolved != null && resolved.activityInfo != null) {
                    component = new ComponentName(resolved.activityInfo.packageName,
                            resolved.activityInfo.name);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unable to resolve OneStep launch intent", e);
            }
        }
        synchronized (mLock) {
            final long requestId = nextTaskRequestIdLocked();
            final String packageName = component != null ? component.getPackageName()
                    : launchIntent.getPackage();
            final OneStepTaskInfo existing = !separateDocument
                    ? mTaskStore.findForLaunch(component, packageName, userId) : null;
            enqueueTaskOperationLocked(new PendingTaskOperation(requestId,
                    existing != null ? OP_REOPEN_INTENT : OP_LAUNCH,
                    existing != null ? existing.taskId : -1, preferredSlot, launchIntent,
                    userId, null, OneStepTaskInfo.SOURCE_SIDEBAR_APP, -1));
            return requestId;
        }
    }

    @Override
    public long requestActivateOneStepTask(int taskId) {
        return enqueueExistingTaskOperation(taskId, OP_ACTIVATE);
    }

    @Override
    public long requestRestoreOneStepTask(int taskId, boolean toFront) {
        return enqueueExistingTaskOperation(taskId, OP_RESTORE, toFront ? 1 : 0);
    }

    @Override
    public long requestCloseOneStepTask(int taskId) {
        return enqueueExistingTaskOperation(taskId, OP_CLOSE);
    }

    private long enqueueExistingTaskOperation(int taskId, int operation) {
        return enqueueExistingTaskOperation(taskId, operation, -1);
    }

    private long enqueueExistingTaskOperation(int taskId, int operation, int option) {
        enforceSystemUiCaller();
        synchronized (mLock) {
            if (findTaskLocked(taskId) == null) {
                return failedTaskRequestLocked(taskId, "Task is not in OneStep");
            }
            final long requestId = nextTaskRequestIdLocked();
            enqueueTaskOperationLocked(new PendingTaskOperation(requestId, operation, taskId,
                    option, null, mWindowManager.mAtmService.getCurrentUserId(), null,
                    OneStepTaskInfo.SOURCE_SIDEBAR_APP, -1));
            return requestId;
        }
    }

    private void enqueueTaskOperationLocked(PendingTaskOperation operation) {
        mTaskOperationQueue.addLast(operation);
        mHandler.post(this::dispatchNextTaskOperation);
    }

    private void dispatchNextTaskOperation() {
        final PendingTaskOperation operation;
        final IOneStepTaskHost host;
        synchronized (mLock) {
            if (mPendingTaskOperation != null) return;
            operation = mTaskOperationQueue.pollFirst();
            if (operation == null) return;
            host = mTaskHost;
            if (host == null) {
                finishExternalReopenLocked(operation, "failed: task host unavailable");
                postTaskResult(operation.requestId, operation.requestedTaskId,
                        OneStepTaskInfo.RESULT_UNAVAILABLE,
                        "SystemUI OneStep host unavailable");
                mHandler.post(this::dispatchNextTaskOperation);
                return;
            }
            if (operation.operation != OP_ADOPT && operation.operation != OP_LAUNCH
                    && findTaskLocked(operation.requestedTaskId) == null) {
                finishExternalReopenLocked(operation, "failed: task left OneStep");
                postTaskResult(operation.requestId, operation.requestedTaskId,
                        OneStepTaskInfo.RESULT_NOT_FOUND, "Task is not in OneStep");
                mHandler.post(this::dispatchNextTaskOperation);
                return;
            }
            if (operation.operation == OP_ADOPT || operation.operation == OP_LAUNCH) {
                operation.slot = chooseSlotLocked(operation.preferredSlot);
                operation.evictedTaskId = mTaskStore.evictionCandidate();
            } else if (operation.operation == OP_SWAP) {
                operation.slot = findTaskLocked(operation.requestedTaskId).slot;
            }
            mPendingTaskOperation = operation;
            scheduleTaskOperationTimeoutLocked(operation.requestId);
        }

        if (operation.operation == OP_ADOPT) {
            mWindowManager.setOneStepTaskEmbedded(operation.requestedTaskId, true);
        } else if (operation.operation == OP_SWAP && operation.replacementTaskId >= 0) {
            mWindowManager.setOneStepTaskEmbedded(operation.replacementTaskId, true);
        }
        if (operation.operation == OP_RESTORE || operation.operation == OP_REOPEN_INTENT
                || operation.operation == OP_CLOSE || operation.operation == OP_SWAP) {
            // The factory ActivityStackView clears its client-only focus before detaching or
            // promoting card content. Real focus and all policy targets therefore settle on the
            // main scene before Shell starts moving the task. rollbackEmbeddedMarker() restores
            // this marker if the transition does not commit.
            mWindowManager.setOneStepTaskEmbedded(operation.requestedTaskId, false);
        }
        try {
            if (movesTaskToMainScene(operation)) {
                InputMethodManagerInternal.get().hideInputMethod(
                        HIDE_BUBBLES, Display.DEFAULT_DISPLAY);
                mWindowManager.beginOneStepTaskToFullscreenTransition(operation.requestId);
            }
            switch (operation.operation) {
                case OP_ADOPT:
                    host.adoptTask(operation.requestId, operation.requestedTaskId, operation.slot,
                            operation.evictedTaskId, operation.sourceBounds);
                    break;
                case OP_LAUNCH:
                    host.launchTask(operation.requestId, operation.intent, operation.userId,
                            operation.slot, operation.evictedTaskId);
                    break;
                case OP_ACTIVATE:
                    host.activateTask(operation.requestId, operation.requestedTaskId);
                    break;
                case OP_RESTORE:
                    host.restoreTask(operation.requestId, operation.requestedTaskId,
                            operation.preferredSlot == 1);
                    break;
                case OP_REOPEN_INTENT: {
                    final Intent reopenIntent = new Intent(operation.intent)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    final ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchTaskId(operation.requestedTaskId);
                    mContext.startActivityAsUser(reopenIntent, options.toBundle(),
                            UserHandle.of(operation.userId));
                    host.restoreTask(operation.requestId, operation.requestedTaskId,
                            true /* toFront */);
                    break;
                }
                case OP_CLOSE:
                    host.closeTask(operation.requestId, operation.requestedTaskId);
                    break;
                case OP_SWAP:
                    host.swapTask(operation.requestId, operation.requestedTaskId,
                            operation.replacementTaskId, operation.slot);
                    break;
                default:
                    completeTaskOperationFailure(operation.requestId,
                            operation.requestedTaskId, "Unknown OneStep operation");
            }
        } catch (RemoteException | RuntimeException e) {
            completeTaskOperationFailure(operation.requestId, operation.requestedTaskId,
                    "Unable to contact SystemUI");
        }
    }

    private static boolean movesTaskToMainScene(PendingTaskOperation operation) {
        return operation.operation == OP_SWAP || operation.operation == OP_REOPEN_INTENT
                || (operation.operation == OP_RESTORE && operation.preferredSlot == 1);
    }

    @Override
    public boolean launchPreviousApp() {
        enforceSystemUiCaller();
        final List<ActivityManager.RecentTaskInfo> tasks = getEligibleMainTasks();
        if (tasks.size() < 2) return false;
        final int targetTaskId = tasks.get(1).taskId;
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowManager.mGlobalLock) {
                final Task task = mWindowManager.mRoot.anyTaskForId(targetTaskId);
                if (task == null || mWindowManager.mAtmService.getLockTaskController()
                        .isLockTaskModeViolation(task)) {
                    return false;
                }
                mWindowManager.mAtmService.mTaskSupervisor.findTaskToMoveToFront(task,
                        ActivityManager.MOVE_TASK_WITH_HOME, null,
                        "OneStep launchPreviousApp", false /* forceNonResizable */);
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public long requestAdoptCurrentOneStepTask() {
        enforceSystemUiCaller();
        final ActivityManager.RecentTaskInfo current = getCurrentMainTask();
        if (!isEmbeddableMainTask(current)) {
            return failedTaskRequest(current != null ? current.taskId : -1,
                    "Current task cannot be added to OneStep");
        }
        synchronized (mLock) {
            if (findTaskLocked(current.taskId) != null) {
                return failedTaskRequestLocked(current.taskId, "Task is already in OneStep");
            }
            final long requestId = nextTaskRequestIdLocked();
            enqueueTaskOperationLocked(new PendingTaskOperation(requestId, OP_ADOPT,
                    current.taskId, -1, null, current.userId, null,
                    OneStepTaskInfo.SOURCE_FOCUSED_TASK, -1));
            return requestId;
        }
    }

    @Override
    public long requestSwapOneStepTask(int taskId) {
        enforceSystemUiCaller();
        final ActivityManager.RecentTaskInfo current = getCurrentMainTask();
        final int replacementTaskId = isEmbeddableMainTask(current) && current.taskId != taskId
                ? current.taskId : -1;
        synchronized (mLock) {
            if (findTaskLocked(taskId) == null) {
                return failedTaskRequestLocked(taskId, "Task is not in OneStep");
            }
            final long requestId = nextTaskRequestIdLocked();
            enqueueTaskOperationLocked(new PendingTaskOperation(requestId, OP_SWAP, taskId,
                    -1, null, current != null ? current.userId
                            : mWindowManager.mAtmService.getCurrentUserId(), null,
                    OneStepTaskInfo.SOURCE_SIDEBAR_APP, replacementTaskId));
            return requestId;
        }
    }

    private ActivityManager.RecentTaskInfo getCurrentMainTask() {
        final List<ActivityManager.RecentTaskInfo> tasks = getEligibleMainTasks();
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private List<ActivityManager.RecentTaskInfo> getEligibleMainTasks() {
        final List<ActivityManager.RecentTaskInfo> recentTasks;
        final int userId = mWindowManager.mAtmService.getCurrentUserId();
        final long identity = Binder.clearCallingIdentity();
        try {
            recentTasks = mWindowManager.mAtmService.getRecentTasks(20,
                    ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId).getList();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        final ArrayList<ActivityManager.RecentTaskInfo> eligible = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo info : recentTasks) {
            if (info == null || info.taskId <= 0 || info.userId != userId
                    || info.displayId != Display.DEFAULT_DISPLAY
                    || info.getWindowingMode() == WINDOWING_MODE_PINNED) {
                continue;
            }
            final int activityType = info.getActivityType();
            if (activityType != ACTIVITY_TYPE_STANDARD && activityType != ACTIVITY_TYPE_HOME) {
                continue;
            }
            final ComponentName component = info.topActivity != null
                    ? info.topActivity : info.baseActivity;
            if (component != null && SIDEBAR_PACKAGE.equals(component.getPackageName())) continue;
            synchronized (mLock) {
                if (findTaskLocked(info.taskId) != null) continue;
            }
            eligible.add(info);
        }
        return eligible;
    }

    private static boolean isEmbeddableMainTask(ActivityManager.RecentTaskInfo info) {
        return info != null && info.getActivityType() == ACTIVITY_TYPE_STANDARD
                && info.getWindowingMode() != WINDOWING_MODE_PINNED
                && info.isResizeable && info.supportsMultiWindow
                && info.configuration.orientation
                        != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    public void reportOneStepTaskResult(long requestId, int taskId, int result,
            OneStepTaskInfo info, String message) {
        final int callingUid = Binder.getCallingUid();
        if (!isRootOrSystem(callingUid)) {
            enforceSystemUiCaller();
            synchronized (mLock) {
                if (callingUid != mTaskHostUid) {
                    throw new SecurityException("UID " + callingUid
                            + " is not the registered OneStep task host");
                }
            }
        }
        if (requestId == 0 && result == OneStepTaskInfo.RESULT_TASK_REMOVED) {
            final boolean removed;
            synchronized (mLock) {
                removed = removeTaskLocked(taskId);
                if (removed) mTaskRevision++;
            }
            if (removed) {
                mWindowManager.setOneStepTaskEmbedded(taskId, false);
                dispatchTaskState();
            }
            return;
        }

        final PendingTaskOperation operation;
        synchronized (mLock) {
            operation = mPendingTaskOperation;
            if (operation == null) {
                postTaskResult(requestId, taskId, result, message);
                return;
            }
            if (operation.requestId != requestId) {
                postTaskResult(requestId, taskId, OneStepTaskInfo.RESULT_REJECTED,
                        "Stale OneStep task result");
                return;
            }
            mPendingTaskOperation = null;
        }
        mWindowManager.finishOneStepTaskToFullscreenTransition(operation.requestId);

        if (result == OneStepTaskInfo.RESULT_OK
                && !isExpectedTaskResult(operation, taskId, info)) {
            result = OneStepTaskInfo.RESULT_REJECTED;
            message = "SystemUI returned a mismatched OneStep task";
        }

        OneStepTaskInfo trustedInfo = info;
        if (result == OneStepTaskInfo.RESULT_OK
                && (operation.operation == OP_ADOPT || operation.operation == OP_LAUNCH
                        || (operation.operation == OP_SWAP
                                && operation.replacementTaskId >= 0))) {
            final int committedTaskId = operation.operation == OP_SWAP
                    ? operation.replacementTaskId : taskId;
            trustedInfo = resolveLiveTaskInfo(committedTaskId, operation.slot, info,
                    true /* requireMultiWindow */);
            if (trustedInfo == null) {
                result = OneStepTaskInfo.RESULT_REJECTED;
                message = "Task no longer matches the live embedded task";
            }
        }

        if (result != OneStepTaskInfo.RESULT_OK) {
            synchronized (mLock) {
                finishExternalReopenLocked(operation, "failed: " + message);
            }
            rollbackEmbeddedMarker(operation);
            postTaskResult(requestId, taskId, result, message);
            dispatchTaskState();
            mHandler.post(this::drainDeferredExternalReopens);
            mHandler.post(this::dispatchNextTaskOperation);
            return;
        }

        int removedTaskId = -1;
        int embeddedTaskId = -1;
        boolean stateChanged = false;
        synchronized (mLock) {
            if (operation.operation == OP_ADOPT || operation.operation == OP_LAUNCH) {
                final int targetSlot = operation.slot;
                final OneStepTaskInfo reported = trustedInfo != null
                        ? trustedInfo : new OneStepTaskInfo(taskId, operation.userId,
                                targetSlot, null, null, boundsForSlotLocked(targetSlot), 0,
                                OneStepTaskInfo.STATE_EMBEDDED, isInSidebarModeInternal());
                removedTaskId = mTaskStore.commitAdd(reported.withSlot(targetSlot,
                        boundsForSlotLocked(targetSlot)).withState(
                        OneStepTaskInfo.STATE_EMBEDDED, isInSidebarModeInternal()),
                        targetSlot, this::boundsForSlotLocked);
                if (operation.operation == OP_LAUNCH && taskId >= 0) {
                    embeddedTaskId = taskId;
                }
                stateChanged = true;
            } else if (operation.operation == OP_RESTORE || operation.operation == OP_CLOSE
                    || operation.operation == OP_REOPEN_INTENT) {
                if (removeTaskLocked(operation.requestedTaskId)) {
                    removedTaskId = operation.requestedTaskId;
                    stateChanged = true;
                }
            } else if (operation.operation == OP_SWAP) {
                final OneStepTaskInfo replacement;
                if (operation.replacementTaskId >= 0) {
                    replacement = trustedInfo != null ? trustedInfo : new OneStepTaskInfo(
                            operation.replacementTaskId, operation.userId, operation.slot,
                            null, null, boundsForSlotLocked(operation.slot), 0,
                            OneStepTaskInfo.STATE_EMBEDDED, isInSidebarModeInternal());
                    embeddedTaskId = operation.replacementTaskId;
                } else {
                    replacement = null;
                }
                stateChanged = mTaskStore.replace(operation.requestedTaskId, replacement,
                        this::boundsForSlotLocked);
                if (stateChanged) removedTaskId = operation.requestedTaskId;
            }
            if (stateChanged) mTaskRevision++;
            finishExternalReopenLocked(operation, "completed request=" + requestId);
        }
        if (removedTaskId >= 0) {
            mWindowManager.setOneStepTaskEmbedded(removedTaskId, false);
        }
        if (embeddedTaskId >= 0) {
            mWindowManager.setOneStepTaskEmbedded(embeddedTaskId, true);
        }
        postTaskResult(requestId, taskId, result, message);
        if (stateChanged) dispatchTaskState();
        mHandler.post(this::drainDeferredExternalReopens);
        mHandler.post(this::dispatchNextTaskOperation);
    }

    boolean isOneStepTaskEmbedded(int taskId) {
        synchronized (mLock) {
            if (findTaskLocked(taskId) != null) return true;
            if (mPendingTaskOperation == null) return false;
            return (mPendingTaskOperation.operation == OP_ADOPT
                    && mPendingTaskOperation.requestedTaskId == taskId)
                    || (mPendingTaskOperation.operation == OP_SWAP
                    && mPendingTaskOperation.replacementTaskId == taskId);
        }
    }

    private long failedTaskRequest(int taskId, String message) {
        synchronized (mLock) {
            return failedTaskRequestLocked(taskId, message);
        }
    }

    private long failedTaskRequestLocked(int taskId, String message) {
        final long requestId = nextTaskRequestIdLocked();
        postTaskResult(requestId, taskId, OneStepTaskInfo.RESULT_REJECTED, message);
        return requestId;
    }

    private long nextTaskRequestIdLocked() {
        if (mNextTaskRequestId == Long.MAX_VALUE) mNextTaskRequestId = 1;
        return mNextTaskRequestId++;
    }

    private void scheduleTaskOperationTimeoutLocked(long requestId) {
        mHandler.postDelayed(() -> handleTaskOperationTimeout(requestId),
                TASK_OPERATION_TIMEOUT_MS);
    }

    private void handleTaskOperationTimeout(long requestId) {
        final PendingTaskOperation operation;
        synchronized (mLock) {
            if (mPendingTaskOperation == null
                    || mPendingTaskOperation.requestId != requestId) {
                return;
            }
            operation = mPendingTaskOperation;
            mPendingTaskOperation = null;
            finishExternalReopenLocked(operation, "failed: operation timed out");
        }
        mWindowManager.finishOneStepTaskToFullscreenTransition(operation.requestId);
        rollbackEmbeddedMarker(operation);
        postTaskResult(operation.requestId, operation.requestedTaskId,
                OneStepTaskInfo.RESULT_UNAVAILABLE, "OneStep task operation timed out");
        dispatchTaskState();
        mHandler.post(this::drainDeferredExternalReopens);
        mHandler.post(this::dispatchNextTaskOperation);
    }

    private void rollbackEmbeddedMarker(PendingTaskOperation operation) {
        if (operation == null) return;
        if (operation.operation == OP_ADOPT && operation.requestedTaskId >= 0) {
            mWindowManager.setOneStepTaskEmbedded(operation.requestedTaskId, false);
        } else if (operation.operation == OP_SWAP && operation.replacementTaskId >= 0) {
            mWindowManager.setOneStepTaskEmbedded(operation.replacementTaskId, false);
        }
        if ((operation.operation == OP_RESTORE || operation.operation == OP_REOPEN_INTENT
                || operation.operation == OP_CLOSE || operation.operation == OP_SWAP)
                && operation.requestedTaskId >= 0) {
            synchronized (mLock) {
                if (findTaskLocked(operation.requestedTaskId) == null) return;
            }
            mWindowManager.setOneStepTaskEmbedded(operation.requestedTaskId, true);
        }
    }

    private int chooseSlotLocked(int preferredSlot) {
        return mTaskStore.chooseSlot(preferredSlot);
    }

    private static boolean isExpectedTaskResult(PendingTaskOperation operation, int taskId,
            OneStepTaskInfo info) {
        final int expectedTaskId;
        switch (operation.operation) {
            case OP_LAUNCH:
                expectedTaskId = taskId;
                if (taskId < 0) return false;
                break;
            case OP_SWAP:
                expectedTaskId = operation.replacementTaskId >= 0
                        ? operation.replacementTaskId : operation.requestedTaskId;
                if (taskId != expectedTaskId) return false;
                break;
            default:
                expectedTaskId = operation.requestedTaskId;
                if (taskId != expectedTaskId) return false;
                break;
        }
        return info == null || info.taskId == expectedTaskId;
    }

    private OneStepTaskInfo findTaskLocked(int taskId) {
        return mTaskStore.find(taskId);
    }

    private boolean removeTaskLocked(int taskId) {
        return mTaskStore.remove(taskId, this::boundsForSlotLocked);
    }

    private List<OneStepTaskInfo> copyTasksLocked() {
        final boolean visible = mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID;
        return mTaskStore.snapshot(visible, this::boundsForSlotLocked);
    }

    /** Refreshes display metadata from ATMS; SystemUI never owns task identity or components. */
    private void refreshCommittedTasksFromAtms() {
        final List<OneStepTaskInfo> committed;
        synchronized (mLock) {
            committed = copyTasksLocked();
        }
        if (committed.isEmpty()) return;

        final ArrayList<OneStepTaskInfo> live = new ArrayList<>(committed.size());
        final ArrayList<Integer> missing = new ArrayList<>();
        for (OneStepTaskInfo stored : committed) {
            final OneStepTaskInfo refreshed = resolveLiveTaskInfo(stored.taskId, stored.slot,
                    stored, false /* requireMultiWindow */);
            if (refreshed != null) {
                live.add(refreshed.withState(stored.state, stored.visible));
            } else {
                missing.add(stored.taskId);
            }
        }

        synchronized (mLock) {
            for (OneStepTaskInfo task : live) {
                mTaskStore.replace(task.taskId, task, this::boundsForSlotLocked);
            }
            for (int taskId : missing) {
                if (removeTaskLocked(taskId)) mTaskRevision++;
            }
        }
        for (int taskId : missing) {
            mWindowManager.setOneStepTaskEmbedded(taskId, false);
        }
        for (OneStepTaskInfo task : live) {
            // Re-select the top client window after in-card navigation and reassert the factory
            // fake-focus/real-focus split without trusting stale SystemUI metadata.
            mWindowManager.setOneStepTaskEmbedded(task.taskId, true);
        }
    }

    private OneStepTaskInfo resolveLiveTaskInfo(int taskId, int slot,
            OneStepTaskInfo fallback, boolean requireMultiWindow) {
        final ActivityManager.RunningTaskInfo running;
        synchronized (mWindowManager.mGlobalLock) {
            final Task task = mWindowManager.mRoot.anyTaskForId(taskId);
            if (task == null || task.getUserId() != mWindowManager.mAtmService.getCurrentUserId()
                    || task.getDisplayId() != Display.DEFAULT_DISPLAY
                    || (requireMultiWindow && !task.inMultiWindowMode())) {
                return null;
            }
            running = task.getTaskInfo();
        }
        if (running.topActivity == null) return null;
        final String label = running.taskDescription != null
                ? running.taskDescription.getLabel() : null;
        final int backgroundColor = running.taskDescription != null
                ? running.taskDescription.getBackgroundColor()
                : fallback != null ? fallback.backgroundColor : 0;
        final Rect bounds = fallback != null ? fallback.bounds : new Rect();
        final int state = fallback != null ? fallback.state : OneStepTaskInfo.STATE_EMBEDDED;
        final boolean visible = fallback != null && fallback.visible;
        return new OneStepTaskInfo(taskId, running.userId, slot, running.topActivity, label,
                bounds, backgroundColor, state, visible);
    }

    private Rect boundsForSlotLocked(int slot) {
        return buildPanelSpecLocked().getSlotBounds(slot);
    }

    private OneStepPanelSpec buildPanelSpecLocked() {
        final boolean showing = mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID;
        // Keep the last portrait geometry while hidden. SystemUI can then recreate its TaskViews
        // after a process restart and finish adopting them as soon as the panel becomes visible.
        final int panelMode = showing ? mMode : mLastMode;
        return buildPanelSpecLocked(panelMode, showing);
    }

    private OneStepPanelSpec buildPanelSpecLocked(int panelMode, boolean showing) {
        final DisplayContent display;
        final Rect displayBounds = new Rect();
        synchronized (mWindowManager.mGlobalLock) {
            display = mWindowManager.mRoot.getDisplayContent(Display.DEFAULT_DISPLAY);
            if (display != null) displayBounds.set(display.getBounds());
        }
        if (displayBounds.isEmpty()) {
            displayBounds.set(0, 0, mContext.getResources().getDisplayMetrics().widthPixels,
                    mContext.getResources().getDisplayMetrics().heightPixels);
        }
        final int topHeight = Math.max(dp(132), Math.round(displayBounds.height()
                * SIDEBAR_FRACTION));
        final int sideWidth = Math.max(dp(88), Math.round(displayBounds.width()
                * SIDEBAR_FRACTION));
        final boolean left = panelMode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT;
        final Rect top = new Rect(displayBounds.left, displayBounds.top, displayBounds.right,
                displayBounds.top + topHeight);
        final Rect side = left
                ? new Rect(displayBounds.left, top.bottom, displayBounds.left + sideWidth,
                        displayBounds.bottom)
                : new Rect(displayBounds.right - sideWidth, top.bottom, displayBounds.right,
                        displayBounds.bottom);
        // The original R2 layout uses 267x579 task cards with 6px gaps on a 1080x2340 panel.
        // Derive the same uniform portrait scale from the current display instead of dividing the
        // strip into three arbitrary rectangles (which changes app aspect ratio on tall phones).
        int itemWidth = Math.max(1, Math.round(displayBounds.width()
                * ORIGINAL_TASK_WIDTH / ORIGINAL_DISPLAY_WIDTH));
        int gap = Math.max(1, Math.round(displayBounds.width()
                * ORIGINAL_TASK_GAP / ORIGINAL_DISPLAY_WIDTH));
        int itemHeight = Math.max(1, Math.round(displayBounds.height()
                * (itemWidth / (float) Math.max(1, displayBounds.width()))));
        final int availableForItems = side.height() - gap * (MAX_TASKS - 1);
        if (itemHeight * MAX_TASKS > availableForItems) {
            itemHeight = Math.max(1, availableForItems / MAX_TASKS);
            itemWidth = Math.max(1, Math.round(itemHeight
                    * (displayBounds.width() / (float) Math.max(1, displayBounds.height()))));
        }
        final int itemLeft = left ? side.left : side.right - itemWidth;
        final ArrayList<Rect> slots = new ArrayList<>(MAX_TASKS);
        for (int i = 0; i < MAX_TASKS; i++) {
            final int topEdge = side.top + (itemHeight + gap) * i;
            slots.add(new Rect(itemLeft, topEdge, itemLeft + itemWidth, topEdge + itemHeight));
        }
        return new OneStepPanelSpec(Display.DEFAULT_DISPLAY, panelMode, top, side, slots,
                showing);
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }

    private IOneStepTaskHost getTaskHost() {
        synchronized (mLock) {
            return mTaskHost;
        }
    }

    private void completeTaskOperationFailure(long requestId, int taskId, String message) {
        PendingTaskOperation operation = null;
        synchronized (mLock) {
            if (mPendingTaskOperation != null && mPendingTaskOperation.requestId == requestId) {
                operation = mPendingTaskOperation;
                mPendingTaskOperation = null;
                finishExternalReopenLocked(operation, "failed: " + message);
            }
        }
        rollbackEmbeddedMarker(operation);
        if (operation != null) {
            mWindowManager.finishOneStepTaskToFullscreenTransition(operation.requestId);
        }
        postTaskResult(requestId, taskId, OneStepTaskInfo.RESULT_UNAVAILABLE, message);
        mHandler.post(this::drainDeferredExternalReopens);
        mHandler.post(this::dispatchNextTaskOperation);
    }

    private void postTaskResult(long requestId, int taskId, int result, String message) {
        mHandler.post(() -> notifyTaskListener(requestId, taskId, result, message));
    }

    private void notifyTaskListener(long requestId, int taskId, int result, String message) {
        final IOneStepTaskListener listener;
        synchronized (mLock) {
            listener = mTaskListener;
        }
        if (listener == null) return;
        try {
            listener.onOneStepTaskOperationResult(requestId, taskId, result, message);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchTaskState() {
        refreshCommittedTasksFromAtms();
        final IOneStepTaskHost host;
        final IOneStepTaskListener listener;
        final OneStepPanelSpec spec;
        final List<OneStepTaskInfo> tasks;
        final long revision;
        synchronized (mLock) {
            host = mTaskHost;
            listener = mTaskListener;
            spec = buildPanelSpecLocked();
            tasks = copyTasksLocked();
            revision = mTaskRevision;
        }
        if (host != null) {
            try {
                host.applyState(spec, tasks, revision);
            } catch (RemoteException ignored) {
            }
        }
        if (listener != null) {
            try {
                listener.onOneStepTasksChanged(spec, tasks, revision);
            } catch (RemoteException ignored) {
            }
        }
    }

    void requestZoom(int mode, int reason) {
        enforceSidebarPermission();
        requestZoomInternal(mode, reason);
    }

    private void requestZoomInternal(int mode, int reason) {
        requestZoomInternal(mode, reason, false /* forceSafetyExit */);
    }

    private void requestZoomInternal(int requestedMode, int reason, boolean forceSafetyExit) {
        mHandler.post(() -> {
            final int mode = requestedMode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                    || requestedMode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT
                    ? requestedMode : MagnificationSpecSmt.TYPE_ZOOM_INVALID;
            if (mode != MagnificationSpecSmt.TYPE_ZOOM_INVALID && !canEnterSidebarMode()) {
                return;
            }
            final MagnificationSpecSmt spec;
            synchronized (mLock) {
                if (mode != MagnificationSpecSmt.TYPE_ZOOM_INVALID && mTaskHost == null) {
                    Slog.w(TAG, "Rejecting OneStep enter before SystemUI windows are ready");
                    return;
                }
                if (!forceSafetyExit && mZoomTransitionPending) {
                    Slog.w(TAG, "Ignoring OneStep request during transition target="
                            + mZoomTransitionTarget + " requested=" + mode);
                    return;
                }
                if (!mZoomTransitionPending && mMode == mode) return;
                mZoomTransitionPending = true;
                mZoomTransitionTarget = mode;
                spec = createMagnificationSpecLocked(mode);
            }
            final boolean accepted;
            try {
                accepted = mWindowManager.setMagnificationSpecSmt(spec);
            } finally {
                spec.recycle();
            }
            if (!accepted) {
                synchronized (mLock) {
                    if (mZoomTransitionTarget == mode) {
                        mZoomTransitionPending = false;
                    }
                }
                Slog.w(TAG, "Window manager rejected OneStep mode " + mode
                        + " reason=" + reason);
            }
        });
    }

    private MagnificationSpecSmt createMagnificationSpecLocked(int mode) {
        final Rect displayBounds = new Rect();
        synchronized (mWindowManager.mGlobalLock) {
            final DisplayContent display = mWindowManager.mRoot.getDisplayContent(
                    Display.DEFAULT_DISPLAY);
            if (display != null) displayBounds.set(display.getBounds());
        }
        if (displayBounds.isEmpty()) {
            displayBounds.set(0, 0, mContext.getResources().getDisplayMetrics().widthPixels,
                    mContext.getResources().getDisplayMetrics().heightPixels);
        }
        final MagnificationSpecSmt spec = MagnificationSpecSmt.obtain();
        if (mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                || mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_RIGHT) {
            final float scale = 1f - SIDEBAR_FRACTION;
            spec.type(mode).scale(scale)
                    .offsetXY(mode == MagnificationSpecSmt.TYPE_ZOOM_SIDEBAR_IN_LEFT
                                    ? displayBounds.width() * SIDEBAR_FRACTION : 0f,
                            displayBounds.height() * SIDEBAR_FRACTION)
                    .cropRect(displayBounds).anim(true, 300);
        } else {
            spec.clear();
            spec.cropRect(displayBounds).anim(true, 300);
        }
        return spec;
    }

    void onMagnificationSpecApplied(MagnificationSpecSmt spec) {
        final int oldMode;
        final int newMode = spec == null || spec.isNop()
                ? MagnificationSpecSmt.TYPE_ZOOM_INVALID : spec.type;
        synchronized (mLock) {
            oldMode = mMode;
            if (oldMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID) mLastMode = oldMode;
            mMode = newMode;
            if (newMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID) mLastMode = newMode;
            mZoomTransitionPending = false;
            mZoomTransitionTarget = newMode;
        }
        Settings.Global.putInt(mContext.getContentResolver(), "side_bar_zoom_type", newMode);
        Settings.Global.putInt(mContext.getContentResolver(), "sidebar_switch_status",
                newMode == MagnificationSpecSmt.TYPE_ZOOM_INVALID ? 0 : 1);
        final ISidebar sidebar = getSidebar();
        if (sidebar != null && oldMode != newMode) {
            try {
                if (newMode == MagnificationSpecSmt.TYPE_ZOOM_INVALID) {
                    sidebar.onExitSidebarMode(0);
                } else {
                    sidebar.onEnterSidebarMode(newMode, 0);
                }
            } catch (RemoteException ignored) {
            }
        }
        if (spec != null && spec.anim && spec.animCallback != null) {
            final Bundle result = new Bundle();
            result.putInt(MagnificationSpecSmt.SPEC_ANIM_KEY, MagnificationSpecSmt.ANIM_END);
            try {
                spec.animCallback.sendResult(result);
            } catch (RemoteException ignored) {
            }
        }
        dispatchTaskState();
        final boolean reenter;
        synchronized (mLock) {
            reenter = newMode == MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    && mReenterAfterHostRegistration && mTaskHost != null;
        }
        if (reenter) mHandler.post(this::attemptHostReentry);
    }

    private void attemptHostReentry() {
        final int mode;
        synchronized (mLock) {
            if (!mReenterAfterHostRegistration || mTaskHost == null
                    || mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    || mZoomTransitionPending) {
                return;
            }
            mode = mLastMode;
        }
        if (!canEnterSidebarMode()) {
            mHandler.postDelayed(this::attemptHostReentry, 1000);
            return;
        }
        synchronized (mLock) {
            if (!mReenterAfterHostRegistration || mTaskHost == null
                    || mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    || mZoomTransitionPending) {
                return;
            }
            mReenterAfterHostRegistration = false;
        }
        requestZoomInternal(mode, 0);
    }

    /**
     * Publishes the target scene before WMS starts changing surfaces.  Entering needs the two
     * trusted overlay windows visible on the first animation frame.  Exiting is deliberately
     * different: the original implementation keeps both windows (and their TaskViews) visible
     * until the reverse surface animation has reached its last frame, then hides them from
     * {@link #onMagnificationSpecApplied}.  Publishing a hidden panel here makes the close
     * animation appear to be missing and can also detach a task surface midway through WMS' zoom.
     */
    void onMagnificationSpecAnimationStarted(MagnificationSpecSmt spec) {
        if (spec == null || !spec.anim) return;
        if (spec.isNop()) {
            mWindowManager.cancelOneStepTaskToFullscreenTransition();
        }
        refreshCommittedTasksFromAtms();
        final int targetMode = spec.isNop()
                ? MagnificationSpecSmt.TYPE_ZOOM_INVALID : spec.type;
        final IOneStepTaskHost host;
        final IOneStepTaskListener listener;
        final OneStepPanelSpec panelSpec;
        final List<OneStepTaskInfo> tasks;
        final long revision;
        synchronized (mLock) {
            final boolean entering = targetMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID;
            final boolean switchingSides = entering
                    && mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID
                    && targetMode != mMode;
            // A side swap needs the old scene through the first animation frame. Publishing the
            // target side here makes SystemUI migrate the real TaskView before the DisplayArea
            // has committed its target mode. The final apply callback publishes targetMode.
            final int panelMode = switchingSides ? mMode : entering ? targetMode
                    : (mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID ? mMode : mLastMode);
            host = mTaskHost;
            listener = mTaskListener;
            // Keep the current scene visible during an exit. onMagnificationSpecApplied() sends
            // the only visible=false state after WMS has committed the final full-screen frame.
            panelSpec = buildPanelSpecLocked(panelMode, true /* showing */);
            tasks = copyTasksLocked();
            revision = mTaskRevision;
        }
        if (host != null) {
            try {
                host.applyState(panelSpec, tasks, revision);
            } catch (RemoteException ignored) {
            }
        }
        if (listener != null) {
            try {
                listener.onOneStepTasksChanged(panelSpec, tasks, revision);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** Restores the last committed scene after WMS atomically rolled back a failed animation. */
    void onMagnificationSpecAnimationCancelled(MagnificationSpecSmt target) {
        final int cancelledTarget = target == null || target.isNop()
                ? MagnificationSpecSmt.TYPE_ZOOM_INVALID : target.type;
        synchronized (mLock) {
            if (!mZoomTransitionPending || mZoomTransitionTarget != cancelledTarget) return;
            mZoomTransitionPending = false;
            mZoomTransitionTarget = mMode;
        }
        dispatchTaskState();
    }

    /** WMS rejected or lost the factory DisplayArea scene; publish one atomic safe state. */
    void onOneStepSceneInvalid() {
        mHandler.post(() -> {
            final boolean changed;
            synchronized (mLock) {
                changed = mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID
                        || mZoomTransitionPending;
                if (mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID) mLastMode = mMode;
                mMode = MagnificationSpecSmt.TYPE_ZOOM_INVALID;
                mZoomTransitionPending = false;
                mZoomTransitionTarget = MagnificationSpecSmt.TYPE_ZOOM_INVALID;
            }
            if (!changed) return;
            Settings.Global.putInt(mContext.getContentResolver(), "side_bar_zoom_type",
                    MagnificationSpecSmt.TYPE_ZOOM_INVALID);
            Settings.Global.putInt(mContext.getContentResolver(), "sidebar_switch_status", 0);
            final ISidebar sidebar = getSidebar();
            if (sidebar != null) {
                try {
                    sidebar.onExitSidebarMode(0);
                } catch (RemoteException ignored) {
                }
            }
            dispatchTaskState();
        });
    }

    private ISidebar getSidebar() {
        synchronized (mLock) {
            return mSidebar;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        refreshCommittedTasksFromAtms();
        final String displayState = mWindowManager.getOneStepDisplayStateForDump();
        synchronized (mLock) {
            pw.println("Smartisan OneStep:");
            pw.println("  enabled=" + mEnabled);
            pw.println("  mode=" + mMode + " lastMode=" + mLastMode
                    + " currentUser=" + mWindowManager.mAtmService.getCurrentUserId());
            pw.println("  moduleAuthEnabled="
                    + SystemProperties.getBoolean(MAGISK_MODULE_PROPERTY, false));
            pw.println("  sidebarAppBound=" + mSidebarAppBound
                    + " binding=" + mSidebarAppBinding
                    + " sidebarRegistered=" + (mSidebar != null));
            pw.println("  launcherRegistered=" + (mLauncher != null)
                    + " launcherUid=" + mLauncherUid);
            pw.println("  lastSecurityRejection=" + mLastSecurityRejection);
            pw.println("  zoomTransitionPending=" + mZoomTransitionPending
                    + " target=" + mZoomTransitionTarget);
            pw.println("  displayState=" + displayState);
            pw.println("  lastSystemGestureMode=" + mLastSystemGestureMode
                    + " reason=" + mLastSystemGestureReason
                    + " result=" + mLastSystemGestureResult);
            pw.println("  taskHostRegistered=" + (mTaskHost != null)
                    + " taskHostUid=" + mTaskHostUid
                    + " taskListenerRegistered=" + (mTaskListener != null));
            pw.println("  taskRevision=" + mTaskRevision + " tasks=" + mTaskStore.snapshot(
                    mMode != MagnificationSpecSmt.TYPE_ZOOM_INVALID, this::boundsForSlotLocked));
            pw.println("  taskOperationPending="
                    + (mPendingTaskOperation != null ? mPendingTaskOperation.requestId : 0)
                    + " queued=" + mTaskOperationQueue.size());
            pw.println("  externalReopenTask=" + mLastExternalReopenTaskId
                    + " package=" + mLastExternalReopenPackage
                    + " waiting=" + mExternalReopenTasks
                    + " result=" + mLastExternalReopenResult);
        }
    }
}
