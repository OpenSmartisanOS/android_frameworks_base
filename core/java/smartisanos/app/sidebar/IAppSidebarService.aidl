package smartisanos.app.sidebar;

import android.view.MagnificationSpecSmt;

/** ABI-compatible binder exposed by com.smartisanos.sidebar.SidebarService. @hide */
interface IAppSidebarService {
    oneway void requestZoom(int mode, int reason);
    MagnificationSpecSmt requestGetSidebarData();
}
