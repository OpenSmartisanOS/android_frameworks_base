/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.ui;

import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_BINDABLE;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_ICON;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_MOBILE_NEW;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_WIFI_NEW;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.OptIn;
import androidx.collection.MutableIntObjectMap;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIcon.Shape;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.kairos.ExperimentalKairosApi;
import com.android.systemui.kairos.KairosNetwork;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.BaseStatusBarFrameLayout;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.NetworkSignalCluster;
import com.android.systemui.statusbar.phone.NetworkClusterStateController;
import com.android.systemui.statusbar.phone.DynamicIconPolicy;
import com.android.systemui.statusbar.phone.StatusBarGeometry;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarIconHolder.BindableIconHolder;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapterKairos;
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconsBinder;
import com.android.systemui.statusbar.pipeline.mobile.ui.view.SignalClusterView;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel;
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;
import com.android.systemui.statusbar.pipeline.wifi.ui.view.WifiView;
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel;
import com.android.systemui.util.Assert;

import dagger.Lazy;

import kotlin.Pair;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns info from StatusBarIconController into ImageViews in a ViewGroup.
 */
@OptIn(markerClass = ExperimentalKairosApi.class)
public class IconManager implements DemoModeCommandReceiver {
    protected final ViewGroup mGroup;
    private final MobileContextProvider mMobileContextProvider;
    private final LocationBasedWifiViewModel mWifiViewModel;
    private final MobileIconsViewModel mMobileIconsViewModel;

    private final Lazy<MobileUiAdapterKairos> mMobileUiAdapterKairos;
    private final KairosNetwork mKairosNetwork;
    private final CoroutineScope mAppScope;
    private final MutableIntObjectMap<Job> mBindingJobs = new MutableIntObjectMap<>();

    /**
     * Stores the list of bindable icons that have been added, keyed on slot name. This ensures
     * we don't accidentally add the same bindable icon twice.
     */
    private final Map<String, BindableIconHolder> mBindableIcons = new HashMap<>();
    protected final Context mContext;
    protected int mIconSize;
    // Whether or not these icons show up in dumpsys
    protected boolean mShouldLog = false;
    private StatusBarIconController mController;
    private final StatusBarLocation mLocation;
    private final ArrayList<View> mAttachedViews = new ArrayList<>();
    private final ArrayList<View> mSuppressedViews = new ArrayList<>();
    private final Map<String, Integer> mDemoOverrides = new HashMap<>();
    private final Map<StatusBarIconView, StatusBarIcon> mDemoOriginalIcons = new HashMap<>();
    private NetworkSignalCluster mSignalCluster;

    // Enables SystemUI demo mode to take effect in this group
    protected boolean mDemoable = true;
    private boolean mIsInDemoMode;

    protected ArrayList<String> mBlockList = new ArrayList<>();

    public IconManager(
            ViewGroup group,
            StatusBarLocation location,
            WifiUiAdapter wifiUiAdapter,
            MobileUiAdapter mobileUiAdapter,
            Lazy<MobileUiAdapterKairos> mobileUiAdapterKairos,
            MobileContextProvider mobileContextProvider,
            KairosNetwork kairosNetwork,
            CoroutineScope appScope
    ) {
        mGroup = group;
        mMobileContextProvider = mobileContextProvider;
        mContext = group.getContext();
        mLocation = location;
        mDemoable = true;
        mKairosNetwork = kairosNetwork;
        mAppScope = appScope;

        reloadDimens();

        // This starts the flow for the new pipeline, and will notify us of changes via
        // {@link #setNewMobileIconIds}
        mMobileIconsViewModel = mobileUiAdapter.getMobileIconsViewModel();
        MobileIconsBinder.bind(mGroup, mMobileIconsViewModel);


        mMobileUiAdapterKairos = mobileUiAdapterKairos;

        mWifiViewModel = wifiUiAdapter.bindGroup(mGroup, mLocation);
    }

    public boolean isDemoable() {
        return mDemoable;
    }

    void setController(StatusBarIconController controller) {
        mController = controller;
    }

