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

import android.graphics.PorterDuff;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.kairos.ExperimentalKairosApi;
import com.android.systemui.kairos.KairosNetwork;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.SystemIconsView;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapterKairos;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;

import dagger.Lazy;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import kotlin.OptIn;

import kotlinx.coroutines.CoroutineScope;

/** Version of {@link IconManager} that observes state from the {@link DarkIconDispatcher}. */
@OptIn(markerClass = ExperimentalKairosApi.class)
public class DarkIconManager extends IconManager {
    private final DarkIconDispatcher mDarkIconDispatcher;
    @Nullable private Integer mKeyguardTint;
    @Nullable private Integer mKeyguardForegroundTint;
    @Nullable private final SystemIconsView mSystemIconsView;
    private final java.util.ArrayList<View> mAccessoryViews = new java.util.ArrayList<>();
    private final DarkIconDispatcher.DarkReceiver mAccessoryReceiver =
            (areas, darkIntensity, tint) -> {
                for (View view : mAccessoryViews) {
                    applyAccessoryTint(view, DarkIconDispatcher.getTint(areas, view, tint));
                }
            };

    @AssistedInject
    public DarkIconManager(
            @Assisted LinearLayout linearLayout,
            @Assisted StatusBarLocation location,
            WifiUiAdapter wifiUiAdapter,
            MobileUiAdapter mobileUiAdapter,
            Lazy<MobileUiAdapterKairos> mobileUiAdapterKairos,
            MobileContextProvider mobileContextProvider,
            KairosNetwork kairosNetwork,
            @Application CoroutineScope appScope,
            @Assisted DarkIconDispatcher darkIconDispatcher) {
        super(linearLayout,
                location,
                wifiUiAdapter,
                mobileUiAdapter,
                mobileUiAdapterKairos,
                mobileContextProvider,
                kairosNetwork,
                appScope);
        mDarkIconDispatcher = darkIconDispatcher;
        Object rawParent = linearLayout.getParent();
        View parent = rawParent instanceof View ? (View) rawParent : null;
        mSystemIconsView = parent == null ? null
                : parent.findViewById(com.android.systemui.res.R.id.system_icons);
        View statusBarRoot = linearLayout;
        ViewParent ancestor = statusBarRoot.getParent();
        while (ancestor instanceof View ancestorView) {
            statusBarRoot = ancestorView;
            if (statusBarRoot.getId() == com.android.systemui.res.R.id.status_bar) break;
            ancestor = ancestorView.getParent();
        }
        // These two live outside SystemIconsView and therefore keep an independent left-side
        // receiver. OTG and net speed are children of SystemIconsView and are tinted atomically.
        addAccessory(statusBarRoot.findViewById(com.android.systemui.res.R.id.sidebar_drag));
        addAccessory(statusBarRoot.findViewById(com.android.systemui.res.R.id.network_label));
        if (!mAccessoryViews.isEmpty()) {
            mDarkIconDispatcher.addDarkReceiver(mAccessoryReceiver);
        }
    }

    @Override
    protected void onIconAdded(
            int index, String slot, boolean blocked, StatusBarIconHolder holder) {
        StatusIconDisplayable view = addHolder(index, slot, blocked, holder);
        if (mSystemIconsView != null) {
            mSystemIconsView.applyCurrentTint(view);
        } else if (mKeyguardTint == null) {
            mDarkIconDispatcher.addDarkReceiver(view);
        } else {
            applyTintOverride(view);
        }
    }

    @Override
    protected void destroy() {
        // Demo network overrides are process-wide and are cleared by IconManager only when the
        // global Demo Mode session ends. Destroying one HOME/PANEL/display host during rotation or
        // display removal must not reset the state still consumed by the remaining hosts.
        if (!mAccessoryViews.isEmpty() && mKeyguardTint == null) {
            mDarkIconDispatcher.removeDarkReceiver(mAccessoryReceiver);
        }
        if (mSystemIconsView == null && mKeyguardTint == null) {
            forEachAttachedView(child -> {
                if (child instanceof DarkIconDispatcher.DarkReceiver receiver) {
                    mDarkIconDispatcher.removeDarkReceiver(receiver);
                }
            });
        }
        super.destroy();
    }

