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

import android.graphics.Rect
import android.view.DisplayCutout

/** Cutout placement relative to the physical screen center. */
enum class StatusBarCutoutMode {
    NONE,
    CENTER,
    LEFT,
    RIGHT,
}

object StatusBarCutoutClassifier {
    @JvmStatic
    fun classify(cutout: DisplayCutout?, screenWidth: Int): StatusBarCutoutMode {
        if (cutout == null || cutout.isEmpty || screenWidth <= 0) {
            return StatusBarCutoutMode.NONE
        }
        val bounds: Rect = cutout.boundingRectTop
        if (bounds.isEmpty) {
            return StatusBarCutoutMode.NONE
        }
        val screenCenter = screenWidth / 2
        if (bounds.left < screenCenter && bounds.right > screenCenter) {
            return StatusBarCutoutMode.CENTER
        }
        val holeCenter = bounds.centerX()
        return if (holeCenter < screenWidth / 3) {
            StatusBarCutoutMode.LEFT
        } else if (holeCenter > (screenWidth * 2) / 3) {
            StatusBarCutoutMode.RIGHT
        } else {
            StatusBarCutoutMode.CENTER
        }
    }
}
