package com.android.internal.sidebar;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.FooDisplayResultInfo;
import com.android.internal.sidebar.IIdeaPills;
import com.android.internal.sidebar.ILauncher;
import com.android.internal.sidebar.IOneStepTaskHost;
import com.android.internal.sidebar.IOneStepTaskListener;
import com.android.internal.sidebar.ISidebar;
import com.android.internal.sidebar.OneStepTaskInfo;

/** ABI-compatible Smartisan OneStep system service. @hide */
interface ISidebarService {
    oneway void registerSidebar(ISidebar sidebar);
    oneway void resetWindow();
    boolean isInSidebarMode();
    boolean canEnterSidebarMode();
    int getSidebarModeState();
    boolean isFocusedOnSidebar();
    oneway void resetWindowForTemp();
    oneway void requestEnterLastMode();
    oneway void resumeSidebar();
    oneway void updateOngoing(in ComponentName component, int uid, int pid,
            CharSequence text, int state);
    oneway void requestExitSidebarMode();
    oneway void requestEnterSidebarMode(int mode);
    oneway void setEnabled(boolean enabled);
    Bundle noticeSidebarIconFloat(ILauncher launcher, in Bundle args);
    oneway void contentWindowOnTouch(int action, in float[] points);
    oneway void fooDisplay(in FooDisplayResultInfo result);
    oneway void onSidebarBackgroundChanged();
    Bitmap getSidebarBackground();
    oneway void showGlobalShare(in Intent intent);
    oneway void registerIdeaPills(IIdeaPills ideaPills);
    Bundle callIdeaPills(String method, in Bundle extras);
    oneway void handleSidebarShareList();
    oneway void dismissFooResultDisplay();
    oneway void requestEnterSidebarModeWithFrom(int from);
    oneway void requestDockWindow(int mode, boolean animate);

    // Source-built phone OneStep extensions. Keep all legacy methods above in their ABI order.
    void registerOneStepTaskHost(IOneStepTaskHost host);
    void registerOneStepTaskListener(IOneStepTaskListener listener);
    List<OneStepTaskInfo> getOneStepTasks();
    long requestAdoptOneStepTask(int taskId, int preferredSlot, in Rect sourceBounds,
            int source);
    long requestLaunchOneStepActivity(in Intent intent, int userId, int preferredSlot);
    long requestActivateOneStepTask(int taskId);
    long requestRestoreOneStepTask(int taskId, boolean toFront);
    long requestCloseOneStepTask(int taskId);
    oneway void reportOneStepTaskResult(long requestId, int taskId, int result,
            in OneStepTaskInfo info, String message);

    // Original OneStep task-switch/card interactions. Append-only for Binder ABI stability.
    boolean launchPreviousApp();
    long requestAdoptCurrentOneStepTask();
    long requestSwapOneStepTask(int taskId);

    // Privileged SystemUI publishes the original low-resolution blurred wallpaper cache here.
    oneway void updateOneStepBackground(in Bitmap background);
}