    @Override
    protected void onRemoveIcon(int viewIndex) {
        if (mSystemIconsView == null && mKeyguardTint == null) {
            View child = getAttachedView(viewIndex);
            if (child instanceof DarkIconDispatcher.DarkReceiver receiver) {
                mDarkIconDispatcher.removeDarkReceiver(receiver);
            }
        }
        super.onRemoveIcon(viewIndex);
    }

    @Override
    public void onSetIcon(int viewIndex, StatusBarIcon icon) {
        super.onSetIcon(viewIndex, icon);
        View attached = getAttachedView(viewIndex);
        if (!(attached instanceof StatusIconDisplayable child)) {
            return;
        }
        if (mSystemIconsView != null) {
            mSystemIconsView.applyCurrentTint(child);
        } else if (mKeyguardTint == null) {
            if (attached instanceof DarkIconDispatcher.DarkReceiver receiver) {
                mDarkIconDispatcher.applyDark(receiver);
            }
        } else {
            applyTintOverride(child);
        }
    }

    /** Temporarily removes HOME icons from Monet/dark dispatch while R2 owns the status bar. */
    public void setKeyguardTintOverride(
            @Nullable Integer tint, @Nullable Integer foregroundTint) {
        final boolean wasOverridden = mKeyguardTint != null;
        final boolean willOverride = tint != null;
        if (wasOverridden == willOverride
                && java.util.Objects.equals(mKeyguardTint, tint)
                && java.util.Objects.equals(mKeyguardForegroundTint, foregroundTint)) {
            return;
        }
        if (!wasOverridden && willOverride) {
            if (mSystemIconsView == null) {
                forEachAttachedView(child -> {
                    if (child instanceof DarkIconDispatcher.DarkReceiver receiver) {
                        mDarkIconDispatcher.removeDarkReceiver(receiver);
                    }
                });
            }
            if (!mAccessoryViews.isEmpty()) {
                mDarkIconDispatcher.removeDarkReceiver(mAccessoryReceiver);
            }
        }
        mKeyguardTint = tint;
        mKeyguardForegroundTint = foregroundTint;
        if (mSystemIconsView != null) {
            mSystemIconsView.setKeyguardTintOverride(tint, foregroundTint);
        }
        if (mSystemIconsView == null) {
            forEachAttachedView(child -> {
                if (!(child instanceof StatusIconDisplayable displayable)) {
                    return;
                }
                if (willOverride) {
                    applyTintOverride(displayable);
                } else if (child instanceof DarkIconDispatcher.DarkReceiver receiver) {
                    mDarkIconDispatcher.addDarkReceiver(receiver);
                    mDarkIconDispatcher.applyDark(receiver);
                }
            });
        }
        if (!mAccessoryViews.isEmpty()) {
            if (willOverride) {
                for (View view : mAccessoryViews) {
                    applyAccessoryTint(view, tint);
                }
            } else {
                mDarkIconDispatcher.addDarkReceiver(mAccessoryReceiver);
                mDarkIconDispatcher.applyDark(mAccessoryReceiver);
            }
        }
    }

    private void addAccessory(@Nullable View view) {
        if (view != null && !mAccessoryViews.contains(view)) {
            mAccessoryViews.add(view);
        }
    }

    private static void applyAccessoryTint(View view, int tint) {
        if (view instanceof TextView textView) {
            textView.setTextColor(tint);
        } else if (view instanceof ImageView imageView) {
            imageView.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        }
    }

    private void applyTintOverride(StatusIconDisplayable view) {
        if (mKeyguardTint == null) return;
        view.setStaticDrawableColor(
                mKeyguardTint,
                mKeyguardForegroundTint != null
                        ? mKeyguardForegroundTint : mKeyguardTint);
        view.setDecorColor(mKeyguardTint);
    }

    /**  */
    @AssistedFactory
    public interface Factory {

        /** Creates a new {@link DarkIconManager}. */
        DarkIconManager create(
                LinearLayout group,
                StatusBarLocation location,
                DarkIconDispatcher darkIconDispatcher);
    }
}
