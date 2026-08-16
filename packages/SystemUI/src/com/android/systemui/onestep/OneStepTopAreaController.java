/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.onestep;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.ViewRootImpl;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.sidebar.ISidebarService;
import com.android.internal.sidebar.OneStepPanelSpec;
import com.android.systemui.res.R;
import com.android.systemui.keyguard.pin.SosKeyguardPinProvider;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import smartisanos.api.LayoutParamsSmt;

/** SystemUI owner of the factory-equivalent trusted OneStep top-area window. */
final class OneStepTopAreaController {
    private static final String TAG = "OneStepTopArea";
    private static final Uri APPS_URI = Uri.parse("content://com.smartisanos.sidebar.sync/apps");
    private static final Uri RESOLVEINFO_URI =
            Uri.parse("content://com.smartisanos.sidebar.sync/resolveinfo");
    static final String ACTION_GENERIC_TOP_SHARE =
            "com.smartisanos.sidebar.action.GENERIC_TOP_SHARE";
    static final String ACTION_TEXT_BOOM_TARGETS =
            "com.smartisanos.sidebar.action.TEXT_BOOM_TARGETS";
    static final String EXTRA_SHARE_ITEM_COUNT = "onestep_share_item_count";
    static final String EXTRA_SHARE_MIME_TYPE = "onestep_share_mime_type";
    private static final int WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

    interface BackgroundProvider {
        Bitmap getBackground();
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final PackageManager mPackageManager;
    private final Handler mHandler;
    private final BackgroundProvider mBackgroundProvider;
    private final Runnable mWindowStateChanged;
    private final float mDensity;
    private final LinkedHashMap<String, OngoingItem> mOngoing = new LinkedHashMap<>();
    private final ContentObserver mSyncObserver;
    private final LongSparseArray<PendingDragPermission> mPendingDragPermissions =
            new LongSparseArray<>();

