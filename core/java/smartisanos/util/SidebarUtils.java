/*
 * Copyright (C) 2026 The Open Smartisan OS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package smartisanos.util;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.View;

import com.android.internal.sidebar.ILauncher;
import com.android.internal.sidebar.ISidebarService;

import java.io.File;

/** Smartisan OneStep compatibility and standard Android drag-and-drop bridge. @hide */
public class SidebarUtils {
    public static final String FORCE_TOUCH_SECTOR_MENU_DRAG = "force_touch_sector_menu_drag";
    public static final String GLOBAL_DRAG_TYPE = "global_drag_type";
    public static final int TEXT_GLOBALSHARE_TYPE = 1;
    public static final int IMAGE_GLOBALSHARE_TYPE = 2;
    public static final int OCR_GLOBALSHARE_TYPE = 3;
    public static final int SMT_DRAG_FLAG_GLOBAL = 2048;

    private static final String KEY_TO_NOTES = "com.smartisanos.onestep.key.TO_NOTES";
    private static final int STANDARD_GLOBAL_DRAG_FLAGS = View.DRAG_FLAG_GLOBAL
            | View.DRAG_FLAG_GLOBAL_URI_READ;

    public SidebarUtils() {}

    static abstract class BaseDragBuilder<T extends BaseDragBuilder<T>> {
        protected final View view;
        protected Context context;
        protected Bundle extras;
        protected int flags;
        protected Object localState;
        protected boolean withGlobalShare;

        public BaseDragBuilder(View view, Context context) {
            this.view = view;
            this.context = context;
        }

        public T extras(Bundle value) {
            extras = value;
            return self();
        }

        public T flags(int value) {
            flags = value;
            return self();
        }

        public T localState(Object value) {
            localState = value;
            return self();
        }

        public T withGlobalShare(boolean value) {
            withGlobalShare = value;
            return self();
        }

        abstract T self();
        abstract void startDrag();
    }

    public static class ImageDragBuilder extends BaseDragBuilder<ImageDragBuilder> {
        private Bitmap contentBitmap;
        private File file;
        private String mimeType;
        private boolean showAnim;

        public ImageDragBuilder(View view, Context context) {
            super(view, context);
        }

        public ImageDragBuilder contentBitmap(Bitmap value) {
            contentBitmap = value;
            return this;
        }

        public ImageDragBuilder file(File value) {
            file = value;
            return this;
        }

        public ImageDragBuilder mimeType(String value) {
            mimeType = value;
            return this;
        }

        public ImageDragBuilder showAnim(boolean value) {
            showAnim = value;
            return this;
        }

        @Override ImageDragBuilder self() {
            return this;
        }

        @Override public void startDrag() {
            dragImage(this);
        }
    }

    public static class TextDragBuilder extends BaseDragBuilder<TextDragBuilder> {
        private ClipData clipData;
        private CharSequence message;
        private int shadowBgRes;
        private CharSequence shadowText;
        private int shadowTextColorRes;
        private boolean showTrash;
        private CharSequence text;
        private CharSequence title;

        public TextDragBuilder(View view, Context context) {
            super(view, context);
        }

        public TextDragBuilder clipData(ClipData value) {
            clipData = value;
            return this;
        }

        public TextDragBuilder message(CharSequence value) {
            message = value;
            return this;
        }

        public TextDragBuilder shadowBgRes(int value) {
            shadowBgRes = value;
            return this;
        }

        public TextDragBuilder shadowText(CharSequence value) {
            shadowText = value;
            return this;
        }

        public TextDragBuilder shadowTextColorRes(int value) {
            shadowTextColorRes = value;
            return this;
        }

        public TextDragBuilder showTrash(boolean value) {
            showTrash = value;
            return this;
        }

        public TextDragBuilder text(CharSequence value) {
            text = value;
            return this;
        }

        public TextDragBuilder title(CharSequence value) {
            title = value;
            return this;
        }

        @Override TextDragBuilder self() {
            return this;
        }

        @Override public void startDrag() {
            dragText(this);
        }
    }

