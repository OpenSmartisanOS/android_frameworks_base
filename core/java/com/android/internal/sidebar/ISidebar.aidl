package com.android.internal.sidebar;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.FooDisplayResultInfo;
import com.android.internal.sidebar.ILauncher;

/** ABI-compatible callback implemented by Sidebar.apk. @hide */
interface ISidebar {
    oneway void onEnterSidebarMode(int mode, int flags);
    oneway void onExitSidebarMode(int flags);
    oneway void resumeSidebar();
    oneway void updateOngoing(in ComponentName component, int uid, int pid,
            CharSequence text, int state);
    oneway void setEnabled(boolean enabled);
    Bundle noticeSidebarIconFloat(ILauncher launcher, in Bundle args);
    oneway void contentWindowOnTouch(int action, in float[] points);
    oneway void fooDisplay(in FooDisplayResultInfo result);
    Bitmap getSidebarBackground();
    oneway void handleSidebarShareList();
    oneway void showGlobalShare(in Intent intent);
    oneway void dismissFooResultDisplay();

    // Source-built OneStep extension. Keep the original 12 callbacks above in ABI order.
    oneway void onOneStepBackgroundChanged();
}
