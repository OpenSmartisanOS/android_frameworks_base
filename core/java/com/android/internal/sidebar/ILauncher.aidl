package com.android.internal.sidebar;

/** ABI-compatible Smartisan launcher callback. @hide */
interface ILauncher {
    oneway void setIconVisible(boolean visible);
    oneway void setLaunchPadVisible(boolean visible, boolean animate);
    boolean isLaunchPadVisible();
    oneway void notifyUpdateLaunchPadStatus();
    boolean isLaunchpadAlive();
    oneway void launchShortcut(String packageName, String className, int userId);
}
