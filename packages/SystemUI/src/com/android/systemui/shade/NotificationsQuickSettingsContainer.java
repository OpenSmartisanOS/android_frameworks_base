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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Fragment;
import android.content.Context;
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
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AboveShelfObserver;

import java.util.function.Consumer;

/**
 * The container with notification stack scroller and quick settings inside.
 */
public class NotificationsQuickSettingsContainer extends ConstraintLayout
        implements FragmentListener, AboveShelfObserver.HasViewAboveShelfChangedListener {

    /** Physical expansion feed used by the R2 HOME/PANEL status-bar mode coordinator. */
    public interface PanelStatusBarExpansionListener {
        void onExpansionChanged(
                float expandedHeight, float maxPanelHeight, boolean shadeContentAllowed);
    }

    private View mQsFrame;
    private View mStackScroller;
    private View mSharedNotificationContainer;
    private View mQsNavbarScrim;
    private View mHeader;
    private View mHeaderContent;
    private View mHeaderShadow;
    private ShadePageSwitch mPageSwitch;
    private View mNotificationsButton;
    private View mQuickSettingsButton;
    private View mClearAllContainer;
    private View mSettingsContainer;
    private View mClearAllButton;
    private View mSettingsButton;
    private NotificationShadeBackgroundView mShadeBackground;
    private View mShadeGlass;
    private final Rect mShadeGlassClip = new Rect();
    private boolean mShadeGlassClipApplied;
    private boolean mQuickSettingsPage;
    private boolean mChromeVisible;
    private int mTopInset;
    private int mBottomInset;
    private float mRawExpandedHeight;
    private float mRawMaxPanelHeight;
    private float mExpandedHeight;
    private float mMaxPanelHeight;
    private Consumer<Boolean> mPageChangedListener = quickSettings -> {};
    private Runnable mPageAnimationFinishedListener = () -> {};
    private Runnable mSearchAction = () -> {};
    private Runnable mSettingsAction = () -> {};
    private Consumer<Boolean> mPanelStatusBarVisibleListener = visible -> {};
    private Consumer<Integer> mPanelStatusBarTopInsetListener = topInset -> {};
    private PanelStatusBarExpansionListener mPanelStatusBarExpansionListener =
            (expandedHeight, maxPanelHeight, shadeContentAllowed) -> {};
    @Nullable private AnimatorSet mPageAnimator;
    private int mPageAnimationGeneration;

    private static final long PAGE_ANIMATION_DELAY = 50L;
    private static final long PAGE_ANIMATION_DURATION = 300L;
    private static final Interpolator PAGE_INTERPOLATOR = input -> {
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
        View root = getRootView();
        mSharedNotificationContainer = root.findViewById(R.id.shared_notification_container);
        if (mStackScroller == null) {
            mStackScroller = root.findViewById(R.id.notification_stack_scroller);
        }
        mQsNavbarScrim = root.findViewById(R.id.qs_navbar_scrim);
        mHeader = root.findViewById(R.id.shade_header);
        mHeaderContent = root.findViewById(R.id.shade_header_content);
        mHeaderShadow = root.findViewById(R.id.shade_header_shadow);
        mPageSwitch = root.findViewById(R.id.shade_page_switch);
        mNotificationsButton = root.findViewById(R.id.shade_notifications_button);
        mQuickSettingsButton = root.findViewById(R.id.shade_quick_settings_button);
        mClearAllContainer = root.findViewById(R.id.shade_clear_all_container);
        mSettingsContainer = root.findViewById(R.id.shade_settings_container);
        mClearAllButton = root.findViewById(R.id.shade_clear_all_button);
        mSettingsButton = root.findViewById(R.id.shade_settings_button);
        mShadeBackground = root.findViewById(R.id.shade_background);
        mShadeGlass = root.findViewById(R.id.shade_glass);

        View searchButton = root.findViewById(R.id.shade_header_search);
        searchButton.setOnClickListener(v -> mSearchAction.run());
        mNotificationsButton.setOnClickListener(v -> {
            setQuickSettingsPage(false, true);
            mPageChangedListener.accept(false);
        });
        mQuickSettingsButton.setOnClickListener(v -> {
            setQuickSettingsPage(true, true);
            mPageChangedListener.accept(true);
        });
        mSettingsButton.setOnClickListener(v -> mSettingsAction.run());
        int currentTopInset = mTopInset;
        mTopInset = -1;
        setTopInset(currentTopInset);
        applyChromeVisibility();
        int currentBottomInset = mBottomInset;
        mBottomInset = -1;
        setBottomInset(currentBottomInset);
        post(() -> {
            applyPagePosition(false);
            applyExpansionTransforms();
            updateShadeGlass();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPageAnimation();
        super.onDetachedFromWindow();
    }

    public void setPageChangedListener(Consumer<Boolean> listener) {
        mPageChangedListener = listener != null ? listener : quickSettings -> {};
    }

    public void setPageAnimationFinishedListener(Runnable listener) {
        mPageAnimationFinishedListener = listener != null ? listener : () -> {};
    }

    public void setSearchAction(@Nullable Runnable action) {
        mSearchAction = action != null ? action : () -> {};
    }

    public void setSettingsAction(@Nullable Runnable action) {
        mSettingsAction = action != null ? action : () -> {};
    }

    public void setPanelStatusBarVisibleListener(Consumer<Boolean> listener) {
        mPanelStatusBarVisibleListener = listener != null ? listener : visible -> {};
        mPanelStatusBarVisibleListener.accept(mChromeVisible);
    }

    public void setPanelStatusBarExpansionListener(
            PanelStatusBarExpansionListener listener) {
        mPanelStatusBarExpansionListener = listener != null
                ? listener
                : (expandedHeight, maxPanelHeight, shadeContentAllowed) -> {};
        mPanelStatusBarExpansionListener.onExpansionChanged(
                mExpandedHeight, mMaxPanelHeight, mChromeVisible);
    }

    public void setPanelStatusBarTopInsetListener(Consumer<Integer> listener) {
        mPanelStatusBarTopInsetListener = listener != null ? listener : topInset -> {};
        mPanelStatusBarTopInsetListener.accept(mTopInset);
    }

    public void setQuickSettingsPage(boolean quickSettings, boolean animate) {
        boolean changed = mQuickSettingsPage != quickSettings;
        mQuickSettingsPage = quickSettings;
        applyPagePosition(animate && changed);
        applyExpansionTransforms();
    }

    public boolean isQuickSettingsPage() {
        return mQuickSettingsPage;
    }

    public float getNotificationStackTopPosition() {
        return mTopInset + getResources().getDimensionPixelSize(
                R.dimen.shade_header_outer_height);
    }

    public int getPageSwitchHeight() {
        return mPageSwitch != null ? mPageSwitch.getSwitchHeight() : 0;
    }

    public void resetPage(boolean hasVisibleNotifications, boolean animate) {
        setQuickSettingsPage(!hasVisibleNotifications, animate);
        mPageChangedListener.accept(mQuickSettingsPage);
    }

    public void setChromeVisible(boolean visible) {
        if (mChromeVisible == visible) {
            return;
        }
        mChromeVisible = visible;
        applyChromeVisibility();
    }

    /** Applies stored state after inflation/reattach even when the logical value did not change. */
    private void applyChromeVisibility() {
        updateShadeGlass();
        if (mQsFrame != null) {
            mQsFrame.setVisibility(mChromeVisible ? VISIBLE : INVISIBLE);
        }
        mPanelStatusBarVisibleListener.accept(mChromeVisible);
        if (!mChromeVisible && mQsNavbarScrim != null) {
            mQsNavbarScrim.setVisibility(INVISIBLE);
        }
        if (mHeader != null) {
            mHeader.setVisibility(mChromeVisible ? VISIBLE : GONE);
        }
        if (mPageSwitch != null) {
            mPageSwitch.setVisibility(mChromeVisible ? VISIBLE : GONE);
        }
        if (mClearAllContainer != null) {
            mClearAllContainer.setVisibility(mChromeVisible ? VISIBLE : GONE);
        }
        if (mSettingsContainer != null) {
            mSettingsContainer.setVisibility(mChromeVisible ? VISIBLE : GONE);
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
    public void setPanelExpansion(
            float expandedHeight, float maxPanelHeight, boolean shadeContentAllowed) {
        mRawExpandedHeight = Math.max(0f, expandedHeight);
        mRawMaxPanelHeight = Math.max(0f, maxPanelHeight);
        if (mShadeBackground != null) {
            mShadeBackground.setExpansion(
                    mRawExpandedHeight, mRawMaxPanelHeight, shadeContentAllowed);
        }
        updateEdgeExpansionHeights();
        mPanelStatusBarExpansionListener.onExpansionChanged(
                mExpandedHeight, mMaxPanelHeight, shadeContentAllowed);
        setChromeVisible(shadeContentAllowed && mMaxPanelHeight > 0f && mExpandedHeight > 0f);
        updateShadeGlass();
        applyExpansionTransforms();
    }

    private void updateEdgeExpansionHeights() {
        mExpandedHeight = mRawExpandedHeight;
        mMaxPanelHeight = mRawMaxPanelHeight;
    }

    private void applyExpansionTransforms() {
        if (mMaxPanelHeight <= 0f) {
            return;
        }
        final float expansion =
                Math.max(0f, Math.min(1f, mExpandedHeight / mMaxPanelHeight));
        final float panelTranslation = Math.min(0f, mExpandedHeight - mMaxPanelHeight);
        if (mQsNavbarScrim != null) {
            mQsNavbarScrim.setVisibility(INVISIBLE);
            mQsNavbarScrim.setAlpha(0f);
        }
        if (mQsFrame != null) {
            mQsFrame.setTranslationY(panelTranslation);
        }
        if (mHeader != null) {
            mHeader.setTranslationY(panelTranslation);
            mHeader.setAlpha(mQuickSettingsPage ? 1f : expansion);
        }
        if (mPageSwitch != null) {
            mPageSwitch.setExpandedHeight(mExpandedHeight);
        }
        if (mSettingsContainer != null) {
            mSettingsContainer.setTranslationY(panelTranslation);
        }
        if (mClearAllContainer != null) {
            mClearAllContainer.setTranslationY(panelTranslation);
        }
        if (mClearAllContainer != null && mPageSwitch != null && mPageSwitch.getSwitchHeight() > 0) {
            final float reveal = Math.max(
                    0f,
                    Math.min(
                            1f,
                            1f - ((mMaxPanelHeight - mExpandedHeight) * 3f
                                    / mPageSwitch.getSwitchHeight())));
            mClearAllContainer.setScaleX(reveal);
            mClearAllContainer.setScaleY(reveal);
            mClearAllContainer.setAlpha(reveal);
        }
    }

    public void setTopInset(int topInset) {
        if (mTopInset == topInset) {
            return;
        }
        mTopInset = topInset;
        updateShadeGlass();
        mPanelStatusBarTopInsetListener.accept(topInset);
        if (mHeader != null && mHeaderContent != null) {
            ViewGroup.LayoutParams headerParams = mHeader.getLayoutParams();
            headerParams.height = topInset + getResources().getDimensionPixelSize(
                    R.dimen.shade_header_outer_height);
            mHeader.setLayoutParams(headerParams);

            ViewGroup.MarginLayoutParams contentParams =
                    (ViewGroup.MarginLayoutParams) mHeaderContent.getLayoutParams();
            contentParams.topMargin = topInset;
            mHeaderContent.setLayoutParams(contentParams);
        }
        if (mHeaderShadow != null) {
            ViewGroup.MarginLayoutParams shadowParams =
                    (ViewGroup.MarginLayoutParams) mHeaderShadow.getLayoutParams();
            shadowParams.topMargin = topInset + getResources().getDimensionPixelSize(
                    R.dimen.shade_header_height);
            mHeaderShadow.setLayoutParams(shadowParams);
        }
    }

    public void setBottomInset(int bottomInset) {
        if (mBottomInset == bottomInset) {
            return;
        }
        mBottomInset = bottomInset;
        if (mClearAllContainer != null) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) mClearAllContainer.getLayoutParams();
            params.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.shade_action_margin_bottom) + bottomInset;
            mClearAllContainer.setLayoutParams(params);
        }
        if (mSettingsContainer != null) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) mSettingsContainer.getLayoutParams();
            params.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.shade_action_margin_bottom) + bottomInset;
            mSettingsContainer.setLayoutParams(params);
        }
        updateEdgeExpansionHeights();
        applyExpansionTransforms();
    }

    private void applyPagePosition(boolean animate) {
        if (mQsFrame == null || mSharedNotificationContainer == null
                || mHeader == null || mPageSwitch == null
                || mClearAllContainer == null || mSettingsContainer == null
                || mClearAllButton == null || mSettingsButton == null
                || mNotificationsButton == null || mQuickSettingsButton == null) {
            return;
        }
        float width = getRootView() != null ? getRootView().getWidth() : getWidth();
        if (width == 0) {
            return;
        }
        float notificationX = mQuickSettingsPage ? -width : 0f;
        float qsX = mQuickSettingsPage ? 0f : width;
        final View notificationPage = mStackScroller != null
                ? mStackScroller : mSharedNotificationContainer;
        cancelPageAnimation();
        // Older builds translated the whole Android 16 shared host. Keep that host fixed: only
        // the original notification pile participates in R2's page switch.
        mSharedNotificationContainer.setTranslationX(0f);
        if (animate) {
            mQsFrame.setLayerType(LAYER_TYPE_HARDWARE, null);
            notificationPage.setLayerType(LAYER_TYPE_HARDWARE, null);
            mClearAllContainer.setLayerType(LAYER_TYPE_HARDWARE, null);
            mSettingsContainer.setLayerType(LAYER_TYPE_HARDWARE, null);
            mQsFrame.buildLayer();
            notificationPage.buildLayer();
            mClearAllContainer.buildLayer();
            mSettingsContainer.buildLayer();

            final int generation = ++mPageAnimationGeneration;
            AnimatorSet animator = new AnimatorSet();
            animator.playTogether(
                    ObjectAnimator.ofFloat(mQsFrame, View.TRANSLATION_X, qsX),
                    ObjectAnimator.ofFloat(notificationPage, View.TRANSLATION_X, notificationX),
                    ObjectAnimator.ofFloat(
                            mClearAllContainer, View.TRANSLATION_X, notificationX),
                    ObjectAnimator.ofFloat(mSettingsContainer, View.TRANSLATION_X, qsX));
            animator.setStartDelay(PAGE_ANIMATION_DELAY);
            animator.setDuration(PAGE_ANIMATION_DURATION);
            animator.setInterpolator(PAGE_INTERPOLATOR);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (generation != mPageAnimationGeneration) {
                        return;
                    }
                    mPageAnimator = null;
                    clearPageLayers();
                    mPageAnimationFinishedListener.run();
                }
            });
            mPageAnimator = animator;
            animator.start();
        } else {
            mQsFrame.setTranslationX(qsX);
            notificationPage.setTranslationX(notificationX);
            mClearAllContainer.setTranslationX(notificationX);
            mSettingsContainer.setTranslationX(qsX);
            clearPageLayers();
            mPageAnimationFinishedListener.run();
        }
        setPageButtonChecked(mNotificationsButton, !mQuickSettingsPage);
        setPageButtonChecked(mQuickSettingsButton, mQuickSettingsPage);
        mQsFrame.setImportantForAccessibility(mQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        mSharedNotificationContainer.setImportantForAccessibility(mQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mClearAllContainer.setImportantForAccessibility(mQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mSettingsContainer.setImportantForAccessibility(mQuickSettingsPage
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static void setPageButtonChecked(View button, boolean checked) {
        button.setSelected(checked);
        button.setActivated(checked);
        if (button instanceof Checkable) {
            ((Checkable) button).setChecked(checked);
        }
    }

    private void clearPageLayers() {
        if (mQsFrame == null || mSharedNotificationContainer == null
                || mClearAllContainer == null || mSettingsContainer == null) {
            return;
        }
        mQsFrame.setLayerType(LAYER_TYPE_NONE, null);
        final View notificationPage = mStackScroller != null
                ? mStackScroller : mSharedNotificationContainer;
        notificationPage.setLayerType(LAYER_TYPE_NONE, null);
        mClearAllContainer.setLayerType(LAYER_TYPE_NONE, null);
        mSettingsContainer.setLayerType(LAYER_TYPE_NONE, null);
    }

    private void cancelPageAnimation() {
        final boolean hadRunningAnimation = mPageAnimator != null;
        mPageAnimationGeneration++;
        if (mPageAnimator != null) {
            mPageAnimator.cancel();
            mPageAnimator = null;
        }
        clearPageLayers();
        if (hadRunningAnimation) {
            mPageAnimationFinishedListener.run();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            cancelPageAnimation();
            post(() -> {
                applyPagePosition(false);
                updateShadeGlass();
            });
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
        cancelPageAnimation();
        post(this::updateShadeGlass);
        if (mConfigurationChangedListener != null) {
            mConfigurationChangedListener.accept(newConfig);
        }
    }

    /** Crops the panel surface to the physical shade edge while leaving the status bar dark. */
    private void updateShadeGlass() {
        if (mShadeGlass == null) {
            return;
        }
        final int width = mShadeGlass.getWidth();
        final int height = mShadeGlass.getHeight();
        final int top = Math.max(0, Math.min(mTopInset, height));
        final int bottom = Math.max(top, Math.min(Math.round(mRawExpandedHeight), height));
        final boolean visible = mChromeVisible && width > 0 && bottom > top;
        if (visible) {
            if (!mShadeGlassClipApplied
                    || mShadeGlassClip.left != 0
                    || mShadeGlassClip.top != top
                    || mShadeGlassClip.right != width
                    || mShadeGlassClip.bottom != bottom) {
                mShadeGlassClip.set(0, top, width, bottom);
                mShadeGlass.setClipBounds(mShadeGlassClip);
                mShadeGlassClipApplied = true;
            }
            if (mShadeGlass.getVisibility() != VISIBLE) {
                mShadeGlass.setVisibility(VISIBLE);
            }
        } else {
            // Keep a zero-sized clip installed while collapsed. View then reuses its internal Rect
            // on the first expanded frame instead of allocating one in the gesture hot path.
            if (!mShadeGlassClipApplied || !mShadeGlassClip.isEmpty()) {
                mShadeGlassClip.setEmpty();
                mShadeGlass.setClipBounds(mShadeGlassClip);
                mShadeGlassClipApplied = true;
            }
            if (mShadeGlass.getVisibility() != INVISIBLE) {
                mShadeGlass.setVisibility(INVISIBLE);
            }
        }
    }

    public void setConfigurationChangedListener(Consumer<Configuration> listener) {
        mConfigurationChangedListener = listener;
    }

    public void setNotificationsMarginBottom(int margin) {
        MarginLayoutParams params = (MarginLayoutParams) mStackScroller.getLayoutParams();
        // The 15dp shadow hangs below the physical panel edge. Content reserves only the original
        // 72dp visible handle, never an Android navigation-bar inset.
        params.bottomMargin = margin + getResources().getDimensionPixelSize(
                R.dimen.shade_page_switch_container_height);
        mStackScroller.setLayoutParams(params);
    }

    public void setQSContainerPaddingBottom(int paddingBottom) {
        mLastQSPaddingBottom = paddingBottom;
        if (mQSContainer != null) {
            mQSContainer.setPadding(
                    mQSContainer.getPaddingLeft(),
                    mQSContainer.getPaddingTop(),
                    mQSContainer.getPaddingRight(),
                    paddingBottom
            );
        }
    }

    public void setQSNegativeMarginBottom(int margin) {
        // The canonical R2 QS is edge-to-edge and never uses the Compose QS negative margin.
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
