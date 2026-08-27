/*
 * Copyright (C) 2025 crDroid Android Project
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

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.LayerDrawable
import android.content.Context
import android.widget.ImageView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.common.ui.compose.load
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel

private enum class BatteryState {
    FULL,
    CHARGING,
    POWER_SAVE,
    NORMAL,
}

private fun resolveBatteryState(
    isFull: Boolean,
    isPluggedIn: Boolean,
    isCharging: Boolean,
    isPowerSave: Boolean,
): BatteryState =
    when {
        isFull && isPluggedIn -> BatteryState.FULL
        isCharging -> BatteryState.CHARGING
        isPowerSave -> BatteryState.POWER_SAVE
        else -> BatteryState.NORMAL
    }

@Composable
fun BatteryWithPercent(
    viewModel: BatteryViewModel,
    isDarkProvider: () -> IsAreaDark,
    modifier: Modifier = Modifier,
    showPercent: Boolean = true,
    showEstimate: Boolean = false,
) {
    val batteryHeight =
        with(LocalDensity.current) {
            BatteryViewModel.getStatusBarBatteryHeight(LocalContext.current).toDp()
        }

    val textStyle =
        with(LocalDensity.current) {
            BatteryViewModel.getStatusBarBatteryTextStyle(LocalContext.current)
        }

    var bounds by remember { mutableStateOf(Rect()) }

    val colorProducer = {
        if (isDarkProvider().isDarkTheme(bounds)) {
            BatteryColors.DarkTheme.Default.fill
        } else {
            BatteryColors.LightTheme.Default.fill
        }
    }

    Row(
        modifier =
            modifier.wrapContentWidth().onLayoutRectChanged { relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val context = LocalContext.current
        val level = viewModel.level?.coerceIn(0, 100) ?: 0
        val batteryState =
            resolveBatteryState(
                isFull = viewModel.isFull,
                isPluggedIn = viewModel.isPluggedIn,
                isCharging = viewModel.isCharging,
                isPowerSave = viewModel.isPowerSave,
            )
        val levelResourceName =
            if (showPercent) {
                val prefix =
                    if (viewModel.isPowerSave) "smaritisan_stat_sys_powersave_battery"
                    else "smaritisan_stat_sys_battery"
                "${prefix}_${level.coerceAtLeast(1)}"
            } else {
                when (batteryState) {
                    BatteryState.FULL -> "stat_sys_battery_full"
                    BatteryState.CHARGING -> "stat_sys_battery_charge"
                    BatteryState.POWER_SAVE,
                    BatteryState.NORMAL -> "stat_sys_battery"
                }
            }
        val levelRes =
            context.resources.getIdentifier(levelResourceName, "drawable", context.packageName)
        if (levelRes != 0) {
            if (showPercent && batteryState == BatteryState.CHARGING) {
                val backgroundName =
                    when {
                        viewModel.isPowerSave -> "smaritisan_stat_sys_powersave_battery_backgroud"
                        level <= 10 -> "smaritisan_stat_sys_battery_low_backgroud"
                        else -> "smaritisan_stat_sys_battery_background"
                    }
                val backgroundRes =
                    context.resources.getIdentifier(backgroundName, "drawable", context.packageName)
                val transition = rememberInfiniteTransition(label = "batteryCharge")
                val backgroundAlpha by
                    transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 0f,
                        animationSpec =
                            infiniteRepeatable(
                                animation =
                                    keyframes {
                                        durationMillis = 3500
                                        0f at 0
                                        0f at 1000
                                        1f at 1500
                                        1f at 2500
                                        0f at 3500
                                    },
                                repeatMode = RepeatMode.Restart,
                            ),
                        label = "batteryChargeBackground",
                    )
                Box(modifier = Modifier.height(batteryHeight).wrapContentWidth()) {
                    BatteryImage(
                        resourceId = levelRes,
                        level = level,
                        contentDescription = viewModel.contentDescription.load(),
                        tint = colorProducer().toArgb(),
                        modifier = Modifier.height(batteryHeight).alpha(1f - backgroundAlpha),
                    )
                    if (backgroundRes != 0) {
                        BatteryImage(
                            resourceId = backgroundRes,
                            level = level,
                            contentDescription = null,
                            tint = colorProducer().toArgb(),
                            modifier =
                                Modifier.height(batteryHeight)
                                    .wrapContentWidth()
                                    .alpha(backgroundAlpha),
                        )
                    }
                }
            } else {
                BatteryImage(
                    resourceId = levelRes,
                    level = level,
                    contentDescription = viewModel.contentDescription.load(),
                    tint = colorProducer().toArgb(),
                    startAnimation = batteryState == BatteryState.CHARGING && !showPercent,
                    modifier = Modifier.height(batteryHeight).wrapContentWidth(),
                )
            }
        }

        if (showEstimate) {
            viewModel.batteryTimeRemainingEstimate?.let {
                BasicText(
                    text = it,
                    color = colorProducer,
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
            }
        }
    }
}

@Composable
private fun BatteryImage(
    resourceId: Int,
    level: Int,
    contentDescription: String?,
    tint: Int,
    modifier: Modifier,
    startAnimation: Boolean = false,
) {
    AndroidView(
        factory = { BatteryImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } },
        update = { imageView ->
            val resourceChanged = imageView.tag != resourceId
            if (resourceChanged) {
                imageView.stopDrawableAnimation()
                imageView.setImageResource(resourceId)
                imageView.tag = resourceId
            }
            imageView.setImageLevel(level)
            imageView.contentDescription = contentDescription
            imageView.setColorFilter(tint)
            val animation = imageView.drawable?.findAnimationDrawable()
            if (startAnimation && animation?.isRunning == false) {
                animation.start()
            } else if (!startAnimation) {
                animation?.stop()
            }
        },
        modifier = modifier,
    )
}

private class BatteryImageView(context: Context) : ImageView(context) {
    override fun onDetachedFromWindow() {
        stopDrawableAnimation()
        super.onDetachedFromWindow()
    }
}

private fun ImageView.stopDrawableAnimation() {
    drawable?.findAnimationDrawable()?.stop()
}

private fun Drawable.findAnimationDrawable(): AnimationDrawable? {
    if (this is AnimationDrawable) return this
    if (this is LayerDrawable) {
        for (index in 0 until numberOfLayers) {
            getDrawable(index).findAnimationDrawable()?.let { return it }
        }
    }
    val currentDrawable = current
    return if (currentDrawable !== this) currentDrawable.findAnimationDrawable() else null
}
