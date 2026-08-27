/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.window.DesktopExperienceFlags;

import androidx.annotation.NonNull;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeExpandsOnStatusBarLongPress;
import com.android.systemui.shade.StatusBarLongPressGestureDetector;
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays;
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public class PhoneStatusBarView extends FrameLayout {
    private static final String TAG = "PhoneStatusBarView";

    private StatusBarWindowControllerStore mStatusBarWindowControllerStore;
    private boolean mShouldUpdateStatusBarHeightWhenControllerSet = false;
    private int mRotationOrientation = -1;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private StatusBarCutoutMode mCutoutMode = StatusBarCutoutMode.NONE;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private Gefingerpoken mTouchEventHandler;
    @Nullable
    private BooleanSupplier mIsStatusBarInteractiveSupplier;
    @Nullable
    private InsetsFetcher mInsetsFetcher;
    private int mDensity;
    private float mFontScale;
    private StatusBarLongPressGestureDetector mStatusBarLongPressGestureDetector;
    private final Region mTouchableRegion = Region.obtain();

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setLongPressGestureDetector(
            StatusBarLongPressGestureDetector statusBarLongPressGestureDetector) {
        if (ShadeExpandsOnStatusBarLongPress.isEnabled()) {
            mStatusBarLongPressGestureDetector = statusBarLongPressGestureDetector;
        }
    }

    void setTouchEventHandler(Gefingerpoken handler) {
        mTouchEventHandler = handler;
    }

    void setIsStatusBarInteractiveSupplier(BooleanSupplier isStatusBarInteractiveSupplier) {
        mIsStatusBarInteractiveSupplier = isStatusBarInteractiveSupplier;
    }

    void setHasCornerCutoutFetcher(@NonNull HasCornerCutoutFetcher cornerCutoutFetcher) {
        applyStatusBarCutoutLayout();
    }

    void setInsetsFetcher(@NonNull InsetsFetcher insetsFetcher) {
        mInsetsFetcher = insetsFetcher;
        updateSafeInsets();
    }

    void init(StatusBarUserChipViewModel viewModel) {
        // R2 has no status-bar user chip. User switching remains owned by Keyguard/QS.
    }

    /** Updates the status bar's touchable region. */
    public void updateTouchableRegion(Region touchableRegion) {
        mTouchableRegion.set(touchableRegion);
        getViewRootImpl().setTouchableRegion(touchableRegion);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        updateResources();
        applyStatusBarCutoutLayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            updateWindowHeight();
        } else {
            applyStatusBarCutoutLayout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDisplayCutout = null;
    }

    // Per b/300629388, we let the PhoneStatusBarView detect onConfigurationChanged to
    // updateResources, instead of letting the PhoneStatusBarViewController detect onConfigChanged
    // then notify PhoneStatusBarView.
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        updateWindowHeight();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        final WindowInsets result = super.onApplyWindowInsets(insets);
        final boolean changed = updateDisplayParameters(insets);
        // The R2 host must consume the insets delivered for this traversal. Re-reading
        // getRootWindowInsets() before View has committed them can retain the previous rotation's
        // cutout (or NO_CUTOUT after a SystemUI restart), leaving the clock in the NONE layout.
        if (changed || isAttachedToWindow()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return result;
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        return updateDisplayParameters(getRootWindowInsets());
    }

    private boolean updateDisplayParameters(@Nullable WindowInsets insets) {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        // A null root-insets snapshot is only a transient lifecycle state. Do not erase the last
        // valid cutout while the new rotation is being attached; the next onApplyWindowInsets()
        // call supplies the authoritative value.
        final DisplayCutout newCutout = insets == null ? null : insets.getDisplayCutout();
        if (insets != null && !Objects.equals(newCutout, mDisplayCutout)) {
            changed = true;
            mDisplayCutout = newCutout;
        }

        Configuration newConfiguration = mContext.getResources().getConfiguration();
        final Rect newSize = newConfiguration.windowConfiguration.getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        int density = newConfiguration.densityDpi;
        if (density != mDensity) {
            changed = true;
            mDensity = density;
        }
        float fontScale = newConfiguration.fontScale;
        if (fontScale != mFontScale) {
            changed = true;
            mFontScale = fontScale;
        }
        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onHoverEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onTouchEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Touch events outside of the touchable regions are still received by this view. Touch
        // events started within the view should not be handled to allow app handle views behind
        // the status bar to handle the event. ACTION_MOVE and ACTION_UP events outside the
        // touchable region should still be handled so that an open notification shade can be
        // correctly updated and closed.
        if (DesktopExperienceFlags.ENABLE_REMOVE_STATUS_BAR_INPUT_LAYER.isTrue()
                && event.getAction() == MotionEvent.ACTION_DOWN
                && !mTouchableRegion.contains((int) event.getRawX(), (int) event.getRawY())) {
            return false;
        }

        if (ShadeExpandsOnStatusBarLongPress.isEnabled()
                && mStatusBarLongPressGestureDetector != null) {
            mStatusBarLongPressGestureDetector.handleTouch(event);
        }
        if (mTouchEventHandler == null) {
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mTouchEventHandler.onInterceptTouchEvent(event);
    }

    public void updateResources() {
        updateStatusBarHeight();
    }

    /**
     * Sets the store responsible for managing the status bar window controller.
     *
     * <p>This setter is used to facilitate dependency injection for the
     * {@link PhoneStatusBarViewController}, which receives the store via Dagger. This avoids
     * using the legacy {@link com.android.systemui.Dependency} pattern directly in the constructor.
     *
     * @param statusBarWindowControllerStore The {@link StatusBarWindowControllerStore} instance
     * to set
     */
    public void setStatusBarWindowControllerStore(
            StatusBarWindowControllerStore statusBarWindowControllerStore) {
        mStatusBarWindowControllerStore = statusBarWindowControllerStore;
        if (mShouldUpdateStatusBarHeightWhenControllerSet) {
            mShouldUpdateStatusBarHeightWhenControllerSet = false;
            updateWindowHeight();
        }
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;
        updateSystemIconsContainerHeight();
        updatePaddings();
        applyStatusBarGeometry();
        setLayoutParams(layoutParams);
    }

    private void updateSystemIconsContainerHeight() {
        View systemIconsContainer = findViewById(R.id.system_icons);
        if (systemIconsContainer == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = systemIconsContainer.getLayoutParams();
        int newSystemIconsHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        if (layoutParams.height != newSystemIconsHeight) {
            layoutParams.height = newSystemIconsHeight;
            systemIconsContainer.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddings() {
        View contents = findViewById(R.id.status_bar_contents);
        if (contents != null) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) contents.getLayoutParams();
            StatusBarMetrics metrics = getStatusBarMetrics();
            lp.setMarginStart(metrics.getContentMarginStart());
            lp.setMarginEnd(metrics.getContentMarginEnd());
            contents.setLayoutParams(lp);
            contents.setPadding(0, 0, 0, 0);
        }
        View systemIcons = findViewById(R.id.system_icons);
        if (systemIcons != null) {
            systemIcons.setPadding(0, 0, 0, 0);
        }
    }

    private StatusBarMetrics getStatusBarMetrics() {
        int width = getWidth();
        if (width <= 0 && getRootView() != null) {
            width = getRootView().getWidth();
        }
        if (width <= 0) {
            width = getResources().getConfiguration().windowConfiguration.getBounds().width();
        }
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        Insets insets = mInsetsFetcher == null ? Insets.NONE : mInsetsFetcher.fetchInsets();
        return StatusBarGeometry.calculate(
                width,
                getResources().getDisplayMetrics().density,
                mStatusBarHeight > 0 ? mStatusBarHeight : SystemBarUtils.getStatusBarHeight(mContext),
                insets);
    }

    private void applyStatusBarGeometry() {
        StatusBarMetrics metrics = getStatusBarMetrics();
        setViewSize(findViewById(R.id.notification_lights_out),
                metrics.getIconHeight(), ViewGroup.LayoutParams.MATCH_PARENT);
        View lightsOut = findViewById(R.id.notification_lights_out);
        if (lightsOut != null) {
            lightsOut.setPaddingRelative(metrics.getItemMarginStart(), 0, 0, 0);
        }
        setViewSize(findViewById(R.id.privacy_icon),
                metrics.getIconHeight(), metrics.getIconHeight());
        setViewHeightAndMargins(findViewById(R.id.otg), metrics.getIconHeight(), metrics);
        setViewMargins(findViewById(R.id.battery), metrics);
        setViewMargins(findViewById(R.id.network_label), metrics);
        setViewMargins(findViewById(R.id.sidebar_drag), metrics);
        setViewSize(findViewById(R.id.net_speed_view),
                metrics.getNetworkSpeedWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        View contents = findViewById(R.id.status_bar_contents);
        if (contents != null
                && contents.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) {
            lp.setMarginStart(metrics.getContentMarginStart());
            lp.setMarginEnd(metrics.getContentMarginEnd());
            contents.setLayoutParams(lp);
        }
        View ticker = findViewById(R.id.status_bar_ticker_view);
        if (ticker != null) {
            ticker.setPaddingRelative(
                    metrics.getTickerPaddingStart(), 0, metrics.getTickerPaddingEnd(), 0);
        }
    }

    private static void setViewSize(View view, int width, int height) {
        if (view == null) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = width;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    private static void setViewHeightAndMargins(
            View view, int height, StatusBarMetrics metrics) {
        if (view == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        raw.height = height;
        if (raw instanceof ViewGroup.MarginLayoutParams lp) {
            lp.setMarginStart(metrics.getItemMarginStart());
            lp.setMarginEnd(metrics.getItemMarginEnd());
        }
        view.setLayoutParams(raw);
    }

    private static void setViewMargins(View view, StatusBarMetrics metrics) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) {
            return;
        }
        lp.setMarginStart(metrics.getItemMarginStart());
        lp.setMarginEnd(metrics.getItemMarginEnd());
        view.setLayoutParams(lp);
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        applyStatusBarCutoutLayout();
        updateSafeInsets();
    }

    private void applyStatusBarCutoutLayout() {
        View contents = findViewById(R.id.status_bar_contents);
        // The status-bar window can be narrower than max bounds in split/folded configurations.
        int screenWidth = getWidth();
        if (screenWidth <= 0 && getRootView() != null) {
            screenWidth = getRootView().getWidth();
        }
        if (screenWidth <= 0) {
            screenWidth = getResources().getConfiguration().windowConfiguration.getBounds().width();
        }
        if (screenWidth <= 0) {
            screenWidth = getResources().getDisplayMetrics().widthPixels;
        }
        StatusBarCutoutMode mode =
                StatusBarCutoutClassifier.classify(mDisplayCutout, screenWidth);
        mCutoutMode = mode;
        // R2 remains fully transparent on modern displays. The cutout still participates in
        // geometry so icons never render beneath the camera, but it does not own a black mask.
        setBackground(null);
        Rect bounds =
                mDisplayCutout == null ? null : new Rect(mDisplayCutout.getBoundingRectTop());
        StatusBarCutoutLayout.apply(
                contents instanceof ViewGroup ? (ViewGroup) contents : null, mode, bounds);
    }

    private void updateSafeInsets() {
        if (mInsetsFetcher == null) {
            Log.e(TAG, "mInsetsFetcher unexpectedly null");
            return;
        }

        Insets insets = mInsetsFetcher.fetchInsets();
        int leftInset = insets.left;
        int rightInset = insets.right;
        StatusBarMetrics metrics = getStatusBarMetrics();
        leftInset = Math.max(0, leftInset - metrics.getContentMarginStart());
        rightInset = Math.max(0, rightInset - metrics.getContentMarginEnd());
        setPadding(
                leftInset,
                insets.top,
                rightInset,
                getPaddingBottom());

        View clockHost = findViewById(R.id.privacy_highlight);
        if (clockHost == null) {
            clockHost = findViewById(R.id.clock);
        }
        if (clockHost != null) {
            clockHost.setTranslationX(
                    mCutoutMode == StatusBarCutoutMode.NONE
                            ? (getPaddingRight() - getPaddingLeft()) / 2f
                            : 0f);
        }
    }

    private void updateWindowHeight() {
        if (StatusBarConnectedDisplays.isEnabled()) {
            // Handled directly from StatusBarWindowControllerImpl (for each display)
            return;
        }
        if (mStatusBarWindowControllerStore != null) {
            mStatusBarWindowControllerStore.getDefaultDisplay().refreshStatusBarHeight();
        } else {
            Log.e(TAG, "mStatusBarWindowControllerStore unexpectedly null");
            mShouldUpdateStatusBarHeightWhenControllerSet = true;
        }
    }

    interface HasCornerCutoutFetcher {
        boolean fetchHasCornerCutout();
    }

    interface InsetsFetcher {
        Insets fetchInsets();
    }
}
