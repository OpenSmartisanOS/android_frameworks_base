/*
 * Copyright (C) 2026 OpenSmartisanOS
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

package com.android.systemui.keyguard.pin;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android 16 implementation of the public pin contract shipped by the R2 Keyguard APK. */
public final class SosKeyguardPinProvider extends ContentProvider {
    public static final String AUTHORITY = "com.smartisanos.keyguard.pin.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_PIN_CURRENT_TASK = "pin_current_task";
    public static final String METHOD_UNPIN_TASK = "unpin_task";
    /** SystemUI-only extension. The original query contract remains available to Sidebar. */
    public static final String METHOD_GET_PINNED_TASKS = "get_pinned_tasks_for_user";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_TASK_IDS = "task_ids";
    public static final String EXTRA_COMPONENTS = "components";
    public static final String EXTRA_PINNED_AT = "pinned_at";
    public static final String EXTRA_PINNED_PACKAGE = "pinned_package";
    private static final String PERMISSION_ACCESS_CALL =
            "com.smartisanos.keyguard.ACCESS_CALL";

    private static final String TAG = "SosKeyguardPin";
    private static final String PREFS = "sos_keyguard_pinned_tasks";
    private static final String KEY_LEGACY_TASK_IDS = "task_ids";
    private static final String KEY_LEGACY_MIGRATED = "legacy_migrated_v2";
    private static final String KEY_RECORDS_PREFIX = "records_v2_u";
    private static final int STORE_VERSION = 2;
    private static final int MAX_PINNED_TASKS = 5;
    private static final int MAX_RECENT_TASKS = 100;
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String SIDEBAR_PACKAGE = "com.smartisanos.sidebar";

    private Context mContext;
    private SharedPreferences mPreferences;

    private static final class PinnedRecord {
        final int userId;
        final int taskId;
        final ComponentName component;
        final long pinnedAt;

        PinnedRecord(int userId, int taskId, ComponentName component, long pinnedAt) {
            this.userId = userId;
            this.taskId = taskId;
            this.component = component;
            this.pinnedAt = pinnedAt;
        }
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        return context != null && initialize(context);
    }

    private boolean initialize(Context context) {
        mContext = context.createDeviceProtectedStorageContext();
        mPreferences = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return true;
    }

    /**
     * Late-bound SystemUI APKs are installed after PackageManager's provider scan. Keep the exact
     * same store available in-process so the existing v11 runtime can exercise PinRoot without
     * changing its verified mount order. Full ROM builds continue to use the public authority.
     */
    public static Bundle toggleCurrentTaskForSystemUi(Context context, int userId) {
        final SosKeyguardPinProvider provider = new SosKeyguardPinProvider();
        return provider.initialize(context) ? provider.toggleCurrentTask(userId) : Bundle.EMPTY;
    }

    public static void unpinTaskForSystemUi(Context context, int userId, int taskId) {
        final SosKeyguardPinProvider provider = new SosKeyguardPinProvider();
        if (provider.initialize(context) && taskId >= 0) {
            provider.removeTask(userId, taskId);
        }
    }

    public static Bundle getPinnedTasksForSystemUi(Context context, int userId) {
        final SosKeyguardPinProvider provider = new SosKeyguardPinProvider();
        return provider.initialize(context)
                ? recordsToBundle(provider.readRecords(userId)) : Bundle.EMPTY;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (isPinContractMethod(method)) {
            enforcePinContractPermission();
        }
        if (METHOD_PIN_CURRENT_TASK.equals(method)) {
            return toggleCurrentTask(resolveCallingUserId(extras, false));
        }
        if (METHOD_UNPIN_TASK.equals(method)) {
            final int userId = resolveCallingUserId(extras, true);
            final int taskId = extras != null ? extras.getInt(EXTRA_TASK_ID, -1) : -1;
            if (taskId >= 0) removeTask(userId, taskId);
            return Bundle.EMPTY;
        }
        if (METHOD_GET_PINNED_TASKS.equals(method)) {
            if (!isSystemUiCaller()) {
                throw new SecurityException("Only SystemUI may enumerate another user's pins");
            }
            return recordsToBundle(readRecords(resolveCallingUserId(extras, true)));
        }
        return super.call(method, arg, extras);
    }

    private static boolean isPinContractMethod(String method) {
        return METHOD_PIN_CURRENT_TASK.equals(method)
                || METHOD_UNPIN_TASK.equals(method)
                || METHOD_GET_PINNED_TASKS.equals(method);
    }

