/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.battery

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.android.systemui.SysuiTestCase
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryResourceTest : SysuiTestCase() {
    @Test
    fun full_requiresPlugAndHasPriorityOverCharging() {
        val full = state(level = 100, plugged = true, charging = true, charged = true)
        assertThat(full.isFull).isTrue()
        assertThat(full.shouldAnimateCharging()).isFalse()

        val unplugged = state(level = 100, plugged = false, charged = true)
        assertThat(unplugged.isFull).isFalse()
    }

    @Test
    fun charging_requiresEveryOriginalRuntimeCondition() {
        assertThat(state(plugged = true, charging = true).shouldAnimateCharging()).isTrue()
        assertThat(state(plugged = true, charging = false).shouldAnimateCharging()).isFalse()
        assertThat(state(plugged = false, charging = true).shouldAnimateCharging()).isFalse()
        assertThat(state(plugged = true, charging = true, screenOn = false).shouldAnimateCharging())
            .isFalse()
        assertThat(
                state(plugged = true, charging = true, animationEnabled = false)
                    .shouldAnimateCharging()
            )
            .isFalse()
        assertThat(
                state(plugged = true, charging = true, incompatible = true)
                    .shouldAnimateCharging()
            )
            .isFalse()
        assertThat(state(plugged = true, charging = true, unknown = true).shouldAnimateCharging())
            .isFalse()
    }

    @Test
    fun style_normalization_matchesOriginalContract() {
        assertThat(BatteryState.normalizeStyle(0)).isEqualTo(0)
        assertThat(BatteryState.normalizeStyle(1)).isEqualTo(1)
        assertThat(BatteryState.normalizeStyle(3)).isEqualTo(3)
        assertThat(BatteryState.normalizeStyle(2)).isEqualTo(0)
        assertThat(BatteryState.normalizeStyle(99)).isEqualTo(0)
    }

    @Test
    fun graphicBuckets_matchOriginalLevelList() {
        assertThat(BatteryState.graphicalBucketForLevel(-1)).isEqualTo(0)
        assertThat(BatteryState.graphicalBucketForLevel(3)).isEqualTo(0)
        assertThat(BatteryState.graphicalBucketForLevel(10)).isEqualTo(10)
        assertThat(BatteryState.graphicalBucketForLevel(11)).isEqualTo(20)
        assertThat(BatteryState.graphicalBucketForLevel(23)).isEqualTo(24)
        assertThat(BatteryState.graphicalBucketForLevel(87)).isEqualTo(88)
        assertThat(BatteryState.graphicalBucketForLevel(99)).isEqualTo(88)
        assertThat(BatteryState.graphicalBucketForLevel(101)).isEqualTo(100)
    }

    @Test
    fun percentDrawable_usesExactOriginalFamiliesAndClampsZero() {
        assertThat(state(level = 0).getPercentDrawableName(false))
            .isEqualTo("smaritisan_stat_sys_battery_1")
        assertThat(state(level = 73).getPercentDrawableName(true))
            .isEqualTo("colored_smaritisan_stat_sys_battery_73")
        assertThat(state(level = 73, powerSave = true).getPercentDrawableName(true))
            .isEqualTo("smaritisan_stat_sys_powersave_battery_73")
        assertThat(state(level = 100, plugged = true, charged = true).getPercentDrawableName(false))
            .isEqualTo("stat_sys_battery_full")
    }

    @Test
    fun percentDrawable_allOriginalLevelsResolveForEveryFamily() {
        val resources = context.resources
        val packageName = resources.getResourcePackageName(
            com.android.systemui.res.R.drawable.stat_sys_battery
        )
        val families =
            listOf(
                "smaritisan_stat_sys_battery_",
                "colored_smaritisan_stat_sys_battery_",
                "smaritisan_stat_sys_powersave_battery_",
            )

        families.forEach { prefix ->
            (1..100).forEach { level ->
                assertThat(resources.getIdentifier("$prefix$level", "drawable", packageName))
                    .isNotEqualTo(0)
            }
        }
    }

    private fun state(
        level: Int = 50,
        style: Int = BatteryState.STYLE_GRAPHIC,
        plugged: Boolean = false,
        charging: Boolean = false,
        charged: Boolean = false,
        powerSave: Boolean = false,
        screenOn: Boolean = true,
        animationEnabled: Boolean = true,
        incompatible: Boolean = false,
        unknown: Boolean = false,
    ) =
        BatteryState(
            level,
            style,
            plugged,
            charging,
            charged,
            powerSave,
            screenOn,
            animationEnabled,
            incompatible,
            unknown,
        )
}
