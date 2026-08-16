/*
 * Copyright (C) 2026 OpenSmartisanOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.TaskSnapshot
import android.window.TaskSnapshotManager
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.res.R
import com.android.systemui.keyguard.pin.SosKeyguardPinProvider
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import java.util.concurrent.Executor

/** Bridges the original Smartisan pin contract to a security-reviewed static task preview. */
class SosKeyguardPinnedTaskController(
    private val context: Context,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val mainExecutor: Executor,
    private val snapshotExecutor: Executor,
    private val previewLayer: FrameLayout,
    private val listener: Listener,
) {
    data class PinnedTask(
        val taskId: Int,
        val userId: Int,
        val component: ComponentName,
        val packageName: String,
        val label: CharSequence,
        val icon: Drawable,
        val pinnedAt: Long,
    )

    interface Listener {
        fun onPinnedTasksChanged(tasks: List<PinnedTask>)
    }

    private data class StoredRecord(
        val taskId: Int,
        val component: ComponentName,
        val pinnedAt: Long,
    )

    private data class SafePreview(val bitmap: Bitmap, val snapshot: TaskSnapshot) {
        fun release() {
            if (!bitmap.isRecycled) bitmap.recycle()
            snapshot.closeBuffer()
        }
    }

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val activityTaskManager = ActivityTaskManager.getService()
    private val userManager = context.getSystemService(UserManager::class.java)
    private val providerAvailable =
        context.packageManager.resolveContentProvider(AUTHORITY, 0) != null
    private val snapshotManager = TaskSnapshotManager.getInstance()
    private var attached = false
    private var providerObserverRegistered = false
    private var activeTaskId = -1
    private var activePreview: SafePreview? = null
    private var previewGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentTasks = emptyList<PinnedTask>()
    private var layoutMetrics: SosKeyguardLayoutModel.Metrics? = null
    private var activeColorIndex = 0
    private val pinRootView =
        SosR2PinRootView(
            context,
            object : SosR2PinRootView.Callback {
                override fun onTaskSelected(task: PinnedTask, colorIndex: Int) {
                    activeColorIndex = colorIndex
                    showPreview(task)
                }
            },
        )
    private val taskPreviewLayer =
        FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = false
            clipChildren = false
            clipToPadding = false
        }
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    fun attach() {
        if (attached) return
        attached = true
        ensureLayers()
        providerObserverRegistered =
            providerAvailable &&
                runCatching {
                        context.contentResolver.registerContentObserver(CONTENT_URI, false, observer)
                    }
                    .onFailure { Log.w(TAG, "Unable to watch registered pin provider", it) }
                    .isSuccess
        if (!providerAvailable) {
            Log.i(TAG, "Pin provider unavailable; using the in-process R2 store")
        }
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (providerObserverRegistered) {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
            providerObserverRegistered = false
        }
        clearPreview()
        listener.onPinnedTasksChanged(emptyList())
        currentTasks = emptyList()
        pinRootView.setTasks(emptyList())
    }

    fun onUserChanged() {
        clearPreview()
        refresh()
    }

    fun updateLayout(metrics: SosKeyguardLayoutModel.Metrics) {
        layoutMetrics = metrics
        pinRootView.updateLayout(metrics)
        taskPreviewLayer.findViewWithTag<View>(TASK_FRAME_TAG)?.layoutParams =
            taskPreviewLayoutParams()
        taskPreviewLayer.findViewWithTag<View>(CLOSE_BUTTON_TAG)?.layoutParams =
            closeButtonLayoutParams()
        taskPreviewLayer.findViewWithTag<View>(NAIL_BUTTON_TAG)?.layoutParams =
            nailButtonLayoutParams()
    }

    fun unpin(taskId: Int) {
        val userId = ActivityManager.getCurrentUser()
        unpinStoredTask(userId, taskId)
        refresh()
    }

    private fun unpinStoredTask(userId: Int, taskId: Int) {
        val extras =
            Bundle().apply {
                putInt(EXTRA_TASK_ID, taskId)
                putInt(EXTRA_USER_ID, userId)
            }
        val providerCallSucceeded =
            providerAvailable &&
                runCatching {
                        context.contentResolver.call(CONTENT_URI, METHOD_UNPIN_TASK, null, extras)
                    }
                    .onFailure { Log.w(TAG, "Registered pin provider rejected unpin", it) }
                    .getOrNull() != null
        if (!providerCallSucceeded) {
            SosKeyguardPinProvider.unpinTaskForSystemUi(context, userId, taskId)
        }
    }

    fun open(taskId: Int) {
        currentTasks.firstOrNull { it.taskId == taskId }?.let(::showPreview)
    }

    fun closePreview() {
        ++previewGeneration
        clearPreviewContent()
        pinRootView.resetToIndicator()
        previewLayer.visibility = if (currentTasks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPreview(task: PinnedTask) {
        if (!attached || !isTaskStillValid(task)) {
            unpin(task.taskId)
            return
        }
        val generation = ++previewGeneration
        activeTaskId = task.taskId
        ensureLayers()
        previewLayer.visibility = View.VISIBLE
        taskPreviewLayer.removeAllViews()
        taskPreviewLayer.visibility = View.VISIBLE
        taskPreviewLayer.alpha = 1f
        taskPreviewLayer.setBackgroundColor(PREVIEW_BACKGROUND)
        pinRootView.showStack(activeColorIndex)

        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(android.graphics.Color.BLACK)
                contentDescription = context.getString(R.string.sos_delta_pinned_open)
            }
        val taskFrame =
            FrameLayout(context).apply {
                tag = TASK_FRAME_TAG
                foreground = context.getDrawable(R.drawable.pinned_task_outer_frame)
                addView(
                    image,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        taskPreviewLayer.addView(taskFrame, taskPreviewLayoutParams())
        taskPreviewLayer.addView(
            View(context).apply {
                isClickable = true
                contentDescription = context.getString(R.string.sos_delta_pinned_open)
                setOnClickListener { openFullscreen(task) }
            },
            taskPreviewLayoutParams(),
        )
        addPreviewControls(task)
        taskFrame.translationY = TASK_ENTRY_TRANSLATION_DESIGN * (layoutMetrics?.scale ?: 1f)
        taskFrame.alpha = 0f
        taskFrame.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(TASK_ENTRY_ANIM_MS)
            .start()
        loadSafeSnapshot(task, generation, image)
    }

    private fun addPreviewControls(task: PinnedTask) {
        taskPreviewLayer.addView(
            ImageButton(context).apply {
                tag = CLOSE_BUTTON_TAG
                setImageResource(R.drawable.pinned_close_btn)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                contentDescription = context.getString(R.string.sos_delta_pinned_close_preview)
                setOnClickListener { closePreview() }
            },
            closeButtonLayoutParams(),
        )
        taskPreviewLayer.addView(
            ImageButton(context).apply {
                tag = NAIL_BUTTON_TAG
                setImageResource(PINNED_NAIL_DRAWABLES[activeColorIndex % PINNED_NAIL_DRAWABLES.size])
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isSelected = false
                isClickable = true
                contentDescription = context.getString(R.string.sos_delta_pinned_close_preview)
                setOnClickListener {
                    isSelected = false
                    unpin(task.taskId)
                    closePreview()
                }
                postDelayed({ isSelected = true }, NAIL_DOWN_DELAY_MS)
            },
            nailButtonLayoutParams(),
        )
    }

    private fun loadSafeSnapshot(task: PinnedTask, generation: Int, image: ImageView) {
        snapshotExecutor.execute {
            val recent = findRecentTask(task)
            val snapshot =
                if (recent == null) {
                    null
                } else {
                    runCatching {
                            if (recent.isVisible) {
                                snapshotManager.takeTaskSnapshot(task.taskId, true /* updateCache */)
                            } else {
                                snapshotManager.getTaskSnapshot(
                                    task.taskId,
                                    TaskSnapshotManager.RESOLUTION_HIGH,
                                )
                            }
                        }
                        .onFailure { Log.w(TAG, "Unable to load safe snapshot ${task.taskId}", it) }
                        .getOrNull()
                }
            val safeSnapshot = snapshot?.takeIf {
                isSafeTaskSnapshotForComponent(it, task.component)
            }
            val safePreview =
                if (safeSnapshot != null) {
                    runCatching { safeSnapshot.wrapToBitmap() }
                        .onFailure {
                            Log.w(TAG, "Unable to wrap safe snapshot ${task.taskId}", it)
                        }
                        .getOrNull()
                        ?.let { SafePreview(it, safeSnapshot) }
                        ?: run {
                            safeSnapshot.closeBuffer()
                            null
                        }
                } else {
                    snapshot?.closeBuffer()
                    null
                }
            mainExecutor.execute {
                if (
                    !attached ||
                        generation != previewGeneration ||
                        activeTaskId != task.taskId ||
                        !isTaskStillValid(task)
                ) {
                    safePreview?.release()
                    return@execute
                }
                activePreview?.release()
                activePreview = safePreview
                image.setImageBitmap(safePreview?.bitmap)
            }
        }
    }

    private fun openFullscreen(task: PinnedTask) {
        if (!isTaskStillValid(task)) {
            unpin(task.taskId)
            closePreview()
            return
        }
        statusBarKeyguardViewManager.dismissWithAction(
            object : OnDismissAction {
                override fun onDismiss(): Boolean {
                    if (!isTaskStillValid(task)) {
                        unpin(task.taskId)
                        return false
                    }
                    runCatching {
                            activityTaskManager?.startActivityFromRecents(
                                task.taskId,
                                ActivityOptions.makeBasic().toBundle(),
                            )
                        }
                        .onFailure {
                            Log.w(TAG, "Pinned task ${task.taskId} is no longer available", it)
                            unpin(task.taskId)
                        }
                    return false
                }
            },
            null,
            true,
        )
    }

    private fun findRecentTask(task: PinnedTask): ActivityManager.RecentTaskInfo? =
        runCatching {
                activityManager
                    ?.getRecentTasks(MAX_RECENT_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                    ?.firstOrNull { recent ->
                        recent.taskId == task.taskId &&
                            recent.userId == task.userId &&
                            recentComponent(recent) == task.component &&
                            isEligibleRecentTask(recent, task.component)
                    }
            }
            .getOrNull()

    private fun isTaskStillValid(task: PinnedTask): Boolean =
        task.userId == ActivityManager.getCurrentUser() &&
            userManager?.isManagedProfile(task.userId) != true &&
            findRecentTask(task) != null

    private fun isEligibleRecentTask(
        task: ActivityManager.RecentTaskInfo,
        component: ComponentName,
    ): Boolean {
        val packageName = component.packageName
        return task.userId == ActivityManager.getCurrentUser() &&
            task.baseIntent?.hasCategory(Intent.CATEGORY_HOME) != true &&
            packageName != SYSTEMUI_PACKAGE &&
            packageName != SIDEBAR_PACKAGE
    }

    private fun clearPreview() {
        ++previewGeneration
        clearPreviewContent()
        taskPreviewLayer.visibility = View.GONE
        pinRootView.resetToIndicator()
        previewLayer.visibility = View.GONE
    }

    private fun clearPreviewContent() {
        activePreview?.release()
        activePreview = null
        activeTaskId = -1
        taskPreviewLayer.removeAllViews()
        taskPreviewLayer.visibility = View.GONE
        taskPreviewLayer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun ensureLayers() {
        if (pinRootView.parent !== previewLayer) {
            (pinRootView.parent as? ViewGroup)?.removeView(pinRootView)
            previewLayer.addView(
                pinRootView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        if (taskPreviewLayer.parent !== previewLayer) {
            (taskPreviewLayer.parent as? ViewGroup)?.removeView(taskPreviewLayer)
            previewLayer.addView(
                taskPreviewLayer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun taskPreviewLayoutParams(): FrameLayout.LayoutParams {
        val scale = layoutMetrics?.scale ?: 1f
        return FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            .apply {
                leftMargin = (PIN_STACK_LEFT_DESIGN * scale).toInt()
                rightMargin = (PIN_STACK_RIGHT_DESIGN * scale).toInt()
                topMargin = (PIN_STACK_TOP_DESIGN * scale).toInt()
                bottomMargin = (PIN_STACK_BOTTOM_DESIGN * scale).toInt()
            }
    }

    private fun closeButtonLayoutParams(): FrameLayout.LayoutParams {
        val scale = layoutMetrics?.scale ?: 1f
        val size = (PIN_CLOSE_SIZE_DESIGN * scale).toInt().coerceAtLeast(1)
        return FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = (PIN_CLOSE_BOTTOM_DESIGN * scale).toInt() }
    }

    private fun nailButtonLayoutParams(): FrameLayout.LayoutParams {
        val scale = layoutMetrics?.scale ?: 1f
        val size = (PIN_NAIL_SIZE_DESIGN * scale).toInt().coerceAtLeast(1)
        return FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = (PIN_NAIL_TOP_DESIGN * scale).toInt() }
    }

    private fun refresh() {
        if (!attached) return
        val userId = ActivityManager.getCurrentUser()
        if (userManager?.isManagedProfile(userId) == true) {
            currentTasks = emptyList()
            pinRootView.setTasks(emptyList())
            closePreview()
            listener.onPinnedTasksChanged(emptyList())
            return
        }
        val records = readPinnedRecords(userId)
        val recentTasks =
            runCatching {
                    activityManager?.getRecentTasks(
                        MAX_RECENT_TASKS,
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                    )
                }
                .getOrNull()
                .orEmpty()
                .filter { it.userId == userId }
                .associateBy { it.taskId }
        val userContext = runCatching { context.createContextAsUser(UserHandle.of(userId), 0) }
            .getOrDefault(context)
        val packageManager = userContext.packageManager
        val staleTaskIds = mutableListOf<Int>()
        val tasks =
            records.mapNotNull { record ->
                val recent = recentTasks[record.taskId]
                val component = recent?.let(::recentComponent)
                if (
                    recent == null ||
                        component != record.component ||
                        !isEligibleRecentTask(recent, record.component)
                ) {
                    staleTaskIds += record.taskId
                    return@mapNotNull null
                }
                runCatching {
                        val appInfo = packageManager.getApplicationInfo(component.packageName, 0)
                        PinnedTask(
                            record.taskId,
                            userId,
                            component,
                            component.packageName,
                            packageManager.getApplicationLabel(appInfo),
                            packageManager.getApplicationIcon(appInfo),
                            record.pinnedAt,
                        )
                    }
                    .getOrNull()
            }
        currentTasks = tasks
        ensureLayers()
        pinRootView.setTasks(tasks)
        layoutMetrics?.let(pinRootView::updateLayout)
        if (activeTaskId >= 0 && tasks.none { it.taskId == activeTaskId }) closePreview()
        if (activeTaskId < 0) previewLayer.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        listener.onPinnedTasksChanged(tasks)
        // The current UI already excludes these records. Remove them without recursively entering
        // refresh(); a registered provider observer may schedule one coalesced follow-up update.
        staleTaskIds.forEach { unpinStoredTask(userId, it) }
    }

    private fun readPinnedRecords(userId: Int): List<StoredRecord> {
        val extras = Bundle().apply { putInt(EXTRA_USER_ID, userId) }
        val providerResult =
            if (providerAvailable) {
                runCatching {
                        context.contentResolver.call(
                            CONTENT_URI,
                            METHOD_GET_PINNED_TASKS,
                            null,
                            extras,
                        )
                    }
                    .onFailure { Log.w(TAG, "Registered pin provider rejected read", it) }
                    .getOrNull()
            } else {
                null
            }
        val result =
            providerResult
                ?: SosKeyguardPinProvider.getPinnedTasksForSystemUi(context, userId)
        val taskIds = result.getIntArray(EXTRA_TASK_IDS) ?: IntArray(0)
        val components = result.getStringArray(EXTRA_COMPONENTS) ?: emptyArray()
        val pinnedAt = result.getLongArray(EXTRA_PINNED_AT) ?: LongArray(0)
        val size = minOf(taskIds.size, components.size, MAX_PINNED_TASKS)
        return buildList {
            repeat(size) { index ->
                val component = ComponentName.unflattenFromString(components[index])
                if (taskIds[index] >= 0 && component != null) {
                    add(StoredRecord(taskIds[index], component, pinnedAt.getOrElse(index) { 0L }))
                }
            }
        }
    }

    private fun recentComponent(task: ActivityManager.RecentTaskInfo): ComponentName? =
        task.topActivity ?: task.baseIntent?.component

    companion object {
        internal fun isSafeTaskSnapshot(snapshot: TaskSnapshot?): Boolean =
            snapshot != null &&
                isSafeTaskSnapshotProperties(
                    isBufferValid = snapshot.isBufferValid,
                    isRealSnapshot = snapshot.isRealSnapshot,
                    hasProtectedContent = snapshot.hasProtectedContent(),
                )

        internal fun isSafeTaskSnapshotForComponent(
            snapshot: TaskSnapshot?,
            expectedComponent: ComponentName,
        ): Boolean =
            isSafeTaskSnapshot(snapshot) &&
                isSafeTaskSnapshotIdentity(
                    expectedComponent = expectedComponent,
                    snapshotComponent = snapshot?.topActivityComponent,
                )

        internal fun isSafeTaskSnapshotProperties(
            isBufferValid: Boolean,
            isRealSnapshot: Boolean,
            hasProtectedContent: Boolean,
        ): Boolean = isBufferValid && isRealSnapshot && !hasProtectedContent

        internal fun isSafeTaskSnapshotIdentity(
            expectedComponent: ComponentName?,
            snapshotComponent: ComponentName?,
        ): Boolean = expectedComponent != null && expectedComponent == snapshotComponent

        private const val TAG = "SosPinnedTasks"
        private const val AUTHORITY = "com.smartisanos.keyguard.pin.provider"
        private const val METHOD_UNPIN_TASK = "unpin_task"
        private const val METHOD_GET_PINNED_TASKS = "get_pinned_tasks_for_user"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_TASK_IDS = "task_ids"
        private const val EXTRA_COMPONENTS = "components"
        private const val EXTRA_PINNED_AT = "pinned_at"
        private const val MAX_PINNED_TASKS = 5
        private const val MAX_RECENT_TASKS = 100
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SIDEBAR_PACKAGE = "com.smartisanos.sidebar"
        private const val PREVIEW_BACKGROUND = 0xE6000000.toInt()
        private const val PIN_STACK_LEFT_DESIGN = 81f
        private const val PIN_STACK_TOP_DESIGN = 216f
        private const val PIN_STACK_RIGHT_DESIGN = 81f
        private const val PIN_STACK_BOTTOM_DESIGN = 135f
        private const val PIN_CLOSE_SIZE_DESIGN = 90f
        private const val PIN_CLOSE_BOTTOM_DESIGN = 24f
        private const val PIN_NAIL_SIZE_DESIGN = 160f
        private const val PIN_NAIL_TOP_DESIGN = 136f
        private const val TASK_ENTRY_TRANSLATION_DESIGN = 204f
        private const val TASK_ENTRY_ANIM_MS = 400L
        private const val NAIL_DOWN_DELAY_MS = 30L
        private const val TASK_FRAME_TAG = "sos_r2_pin_task_frame"
        private const val CLOSE_BUTTON_TAG = "sos_r2_pin_close"
        private const val NAIL_BUTTON_TAG = "sos_r2_pin_nail"
        private val PINNED_NAIL_DRAWABLES =
            intArrayOf(
                R.drawable.pinned_nail_0,
                R.drawable.pinned_nail_1,
                R.drawable.pinned_nail_2,
                R.drawable.pinned_nail_3,
                R.drawable.pinned_nail_4,
                R.drawable.pinned_nail_5,
                R.drawable.pinned_nail_6,
            )
        private val CONTENT_URI = Uri.parse("content://$AUTHORITY")
    }
}