    private FrameLayout mRoot;
    private WindowManager.LayoutParams mLayoutParams;
    private boolean mWindowAdded;
    private boolean mAttached;
    private int mWindowGeneration;
    private LinearLayout mTargetRow;
    private TextView mStatus;
    private Intent mGlobalShareIntent;
    private int mMode = OneStepPanelSpec.MODE_RIGHT;
    private int mTaskCount;
    private boolean mVisible;
    private int mHideGeneration;
    private boolean mOpenSettingsAfterExit;
    private boolean mPackageReceiverRegistered;
    private boolean mSyncObserverRegistered;
    private boolean mControllerDisabled;

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mGlobalShareIntent == null) refreshTargets();
        }
    };
    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mGlobalShareIntent = null;
            mOngoing.clear();
            releaseAllDragPermissions();
            refreshTargets();
        }
    };

    OneStepTopAreaController(Context context, BackgroundProvider backgroundProvider,
            Runnable windowStateChanged) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mPackageManager = context.getPackageManager();
        mHandler = new Handler(context.getMainLooper());
        mBackgroundProvider = backgroundProvider;
        mWindowStateChanged = windowStateChanged;
        mDensity = context.getResources().getDisplayMetrics().density;
        mSyncObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (mGlobalShareIntent == null || RESOLVEINFO_URI.equals(uri)) refreshTargets();
            }
        };
    }

    void start() {
        if (!mPackageReceiverRegistered) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            mContext.registerReceiverAsUser(mPackageReceiver, UserHandle.ALL, filter,
                    null /* broadcastPermission */, mHandler);
            // ActivityManager keys receiver registrations by receiver instance as well as the
            // target user. Reusing mPackageReceiver here would mix UserHandle.ALL with the
            // current user and crash SystemUI during startup on Android 16.
            mContext.registerReceiver(mUserReceiver,
                    new IntentFilter(Intent.ACTION_USER_SWITCHED),
                    null /* broadcastPermission */, mHandler,
                    Context.RECEIVER_NOT_EXPORTED);
            mPackageReceiverRegistered = true;
            try {
                final ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(APPS_URI, true, mSyncObserver,
                        UserHandle.USER_ALL);
                resolver.registerContentObserver(RESOLVEINFO_URI, true, mSyncObserver,
                        UserHandle.USER_ALL);
                mSyncObserverRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to observe OneStep ordering provider", e);
            }
        }
        ensureWindow();
    }

    boolean ensureWindow() {
        if (mControllerDisabled) return false;
        if (isAttached()) return true;
        // addView() can return before ViewRoot dispatches attach. Treat that interval as an
        // existing window; creating another root here produced duplicate 2052 tokens.
        if (mRoot != null && mWindowAdded) return false;
        if (mRoot != null) removeStaleWindow();
        final int generation = ++mWindowGeneration;
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        final int height = Math.max(dp(132), Math.round(display.height() * .253f));
        final FrameLayout root = new FrameLayout(mContext);
        // Keep the trusted 2052 Surface alive while OneStep is hidden. Host registration
        // requires both trusted Surfaces, so GONE here creates a permanent bootstrap deadlock:
        // no Surface -> no host -> no enter request that could make the window visible.
        root.setVisibility(View.VISIBLE);
        root.setAlpha(0f);
        root.setBackground(createBackgroundDrawable(new Rect(0, 0, display.width(), height)));
        mRoot = root;
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (mRoot != root || generation != mWindowGeneration) return;
                mAttached = true;
                notifyWindowStateChanged();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (mRoot != root || generation != mWindowGeneration) return;
                mAttached = false;
                notifyWindowStateChanged();
                mHandler.postDelayed(() -> {
                    if (mRoot != root || generation != mWindowGeneration
                            || root.isAttachedToWindow()) return;
                    removeStaleWindow();
                    ensureWindow();
                }, 100);
            }
        });
        rebuildContent();

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(1, display.width()), height,
                WindowManager.LayoutParams.TYPE_SIDEBAR_TOOLS,
                WINDOW_FLAGS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.alpha = 0f;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = display.left;
        params.y = display.top;
        params.setTitle("sidebar_top_area");
        params.setTrustedOverlay();
        LayoutParamsSmt.getInstance().add_smartisanPrivateFlag(params,
                LayoutParamsSmt.SM_PRIVATE_FLAG_FIXED_ROTATION);
        LayoutParamsSmt.getInstance().add_smartisanPrivateFlag(params,
                LayoutParamsSmt.SM_PRIVATE_FLAG_SIDEBAR_TOP);
        mLayoutParams = params;
        try {
            mWindowManager.addView(root, params);
            mWindowAdded = true;
            mAttached = root.isAttachedToWindow();
            if (!mAttached) {
                mHandler.postDelayed(() -> {
                    if (mRoot != root || generation != mWindowGeneration || mAttached
                            || root.isAttachedToWindow()) return;
                    Log.w(TAG, "Timed out waiting for OneStep top area attach; rebuilding");
                    removeStaleWindow();
                    ensureWindow();
                }, 500);
            }
            return mAttached;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to attach trusted OneStep top area", e);
            removeStaleWindow();
            return false;
        }
    }

    boolean isAttached() {
        return mRoot != null && mWindowAdded && mAttached && mRoot.isAttachedToWindow();
    }

    boolean isSurfaceReady() {
        if (!isAttached()) return false;
        final ViewRootImpl viewRoot = mRoot.getViewRootImpl();
        return viewRoot != null && viewRoot.getSurfaceControl() != null
                && viewRoot.getSurfaceControl().isValid();
    }

    void applyState(OneStepPanelSpec spec, int taskCount) {
        if (spec == null) return;
        mTaskCount = taskCount;
        final int nextMode = spec.mode == OneStepPanelSpec.MODE_LEFT
                ? OneStepPanelSpec.MODE_LEFT : OneStepPanelSpec.MODE_RIGHT;
        if (mMode != nextMode) {
            // During a side switch system_server keeps publishing the old mode until WMS commits
            // the final Surface frame. A changed mode here is therefore authoritative and can be
            // applied atomically; another guessed 300 ms delay would visibly lag the factory
            // DisplayArea animation.
            mMode = nextMode;
            if (mRoot != null) rebuildContent();
        }
        if (!ensureWindow()) return;
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        final Rect top = spec.topBounds.isEmpty()
                ? new Rect(display.left, display.top, display.right,
                        display.top + Math.max(dp(132), Math.round(display.height() * .253f)))
                : new Rect(spec.topBounds);
        mLayoutParams.width = Math.max(1, top.width());
        mLayoutParams.height = Math.max(1, top.height());
        mLayoutParams.x = top.left;
        mLayoutParams.y = top.top;
        if (spec.visible) {
            mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mLayoutParams.alpha = 1f;
        } else {
            mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mLayoutParams.alpha = 0f;
        }
        try {
            mWindowManager.updateViewLayout(mRoot, mLayoutParams);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to resize OneStep top area", e);
            removeStaleWindow();
            return;
        }
        mRoot.setBackground(createBackgroundDrawable(top));
        updateStatus();
        if (spec.visible) {
            ++mHideGeneration;
            mVisible = true;
            mRoot.setAlpha(1f);
            mRoot.setTranslationY(0f);
            mRoot.setVisibility(View.VISIBLE);
        } else if (mVisible) {
            cancelPendingModeLayout();
            ++mHideGeneration;
            mVisible = false;
            // The service publishes hidden only after WMS reaches the final full-screen frame.
            // Keep the trusted Surface attached for the next entry, but make the client scene
            // fully transparent and non-touchable after the final WMS frame.
            mRoot.setAlpha(0f);
            maybeOpenSettingsAfterExit();
        } else {
            cancelPendingModeLayout();
            ++mHideGeneration;
            mRoot.setAlpha(0f);
        }
    }

    void updateOngoing(ComponentName component, int uid, int pid, CharSequence text, int state) {
        if (component == null) return;
        final String key = component.flattenToShortString() + ':' + uid + ':' + pid;
        if (state < 0) {
            mOngoing.remove(key);
        } else {
            mOngoing.put(key, new OngoingItem(component, uid, pid, text, state));
        }
        if (mGlobalShareIntent == null) refreshTargets();
    }

    void showGlobalShare(Intent intent) {
        mGlobalShareIntent = intent != null ? new Intent(intent) : null;
        refreshTargets();
    }

    void handleSidebarShareList() {
        mGlobalShareIntent = null;
        refreshTargets();
    }

    void onTaskOperationFinished(long requestId) {
        releaseDragPermission(requestId);
    }

    void onTaskLaunchDispatched(long requestId) {
        releaseDragPermission(requestId);
    }

    void dump(PrintWriter pw) {
        pw.println("  topArea attached=" + isAttached()
                + " surfaceReady=" + isSurfaceReady()
                + " generation=" + mWindowGeneration
                + " visible=" + mVisible
                + " disabled=" + mControllerDisabled
                + " user=" + currentUserId()
                + " pendingDragPermissions=" + mPendingDragPermissions.size());
    }

    void hideImmediately() {
        cancelPendingModeLayout();
        ++mHideGeneration;
        mVisible = false;
        if (mRoot != null) mRoot.setAlpha(0f);
        if (mRoot != null && mLayoutParams != null) {
            mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mLayoutParams.alpha = 0f;
            try {
                mWindowManager.updateViewLayout(mRoot, mLayoutParams);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to hide trusted OneStep top area", e);
            }
        }
    }

    void refreshBackground() {
        if (mRoot == null || mLayoutParams == null) return;
        mRoot.setBackground(createBackgroundDrawable(new Rect(mLayoutParams.x, mLayoutParams.y,
                mLayoutParams.x + mLayoutParams.width, mLayoutParams.y + mLayoutParams.height)));
    }

    private void rebuildContent() {
        if (mRoot == null) return;
        mRoot.removeAllViews();
        final View content = LayoutInflater.from(mContext).inflate(
                R.layout.onestep_top_area, mRoot, false);
        final LinearLayout header = content.requireViewById(R.id.onestep_header);
        final ImageButton previous = createImageButton(R.drawable.onestep_sidebar_task_previous,
                R.string.onestep_previous_task);
        final ImageButton next = createImageButton(R.drawable.onestep_sidebar_task_next,
                R.string.onestep_previous_task);
        previous.setOnClickListener(this::launchPreviousApp);
        next.setOnClickListener(this::launchPreviousApp);
        mStatus = new TextView(mContext);
        mStatus.setTextSize(18);
        mStatus.setTextColor(0x4dffffff);
        mStatus.setTypeface(mStatus.getTypeface(), android.graphics.Typeface.BOLD);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        final ImageButton settings = createImageButton(R.drawable.onestep_sidebar_setting_gear,
                R.string.onestep_settings);
        settings.setOnClickListener(view -> openSettings(settings));
        final ImageButton pin = createImageButton(R.drawable.sos_onestep_pin,
                R.string.onestep_pin_current_task);
        pin.setOnClickListener(view -> pinCurrentTask());
        final ImageButton close = createImageButton(mMode == OneStepPanelSpec.MODE_LEFT
                        ? R.drawable.onestep_sidebar_exit_left
                        : R.drawable.onestep_sidebar_exit_right,
                R.string.onestep_close);
        close.setOnClickListener(view -> requestExit());
        if (mMode == OneStepPanelSpec.MODE_LEFT) {
            addButton(header, close, 6, 0);
            addButton(header, settings, 12, 0);
            addButton(header, pin, 12, 0);
            addTitle(header);
            addButton(header, previous, 12, 0);
            addButton(header, next, 12, 6);
        } else {
            addButton(header, previous, 6, 0);
            addButton(header, next, 12, 0);
            addTitle(header);
            addButton(header, pin, 12, 0);
            addButton(header, settings, 12, 0);
            addButton(header, close, 12, 6);
        }
        mTargetRow = content.requireViewById(R.id.onestep_target_row);
        mRoot.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateStatus();
        refreshTargets();
    }

    private void updateStatus() {
        if (mStatus == null) return;
        mStatus.setText(R.string.onestep_factory_title);
    }

    private void pinCurrentTask() {
        Bundle result;
        if (mContext.getPackageManager().resolveContentProvider(
                SosKeyguardPinProvider.AUTHORITY, 0) == null) {
            Log.i(TAG, "Pin provider unavailable; using in-process R2 store");
            result = SosKeyguardPinProvider.toggleCurrentTaskForSystemUi(
                    mContext, ActivityManager.getCurrentUser());
        } else {
            try {
                result = mContext.getContentResolver().call(
                        Uri.parse("content://com.smartisanos.keyguard.pin.provider"),
                        "pin_current_task", null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Registered pin provider rejected toggle; using R2 store", e);
                result = SosKeyguardPinProvider.toggleCurrentTaskForSystemUi(
                        mContext, ActivityManager.getCurrentUser());
            }
        }
        final String packageName = result != null
                ? result.getString("pinned_package") : null;
        Toast.makeText(mContext,
                TextUtils.isEmpty(packageName)
                        ? R.string.onestep_pin_removed_or_failed
                        : R.string.onestep_pin_added,
                Toast.LENGTH_SHORT).show();
    }

    private void refreshTargets() {
        final LinearLayout row = mTargetRow;
        if (row == null) return;
        row.removeAllViews();
        if (mGlobalShareIntent == null && !mOngoing.isEmpty()) {
            for (OngoingItem item : mOngoing.values()) {
                try {
                    row.addView(createOngoingTarget(item), cellLayoutParams());
                } catch (RuntimeException e) {
                    Log.w(TAG, "Skipping broken ongoing item " + item.component, e);
                }
            }
            if (row.getChildCount() > 0) addGroupDivider(row);
        }
        final List<ResolveInfo> targets = mGlobalShareIntent != null
                ? queryOrderedShareTargets(mGlobalShareIntent) : queryOrderedApps();
        for (ResolveInfo target : targets) {
            if (target.activityInfo == null) continue;
            try {
                row.addView(createTarget(target), cellLayoutParams());
            } catch (RuntimeException e) {
                Log.w(TAG, "Skipping broken target " + target.activityInfo.packageName, e);
            }
            if (row.getChildCount() >= 20 + mOngoing.size() + 1) break;
        }
        if (row.getChildCount() == 0) addEmptyTarget(row);
    }

    private List<ResolveInfo> queryOrderedApps() {
        final ArrayList<ResolveInfo> ordered = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final int userId = currentUserId();
        final Context userContext = currentUserContext(userId);
        final PackageManager userPackageManager = userContext.getPackageManager();
        try (Cursor cursor = userContext.getContentResolver().query(APPS_URI,
                new String[] {"packagename", "componentname"},
                "(deleted IS NULL OR deleted<>1) AND userid=?",
                new String[] {String.valueOf(userId)},
                "weight DESC, _id ASC LIMIT 20")) {
            if (cursor != null) {
                while (cursor.moveToNext() && ordered.size() < 20) {
                    final String packageName = cursor.getString(0);
                    final String componentValue = cursor.getString(1);
                    final ComponentName component = componentFromRow(packageName, componentValue);
                    final ResolveInfo info = resolveLauncherComponent(
                            userPackageManager, packageName, component);
                    if (info == null || info.activityInfo == null) continue;
                    final String key = new ComponentName(info.activityInfo.packageName,
                            info.activityInfo.name).flattenToShortString();
                    if (seen.add(key)) ordered.add(info);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read ordered OneStep apps", e);
        }
        if (!ordered.isEmpty()) return ordered;

        final Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            final List<ResolveInfo> fallback = userPackageManager.queryIntentActivities(
                    query, PackageManager.MATCH_ALL);
            fallback.sort(new ResolveInfo.DisplayNameComparator(userPackageManager));
            for (ResolveInfo info : fallback) {
                if (info.activityInfo == null) continue;
                final String key = new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name).flattenToShortString();
                if (seen.add(key)) ordered.add(info);
                if (ordered.size() == 20) break;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to build fallback OneStep app list", e);
        }
        return ordered;
    }

    private List<ResolveInfo> queryOrderedShareTargets(Intent source) {
        final int userId = currentUserId();
        final Context userContext = currentUserContext(userId);
        final PackageManager userPackageManager = userContext.getPackageManager();
        final Intent query = createShareQueryIntent(source);
        if (query.getType() == null) query.setType("*/*");
        final List<ResolveInfo> resolved;
        try {
            resolved = userPackageManager.queryIntentActivities(query, PackageManager.MATCH_ALL);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve OneStep share targets", e);
            return new ArrayList<>();
        }
        final LinkedHashMap<String, ArrayList<ResolveInfo>> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            byPackage.computeIfAbsent(info.activityInfo.packageName,
                    key -> new ArrayList<>()).add(info);
        }

        final ArrayList<ResolveInfo> ordered = new ArrayList<>();
        final Set<String> seenPackages = new HashSet<>();
        try (Cursor cursor = userContext.getContentResolver().query(RESOLVEINFO_URI,
                new String[] {"packagename", "names"},
                "(deleted IS NULL OR deleted<>1) AND userid=?",
                new String[] {String.valueOf(userId)},
                "weight DESC, _id ASC LIMIT 20")) {
            if (cursor != null) {
                while (cursor.moveToNext() && ordered.size() < 20) {
                    final String packageName = cursor.getString(0);
                    if (TextUtils.isEmpty(packageName) || seenPackages.contains(packageName)) {
                        continue;
                    }
                    final ArrayList<ResolveInfo> packageTargets = byPackage.get(packageName);
                    final ResolveInfo match = chooseResolveInfo(packageTargets, cursor.getString(1));
                    if (match != null) {
                        seenPackages.add(packageName);
                        ordered.add(match);
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read ordered OneStep share targets", e);
        }
        if (!ordered.isEmpty()) return ordered;
        resolved.sort(new ResolveInfo.DisplayNameComparator(userPackageManager));
        for (ResolveInfo info : resolved) {
            if (info.activityInfo != null && seenPackages.add(info.activityInfo.packageName)) {
                ordered.add(info);
            }
            if (ordered.size() == 20) break;
        }
        return ordered;
    }

    private ResolveInfo chooseResolveInfo(List<ResolveInfo> candidates, String names) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (!TextUtils.isEmpty(names)) {
            final Set<String> allowed = new HashSet<>();
            for (String name : names.split("\\|")) allowed.add(name);
            for (ResolveInfo info : candidates) {
                if (info.activityInfo != null && allowed.contains(info.activityInfo.name)) {
                    return info;
                }
            }
        }
        return candidates.get(0);
    }

    private ResolveInfo resolveLauncherComponent(PackageManager packageManager,
            String packageName, ComponentName component) {
        ComponentName resolvedComponent = component;
        if (resolvedComponent == null && !TextUtils.isEmpty(packageName)) {
            final Intent launch = packageManager.getLaunchIntentForPackage(packageName);
            resolvedComponent = launch != null ? launch.getComponent() : null;
        }
        if (resolvedComponent == null) return null;
        try {
            final ActivityInfo activity = packageManager.getActivityInfo(
                    resolvedComponent, PackageManager.MATCH_ALL);
            if (!activity.enabled || !activity.applicationInfo.enabled) return null;
            final ResolveInfo result = new ResolveInfo();
            result.activityInfo = activity;
            return result;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private Intent createShareQueryIntent(Intent source) {
        if (source != null && (ACTION_GENERIC_TOP_SHARE.equals(source.getAction())
                || ACTION_TEXT_BOOM_TARGETS.equals(source.getAction()))) {
            final int itemCount = Math.max(1,
                    source.getIntExtra(EXTRA_SHARE_ITEM_COUNT, 1));
            final String mimeType = source.getStringExtra(EXTRA_SHARE_MIME_TYPE);
            return new Intent(itemCount > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                    .setType(!TextUtils.isEmpty(mimeType) ? mimeType : "*/*");
        }
        return new Intent(source).setComponent(null).setPackage(null);
    }

    private static ComponentName componentFromRow(String packageName, String value) {
        if (TextUtils.isEmpty(value)) return null;
        final ComponentName flattened = ComponentName.unflattenFromString(value);
        if (flattened != null) return flattened;
        if (TextUtils.isEmpty(packageName)) return null;
        final String className = value.charAt(0) == '.' ? packageName + value : value;
        return new ComponentName(packageName, className);
    }

    private View createTarget(ResolveInfo target) {
        final PackageManager userPackageManager = currentUserContext().getPackageManager();
        final FrameLayout root = new FrameLayout(mContext);
        root.setContentDescription(String.valueOf(target.loadLabel(userPackageManager)));
        final ImageView icon = new ImageView(mContext);
        icon.setImageDrawable(target.loadIcon(userPackageManager));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(41), dp(41),
                Gravity.CENTER);
        root.addView(icon, iconParams);
        final ImageView arrow = new ImageView(mContext);
        arrow.setImageResource(mMode == OneStepPanelSpec.MODE_LEFT
                ? R.drawable.onestep_sidebar_drag_in_right
                : R.drawable.onestep_sidebar_drag_in_left);
        arrow.setVisibility(View.INVISIBLE);
        final FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                mMode == OneStepPanelSpec.MODE_LEFT
                        ? Gravity.LEFT | Gravity.CENTER_VERTICAL
                        : Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        root.addView(arrow, arrowParams);
        root.setTag(new TargetViewHolder(icon, arrow));
        root.setOnDragListener((view, event) -> handleDrag(view, event, target));
        root.setOnClickListener(view -> {
            if (mGlobalShareIntent != null) {
                if (isTargetOnlyShareIntent(mGlobalShareIntent)) return;
                launchShareTarget(target, mGlobalShareIntent.getClipData(), mGlobalShareIntent,
                        null);
            } else {
                final Intent launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(new ComponentName(target.activityInfo.packageName,
                                target.activityInfo.name));
                launchInSlot(launch);
            }
        });
        return root;
    }

    private View createOngoingTarget(OngoingItem item) {
        final PackageManager userPackageManager = currentUserContext().getPackageManager();
        final FrameLayout root = new FrameLayout(mContext);
        root.setContentDescription(item.text);
        final ImageView icon = new ImageView(mContext);
        try {
            icon.setImageDrawable(userPackageManager.getActivityInfo(
                    item.component, PackageManager.MATCH_ALL).loadIcon(userPackageManager));
        } catch (PackageManager.NameNotFoundException e) {
            try {
                icon.setImageDrawable(userPackageManager.getApplicationIcon(
                        item.component.getPackageName()));
            } catch (PackageManager.NameNotFoundException ignored) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(icon, new FrameLayout.LayoutParams(dp(41), dp(41), Gravity.CENTER));
        if (item.state > 0) {
            final TextView badge = new TextView(mContext);
            badge.setText(item.state > 99 ? "99+" : String.valueOf(item.state));
            badge.setTextSize(7);
            badge.setTextColor(0x4c000000);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundResource(R.drawable.onestep_ongoing_badge);
            badge.setMinWidth(dp(12));
            badge.setMaxWidth(dp(22));
            final FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(13), Gravity.TOP | Gravity.RIGHT);
            badgeParams.topMargin = dp(2);
            root.addView(badge, badgeParams);
        }
        root.setOnClickListener(view -> {
            final Intent launch = userPackageManager.getLaunchIntentForPackage(
                    item.component.getPackageName());
            if (launch != null) launchInSlot(launch);
        });
        return root;
    }

    private LinearLayout.LayoutParams cellLayoutParams() {
        return new LinearLayout.LayoutParams(dp(45), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void addGroupDivider(LinearLayout row) {
        final View divider = new View(mContext);
        divider.setBackgroundColor(0x33ffffff);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(32));
        params.setMarginStart(dp(5));
        params.setMarginEnd(dp(5));
        row.addView(divider, params);
    }

    private boolean handleDrag(View view, DragEvent event, ResolveInfo target) {
        final TargetViewHolder holder = view.getTag() instanceof TargetViewHolder
                ? (TargetViewHolder) view.getTag() : null;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (event.getClipDescription() == null) return false;
                final Intent probe = new Intent(event.getClipData() != null
                        && event.getClipData().getItemCount() > 1
                        ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                        .setType(SharePayload.commonMimeType(event.getClipDescription()))
                        .setPackage(target.activityInfo.packageName);
                return currentUserContext().getPackageManager().resolveActivity(
                        probe, PackageManager.MATCH_ALL) != null;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (holder != null) {
                    holder.arrow.setVisibility(View.VISIBLE);
                    holder.icon.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start();
                }
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                if (holder != null) {
                    holder.arrow.setVisibility(View.INVISIBLE);
                    holder.icon.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                }
                return true;
            case DragEvent.ACTION_DROP:
                if (holder != null) {
                    holder.arrow.setVisibility(View.INVISIBLE);
                    holder.icon.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                }
                final DragAndDropPermissions permissions = DragAndDropPermissions.obtain(event);
                final boolean taken = permissions != null && permissions.takeTransient();
                launchShareTarget(target, event.getClipData(), null,
                        taken ? permissions : null);
                return true;
            default:
                return true;
        }
    }

    private void launchShareTarget(ResolveInfo target, ClipData clip, Intent source,
            DragAndDropPermissions permissions) {
        final SharePayload payload = SharePayload.create(mContext,
                new ComponentName(target.activityInfo.packageName, target.activityInfo.name),
                clip, source);
        final long requestId = launchInSlot(payload.intent);
        if (permissions != null) {
            if (requestId > 0) {
                retainDragPermission(requestId, permissions);
            } else {
                permissions.release();
            }
        }
        handleSidebarShareList();
    }

    private long launchInSlot(Intent intent) {
        final ISidebarService service = getService();
        if (service == null || intent == null) return -1;
        try {
            return service.requestLaunchOneStepActivity(
                    new Intent(intent), currentUserId(), -1);
        } catch (SecurityException e) {
            disableForSecurityRejection("launch target", e);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to launch OneStep target", e);
        }
        return -1;
    }

    private void launchPreviousApp(View button) {
        if (!button.isEnabled()) return;
        button.setEnabled(false);
        mHandler.postDelayed(() -> {
            if (button.isAttachedToWindow()) button.setEnabled(true);
        }, 600);
        boolean launched = false;
        final ISidebarService service = getService();
        try {
            launched = service != null && service.launchPreviousApp();
        } catch (SecurityException e) {
            disableForSecurityRejection("launch previous app", e);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to launch previous task", e);
        }
        if (!launched) {
            Toast.makeText(mContext, R.string.onestep_no_recent_app,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestExit() {
        final ISidebarService service = getService();
        try {
            if (service != null) service.requestExitSidebarMode();
        } catch (SecurityException e) {
            disableForSecurityRejection("exit OneStep", e);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to exit OneStep", e);
        }
    }

    private void openSettings(ImageButton settings) {
        settings.animate().cancel();
        settings.animate().rotationBy(mMode == OneStepPanelSpec.MODE_RIGHT ? 90f : -90f)
                .setDuration(400).start();
        mOpenSettingsAfterExit = true;
        requestExit();
        // A rejected transition must not make a later unrelated exit open Settings.
        mHandler.postDelayed(() -> {
            if (mVisible) mOpenSettingsAfterExit = false;
        }, 2_000);
    }

    private void maybeOpenSettingsAfterExit() {
        if (!mOpenSettingsAfterExit) return;
        mOpenSettingsAfterExit = false;
        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$OneStepSettingsActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(intent, UserHandle.of(currentUserId()));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to open OneStep settings", e);
        }
    }

    private ImageButton createImageButton(int drawable, int description) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawable);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(mContext.getString(description));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void addButton(LinearLayout header, ImageButton button, int start, int end) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
        params.setMarginStart(dp(start));
        params.setMarginEnd(dp(end));
        header.addView(button, params);
    }

    private void addTitle(LinearLayout header) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1);
        params.setMarginStart(dp(12));
        header.addView(mStatus, params);
    }

    private void addEmptyTarget(LinearLayout row) {
        final TextView empty = new TextView(mContext);
        empty.setText(R.string.onestep_share);
        empty.setTextSize(14);
        empty.setTextColor(0xff756d65);
        empty.setGravity(Gravity.CENTER);
        row.addView(empty, new LinearLayout.LayoutParams(dp(180),
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Drawable createBackgroundDrawable(Rect topBounds) {
        final Bitmap background = mBackgroundProvider != null
                ? mBackgroundProvider.getBackground() : null;
        if (background != null && !background.isRecycled()) {
            return new TopWallpaperDrawable(background, topBounds,
                    mWindowManager.getMaximumWindowMetrics().getBounds());
        }
        return mContext.getDrawable(R.drawable.onestep_sidebar_background);
    }

    private void removeStaleWindow() {
        final FrameLayout root = mRoot;
        final boolean windowAdded = mWindowAdded;
        mRoot = null;
        mLayoutParams = null;
        mTargetRow = null;
        mStatus = null;
        mWindowAdded = false;
        mAttached = false;
        ++mWindowGeneration;
        ++mHideGeneration;
        notifyWindowStateChanged();
        if (root == null || !windowAdded) return;
        try {
            mWindowManager.removeViewImmediate(root);
        } catch (RuntimeException ignored) {
        }
    }

    private void cancelPendingModeLayout() {
        // Side layout changes are committed only by the final authoritative applyState callback.
    }

    private void notifyWindowStateChanged() {
        if (mWindowStateChanged != null) mHandler.post(mWindowStateChanged);
    }

    private static boolean isTargetOnlyShareIntent(Intent intent) {
        return intent != null && (ACTION_GENERIC_TOP_SHARE.equals(intent.getAction())
                || ACTION_TEXT_BOOM_TARGETS.equals(intent.getAction()));
    }

    private int currentUserId() {
        return ActivityManager.getCurrentUser();
    }

    private Context currentUserContext() {
        return currentUserContext(currentUserId());
    }

    private Context currentUserContext(int userId) {
        if (mContext.getUserId() == userId) return mContext;
        try {
            return mContext.createContextAsUser(UserHandle.of(userId), 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to create current-user OneStep context", e);
            return mContext;
        }
    }

    private void retainDragPermission(long requestId, DragAndDropPermissions permissions) {
        releaseDragPermission(requestId);
        final Runnable timeout = () -> releaseDragPermission(requestId);
        mPendingDragPermissions.put(requestId,
                new PendingDragPermission(permissions, timeout));
        mHandler.postDelayed(timeout, 12_000);
    }

    private void releaseDragPermission(long requestId) {
        final PendingDragPermission pending = mPendingDragPermissions.get(requestId);
        if (pending == null) return;
        mPendingDragPermissions.remove(requestId);
        mHandler.removeCallbacks(pending.timeout);
        pending.permissions.release();
    }

    private void releaseAllDragPermissions() {
        for (int i = mPendingDragPermissions.size() - 1; i >= 0; i--) {
            final PendingDragPermission pending = mPendingDragPermissions.valueAt(i);
            mHandler.removeCallbacks(pending.timeout);
            pending.permissions.release();
        }
        mPendingDragPermissions.clear();
    }

    private void disableForSecurityRejection(String operation, SecurityException error) {
        if (mControllerDisabled) return;
        mControllerDisabled = true;
        mVisible = false;
        releaseAllDragPermissions();
        if (mRoot != null) mRoot.setVisibility(View.GONE);
        Log.e(TAG, "OneStep authorization permanently rejected for " + operation, error);
        notifyWindowStateChanged();
    }

    private ISidebarService getService() {
        if (mControllerDisabled) return null;
        return ISidebarService.Stub.asInterface(ServiceManager.getService("sidebar"));
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }

    private static final class TopWallpaperDrawable extends Drawable {
        private final Bitmap mBitmap;
        private final Rect mSource;
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        TopWallpaperDrawable(Bitmap bitmap, Rect top, Rect display) {
            mBitmap = bitmap;
            final float sx = bitmap.getWidth() / (float) Math.max(1, display.width());
            final float sy = bitmap.getHeight() / (float) Math.max(1, display.height());
            mSource = new Rect(Math.round((top.left - display.left) * sx),
                    Math.round((top.top - display.top) * sy),
                    Math.round((top.right - display.left) * sx),
                    Math.round((top.bottom - display.top) * sy));
            mSource.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(mBitmap, mSource, getBounds(), mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class TargetViewHolder {
        final ImageView icon;
        final ImageView arrow;

        TargetViewHolder(ImageView icon, ImageView arrow) {
            this.icon = icon;
            this.arrow = arrow;
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
            this.text = text != null ? text : component.getPackageName();
            this.state = state;
        }
    }

    private static final class PendingDragPermission {
        final DragAndDropPermissions permissions;
        final Runnable timeout;

        PendingDragPermission(DragAndDropPermissions permissions, Runnable timeout) {
            this.permissions = permissions;
            this.timeout = timeout;
        }
    }

    private static final class SharePayload {
        final Intent intent;
        final ArrayList<Uri> grantUris;

        SharePayload(Intent intent, ArrayList<Uri> grantUris) {
            this.intent = intent;
            this.grantUris = grantUris;
        }

        static SharePayload create(Context context, ComponentName target, ClipData clip,
                Intent source) {
            final LinkedHashSet<Uri> streamSet = new LinkedHashSet<>();
            final LinkedHashSet<CharSequence> textSet = new LinkedHashSet<>();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    final ClipData.Item item = clip.getItemAt(i);
                    if (item.getUri() != null) {
                        streamSet.add(item.getUri());
                    } else {
                        final CharSequence text = item.coerceToText(context);
                        if (!TextUtils.isEmpty(text)) textSet.add(text);
                    }
                }
            }
            if (source != null) {
                final Uri stream = source.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                if (stream != null) streamSet.add(stream);
                final ArrayList<Uri> sourceStreams = source.getParcelableArrayListExtra(
                        Intent.EXTRA_STREAM, Uri.class);
                if (sourceStreams != null) streamSet.addAll(sourceStreams);
                final CharSequence sourceText = source.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (!TextUtils.isEmpty(sourceText)) textSet.add(sourceText);
                final ArrayList<CharSequence> sourceTexts =
                        source.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
                if (sourceTexts != null) textSet.addAll(sourceTexts);
            }
            final ArrayList<Uri> streams = new ArrayList<>(streamSet);
            final ArrayList<CharSequence> texts = new ArrayList<>(textSet);
            final boolean multiple = streams.size() > 1 || texts.size() > 1
                    || (clip != null && clip.getItemCount() > 1);
            final Intent intent = source != null ? new Intent(source) : new Intent();
            intent.setAction(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                    .setComponent(target).setPackage(null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (clip != null) intent.setClipData(clip);
            if (intent.getType() == null && clip != null) {
                intent.setType(commonMimeType(clip.getDescription()));
            }
            if (intent.getType() == null) {
                intent.setType(streams.isEmpty() ? "text/plain" : "*/*");
            }
            intent.removeExtra(Intent.EXTRA_STREAM);
            intent.removeExtra(Intent.EXTRA_TEXT);
            if (multiple && !streams.isEmpty()) {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
            } else if (!streams.isEmpty()) {
                intent.putExtra(Intent.EXTRA_STREAM, streams.get(0));
            }
            if (texts.size() > 1) {
                intent.putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, texts);
            } else if (texts.size() == 1) {
                intent.putExtra(Intent.EXTRA_TEXT, texts.get(0));
            }
            return new SharePayload(intent, streams);
        }

        static String commonMimeType(ClipDescription description) {
            if (description == null || description.getMimeTypeCount() == 0) return "text/plain";
            final String first = description.getMimeType(0);
            if (description.getMimeTypeCount() == 1) return first;
            final int slash = first != null ? first.indexOf('/') : -1;
            if (slash <= 0) return "*/*";
            final String major = first.substring(0, slash);
            for (int i = 1; i < description.getMimeTypeCount(); i++) {
                final String type = description.getMimeType(i);
                if (type == null || !type.startsWith(major + "/")) return "*/*";
            }
            return major + "/*";
        }
    }
}