    @Deprecated
    public static boolean canEnterSidebarMode(Context context) {
        final ISidebarService service = getSidebarService();
        if (service == null) return false;
        try {
            return service.canEnterSidebarMode();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Deprecated
    public static void dismissFooResultDisplay() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.dismissFooResultDisplay();
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void dispatchTouchEventToSidebar(int action, float[] points) {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.contentWindowOnTouch(action, points);
        } catch (RemoteException ignored) {
        }
    }

    public static void dragEmail(View view, Context context, File file, String mimeType,
            Bitmap background, Bitmap content, String toNotes) {
        final Bundle extras = new Bundle();
        extras.putString(KEY_TO_NOTES, toNotes);
        dragFile(view, context, file, mimeType, background, content, null, extras);
    }

    public static void dragFile(View view, Context context, File file, String mimeType) {
        dragFile(view, context, file, mimeType, file != null ? file.getName() : "");
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            Bitmap content, Bitmap icon) {
        dragFile(view, context, file, mimeType, null, content, icon);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            Bitmap background, Bitmap content, Bitmap icon) {
        dragFile(view, context, file, mimeType, background, content, icon, null);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            Bitmap background, Bitmap content, Bitmap icon, Bundle extras) {
        dragFile(view, context, file, mimeType, background, content, icon, extras, 0);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            Bitmap background, Bitmap content, Bitmap icon, Bundle extras, int flags) {
        dragFile(view, context, file, mimeType, background, content, icon, extras, flags, true);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            Bitmap background, Bitmap content, Bitmap icon, Bundle extras, int flags,
            boolean showAnim) {
        startFileDrag(view, file, mimeType, extras, flags, null, false);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            String displayName) {
        dragFile(view, context, file, mimeType, displayName, null);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            String displayName, Bundle extras) {
        dragFile(view, context, file, mimeType, displayName, extras, true);
    }

    public static void dragFile(View view, Context context, File file, String mimeType,
            String displayName, Bundle extras, boolean showAnim) {
        startFileDrag(view, file, mimeType, extras, 0, null, false);
    }

    public static void dragImage(View view, Context context, Bitmap bitmap, File file,
            String mimeType) {
        dragImage(view, context, bitmap, file, mimeType, 0, null, false);
    }

    public static void dragImage(View view, Context context, Bitmap bitmap, File file,
            String mimeType, int flags, Bundle extras, boolean withGlobalShare) {
        dragImage(view, context, bitmap, file, mimeType, flags, extras, withGlobalShare, true);
    }

    public static void dragImage(View view, Context context, Bitmap bitmap, File file,
            String mimeType, int flags, Bundle extras, boolean withGlobalShare,
            boolean showAnim) {
        new ImageDragBuilder(view, context).contentBitmap(bitmap).file(file).mimeType(mimeType)
                .flags(flags).extras(extras).withGlobalShare(withGlobalShare).showAnim(showAnim)
                .startDrag();
    }

    public static void dragImage(View view, Context context, Bitmap bitmap, File file,
            String mimeType, Bundle extras, boolean withGlobalShare) {
        dragImage(view, context, bitmap, file, mimeType, 0, extras, withGlobalShare);
    }

    public static void dragImage(View view, Context context, Bitmap bitmap, File file,
            String mimeType, boolean withGlobalShare) {
        dragImage(view, context, bitmap, file, mimeType, 0, null, withGlobalShare);
    }

    public static void dragImage(View view, Context context, Bitmap bitmap, File file,
            String mimeType, boolean withGlobalShare, boolean showAnim) {
        dragImage(view, context, bitmap, file, mimeType, 0, null, withGlobalShare, showAnim);
    }

    public static void dragImage(View view, Context context, File file, String mimeType) {
        dragImage(view, context, null, file, mimeType);
    }

    public static void dragImage(View view, Context context, File file, String mimeType,
            boolean withGlobalShare) {
        dragImage(view, context, null, file, mimeType, withGlobalShare);
    }

    public static void dragImage(View view, Context context, File file, String mimeType,
            boolean withGlobalShare, boolean showAnim) {
        dragImage(view, context, null, file, mimeType, withGlobalShare, showAnim);
    }

    public static void dragImage(ImageDragBuilder builder) {
        startFileDrag(builder.view, builder.file,
                TextUtils.isEmpty(builder.mimeType) ? "image/*" : builder.mimeType,
                builder.extras, builder.flags, builder.localState, builder.withGlobalShare);
    }

    public static void dragLink(View view, Context context, CharSequence link) {
        dragText(view, context, link, null);
    }

    public static void dragLink(View view, Context context, CharSequence link,
            Bitmap background, Bitmap content) {
        dragLink(view, context, link);
    }

    public static void dragLink(View view, Context context, CharSequence link,
            Bitmap background, Bitmap content, Bitmap icon) {
        dragLink(view, context, link);
    }

    public static void dragMultipleFile(View view, Context context, File[] files,
            String[] mimeTypes, Bundle extras) {
        dragMultipleFile(view, context, files, mimeTypes, extras, false);
    }

    public static void dragMultipleFile(View view, Context context, File[] files,
            String[] mimeTypes, Bundle extras, boolean withGlobalShare) {
        startMultipleFileDrag(view, files, mimeTypes, extras, 0, withGlobalShare);
    }

    public static void dragMultipleImage(View view, Context context, int selectedIndex,
            File[] files, String[] mimeTypes) {
        dragMultipleImage(view, context, selectedIndex, files, mimeTypes, 0);
    }

    public static void dragMultipleImage(View view, Context context, int selectedIndex,
            File[] files, String[] mimeTypes, int flags) {
        dragMultipleImage(view, context, selectedIndex, files, mimeTypes, flags, 0);
    }

    public static void dragMultipleImage(View view, Context context, int selectedIndex,
            File[] files, String[] mimeTypes, int flags, int fileCount) {
        dragMultipleImage(view, context, selectedIndex, files, mimeTypes, flags, fileCount, false);
    }

    public static void dragMultipleImage(View view, Context context, int selectedIndex,
            File[] files, String[] mimeTypes, int flags, int fileCount,
            boolean withGlobalShare) {
        startMultipleFileDrag(view, files, mimeTypes, null, flags, withGlobalShare);
    }

    public static void dragMultipleImage(View view, Context context, File[] files,
            String[] mimeTypes) {
        dragMultipleImage(view, context, 0, files, mimeTypes);
    }

    public static void dragText(View view, Context context, CharSequence text) {
        dragText(view, context, text, (Object) null);
    }

    public static void dragText(View view, Context context, CharSequence text,
            Bitmap content, Bitmap icon) {
        dragText(view, context, text, null, content, icon, false);
    }

    public static void dragText(View view, Context context, CharSequence text,
            Bitmap background, Bitmap content, Bitmap icon) {
        dragText(view, context, text, background, content, icon, false);
    }

    public static void dragText(View view, Context context, CharSequence text,
            Bitmap background, Bitmap content, Bitmap icon, boolean withGlobalShare) {
        dragText(view, context, text, null, false, null, withGlobalShare);
    }

    public static void dragText(View view, Context context, CharSequence text,
            Bitmap content, Bitmap icon, boolean withGlobalShare) {
        dragText(view, context, text, null, false, null, withGlobalShare);
    }

    public static void dragText(View view, Context context, CharSequence text, Object localState) {
        dragText(view, context, text, localState, false);
    }

    public static void dragText(View view, Context context, CharSequence text, Object localState,
            boolean showTrash) {
        dragText(view, context, text, localState, false, showTrash);
    }

    public static void dragText(View view, Context context, CharSequence text, Object localState,
            boolean showTrash, Bundle extras) {
        dragText(view, context, text, localState, showTrash, extras, false);
    }

    public static void dragText(View view, Context context, CharSequence text, Object localState,
            boolean showTrash, Bundle extras, boolean withGlobalShare) {
        dragText(view, context, text, text, localState, showTrash, extras, null, null,
                withGlobalShare);
    }

    public static void dragText(View view, Context context, CharSequence text, Object localState,
            boolean showTrash, boolean withGlobalShare) {
        dragText(view, context, text, localState, showTrash, null, withGlobalShare);
    }

    public static void dragText(View view, Context context, CharSequence text,
            boolean withGlobalShare) {
        dragText(view, context, text, null, false, null, withGlobalShare);
    }

    public static void dragText(View view, Context context, CharSequence text,
            boolean showTrash, boolean withGlobalShare) {
        dragText(view, context, text, null, showTrash, null, withGlobalShare);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, int shadowBgRes,
            Bundle extras, CharSequence title, CharSequence message) {
        return dragText(view, context, shadowText, text, localState, showTrash, shadowBgRes,
                extras, title, message, 0, false);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, int shadowBgRes,
            Bundle extras, CharSequence title, CharSequence message, int shadowTextColorRes) {
        return dragText(view, context, shadowText, text, localState, showTrash, shadowBgRes,
                extras, title, message, shadowTextColorRes, false);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, int shadowBgRes,
            Bundle extras, CharSequence title, CharSequence message, int shadowTextColorRes,
            boolean withGlobalShare) {
        final TextDragBuilder builder = new TextDragBuilder(view, context);
        builder.shadowText(shadowText).text(text).localState(localState).showTrash(showTrash)
                .shadowBgRes(shadowBgRes).extras(extras).title(title).message(message)
                .shadowTextColorRes(shadowTextColorRes).withGlobalShare(withGlobalShare);
        return dragText(builder);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, int shadowBgRes,
            Bundle extras, CharSequence title, CharSequence message, boolean withGlobalShare) {
        return dragText(view, context, shadowText, text, localState, showTrash, shadowBgRes,
                extras, title, message, 0, withGlobalShare);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, Bundle extras) {
        return dragText(view, context, shadowText, text, localState, showTrash, extras, null, null);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, Bundle extras,
            CharSequence title, CharSequence message) {
        return dragText(view, context, shadowText, text, localState, showTrash, 0, extras,
                title, message);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, Bundle extras,
            CharSequence title, CharSequence message, boolean withGlobalShare) {
        return dragText(view, context, shadowText, text, localState, showTrash, 0, extras,
                title, message, withGlobalShare);
    }

    public static boolean dragText(View view, Context context, CharSequence shadowText,
            CharSequence text, Object localState, boolean showTrash, Bundle extras,
            boolean withGlobalShare) {
        return dragText(view, context, shadowText, text, localState, showTrash, extras, null, null,
                withGlobalShare);
    }

    public static boolean dragText(TextDragBuilder builder) {
        if (builder == null || builder.view == null) return false;
        final CharSequence text = !TextUtils.isEmpty(builder.text)
                ? builder.text : builder.shadowText;
        if (TextUtils.isEmpty(text)) return false;
        final ClipData data = builder.clipData != null
                ? builder.clipData : ClipData.newPlainText(builder.title, text);
        applyExtras(data.getDescription(), builder.extras);
        return startDrag(builder.view, data, builder.localState, builder.flags,
                builder.withGlobalShare);
    }

    @Deprecated
    public static Bitmap getSidebarBackground() {
        final ISidebarService service = getSidebarService();
        if (service == null) return null;
        try {
            return service.getSidebarBackground();
        } catch (RemoteException e) {
            return null;
        }
    }

    @Deprecated
    public static int getSidebarModeState() {
        final ISidebarService service = getSidebarService();
        if (service == null) return -1;
        try {
            return service.getSidebarModeState();
        } catch (RemoteException e) {
            return -1;
        }
    }

    public static String getToNotes(DragEvent event) {
        if (event == null || event.getClipDescription() == null
                || event.getClipDescription().getExtras() == null) return null;
        return event.getClipDescription().getExtras().getString(KEY_TO_NOTES);
    }

    @Deprecated
    public static void handleSidebarShareList() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.handleSidebarShareList();
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static Bundle iconFloatUpNoticeSidebar(ILauncher launcher, Bundle args) {
        final ISidebarService service = getSidebarService();
        if (service == null) return null;
        try {
            return service.noticeSidebarIconFloat(launcher, args);
        } catch (RemoteException e) {
            return null;
        }
    }

    public static boolean isBigBangEnter(View view) {
        return false;
    }

    @Deprecated
    public static boolean isKeyguardLocked(Context context) {
        final KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    @Deprecated
    public static boolean isKeyguardVerified(Context context) {
        return !isKeyguardLocked(context);
    }

    @Deprecated
    public static boolean isSidebarFocused(Context context) {
        final ISidebarService service = getSidebarService();
        if (service == null) return false;
        try {
            return service.isFocusedOnSidebar();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Deprecated
    public static boolean isSidebarInnerDragEnable(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(), "sidebar_innerdrag_state", 0) == 1;
    }

    @Deprecated
    public static boolean isSidebarLeftMode() {
        return getSidebarModeState() == 1;
    }

    @Deprecated
    public static boolean isSidebarShowing(Context context) {
        final ISidebarService service = getSidebarService();
        if (service == null) return false;
        try {
            return service.isInSidebarMode();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Deprecated
    public static void onSidebarBackgroundChanged() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.onSidebarBackgroundChanged();
        } catch (RemoteException ignored) {
        }
    }

    public static void requestBindService(String packageName) {}

    @Deprecated
    public static void requestEnterLastMode() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.requestEnterLastMode();
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void requestEnterSidebarMode() {
        requestEnterSidebarMode(2);
    }

    @Deprecated
    public static void requestEnterSidebarMode(int mode) {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.requestEnterSidebarMode(mode);
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void requestEnterSidebarModeWithFrom(int from) {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.requestEnterSidebarModeWithFrom(from);
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void requestExitSidebarMode() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.requestExitSidebarMode();
        } catch (RemoteException ignored) {
        }
    }

    public static void requestUnbindService(String packageName) {}

    @Deprecated
    public static void resetWindowForTemp() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.resetWindowForTemp();
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void resumeSidebar() {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.resumeSidebar();
        } catch (RemoteException ignored) {
        }
    }

    public static void setAwemeClient(Binder binder) {}

    @Deprecated
    public static void setSidebarEnabled(boolean enabled) {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.setEnabled(enabled);
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void showGlobalShare(Intent intent) {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.showGlobalShare(intent);
        } catch (RemoteException ignored) {
        }
    }

    @Deprecated
    public static void updateOngoing(ComponentName component, int uid, int pid,
            CharSequence text, int state) {
        final ISidebarService service = getSidebarService();
        if (service == null) return;
        try {
            service.updateOngoing(component, uid, pid, text, state);
        } catch (RemoteException ignored) {
        }
    }

    private static void startFileDrag(View view, File file, String mimeType, Bundle extras,
            int flags, Object localState, boolean withGlobalShare) {
        if (view == null || file == null) return;
        final ClipDescription description = new ClipDescription(file.getName(),
                new String[] {TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType});
        applyExtras(description, extras);
        final ClipData data = new ClipData(description, new ClipData.Item(Uri.fromFile(file)));
        startDrag(view, data, localState, flags, withGlobalShare);
    }

    private static void startMultipleFileDrag(View view, File[] files, String[] mimeTypes,
            Bundle extras, int flags, boolean withGlobalShare) {
        if (view == null || files == null || files.length == 0) return;
        final String[] types = mimeTypes != null && mimeTypes.length > 0
                ? mimeTypes : new String[] {"application/octet-stream"};
        final ClipDescription description = new ClipDescription(null, types);
        applyExtras(description, extras);
        final ClipData data = new ClipData(description,
                new ClipData.Item(Uri.fromFile(files[0])));
        for (int i = 1; i < files.length; i++) {
            if (files[i] != null) data.addItem(new ClipData.Item(Uri.fromFile(files[i])));
        }
        startDrag(view, data, null, flags, withGlobalShare);
    }

    private static boolean startDrag(View view, ClipData data, Object localState, int flags,
            boolean withGlobalShare) {
        final Runnable action = () -> view.startDragAndDrop(data,
                new View.DragShadowBuilder(view), localState,
                flags | STANDARD_GLOBAL_DRAG_FLAGS);
        if (withGlobalShare && !isSidebarShowing(view.getContext())) {
            requestEnterSidebarMode();
            view.postDelayed(action, 350);
            return true;
        }
        return view.startDragAndDrop(data, new View.DragShadowBuilder(view), localState,
                flags | STANDARD_GLOBAL_DRAG_FLAGS);
    }

    private static void applyExtras(ClipDescription description, Bundle extras) {
        if (description != null && extras != null) {
            description.setExtras(new PersistableBundle(extras));
        }
    }

    private static ISidebarService getSidebarService() {
        return ISidebarService.Stub.asInterface(ServiceManager.getService("sidebar"));
    }
}
