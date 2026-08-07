package com.android.internal.sidebar;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import com.android.internal.sidebar.OneStepPanelSpec;
import com.android.internal.sidebar.OneStepTaskInfo;

/** SystemUI side of the source-built OneStep task embedding protocol. @hide */
oneway interface IOneStepTaskHost {
    void applyState(in OneStepPanelSpec spec, in List<OneStepTaskInfo> tasks, long revision);
    void adoptTask(long requestId, int taskId, int slot, int evictedTaskId,
            in Rect sourceBounds);
    void launchTask(long requestId, in Intent intent, int userId, int slot,
            int evictedTaskId);
    void activateTask(long requestId, int taskId);
    void restoreTask(long requestId, int taskId, boolean toFront);
    void closeTask(long requestId, int taskId);
    void swapTask(long requestId, int promotedTaskId, int replacementTaskId, int slot);

    // SystemUI-owned top-area state. Append-only for Binder ABI stability.
    void updateOngoing(in ComponentName component, int uid, int pid,
            CharSequence text, int state);
    void showGlobalShare(in Intent intent);
    void handleSidebarShareList();
}
