package com.android.internal.sidebar;

import com.android.internal.sidebar.OneStepPanelSpec;
import com.android.internal.sidebar.OneStepTaskInfo;

/** Sidebar application callback for source-built OneStep task state. @hide */
oneway interface IOneStepTaskListener {
    void onOneStepTasksChanged(in OneStepPanelSpec spec, in List<OneStepTaskInfo> tasks,
            long revision);
    void onOneStepTaskOperationResult(long requestId, int taskId, int result,
            String message);
}