    private void enforcePinContractPermission() {
        if (isSystemUiCaller()) return;
        final boolean permissionGranted = mContext.checkCallingPermission(PERMISSION_ACCESS_CALL)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!isPinContractCallerAllowed(Binder.getCallingUid(), Process.myUid(),
                permissionGranted)) {
            throw new SecurityException("Caller must hold " + PERMISSION_ACCESS_CALL);
        }
    }

    private int resolveCallingUserId(Bundle extras, boolean allowSystemOverride) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final boolean hasRequestedUser = extras != null && extras.containsKey(EXTRA_USER_ID);
        final int requestedUserId = hasRequestedUser
                ? extras.getInt(EXTRA_USER_ID, callingUserId) : callingUserId;
        return resolveUserIdForCaller(callingUid, Process.myUid(), callingUserId,
                requestedUserId, hasRequestedUser, allowSystemOverride);
    }

    private static boolean isSystemUiCaller() {
        // SystemUI uses android.uid.systemui, not android.uid.system. Comparing against
        // SYSTEM_UID silently breaks cross-user reads/removals on production builds. Since this
        // provider is hosted by SystemUI, UID equality is the narrow identity check we need here.
        return isSystemUiUid(Binder.getCallingUid(), Process.myUid());
    }

    static boolean isSystemUiUid(int callingUid, int hostUid) {
        return callingUid == hostUid;
    }

    static boolean isPinContractCallerAllowed(int callingUid, int hostUid,
            boolean permissionGranted) {
        return isSystemUiUid(callingUid, hostUid) || permissionGranted;
    }

    static int resolveUserIdForCaller(int callingUid, int hostUid, int callingUserId,
            int requestedUserId, boolean hasRequestedUser, boolean allowSystemOverride) {
        return allowSystemOverride && hasRequestedUser && isSystemUiUid(callingUid, hostUid)
                ? requestedUserId : callingUserId;
    }

    private Bundle toggleCurrentTask(int userId) {
        if (!isEligibleUser(userId)) return Bundle.EMPTY;
        final ActivityManager.RunningTaskInfo task = findForegroundTask(userId);
        if (task == null || task.taskId < 0) return Bundle.EMPTY;
        final ComponentName component = task.topActivity != null
                ? task.topActivity : task.baseActivity;
        if (component == null) return Bundle.EMPTY;

        final ArrayList<PinnedRecord> records = readRecords(userId);
        for (int i = records.size() - 1; i >= 0; i--) {
            final PinnedRecord record = records.get(i);
            if (record.taskId == task.taskId && record.component.equals(component)) {
                records.remove(i);
                writeRecords(userId, records);
                notifyChanged();
                return Bundle.EMPTY;
            }
            if (record.taskId == task.taskId) {
                // The system may reuse a task id after the old task has disappeared. Remove the
                // stale identity, then pin the newly validated component instead of toggling it.
                records.remove(i);
            }
        }
        if (records.size() >= MAX_PINNED_TASKS) {
            Log.w(TAG, "Pinned task limit reached for user=" + userId);
            return Bundle.EMPTY;
        }

        records.add(new PinnedRecord(userId, task.taskId, component,
                System.currentTimeMillis()));
        writeRecords(userId, records);
        notifyChanged();
        final Bundle result = new Bundle();
        result.putString(EXTRA_PINNED_PACKAGE, component.getPackageName());
        return result;
    }

    private boolean isEligibleUser(int userId) {
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        return userId >= 0 && userId == ActivityManager.getCurrentUser()
                && (userManager == null || !userManager.isManagedProfile(userId));
    }

    private ActivityManager.RunningTaskInfo findForegroundTask(int userId) {
        final ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        if (activityManager == null) return null;
        try {
            for (ActivityManager.RunningTaskInfo task : activityManager.getRunningTasks(12)) {
                final ComponentName component = task.topActivity != null
                        ? task.topActivity : task.baseActivity;
                final String packageName = component != null ? component.getPackageName() : null;
                if (task.userId != userId || TextUtils.isEmpty(packageName)
                        || SYSTEMUI_PACKAGE.equals(packageName)
                        || SIDEBAR_PACKAGE.equals(packageName)
                        || isHomeTask(task)) {
                    continue;
                }
                return task;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to inspect foreground task for user=" + userId, e);
        }
        return null;
    }

    private static boolean isHomeTask(ActivityManager.RunningTaskInfo task) {
        final Intent baseIntent = task.baseIntent;
        return baseIntent != null && baseIntent.hasCategory(Intent.CATEGORY_HOME);
    }

    private void removeTask(int userId, int taskId) {
        final ArrayList<PinnedRecord> records = readRecords(userId);
        if (records.removeIf(record -> record.taskId == taskId)) {
            writeRecords(userId, records);
            notifyChanged();
        }
    }

    private ArrayList<PinnedRecord> readRecords(int userId) {
        migrateLegacyForSystemUserIfNeeded(userId);
        final ArrayList<PinnedRecord> result = new ArrayList<>();
        final Set<Integer> seenTaskIds = new HashSet<>();
        final String serialized = mPreferences.getString(KEY_RECORDS_PREFIX + userId, "");
        if (TextUtils.isEmpty(serialized)) return result;
        try {
            final JSONObject root = new JSONObject(serialized);
            if (root.optInt("version", -1) != STORE_VERSION
                    || root.optInt("userId", UserHandle.USER_NULL) != userId) {
                return result;
            }
            final JSONArray records = root.optJSONArray("records");
            if (records == null) return result;
            for (int i = 0; i < records.length() && result.size() < MAX_PINNED_TASKS; i++) {
                final JSONObject item = records.optJSONObject(i);
                if (item == null) continue;
                final int taskId = item.optInt("taskId", -1);
                final ComponentName component = ComponentName.unflattenFromString(
                        item.optString("component", ""));
                if (taskId < 0 || component == null || !seenTaskIds.add(taskId)) continue;
                result.add(new PinnedRecord(userId, taskId, component,
                        item.optLong("pinnedAt", 0L)));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring corrupt pinned task store for user=" + userId, e);
        }
        return result;
    }

    private void writeRecords(int userId, List<PinnedRecord> records) {
        final JSONArray items = new JSONArray();
        for (PinnedRecord record : records) {
            if (record.userId != userId || record.taskId < 0 || record.component == null
                    || items.length() >= MAX_PINNED_TASKS) {
                continue;
            }
            final JSONObject item = new JSONObject();
            try {
                item.put("taskId", record.taskId);
                item.put("component", record.component.flattenToString());
                item.put("pinnedAt", record.pinnedAt);
                items.put(item);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        final JSONObject root = new JSONObject();
        try {
            root.put("version", STORE_VERSION);
            root.put("userId", userId);
            root.put("records", items);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        mPreferences.edit().putString(KEY_RECORDS_PREFIX + userId, root.toString()).apply();
    }

    private void migrateLegacyForSystemUserIfNeeded(int userId) {
        if (userId != UserHandle.USER_SYSTEM
                || mPreferences.getBoolean(KEY_LEGACY_MIGRATED, false)) {
            return;
        }
        final ArrayList<PinnedRecord> migrated = new ArrayList<>();
        final String legacy = mPreferences.getString(KEY_LEGACY_TASK_IDS, "");
        final ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        final List<ActivityManager.RecentTaskInfo> recentTasks;
        try {
            recentTasks = activityManager != null
                    ? activityManager.getRecentTasks(MAX_RECENT_TASKS,
                            ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                    : Collections.emptyList();
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to validate legacy pinned tasks", e);
            return;
        }
        if (!TextUtils.isEmpty(legacy)) {
            for (String value : legacy.split(",")) {
                if (migrated.size() >= MAX_PINNED_TASKS) break;
                try {
                    final int taskId = Integer.parseInt(value);
                    for (ActivityManager.RecentTaskInfo task : recentTasks) {
                        final ComponentName component = task.topActivity != null
                                ? task.topActivity : task.baseIntent != null
                                        ? task.baseIntent.getComponent() : null;
                        if (task.taskId == taskId && task.userId == UserHandle.USER_SYSTEM
                                && component != null) {
                            migrated.add(new PinnedRecord(UserHandle.USER_SYSTEM, taskId,
                                    component, 0L));
                            break;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        writeRecords(UserHandle.USER_SYSTEM, migrated);
        mPreferences.edit().remove(KEY_LEGACY_TASK_IDS)
                .putBoolean(KEY_LEGACY_MIGRATED, true).apply();
    }

    private static Bundle recordsToBundle(List<PinnedRecord> records) {
        final int[] taskIds = new int[records.size()];
        final String[] components = new String[records.size()];
        final long[] pinnedAt = new long[records.size()];
        for (int i = 0; i < records.size(); i++) {
            final PinnedRecord record = records.get(i);
            taskIds[i] = record.taskId;
            components[i] = record.component.flattenToString();
            pinnedAt[i] = record.pinnedAt;
        }
        final Bundle bundle = new Bundle();
        bundle.putIntArray(EXTRA_TASK_IDS, taskIds);
        bundle.putStringArray(EXTRA_COMPONENTS, components);
        bundle.putLongArray(EXTRA_PINNED_AT, pinnedAt);
        return bundle;
    }

    private void notifyChanged() {
        try {
            mContext.getContentResolver().notifyChange(CONTENT_URI, null);
        } catch (RuntimeException e) {
            // A late bind-mounted SystemUI is not re-scanned by PackageManager. The in-process
            // compatibility entry points still use this exact store, but no provider observer is
            // registered in that boot. Persisting the record is sufficient in that configuration.
            Log.i(TAG, "Pin provider is not registered; record was saved in-process");
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        final int userId = resolveCallingUserId(null, false);
        final MatrixCursor cursor = new MatrixCursor(new String[] {EXTRA_TASK_ID});
        for (PinnedRecord record : readRecords(userId)) {
            cursor.addRow(new Object[] {record.taskId});
        }
        cursor.setNotificationUri(mContext.getContentResolver(), CONTENT_URI);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.smartisan.keyguard.pinned-task";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
