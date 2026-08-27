/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusIconDisplayable

/** Notification-icon merger: whole-slot clipping, no overflow DOT, hide from the tail. */
class LeftIconMerger
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    NotificationIconContainer(context, attrs) {
    private val accessibilityBeforeOverflow = HashMap<View, Int>()
    private var logicalVisibilityListener: (() -> Unit)? = null

    fun setLogicalVisibilityListener(listener: (() -> Unit)?) {
        logicalVisibilityListener = listener
        listener?.invoke()
    }

    fun hasLogicallyVisibleIcons(): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is StatusIconDisplayable) {
                if (
                    child.isIconVisible &&
                        !child.isIconBlocked &&
                        child.visibleState == StatusBarIconView.STATE_ICON
                ) {
                    return true
                }
            } else if (child.visibility == View.VISIBLE) {
                return true
            }
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val slot = slotWidth()
        // A runtime cutout/layout update may briefly offer zero width. Do not turn that transient
        // constraint into a persistent zero-icon capacity; the next valid measure must still be
        // able to account for every logical child.
        if (slot <= 0 || measuredWidth < slot) return
        val aligned = measuredWidth - (measuredWidth % slot)
        setMeasuredDimension(aligned, measuredHeight)
        setMaxIconsAmount(aligned / slot)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is StatusBarIconView && child.visibleState == StatusBarIconView.STATE_DOT) {
                accessibilityBeforeOverflow.putIfAbsent(child, child.importantForAccessibility)
                child.setVisibleState(StatusBarIconView.STATE_HIDDEN, false)
                child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } else if (
                child is StatusBarIconView &&
                    child.visibleState == StatusBarIconView.STATE_ICON
            ) {
                accessibilityBeforeOverflow.remove(child)?.let {
                    child.importantForAccessibility = it
                }
            }
        }
        logicalVisibilityListener?.invoke()
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        post { logicalVisibilityListener?.invoke() }
    }

    override fun onViewRemoved(child: View) {
        accessibilityBeforeOverflow.remove(child)
        super.onViewRemoved(child)
        post { logicalVisibilityListener?.invoke() }
    }

    private fun slotWidth(): Int =
        StatusBarGeometry.calculate(this).notificationSlotWidth
}
