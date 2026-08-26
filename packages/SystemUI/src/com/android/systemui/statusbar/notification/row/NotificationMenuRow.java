/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static android.view.HapticFeedbackConstants.CLOCK_TICK;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Flags;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.AlphaOptimizedImageView;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationClassificationUiFlag;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.NotificationGuts.GutsContent;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NotificationMenuRow implements NotificationMenuRowPlugin, View.OnClickListener,
        ExpandableNotificationRow.LayoutListener {

    private static final String TAG = "NotificationMenuRow";

    // Notification must be swiped at least this fraction of a single menu item to show menu
    private static final float SWIPED_FAR_ENOUGH_MENU_FRACTION = 0.25f;
    private static final float SWIPED_FAR_ENOUGH_MENU_UNCLEARABLE_FRACTION = 0.15f;

    // When the menu is displayed, the notification must be swiped within this fraction of a single
    // menu item to snap back to menu (else it will cover the menu or it'll be dismissed)
    private static final float SWIPED_BACK_ENOUGH_TO_COVER_FRACTION = 0.2f;

    private static final int ICON_ALPHA_ANIM_DURATION = 200;
    private static final long SHOW_MENU_DELAY = 60;

    private ExpandableNotificationRow mParent;

    private Context mContext;
    private FrameLayout mMenuContainer;
    private NotificationMenuItem mInfoItem;
    private MenuItem mFeedbackItem;
    private MenuItem mSnoozeItem;
    private ArrayList<MenuItem> mLeftMenuItems;
    private ArrayList<MenuItem> mRightMenuItems;
    private final Map<View, MenuItem> mMenuItemsByView = new ArrayMap<>();
    private OnMenuEventListener mMenuListener;

    private ValueAnimator mFadeAnimator;
    private boolean mAnimating;
    private boolean mMenuFadedIn;

    private boolean mOnLeft;
    private boolean mIconsPlaced;

    private boolean mDismissing;
    private boolean mSnapping;
    private float mTranslation;

    private int[] mIconLocation = new int[2];
    private int[] mParentLocation = new int[2];

    private int mHorizSpaceForIcon = -1;
    private int mVertSpaceForIcons = -1;
    private int mIconPadding = -1;
    private int mSidePadding;

    private float mAlpha = 0f;

    private CheckForDrag mCheckForDrag;
    private Handler mHandler;

    private boolean mMenuSnapped;
    private boolean mMenuSnappedOnLeft;
    private boolean mShouldShowMenu;

    private boolean mIsUserTouching;

    private boolean mSnappingToDismiss;

    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;

    private NotificationActivityStarter mNotificationActivityStarter;

    public NotificationMenuRow(Context context,
            PeopleNotificationIdentifier peopleNotificationIdentifier,
            NotificationActivityStarter notificationActivityStarter) {
        mContext = context;
        mShouldShowMenu = context.getResources().getBoolean(R.bool.config_showNotificationGear);
        mHandler = new Handler(Looper.getMainLooper());
        mLeftMenuItems = new ArrayList<>();
        mRightMenuItems = new ArrayList<>();
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
        mNotificationActivityStarter = notificationActivityStarter;
    }

    @Override
    public ArrayList<MenuItem> getMenuItems(Context context) {
        return mOnLeft ? mLeftMenuItems : mRightMenuItems;
    }

    @Override
    public MenuItem getLongpressMenuItem(Context context) {
        return mInfoItem;
    }

    @Override
    public MenuItem getFeedbackMenuItem(Context context) {
        return mFeedbackItem;
    }

    @Override
    public MenuItem getSnoozeMenuItem(Context context) {
        return mSnoozeItem;
    }

    @VisibleForTesting
    protected ExpandableNotificationRow getParent() {
        return mParent;
    }

    @VisibleForTesting
    protected boolean isMenuOnLeft() {
        return mOnLeft;
    }

    @VisibleForTesting
    protected boolean isMenuSnappedOnLeft() {
        return mMenuSnappedOnLeft;
    }

    @VisibleForTesting
    protected boolean isMenuSnapped() {
        return mMenuSnapped;
    }

    @VisibleForTesting
    protected boolean isDismissing() {
        return mDismissing;
    }

    @VisibleForTesting
    protected boolean isSnapping() {
        return mSnapping;
    }

    @VisibleForTesting
    protected boolean isSnappingToDismiss() {
        return mSnappingToDismiss;
    }

    @Override
    public void setMenuClickListener(OnMenuEventListener listener) {
        mMenuListener = listener;
    }

    @Override
    public void createMenu(ViewGroup parent) {
        mParent = (ExpandableNotificationRow) parent;
        createMenuViews(true /* resetState */);
    }

    @Override
    public boolean isMenuVisible() {
        return mAlpha > 0;
    }

    @VisibleForTesting
    protected boolean isUserTouching() {
        return mIsUserTouching;
    }

    @Override
    public boolean shouldShowMenu() {
        return mShouldShowMenu;
    }

    @Override
    public View getMenuView() {
        return mMenuContainer;
    }

    @VisibleForTesting
    protected float getTranslation() {
        return mTranslation;
    }

    @Override
    public void resetMenu() {
        resetState(true);
    }

    @Override
    public void onTouchEnd() {
        mIsUserTouching = false;
    }

    @Override
    public void onNotificationUpdated() {
        if (mMenuContainer == null) {
            // Menu hasn't been created yet, no need to do anything.
            return;
        }
        createMenuViews(!isMenuVisible() /* resetState */);
    }

    @Override
    public void onConfigurationChanged() {
        mParent.setLayoutListener(this);
    }

    @Override
    public void onLayout() {
        mIconsPlaced = false; // Force icons to be re-placed
        setMenuLocation();
        mParent.setLayoutListener(null);
    }

    private void createMenuViews(boolean resetState) {
        final Resources res = mContext.getResources();
        mHorizSpaceForIcon = res.getDimensionPixelSize(R.dimen.sos_notification_menu_width);
        mVertSpaceForIcons = res.getDimensionPixelSize(R.dimen.sos_notification_min_height);
        mIconPadding = res.getDimensionPixelSize(R.dimen.sos_notification_menu_icon_padding);
        mLeftMenuItems.clear();
        mRightMenuItems.clear();

        mSnoozeItem = null;
        mFeedbackItem = null;
        // Classification bundles have no package/channel. Controls stay on real children.
        mInfoItem = NotificationBlockDialogController.canShow(mParent)
                ? createBlockInfoItem(mContext) : null;
        if (mInfoItem != null) {
            // R2 exposes the block button only on a physical left swipe.
            mRightMenuItems.add(mInfoItem);
        }
        populateMenuViews();
        if (resetState) {
            resetState(false /* notify */);
        } else {
            mIconsPlaced = false;
            setMenuLocation();
            if (!mIsUserTouching) {
                onSnapOpen();
            }
        }
    }

    private void populateMenuViews() {
        if (mMenuContainer != null) {
            mMenuContainer.removeAllViews();
            mMenuItemsByView.clear();
        } else {
            mMenuContainer = new FrameLayout(mContext);
        }

        // Populate menu items if we are using the new permission helper (U+) or if we are using
        // the very old dismiss setting (SC-).
        List<MenuItem> menuItems = mOnLeft ? mLeftMenuItems : mRightMenuItems;
        for (int i = 0; i < menuItems.size(); i++) {
            addMenuView(menuItems.get(i), mMenuContainer);
        }
    }

    private void resetState(boolean notify) {
        setMenuAlpha(0f);
        mIconsPlaced = false;
        mMenuFadedIn = false;
        mAnimating = false;
        mSnapping = false;
        mDismissing = false;
        mMenuSnapped = false;
        setMenuLocation();
        if (mMenuListener != null && notify) {
            mMenuListener.onMenuReset(mParent);
        }
    }

    @Override
    public void onTouchMove(float delta) {
        mSnapping = false;

        if (!isTowardsMenu(delta) && isMenuLocationChange()) {
            // Don't consider it "snapped" if location has changed.
            mMenuSnapped = false;

            // Changed directions, make sure we check to fade in icon again.
            if (!mHandler.hasCallbacks(mCheckForDrag)) {
                // No check scheduled, set null to schedule a new one.
                mCheckForDrag = null;
            } else {
                // Check scheduled, reset alpha and update location; check will fade it in
                setMenuAlpha(0f);
                setMenuLocation();
            }
        }
        if (mShouldShowMenu
                && !NotificationStackScrollLayout.isPinnedHeadsUp(getParent())
                && !mParent.areGutsExposed()
                && !mParent.showingPulsing()
                && (mCheckForDrag == null || !mHandler.hasCallbacks(mCheckForDrag))) {
            // Only show the menu if we're not a heads up view and guts aren't exposed.
            mCheckForDrag = new CheckForDrag();
            mHandler.postDelayed(mCheckForDrag, SHOW_MENU_DELAY);
        }
        if (canBeDismissed()) {
            final float dismissThreshold = getDismissThreshold();
            final boolean snappingToDismiss = delta < -dismissThreshold || delta > dismissThreshold;
            if (mSnappingToDismiss != snappingToDismiss) {
                getMenuView().performHapticFeedback(CLOCK_TICK);
            }
            mSnappingToDismiss = snappingToDismiss;
        }
    }

    @VisibleForTesting
    protected void beginDrag() {
        mSnapping = false;
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }
        mHandler.removeCallbacks(mCheckForDrag);
        mCheckForDrag = null;
        mIsUserTouching = true;
    }

    @Override
    public void onTouchStart() {
        beginDrag();
        mSnappingToDismiss = false;
    }

    @Override
    public void onSnapOpen() {
        mMenuSnapped = true;
        mMenuSnappedOnLeft = isMenuOnLeft();
        if (mAlpha == 0f && mParent != null) {
            fadeInMenu(mParent.getWidth());
        }
        if (mMenuListener != null) {
            mMenuListener.onMenuShown(getParent());
        }
    }

    @Override
    public void onSnapClosed() {
        cancelDrag();
        mMenuSnapped = false;
        mSnapping = true;
    }

    @Override
    public void onDismiss() {
        cancelDrag();
        mMenuSnapped = false;
        mDismissing = true;
    }

    @VisibleForTesting
    protected void cancelDrag() {
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }
        mHandler.removeCallbacks(mCheckForDrag);
    }

    @VisibleForTesting
    protected float getMinimumSwipeDistance() {
        final float multiplier = getParent().canViewBeDismissed() ? 0.4f : 0.2f;
        return mHorizSpaceForIcon * multiplier;
    }

    @VisibleForTesting
    protected float getMaximumSwipeDistance() {
        return mHorizSpaceForIcon * 0.4f;
    }

    /**
     * Returns whether the gesture is towards the menu location or not.
     */
    @Override
    public boolean isTowardsMenu(float movement) {
        return isMenuVisible() && movement >= 0f;
    }

    @Override
    public void setAppName(String appName) {
        if (appName == null) {
            return;
        }
        setAppName(appName, mLeftMenuItems);
        setAppName(appName, mRightMenuItems);
    }

    private void setAppName(String appName, ArrayList<MenuItem> menuItems) {
        Resources res = mContext.getResources();
        final int count = menuItems.size();
        for (int i = 0; i < count; i++) {
            MenuItem item = menuItems.get(i);
            item.setAppName(appName);
        }
    }

    @Override
    public void onParentHeightUpdate() {
        // If we are using only icon-based buttons, adjust layout for height changes.
        // For permission helper full-layout buttons, do not adjust.
        if (!Flags.permissionHelperInlineUiRichOngoing()) {
            if (mParent == null
                    || (mLeftMenuItems.isEmpty() && mRightMenuItems.isEmpty())
                    || mMenuContainer == null) {
                return;
            }
            int parentHeight = mParent.getActualHeight();
            float translationY;
            if (parentHeight < mVertSpaceForIcons) {
                translationY = (parentHeight / 2) - (mHorizSpaceForIcon / 2);
            } else {
                translationY = (mVertSpaceForIcons - mHorizSpaceForIcon) / 2;
            }
            mMenuContainer.setTranslationY(translationY);
        }
    }

    @Override
    public void onParentTranslationUpdate(float translation) {
        mTranslation = translation;
        if (mAnimating || !mMenuFadedIn) {
            // Don't adjust when animating, or if the menu hasn't been shown yet.
            return;
        }
        final float fadeThreshold = mParent.getWidth() * 0.3f;
        final float absTrans = Math.abs(translation);
        float desiredAlpha = 0;
        if (absTrans == 0) {
            desiredAlpha = 0;
        } else if (absTrans <= fadeThreshold) {
            desiredAlpha = 1;
        } else {
            desiredAlpha = 1 - ((absTrans - fadeThreshold) / (mParent.getWidth() - fadeThreshold));
        }
        setMenuAlpha(desiredAlpha);
    }

    @Override
    public void onClick(View v) {
        if (mMenuListener == null) {
            // Nothing to do
            return;
        }
        v.getLocationOnScreen(mIconLocation);
        mParent.getLocationOnScreen(mParentLocation);
        final int centerX = mHorizSpaceForIcon / 2;
        final int centerY = v.getHeight() / 2;
        final int x = mIconLocation[0] - mParentLocation[0] + centerX;
        final int y = mIconLocation[1] - mParentLocation[1] + centerY;
        if (mMenuItemsByView.containsKey(v)) {
            mMenuListener.onMenuClicked(mParent, x, y, mMenuItemsByView.get(v));
        }
    }

    private boolean isMenuLocationChange() {
        return false;
    }

    private void setMenuLocation() {
        boolean showOnLeft = false;
        if ((mIconsPlaced && showOnLeft == isMenuOnLeft()) || isSnapping() || mMenuContainer == null
                || !mMenuContainer.isAttachedToWindow()) {
            // Do nothing
            return;
        }
        boolean wasOnLeft = mOnLeft;
        mOnLeft = showOnLeft;
        if (wasOnLeft != showOnLeft) {
            populateMenuViews();
        }
        final int count = mMenuContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            final View v = mMenuContainer.getChildAt(i);
            final float left = i * mHorizSpaceForIcon;
            final float right = mParent.getWidth() - (mHorizSpaceForIcon * (i + 1));
            v.setX(showOnLeft ? left : right);
        }
        mIconsPlaced = true;
    }

    @VisibleForTesting
    protected void setMenuAlpha(float alpha) {
        mAlpha = alpha;
        if (mMenuContainer == null) {
            return;
        }
        if (alpha == 0) {
            mMenuFadedIn = false; // Can fade in again once it's gone.
            mMenuContainer.setVisibility(View.INVISIBLE);
        } else {
            mMenuContainer.setVisibility(View.VISIBLE);
        }
        final int count = mMenuContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = mMenuContainer.getChildAt(i);
            child.setAlpha(mAlpha);
            child.setScaleX(mAlpha);
            child.setScaleY(mAlpha);
        }
    }

    /**
     * Returns the horizontal space in pixels required to display the menu.
     */
    @VisibleForTesting
    protected int getSpaceForMenu() {
        return mHorizSpaceForIcon * mMenuContainer.getChildCount();
    }

    private final class CheckForDrag implements Runnable {
        @Override
        public void run() {
            final float absTransX = Math.abs(mTranslation);
            final float bounceBackToMenuWidth = getSpaceForMenu();
            final float notiThreshold = mParent.getWidth() * 0.4f;
            if ((!isMenuVisible() || isMenuLocationChange())
                    && absTransX >= bounceBackToMenuWidth * 0.4
                    && absTransX < notiThreshold
                    && mTranslation < 0f) {
                fadeInMenu(notiThreshold);
            }
        }
    }

    private void fadeInMenu(final float notiThreshold) {
        if (mDismissing || mAnimating) {
            return;
        }
        if (isMenuLocationChange()) {
            setMenuAlpha(0f);
        }
        final float transX = mTranslation;
        final boolean fromLeft = mTranslation > 0;
        setMenuLocation();
        mFadeAnimator = ValueAnimator.ofFloat(mAlpha, 1);
        mFadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float absTrans = Math.abs(transX);

                boolean pastMenu = (fromLeft && transX <= notiThreshold)
                        || (!fromLeft && absTrans <= notiThreshold);
                if (pastMenu && !mMenuFadedIn) {
                    setMenuAlpha((float) animation.getAnimatedValue());
                }
            }
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mAnimating = true;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // TODO should animate back to 0f from current alpha
                setMenuAlpha(0f);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimating = false;
                mMenuFadedIn = mAlpha == 1;
            }
        });
        mFadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mFadeAnimator.setDuration(ICON_ALPHA_ANIM_DURATION);
        mFadeAnimator.start();
    }

    @Override
    public void setMenuItems(ArrayList<MenuItem> items) {
        // Do nothing we use our own for now.
        // TODO -- handle / allow custom menu items!
    }

    @Override
    public boolean shouldShowGutsOnSnapOpen() {
        return false;
    }

    @Override
    public MenuItem menuItemToExposeOnSnap() {
        return null;
    }

    @Override
    public Point getRevealAnimationOrigin() {
        View v = mInfoItem.getMenuView();
        int menuX = v.getLeft() + v.getPaddingLeft() + (v.getWidth() / 2);
        int menuY = v.getTop() + v.getPaddingTop() + (v.getHeight() / 2);
        if (isMenuOnLeft()) {
            return new Point(menuX, menuY);
        } else {
            menuX = mParent.getRight() - menuX;
            return new Point(menuX, menuY);
        }
    }

    @VisibleForTesting static MenuItem createSnoozeItem(Context context) {
        Resources res = context.getResources();
        NotificationSnooze content = (NotificationSnooze) LayoutInflater.from(context)
                .inflate(R.layout.notification_snooze, null, false);
        String snoozeDescription = res.getString(R.string.notification_menu_snooze_description);
        int snoozeId;
        if (Flags.permissionHelperInlineUiRichOngoing()) {
            snoozeId = NotificationMenuItem.OMIT_FROM_SWIPE_MENU;
        } else {
            snoozeId = R.drawable.ic_snooze;
        }
        MenuItem snooze = new NotificationMenuItem(context, snoozeDescription, content,
                snoozeId);
        return snooze;
    }

    static MenuItem createDemoteItem(Context context) {
        PromotedPermissionGutsContent demoteContent =
                (PromotedPermissionGutsContent) LayoutInflater.from(context).inflate(
                R.layout.promoted_permission_guts, null, false);
        View demoteButton = LayoutInflater.from(context)
                .inflate(R.layout.promoted_menu_item, null, false);
        MenuItem info = new NotificationMenuItem(context, null, demoteContent,
                demoteButton) {
            @Override
            public void setAppName(String appName) {
                super.setAppName(appName);
                demoteContent.setAppName(appName);
            }
        };

        return info;
    }

    static NotificationMenuItem createConversationItem(Context context) {
        Resources res = context.getResources();
        String infoDescription = res.getString(R.string.notification_menu_gear_description);
        NotificationConversationInfo infoContent =
                (NotificationConversationInfo) LayoutInflater.from(context).inflate(
                        R.layout.notification_conversation_info, null, false);
        return new NotificationMenuItem(context, infoDescription, infoContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
    }

    static NotificationMenuItem createPromotedItem(Context context) {
        Resources res = context.getResources();
        String infoDescription = res.getString(R.string.notification_menu_gear_description);
        PromotedNotificationInfo infoContent =
                (PromotedNotificationInfo) LayoutInflater.from(context).inflate(
                        R.layout.promoted_notification_info, null, false);
        return new NotificationMenuItem(context, infoDescription, infoContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
    }

    static NotificationMenuItem createPartialConversationItem(Context context) {
        Resources res = context.getResources();
        String infoDescription = res.getString(R.string.notification_menu_gear_description);
        PartialConversationInfo infoContent =
                (PartialConversationInfo) LayoutInflater.from(context).inflate(
                        R.layout.partial_conversation_info, null, false);
        return new NotificationMenuItem(context, infoDescription, infoContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
    }

    static NotificationMenuItem createBundledInfoItem(Context context) {
        Resources res = context.getResources();
        String infoDescription = res.getString(R.string.notification_menu_gear_description);
        int layoutId = R.layout.bundled_notification_info;
        BundledNotificationInfo infoContent =
                (BundledNotificationInfo) LayoutInflater.from(context).inflate(
                        layoutId, null, false);
        return new NotificationMenuItem(context, infoDescription, infoContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
    }

    NotificationMenuItem createBundleHeaderInfoItem() {
        BundleHeaderGutsContent infoContent = new BundleHeaderGutsContent(mContext);
        initializeBundleHeaderGutsContent(infoContent);
        // TODO(b/409748420): correct infoDescription?
        String infoDescription =
                mContext.getResources().getString(R.string.notification_menu_gear_description);
        return new NotificationMenuItem(mContext, infoDescription, infoContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
    }

    /**
     * Sets up the {@link BundleHeaderGutsContent} inside the notification row's guts.
     * @param gutsContent view to set up/bind within {@code mParent}
     */
    @VisibleForTesting
    void initializeBundleHeaderGutsContent(BundleHeaderGutsContent gutsContent) {
        Function0<Unit> onSettingsClicked = () -> {
            mParent.getGuts().resetFalsingCheck();
            startBundleSettingsActivity(0, mParent);
            return Unit.INSTANCE;
        };

        Function0<Unit> onDismissBundle = () -> {
            mParent.getGuts().resetFalsingCheck();
            mParent.getDismissButtonOnClickListener().onClick(gutsContent.getContentView());
            return Unit.INSTANCE;
        };

        Function2<Integer, Boolean, Unit> enableBundle = (type, enable) -> {
            try {
                mContext.getSystemService(NotificationManager.class)
                        .setAssistantClassificationTypeState(type, enable);
                if (!enable) {
                    // if disabling a bundle, we also need to re-sort all notifications in it
                    List<ExpandableNotificationRow> children = mParent.getAttachedChildren();
                    if (children != null) {
                        for (ExpandableNotificationRow child : children) {
                            child.getEntryAdapter().onBundleDisabledForEntry();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Couldn't set bundle state", e);
            }
            return Unit.INSTANCE;
        };

        gutsContent.bindNotification(mParent, onSettingsClicked, onDismissBundle, enableBundle);
    }

    private void startBundleSettingsActivity(final int appUid,
            ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_NOTIFICATION_BUNDLES);
        mNotificationActivityStarter.startNotificationGutsIntent(intent, appUid, row);
    }

    static NotificationMenuItem createInfoItem(Context context) {
        Resources res = context.getResources();
        String infoDescription = res.getString(R.string.notification_menu_gear_description);
        NotificationInfo infoContent = (NotificationInfo) LayoutInflater.from(context).inflate(
                R.layout.notification_info, null, false);
        return new NotificationMenuItem(context, infoDescription, infoContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
    }

    static NotificationMenuItem createBlockInfoItem(Context context) {
        Resources res = context.getResources();
        String description = res.getString(R.string.sos_notification_block);
        return new BlockMenuItem(context, description);
    }

    public static boolean isBlockItem(@Nullable MenuItem item) {
        return item instanceof BlockMenuItem;
    }

    static MenuItem createFeedbackItem(Context context) {
        FeedbackInfo feedbackContent = (FeedbackInfo) LayoutInflater.from(context).inflate(
                R.layout.feedback_info, null, false);
        MenuItem info = new NotificationMenuItem(context, null, feedbackContent,
                NotificationMenuItem.OMIT_FROM_SWIPE_MENU);
        return info;
    }

    private void addMenuView(MenuItem item, ViewGroup parent) {
        View menuView = item.getMenuView();
        if (menuView != null) {
            menuView.setAlpha(mAlpha);
            parent.addView(menuView);
            menuView.setOnClickListener(this);
            if (menuView instanceof ImageView) {
                FrameLayout.LayoutParams lp = (LayoutParams) menuView.getLayoutParams();
                lp.width = mHorizSpaceForIcon;
                lp.height = mHorizSpaceForIcon;
                menuView.setLayoutParams(lp);
            }
        }
        mMenuItemsByView.put(menuView, item);
    }

    @VisibleForTesting
    /**
     * Determine the minimum offset below which the menu should snap back closed.
     */
    protected float getSnapBackThreshold() {
        return getSpaceForMenu() - getMaximumSwipeDistance();
    }

    /**
     * Determine the maximum offset above which the parent notification should be dismissed.
     * @return
     */
    @VisibleForTesting
    protected float getDismissThreshold() {
        return getParent().getWidth() * 0.4f;
    }

    @Override
    public boolean isWithinSnapMenuThreshold() {
        if (getSpaceForMenu() == 0) {
            // don't snap open if there are no items
            return false;
        }
        float translation = getTranslation();
        float snapBackThreshold = getSnapBackThreshold();
        float targetRight = getDismissThreshold();
        return isMenuOnLeft()
                ? translation > snapBackThreshold && translation < targetRight
                : translation < -snapBackThreshold && translation > -targetRight;
    }

    @Override
    public boolean isSwipedEnoughToShowMenu() {
        final float minimumSwipeDistance = getMinimumSwipeDistance();
        final float translation = getTranslation();
        return isMenuVisible() && translation < -minimumSwipeDistance;
    }

    @Override
    public int getMenuSnapTarget() {
        return -getSpaceForMenu();
    }

    @Override
    public boolean shouldSnapBack() {
        float translation = getTranslation();
        float targetLeft = getSnapBackThreshold();
        return translation > -targetLeft;
    }

    @Override
    public boolean isSnappedAndOnSameSide() {
        return isMenuSnapped() && isMenuVisible() && !isMenuSnappedOnLeft();
    }

    @Override
    public boolean canBeDismissed() {
        return getParent().canViewBeDismissed();
    }

    public static class NotificationMenuItem implements MenuItem {

        // Constant signaling that this MenuItem should not appear in slow swipe.
        public static final int OMIT_FROM_SWIPE_MENU = -1;

        View mMenuView;
        GutsContent mGutsContent;
        String mContentDescription;
        Resources mResources;

        /**
         * Add a new 'guts' panel. If iconResId < 0 it will not appear in the slow swipe menu
         * but can still be exposed via other affordances.
         */
        public NotificationMenuItem(Context context, String contentDescription, GutsContent content,
                int iconResId) {
            mResources = context.getResources();
            int padding = mResources.getDimensionPixelSize(
                    R.dimen.sos_notification_menu_icon_padding);
            int tint = mResources.getColor(R.color.notification_gear_color);
            if (iconResId >= 0) {
                AlphaOptimizedImageView iv = new AlphaOptimizedImageView(context);
                iv.setPadding(padding, padding, padding, padding);
                Drawable icon = context.getResources().getDrawable(iconResId);
                iv.setImageDrawable(icon);
                iv.setAlpha(1f);
                mMenuView = iv;
            }
            mContentDescription = contentDescription;
            mGutsContent = content;
        }


        /**
         * Add a new 'guts' panel with custom view.
         */
        public NotificationMenuItem(Context context, String contentDescription, GutsContent content,
                View itemView) {
            mResources = context.getResources();
            mMenuView = itemView;
            mContentDescription = contentDescription;
            mGutsContent = content;
        }

        @Override
        @Nullable
        public View getMenuView() {
            return mMenuView;
        }

        @Override
        public GutsContent getGutsContent() {
            return mGutsContent;
        }

        @Override
        public String getContentDescription() {
            return mContentDescription;
        }

        @Override
        public void setAppName(String appName) {
            String description = String.format(
                    mResources.getString(R.string.notification_menu_accessibility),
                    appName, getContentDescription());
            View menuView = getMenuView();
            if (menuView != null) {
                menuView.setContentDescription(description);
            }
        }
    }

    /** Typed item used to route R2's sole swipe affordance away from NotificationGuts. */
    private static final class BlockMenuItem extends NotificationMenuItem {
        BlockMenuItem(Context context, String description) {
            super(context, description, null, OMIT_FROM_SWIPE_MENU);
            final int width = context.getResources().getDimensionPixelSize(
                    R.dimen.sos_notification_menu_width);
            final int iconWidth = Math.round(36f * context.getResources()
                    .getDisplayMetrics().density);
            final int iconHeight = Math.round(48f * context.getResources()
                    .getDisplayMetrics().density);
            final AlphaOptimizedImageView icon = new AlphaOptimizedImageView(context);
            icon.setImageResource(R.drawable.sos_ic_notification_block);
            icon.setPadding(Math.max(0, (width - iconWidth) / 2),
                    Math.max(0, (width - iconHeight) / 2),
                    Math.max(0, width - iconWidth - (width - iconWidth) / 2),
                    Math.max(0, width - iconHeight - (width - iconHeight) / 2));
            icon.setAlpha(1f);
            mMenuView = icon;
        }
    }
}
