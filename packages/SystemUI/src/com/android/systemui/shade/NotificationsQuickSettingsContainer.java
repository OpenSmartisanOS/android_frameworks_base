/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shade;

import static androidx.constraintlayout.core.widgets.Optimizer.OPTIMIZATION_GRAPH;

import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.widget.Checkable;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.flags.QSComposeFragment;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AboveShelfObserver;

import java.util.function.Consumer;

/**
 * The container with notification stack scroller and quick settings inside.
 */
public class NotificationsQuickSettingsContainer extends ConstraintLayout
        implements FragmentListener, AboveShelfObserver.HasViewAboveShelfChangedListener {

    private View mQsFrame;
    private View mStackScroller;
    private View mSharedNotificationContainer;
    private View mSosQsNavbarScrim;
    private View mSosHeader;
    private View mSosHeaderContent;
    private View mSosHeaderShadow;
    private SosCloseDragHandle mSosPageSwitch;
    private View mSosNotificationsButton;
    private View mSosQuickSettingsButton;
    private View mSosClearAllContainer;
    private View mSosSettingsContainer;
    private View mSosClearAllButton;
    private View mSosSettingsButton;
    private boolean mSosQuickSettingsPage;
    private boolean mSosChromeVisible;
    private int mSosTopInset;
    private int mSosBottomInset;
    private float mSosRawExpandedHeight;
    private float mSosRawMaxPanelHeight;
    private float mSosExpandedHeight;
    private float mSosMaxPanelHeight;
    private Consumer<Boolean> mSosPageChangedListener = quickSettings -> {};
    private Consumer<Boolean> mSosPanelStatusBarVisibleListener = visible -> {};
    private Consumer<Integer> mSosPanelStatusBarTopInsetListener = topInset -> {};

    private static final long SOS_PAGE_ANIMATION_DELAY = 50L;
    private static final long SOS_PAGE_ANIMATION_DURATION = 300L;
    private static final Interpolator SOS_PAGE_INTERPOLATOR = input -> {
        float shifted = input - 1f;
        return shifted * shifted * shifted + 1f;
    };

    private Consumer<WindowInsets> mInsetsChangedListener = insets -> {};
    private Consumer<QS> mQSFragmentAttachedListener = qs -> {};
    private QS mQs;
    private View mQSContainer;
    private int mLastQSPaddingBottom;

    /**
     *  These are used to compute the bounding box containing the shade and the notification scrim,
     *  which is then used to drive the Back gesture animation.
     */
    private final Rect mUpperRect = new Rect();
    private final Rect mBoundingBoxRect = new Rect();

    @Nullable
    private Consumer<Configuration> mConfigurationChangedListener;

    public NotificationsQuickSettingsContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOptimizationLevel(getOptimizationLevel() | OPTIMIZATION_GRAPH);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQsFrame = findViewById(R.id.qs_frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            return;
        }
        View root = getRootView();
        mSharedNotificationContainer = root.findViewById(R.id.shared_notification_container);
        mSosQsNavbarScrim = root.findViewById(R.id.sos_qs_navbar_scrim);
        mSosHeader = root.findViewById(R.id.sos_shade_header);
        mSosHeaderContent = root.findViewById(R.id.sos_shade_header_content);
        mSosHeaderShadow = root.findViewById(R.id.sos_shade_header_shadow);
        mSosPageSwitch = root.findViewById(R.id.sos_shade_page_switch);
        mSosNotificationsButton = root.findViewById(R.id.sos_shade_notifications_button);
        mSosQuickSettingsButton = root.findViewById(R.id.sos_shade_quick_settings_button);
        mSosClearAllContainer = root.findViewById(R.id.sos_shade_clear_all_container);
        mSosSettingsContainer = root.findViewById(R.id.sos_shade_settings_container);
        mSosClearAllButton = root.findViewById(R.id.sos_shade_clear_all_button);
        mSosSettingsButton = root.findViewById(R.id.sos_shade_settings_button);

        View searchButton = root.findViewById(R.id.sos_shade_header_search);
        searchButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_WEB_SEARCH)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // Keep the visual affordance on builds without a search provider.
            }
        });
        mSosNotificationsButton.setOnClickListener(v -> {
            setSosQuickSettingsPage(false, true);
            mSosPageChangedListener.accept(false);
        });
        mSosQuickSettingsButton.setOnClickListener(v -> {
            setSosQuickSettingsPage(true, true);
            mSosPageChangedListener.accept(true);
        });
        mSosSettingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // Settings is expected on platform builds, but keep SystemUI alive if absent.
            }
        });
        int currentTopInset = mSosTopInset;
        mSosTopInset = -1;
        setSosTopInset(currentTopInset);
        setSosChromeVisible(mSosChromeVisible);
        int currentBottomInset = mSosBottomInset;
        mSosBottomInset = -1;
        setSosBottomInset(currentBottomInset);
        post(() -> {
            applySosPagePosition(false);
            applySosExpansionTransforms();
        });
    }

    public void setSosPageChangedListener(Consumer<Boolean> listener) {
        mSosPageChangedListener = listener != null ? listener : quickSettings -> {};
    }

    public void setSosPanelStatusBarVisibleListener(Consumer<Boolean> listener) {
        mSosPanelStatusBarVisibleListener = listener != null ? listener : visible -> {};
        mSosPanelStatusBarVisibleListener.accept(mSosChromeVisible);
    }

    public void setSosPanelStatusBarTopInsetListener(Consumer<Integer> listener) {
        mSosPanelStatusBarTopInsetListener = listener != null ? listener : topInset -> {};
        mSosPanelStatusBarTopInsetListener.accept(mSosTopInset);
    }

    public void setSosQuickSettingsPage(boolean quickSettings, boolean animate) {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            return;
        }
        boolean changed = mSosQuickSettingsPage != quickSettings;
        mSosQuickSettingsPage = quickSettings;
        applySosPagePosition(animate && changed);
        applySosExpansionTransforms();
    }

    public boolean isSosQuickSettingsPage() {
        return mSosQuickSettingsPage;
    }

    public float getSosNotificationStackTopPosition() {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            return 0f;
        }
        return mSosTopInset + getResources().getDimensionPixelSize(
                R.dimen.sos_qs_header_outer_height);
    }

    public void resetSosPage(boolean hasVisibleNotifications, boolean animate) {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            return;
        }
        setSosQuickSettingsPage(!hasVisibleNotifications, animate);
        mSosPageChangedListener.accept(mSosQuickSettingsPage);
    }

    public void setSosChromeVisible(boolean visible) {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            return;
        }
        mSosChromeVisible = visible;
        if (mQsFrame != null) {
            mQsFrame.setVisibility(visible ? VISIBLE : INVISIBLE);
        }
        mSosPanelStatusBarVisibleListener.accept(visible);
        if (!visible && mSosQsNavbarScrim != null) {
            mSosQsNavbarScrim.setVisibility(INVISIBLE);
        }
        if (mSosHeader != null) {
            mSosHeader.setVisibility(visible ? VISIBLE : GONE);
        }
        if (mSosPageSwitch != null) {
            mSosPageSwitch.setVisibility(visible ? VISIBLE : GONE);
        }
        if (mSosClearAllContainer != null) {
            mSosClearAllContainer.setVisibility(visible ? VISIBLE : GONE);
        }
        if (mSosSettingsContainer != null) {
            mSosSettingsContainer.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    /**
     * Mirrors the NotificationPanelView expansion axis for the window-level Smartisan chrome.
     *
     * <p>Android 16 keeps the shared notification stack outside NotificationPanelView, so these
     * controls must remain above that stack for touch dispatch. Mirroring the panel's geometry and
     * keyguard eligibility gives them the same lifecycle as the original in-panel views without
     * breaking SharedNotificationContainer.
     */
    public void setSosExpansion(
            float expandedHeight, float maxPanelHeight, boolean shadeContentAllowed) {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            return;
        }
        mSosRawExpandedHeight = Math.max(0f, expandedHeight);
        mSosRawMaxPanelHeight = Math.max(0f, maxPanelHeight);
        updateSosEdgeExpansionHeights();
        setSosChromeVisible(
                shadeContentAllowed && mSosMaxPanelHeight > 0f && mSosExpandedHeight > 0f);
        applySosExpansionTransforms();
    }

    private void updateSosEdgeExpansionHeights() {
        mSosExpandedHeight = mSosRawExpandedHeight;
        mSosMaxPanelHeight = mSosRawMaxPanelHeight;
    }

    private void applySosExpansionTransforms() {
        if (mSosMaxPanelHeight <= 0f) {
            return;
        }
        final float expansion =
                Math.max(0f, Math.min(1f, mSosExpandedHeight / mSosMaxPanelHeight));
        final float panelTranslation = Math.min(0f, mSosExpandedHeight - mSosMaxPanelHeight);
        if (mSosQsNavbarScrim != null) {
            mSosQsNavbarScrim.setVisibility(INVISIBLE);
            mSosQsNavbarScrim.setAlpha(0f);
        }
        if (mQsFrame != null) {
            mQsFrame.setTranslationY(panelTranslation);
        }
        if (mSosHeader != null) {
            mSosHeader.setTranslationY(panelTranslation);
            mSosHeader.setAlpha(mSosQuickSettingsPage ? 1f : expansion);
        }
        if (mSosPageSwitch != null) {
            mSosPageSwitch.setExpandedHeight(mSosExpandedHeight);
        }
        if (mSosSettingsContainer != null) {
            mSosSettingsContainer.setTranslationY(panelTranslation);
        }
        if (mSosClearAllContainer != null) {
            mSosClearAllContainer.setTranslationY(panelTranslation);
        }
        if (mSosClearAllContainer != null
                && mSosPageSwitch != null
                && mSosPageSwitch.getHandleHeight() > 0) {
            final float reveal = Math.max(
                    0f,
                    Math.min(
                            1f,
                            1f - ((mSosMaxPanelHeight - mSosExpandedHeight) * 3f
                                    / mSosPageSwitch.getHandleHeight())));
            mSosClearAllContainer.setScaleX(reveal);
            mSosClearAllContainer.setScaleY(reveal);
            mSosClearAllContainer.setAlpha(reveal);
        }
    }

    public void setSosTopInset(int topInset) {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)
                || mSosTopInset == topInset) {
            return;
        }
        mSosTopInset = topInset;
        mSosPanelStatusBarTopInsetListener.accept(topInset);
        if (mSosHeader != null && mSosHeaderContent != null) {
            ViewGroup.LayoutParams headerParams = mSosHeader.getLayoutParams();
            headerParams.height = topInset + getResources().getDimensionPixelSize(
                    R.dimen.sos_qs_header_outer_height);
            mSosHeader.setLayoutParams(headerParams);

            ViewGroup.MarginLayoutParams contentParams =
                    (ViewGroup.MarginLayoutParams) mSosHeaderContent.getLayoutParams();
            contentParams.topMargin = topInset;
            mSosHeaderContent.setLayoutParams(contentParams);
        }
        if (mSosHeaderShadow != null) {
            ViewGroup.MarginLayoutParams shadowParams =
                    (ViewGroup.MarginLayoutParams) mSosHeaderShadow.getLayoutParams();
            shadowParams.topMargin = topInset + getResources().getDimensionPixelSize(
                    R.dimen.sos_qs_header_height);
            mSosHeaderShadow.setLayoutParams(shadowParams);
        }
    }

    public void setSosBottomInset(int bottomInset) {
        if (!getResources().getBoolean(R.bool.config_sos_legacy_shade)
                || mSosBottomInset == bottomInset) {
            return;
        }
        mSosBottomInset = bottomInset;
        if (mSosClearAllContainer != null) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) mSosClearAllContainer.getLayoutParams();
            params.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.sos_shade_action_margin_bottom) + bottomInset;
            mSosClearAllContainer.setLayoutParams(params);
        }
        if (mSosSettingsContainer != null) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) mSosSettingsContainer.getLayoutParams();
            params.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.sos_shade_action_margin_bottom) + bottomInset;
            mSosSettingsContainer.setLayoutParams(params);
        }
        updateSosEdgeExpansionHeights();
        applySosExpansionTransforms();
    }

    private void applySosPagePosition(boolean animate) {
        if (mQsFrame == null || mSharedNotificationContainer == null
                || mSosHeader == null || mSosPageSwitch == null
                || mSosClearAllContainer == null || mSosSettingsContainer == null
                || mSosClearAllButton == null || mSosSettingsButton == null
                || mSosNotificationsButton == null || mSosQuickSettingsButton == null) {
            return;
        }
        float width = getRootView() != null ? getRootView().getWidth() : getWidth();
        if (width == 0) {
            return;
        }
        float notificationX = mSosQuickSettingsPage ? -width : 0f;
        float qsX = mSosQuickSettingsPage ? 0f : width;
        mQsFrame.animate().cancel();
        mSharedNotificationContainer.animate().cancel();
        mSosClearAllContainer.animate().cancel();
        mSosSettingsContainer.animate().cancel();
        if (animate) {
            mQsFrame.setLayerType(LAYER_TYPE_HARDWARE, null);
            mSharedNotificationContainer.setLayerType(LAYER_TYPE_HARDWARE, null);
            mSosClearAllContainer.setLayerType(LAYER_TYPE_HARDWARE, null);
            mSosSettingsContainer.setLayerType(LAYER_TYPE_HARDWARE, null);
            mQsFrame.animate().translationX(qsX)
                    .setStartDelay(SOS_PAGE_ANIMATION_DELAY)
                    .setDuration(SOS_PAGE_ANIMATION_DURATION)
                    .setInterpolator(SOS_PAGE_INTERPOLATOR)
                    .withEndAction(this::clearSosPageLayers)
                    .start();
            mSharedNotificationContainer.animate().translationX(notificationX)
                    .setStartDelay(SOS_PAGE_ANIMATION_DELAY)
                    .setDuration(SOS_PAGE_ANIMATION_DURATION)
                    .setInterpolator(SOS_PAGE_INTERPOLATOR)
                    .start();
            mSosClearAllContainer.animate().translationX(notificationX)
                    .setStartDelay(SOS_PAGE_ANIMATION_DELAY)
                    .setDuration(SOS_PAGE_ANIMATION_DURATION)
                    .setInterpolator(SOS_PAGE_INTERPOLATOR)
                    .start();
            mSosSettingsContainer.animate().translationX(qsX)
                    .setStartDelay(SOS_PAGE_ANIMATION_DELAY)
                    .setDuration(SOS_PAGE_ANIMATION_DURATION)
                    .setInterpolator(SOS_PAGE_INTERPOLATOR)
                    .start();
        } else {
            mQsFrame.setTranslationX(qsX);
            mSharedNotificationContainer.setTranslationX(notificationX);
            mSosClearAllContainer.setTranslationX(notificationX);
            mSosSettingsContainer.setTranslationX(qsX);
            clearSosPageLayers();
        }
        setSosPageButtonChecked(mSosNotificationsButton, !mSosQuickSettingsPage);
        setSosPageButtonChecked(mSosQuickSettingsButton, mSosQuickSettingsPage);
        mQsFrame.setImportantForAccessibility(mSosQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        mSharedNotificationContainer.setImportantForAccessibility(mSosQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mSosClearAllContainer.setImportantForAccessibility(mSosQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mSosSettingsContainer.setImportantForAccessibility(mSosQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static void setSosPageButtonChecked(View button, boolean checked) {
        button.setSelected(checked);
        button.setActivated(checked);
        if (button instanceof Checkable) {
            ((Checkable) button).setChecked(checked);
        }
    }

    private void clearSosPageLayers() {
        if (mQsFrame == null || mSharedNotificationContainer == null
                || mSosClearAllContainer == null || mSosSettingsContainer == null) {
            return;
        }
        mQsFrame.setLayerType(LAYER_TYPE_NONE, null);
        mSharedNotificationContainer.setLayerType(LAYER_TYPE_NONE, null);
        mSosClearAllContainer.setLayerType(LAYER_TYPE_NONE, null);
        mSosSettingsContainer.setLayerType(LAYER_TYPE_NONE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            post(() -> applySosPagePosition(false));
        }
    }

    void setStackScroller(View stackScroller) {
        mStackScroller = stackScroller;
    }

    @Override
    public void onFragmentViewCreated(String tag, Fragment fragment) {
        mQs = (QS) fragment;
        mQSFragmentAttachedListener.accept(mQs);
        mQSContainer = mQs.getView().findViewById(R.id.quick_settings_container);
        // We need to restore the bottom padding as the fragment may have been recreated due to
        // some special Configuration change, so we apply the last known padding (this will be
        // correct even if it has changed while the fragment was destroyed and re-created).
        setQSContainerPaddingBottom(mLastQSPaddingBottom);
    }

    @Override
    public void onHasViewsAboveShelfChanged(boolean hasViewsAboveShelf) {
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mConfigurationChangedListener != null) {
            mConfigurationChangedListener.accept(newConfig);
        }
    }

    public void setConfigurationChangedListener(Consumer<Configuration> listener) {
        mConfigurationChangedListener = listener;
    }

    public void setNotificationsMarginBottom(int margin) {
        MarginLayoutParams params = (MarginLayoutParams) mStackScroller.getLayoutParams();
        params.bottomMargin = margin;
        if (getResources().getBoolean(R.bool.config_sos_legacy_shade)) {
            // The original NotificationPanelView subtracts CloseDragHandle from the stack's
            // expanded viewport so the final row and footer never sit underneath the handle.
            params.bottomMargin += getResources().getDimensionPixelSize(
                    R.dimen.sos_shade_close_handle_height);
        }
        mStackScroller.setLayoutParams(params);
    }

    public void setQSContainerPaddingBottom(int paddingBottom) {
        mLastQSPaddingBottom = paddingBottom;
        if (QSComposeFragment.isEnabled()) {
            if (mQs != null) {
                mQs.setQSContentPaddingBottom(paddingBottom);
            }
        } else {
            if (mQSContainer != null) {
                mQSContainer.setPadding(
                        mQSContainer.getPaddingLeft(),
                        mQSContainer.getPaddingTop(),
                        mQSContainer.getPaddingRight(),
                        paddingBottom
                );
            }
        }
    }

    public void setQSNegativeMarginBottom(int margin) {
        if (QSComposeFragment.isEnabled() && mQsFrame != null) {
            MarginLayoutParams params = (MarginLayoutParams) mQsFrame.getLayoutParams();
            params.bottomMargin = -margin;
            mQsFrame.setLayoutParams(params);
        }
    }

    public void setInsetsChangedListener(Consumer<WindowInsets> onInsetsChangedListener) {
        mInsetsChangedListener = onInsetsChangedListener;
    }

    public void removeOnInsetsChangedListener() {
        mInsetsChangedListener = insets -> {};
    }

    public void setQSFragmentAttachedListener(Consumer<QS> qsFragmentAttachedListener) {
        mQSFragmentAttachedListener = qsFragmentAttachedListener;
        // listener might be attached after fragment is attached
        if (mQs != null) {
            mQSFragmentAttachedListener.accept(mQs);
        }
    }

    public void removeQSFragmentAttachedListener() {
        mQSFragmentAttachedListener = qs -> {};
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mInsetsChangedListener.accept(insets);
        return insets;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return TouchLogger.logDispatchTouch("NotificationsQuickSettingsContainer", ev,
                super.dispatchTouchEvent(ev));
    }

    public void applyConstraints(ConstraintSet constraintSet) {
        constraintSet.applyTo(this);
    }

    /**
     *  Scale multiple elements in tandem, for the predictive back animation.
     *  This is how the Shade responds to the Back gesture (by scaling).
     *  Without the common center, individual elements will scale about their respective centers.
     *  Scaling the entire NotificationsQuickSettingsContainer will also resize the shade header
     *  (which we don't want).
     */
    public void applyBackScaling(float scale, boolean usingSplitShade) {
        if (mStackScroller == null || mQSContainer == null) {
            return;
        }

        mQSContainer.getBoundsOnScreen(mUpperRect);
        mStackScroller.getBoundsOnScreen(mBoundingBoxRect);
        mBoundingBoxRect.union(mUpperRect);

        float cx = mBoundingBoxRect.centerX();
        float cy = mBoundingBoxRect.centerY();

        mQSContainer.setPivotX(cx);
        mQSContainer.setPivotY(cy);
        mQSContainer.setScaleX(scale);
        mQSContainer.setScaleY(scale);

        // When in large-screen split-shade mode, the notification stack scroller scales correctly
        // only if the pivot point is at the left edge of the screen (because of its dimensions).
        // When not in large-screen split-shade mode, we can scale correctly via the (cx,cy) above.
        mStackScroller.setPivotX(usingSplitShade ? 0.0f : cx);
        mStackScroller.setPivotY(cy);
        mStackScroller.setScaleX(scale);
        mStackScroller.setScaleY(scale);
    }
}