    /** Sets the list of slots that should be blocked from showing in the status bar. */
    public void setBlockList(@Nullable List<String> blockList) {
        Assert.isMainThread();
        mBlockList.clear();
        mBlockList.addAll(blockList);
        if (mController != null) {
            mController.refreshIconGroup(this);
        }
    }

    /** Sets whether this manager's changes should be dumped in a bug report. */
    public void setShouldLog(boolean should) {
        mShouldLog = should;
    }

    /** Returns true if this manager's changes should be dumped in a bug report. */
    public boolean shouldLog() {
        return mShouldLog;
    }

    protected void onIconAdded(int index, String slot, boolean blocked,
            StatusBarIconHolder holder) {
        addHolder(index, slot, blocked, holder);
    }

    protected StatusIconDisplayable addHolder(int index, String slot, boolean blocked,
            StatusBarIconHolder holder) {
        // This is a little hacky, and probably regrettable, but just set `blocked` on any icon
        // that is in our blocked list, then we'll never see it
        if (mBlockList.contains(slot)) {
            blocked = true;
        }
        if (!DynamicIconPolicy.shouldCreateView(mContext, slot)) {
            return addSuppressedIcon(index, slot);
        }
        String configuredHideList = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), StatusBarIconController.ICON_HIDE_LIST,
                UserHandle.USER_CURRENT);
        blocked |= DynamicIconPolicy.shouldApplyFactoryDefault(
                mContext, slot, configuredHideList);
        return switch (holder.getType()) {
            case TYPE_ICON -> addIcon(index, slot, blocked, holder.getIcon());
            case TYPE_WIFI_NEW -> addNewWifiIcon(index, slot);
            case TYPE_MOBILE_NEW -> addNewMobileIcon(index, slot, holder.getTag());
            case TYPE_BINDABLE ->
                // Safe cast, since only BindableIconHolders can set this tag on themselves
                    addBindableIcon((BindableIconHolder) holder, index);
            default -> null;
        };
    }

    protected StatusBarIconView addIcon(int index, String slot, boolean blocked,
            StatusBarIcon icon) {
        StatusBarIconView view = onCreateStatusBarIconView(slot, blocked);
        view.set(iconForHost(slot, icon));
        attachView(index, view, onCreateLayoutParams(icon.shape), slot);
        applyDemoOverride(view);
        return view;
    }

    /**
     * Converts process-wide icon state into the canonical artwork owned by this display host.
     * The holder stays immutable because all attached display hosts consume the same state.
     */
    private StatusBarIcon iconForHost(String slot, StatusBarIcon source) {
        if (source == null) {
            return source;
        }
        int sourceResource = source.icon.getType() == Icon.TYPE_RESOURCE
                ? source.icon.getResId() : 0;
        int resourceId = DynamicIconPolicy.resourceForHost(mContext, slot, sourceResource);
        if (resourceId == 0 || resourceId == sourceResource) {
            return source;
        }
        StatusBarIcon result = source.clone();
        result.icon = Icon.createWithResource(mContext, resourceId);
        return result;
    }

    private StatusBarIconView addSuppressedIcon(int index, String slot) {
        StatusBarIconView placeholder = onCreateStatusBarIconView(slot, true);
        placeholder.setVisibility(View.GONE);
        rememberAttachedView(index, placeholder);
        mSuppressedViews.add(placeholder);
        return placeholder;
    }

    /**
     * ModernStatusBarViews can be created and bound, and thus do not need to update their
     * drawable by sending multiple calls to setIcon. Instead, by using a bindable
     * icon view, we can simply create the icon when requested and allow the
     * ViewBinder to control its visual state.
     */
    protected StatusIconDisplayable addBindableIcon(BindableIconHolder holder,
            int index) {
        mBindableIcons.put(holder.getSlot(), holder);
        ModernStatusBarView view = holder.getInitializer().createAndBind(mContext);
        attachView(index, view, onCreateLayoutParams(Shape.WRAP_CONTENT), holder.getSlot());
        return view;
    }

    protected StatusIconDisplayable addNewWifiIcon(int index, String slot) {
        StatusIconDisplayable view = WifiView.constructAndBind(mContext, slot, mWifiViewModel);
        View child = (View) view;
        attachView(index, child, onCreateLayoutParams(Shape.WRAP_CONTENT), slot);

        return view;
    }


    protected StatusIconDisplayable addNewMobileIcon(
            int index,
            String slot,
            int subId
    ) {
        BaseStatusBarFrameLayout view = onCreateSignalClusterView(slot, subId);
        attachView(index, view, onCreateLayoutParams(Shape.WRAP_CONTENT), slot);

        return view;
    }

    private StatusBarIconView onCreateStatusBarIconView(String slot, boolean blocked) {
        return new StatusBarIconView(mContext, slot, null, blocked);
    }

    private SignalClusterView onCreateSignalClusterView(String slot, int subId) {
        Context mobileContext = mMobileContextProvider.getMobileContextForSub(subId, mContext);
        if (StatusBarMobileIconKairos.isEnabled()) {
            Pair<SignalClusterView, Job> viewAndJob = SignalClusterView.constructAndBind(
                    mobileContext,
                    mMobileUiAdapterKairos.get().getMobileIconsViewModel().getLogger(),
                    slot,
                    mMobileUiAdapterKairos.get().getMobileIconsViewModel().viewModelForSub(
                            subId, mLocation),
                    mAppScope,
                    subId,
                    mLocation,
                    mKairosNetwork);
            mBindingJobs.put(subId, viewAndJob.getSecond());
            return viewAndJob.getFirst();
        }
        return SignalClusterView.constructAndBind(
                mobileContext,
                mMobileIconsViewModel.getLogger(),
                slot,
                mMobileIconsViewModel.viewModelForSub(subId, mLocation));
    }

    protected LinearLayout.LayoutParams onCreateLayoutParams(Shape shape) {
        int width = shape == StatusBarIcon.Shape.FIXED_SPACE
                ? mIconSize
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        return new LinearLayout.LayoutParams(width, mIconSize);
    }

    protected void destroy() {
        for (int i = mAttachedViews.size() - 1; i >= 0; i--) {
            View view = mAttachedViews.get(i);
            if (view == null) continue;
            cancelBinding(view);
            if (view.getParent() instanceof ViewGroup parent) {
                parent.removeView(view);
            }
        }
        mAttachedViews.clear();
        mSuppressedViews.clear();
        mDemoOriginalIcons.clear();
        if (!mIsInDemoMode) {
            mDemoOverrides.clear();
        }
        mGroup.removeAllViews();
    }

    protected void reloadDimens() {
        mIconSize = StatusBarGeometry.calculate(mGroup).getIconHeight();
    }

    protected void onRemoveIcon(int viewIndex) {
        View view = getAttachedView(viewIndex);
        mSuppressedViews.remove(view);
        if (view instanceof StatusBarIconView iconView) {
            mDemoOriginalIcons.remove(iconView);
        }
        cancelBinding(view);
        if (viewIndex >= 0 && viewIndex < mAttachedViews.size()) {
            mAttachedViews.remove(viewIndex);
        }
        if (view != null) {
            NetworkSignalCluster cluster = findCluster();
            if (cluster != null && cluster.hasAttached(view)) {
                cluster.detach(view);
            } else if (view.getParent() instanceof ViewGroup parent) {
                parent.removeView(view);
            }
        }
    }

    private void cancelBinding(@Nullable View view) {
        if (!StatusBarMobileIconKairos.isEnabled() || view == null) return;
        final int subId;
        if (view instanceof SignalClusterView mobile) {
            subId = mobile.getSubId();
        } else {
            return;
        }
        Job bindingJob = mBindingJobs.remove(subId);
        if (bindingJob != null) bindingJob.cancel(null);
    }

    /** Called once an icon has been set. */
    public void onSetIcon(int viewIndex, StatusBarIcon icon) {
        View attached = getAttachedView(viewIndex);
        if (!(attached instanceof StatusBarIconView view)) {
            return;
        }
        if (mSuppressedViews.contains(view)) {
            return;
        }
        ViewGroup.LayoutParams current = view.getLayoutParams();
        ViewGroup.LayoutParams desired = onCreateLayoutParams(icon.shape);
        if (desired.width != current.width || desired.height != current.height) {
            view.setLayoutParams(desired);
        }
        if (mIsInDemoMode
                && mDemoOverrides.containsKey(view.getSlot())) {
            // Real policy updates remain authoritative and are restored when demo mode ends, but
            // must not overwrite the active demo frame.
            mDemoOriginalIcons.put(view, iconForHost(view.getSlot(), icon).clone());
            setDemoIcon(view, mDemoOverrides.get(view.getSlot()));
        } else {
            view.set(iconForHost(view.getSlot(), icon));
        }
    }

    /** Called once an icon holder has been set. */
    public void onSetIconHolder(int viewIndex, StatusBarIconHolder holder) {
        switch (holder.getType()) {
            case TYPE_ICON:
                onSetIcon(viewIndex, holder.getIcon());
                return;
            case TYPE_MOBILE_NEW:
            case TYPE_WIFI_NEW:
            case TYPE_BINDABLE:
                // Nothing, the new icons update themselves
                return;
            default:
                break;
        }
    }

    /** Returns the display id associated to the view group of this icon manager */
    public int getDisplayId() {
        return mGroup.getContext().getDisplayId();
    }

    @Nullable
    public View findContentsRoot() {
        View current = mGroup;
        while (current != null) {
            int id = current.getId();
            if (id == R.id.status_bar_contents || id == R.id.shade_panel_status_bar_content
                    || id == R.id.system_icons) {
                if (id != R.id.system_icons) {
                    return current;
                }
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return mGroup;
    }

    @Nullable
    protected View getAttachedView(int viewIndex) {
        if (viewIndex < 0 || viewIndex >= mAttachedViews.size()) {
            return null;
        }
        return mAttachedViews.get(viewIndex);
    }

    protected int getAttachedViewCount() {
        return mAttachedViews.size();
    }

    protected void forEachAttachedView(Consumer<View> consumer) {
        for (int i = 0; i < mAttachedViews.size(); i++) {
            View view = mAttachedViews.get(i);
            if (view != null) {
                consumer.accept(view);
            }
        }
    }

    private void attachView(int index, View view, ViewGroup.LayoutParams params, String slot) {
        rememberAttachedView(index, view);
        NetworkSignalCluster cluster = DynamicIconPolicy.shouldAttachToFixedCluster(mContext, slot)
                ? findCluster() : null;
        if (cluster != null) {
            cluster.attach(view);
            return;
        }
        int mergerIndex = mergerChildIndex(index);
        mGroup.addView(view, Math.min(mergerIndex, mGroup.getChildCount()), params);
    }

    private void rememberAttachedView(int index, View view) {
        while (mAttachedViews.size() < index) {
            mAttachedViews.add(null);
        }
        if (index >= mAttachedViews.size()) {
            mAttachedViews.add(view);
        } else {
            mAttachedViews.add(index, view);
        }
    }

    private int mergerChildIndex(int attachedIndex) {
        int childIndex = 0;
        int limit = Math.min(attachedIndex, mAttachedViews.size());
        for (int i = 0; i < limit; i++) {
            View view = mAttachedViews.get(i);
            if (view != null && view.getParent() == mGroup) {
                childIndex++;
            }
        }
        return childIndex;
    }

    @Nullable
    private NetworkSignalCluster findCluster() {
        if (mSignalCluster != null) {
            return mSignalCluster;
        }
        ViewGroup parent = mGroup.getParent() instanceof ViewGroup
                ? (ViewGroup) mGroup.getParent() : null;
        if (parent != null) {
            mSignalCluster = parent.findViewById(R.id.network_signal_cluster);
        }
        return mSignalCluster;
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoable) {
            return;
        }

        if (!mIsInDemoMode) return;
        if (args.containsKey("nosim")) {
            NetworkClusterStateController.get(mContext).setDemoNoSim(
                    "show".equals(args.getString("nosim")));
        }
        if (args.containsKey("airplane")) {
            NetworkClusterStateController.get(mContext).setDemoAirplane(
                    "show".equals(args.getString("airplane")));
        }
        dispatchDemoCommand(args);
    }

    @Override
    public void onDemoModeStarted() {
        mIsInDemoMode = true;
        NetworkClusterStateController.get(mContext).clearDemoOverrides();
        mDemoOverrides.clear();
        mDemoOriginalIcons.clear();
    }

    @Override
    public void onDemoModeFinished() {
        NetworkClusterStateController.get(mContext).clearDemoOverrides();
        restoreDemoIcons();
        mIsInDemoMode = false;
    }

    private void dispatchDemoCommand(Bundle args) {
        String volume = args.getString("volume");
        if (volume != null) {
            updateDemoSlot(
                    mContext.getString(com.android.internal.R.string.status_bar_volume),
                    "vibrate".equals(volume) ? R.drawable.stat_sys_ringer_vibrate : 0);
        }
        String mute = args.getString("mute");
        if (mute != null) {
            updateDemoSlot(
                    mContext.getString(com.android.internal.R.string.status_bar_volume),
                    "show".equals(mute) ? R.drawable.stat_sys_ringer_silent : 0);
        }
        String zen = args.getString("zen");
        if (zen != null) {
            updateDemoSlot(
                    mContext.getString(com.android.internal.R.string.status_bar_zen),
                    "dnd".equals(zen) ? R.drawable.stat_sys_dnd : 0);
        }
        String bluetooth = args.getString("bluetooth");
        if (bluetooth != null) {
            updateDemoSlot(
                    mContext.getString(com.android.internal.R.string.status_bar_bluetooth),
                    "connected".equals(bluetooth)
                            ? R.drawable.stat_sys_data_bluetooth_connected : 0);
        }
        updateDemoShowSlot(args, "alarm",
                mContext.getString(com.android.internal.R.string.status_bar_alarm_clock),
                R.drawable.stat_sys_alarm);
        updateDemoShowSlot(args, "tty",
                mContext.getString(com.android.internal.R.string.status_bar_tty),
                R.drawable.stat_sys_tty_mode);
        updateDemoShowSlot(args, "cast",
                mContext.getString(com.android.internal.R.string.status_bar_cast),
                R.drawable.stat_sys_cast);
        updateDemoShowSlot(args, "hotspot",
                mContext.getString(com.android.internal.R.string.status_bar_hotspot),
                R.drawable.stat_sys_hotspot);
    }

    private void updateDemoShowSlot(Bundle args, String command, String slot, int iconId) {
        String value = args.getString(command);
        if (value != null) {
            updateDemoSlot(slot, "show".equals(value) ? iconId : 0);
        }
    }

    private void updateDemoSlot(String slot, int iconId) {
        mDemoOverrides.put(slot, iconId);
        for (View child : mAttachedViews) {
            if (child instanceof StatusBarIconView iconView
                    && slot.equals(iconView.getSlot())
                    && !mSuppressedViews.contains(iconView)) {
                applyDemoOverride(iconView);
            }
        }
    }

    private void applyDemoOverride(StatusBarIconView view) {
        if (!mIsInDemoMode) return;
        Integer iconId = mDemoOverrides.get(view.getSlot());
        if (iconId == null) return;
        StatusBarIcon current = view.getStatusBarIcon();
        if (current == null) return;
        mDemoOriginalIcons.putIfAbsent(view, current.clone());
        setDemoIcon(view, iconId);
    }

    private void setDemoIcon(StatusBarIconView view, int iconId) {
        StatusBarIcon current = view.getStatusBarIcon();
        if (current == null) return;
        StatusBarIcon demo = current.clone();
        demo.visible = iconId != 0;
        if (iconId != 0) {
            demo.icon = Icon.createWithResource(mContext.getPackageName(), iconId);
        }
        view.set(demo);
        if (iconId != 0) view.updateDrawable();
    }

    private void restoreDemoIcons() {
        for (Map.Entry<StatusBarIconView, StatusBarIcon> entry
                : mDemoOriginalIcons.entrySet()) {
            if (mAttachedViews.contains(entry.getKey())) {
                entry.getKey().set(entry.getValue());
                entry.getKey().updateDrawable();
            }
        }
        mDemoOriginalIcons.clear();
        mDemoOverrides.clear();
    }

}
