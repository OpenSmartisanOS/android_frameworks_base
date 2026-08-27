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
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.util.ArrayDeque
import org.junit.Test

@SmallTest
class StatusIconMergerTest : SysuiTestCase() {
    @Test
    fun laterVisibleChild_isLaidOutCloserToTheCluster() {
        val merger = StatusIconMerger(context)
        val first =
            ImageView(context).apply {
                minimumWidth = 24
                minimumHeight = 24
                layoutParams = iconLayoutParams()
            }
        val second =
            ImageView(context).apply {
                minimumWidth = 24
                minimumHeight = 24
                layoutParams = iconLayoutParams()
            }
        merger.addView(first)
        merger.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
        )
        merger.layout(0, 0, 400, 32)
        merger.addView(second)
        merger.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
        )
        merger.layout(0, 0, 400, 32)

        assertThat(second.right).isGreaterThan(first.right)
    }

    @Test
    fun overflow_doesNotUseDotState() {
        val merger = StatusIconMerger(context)
        merger.setShouldRestrictIcons(false)
        repeat(8) {
            merger.addView(
                ImageView(context).apply {
                    minimumWidth = 80
                    minimumHeight = 24
                    layoutParams = iconLayoutParams()
                }
            )
        }
        merger.measure(
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
        )
        merger.layout(0, 0, 120, 32)

        for (i in 0 until merger.childCount) {
            val child = merger.getChildAt(i)
            assertThat(child.alpha == 0f || child.alpha == 1f).isTrue()
        }
        assertThat(merger).isInstanceOf(StatusIconContainer::class.java)
    }

    @Test
    fun repeatedMeasure_keepsTheSameRetainedSet() {
        val merger = StatusIconMerger(context)
        repeat(6) {
            merger.addView(
                ImageView(context).apply {
                    minimumWidth = 48
                    minimumHeight = 24
                    layoutParams = iconLayoutParams()
                }
            )
        }

        fun retained(): List<Boolean> {
            merger.measure(
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
            )
            merger.layout(0, 0, 120, 32)
            return (0 until merger.childCount).map { merger.getChildAt(it).alpha == 1f }
        }

        assertThat(retained()).isEqualTo(retained())
    }

    @Test
    fun overflow_stopsAtFirstIconThatDoesNotFit() {
        val merger = StatusIconMerger(context)
        val oldNarrow = icon(width = 8)
        val middleWide = icon(width = 200)
        val newest = icon(width = 24)
        merger.addView(oldNarrow)
        merger.addView(middleWide)
        merger.addView(newest)

        merger.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
        )
        merger.layout(0, 0, 100, 32)

        assertThat(newest.alpha).isEqualTo(1f)
        assertThat(middleWide.alpha).isEqualTo(0f)
        // The factory merger does not skip the wide middle icon to recover an older narrow one.
        assertThat(oldNarrow.alpha).isEqualTo(0f)
    }

    @Test
    fun newlyVisibleIcon_movesNearestToNetworkCluster() {
        val merger = StatusIconMerger(context)
        val first = icon(width = 24)
        val later = icon(width = 24).apply { visibility = View.GONE }
        merger.addView(first)
        merger.addView(later)
        measureAndLayout(merger, 160)

        later.visibility = View.VISIBLE
        measureAndLayout(merger, 160)

        assertThat(later.right).isGreaterThan(first.right)
    }

    @Test
    fun drawableNarrowerThanMeasuredBox_doesNotOverlapAdjacentIcon() {
        val merger = StatusIconMerger(context)
        val older = fixedBoxIcon(boxWidth = 60, drawableWidth = 8)
        val newer = fixedBoxIcon(boxWidth = 60, drawableWidth = 8)
        merger.addView(older)
        merger.addView(newer)

        measureAndLayout(merger, 240)

        assertThat(older.right).isAtMost(newer.left)
        assertThat(merger.getRealWidth()).isAtLeast(120)
    }

    @Test
    fun expandingCapacity_restoresOnlyOneIconPerVsync() {
        val merger = FrameControlledMerger(context)
        repeat(3) { merger.addView(icon(width = 40)) }
        measureAndLayout(merger, 70)
        assertThat(visibleChildren(merger)).isEqualTo(1)

        measureAndLayout(merger, 300)
        assertThat(visibleChildren(merger)).isEqualTo(1)

        merger.runNextFrame()
        measureAndLayout(merger, 300)
        assertThat(visibleChildren(merger)).isEqualTo(2)

        merger.runNextFrame()
        measureAndLayout(merger, 300)
        assertThat(visibleChildren(merger)).isEqualTo(3)
    }

    private fun icon(width: Int) =
        ImageView(context).apply {
            minimumWidth = width
            minimumHeight = 24
            layoutParams = iconLayoutParams()
        }

    private fun fixedBoxIcon(boxWidth: Int, drawableWidth: Int) =
        ImageView(context).apply {
            setImageDrawable(
                BitmapDrawable(
                    resources,
                    Bitmap.createBitmap(drawableWidth, 8, Bitmap.Config.ARGB_8888),
                )
            )
            layoutParams =
                LinearLayout.LayoutParams(boxWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        }

    private fun measureAndLayout(merger: StatusIconMerger, width: Int) {
        merger.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
        )
        merger.layout(0, 0, width, 32)
    }

    private fun visibleChildren(merger: StatusIconMerger): Int =
        (0 until merger.childCount).count { merger.getChildAt(it).alpha == 1f }

    private class FrameControlledMerger(context: Context) : StatusIconMerger(context) {
        private val nextFrame = ArrayDeque<Runnable>()

        override fun postOnAnimation(action: Runnable) {
            nextFrame.addLast(action)
        }

        override fun removeCallbacks(action: Runnable): Boolean = nextFrame.remove(action)

        fun runNextFrame() {
            val callbacks = nextFrame.toList()
            nextFrame.clear()
            callbacks.forEach { it.run() }
        }
    }

    private fun iconLayoutParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
}
