/*
 * Copyright (C) 2026 OpenSmartisanOS
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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import com.android.systemui.res.R
import java.util.Calendar
import java.util.Date

/** Keeps the Smartisan base bar's four time/date fields in sync with system time. */
internal class SosKeyguardTimeController(
    private val context: Context,
    private val timeView: TextView,
    private val amPmView: TextView,
    private val weekDayView: TextView,
    private val dateView: TextView,
) {
    private var receiverRegistered = false

    private val timeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                update()
            }
        }

    fun start() {
        if (receiverRegistered) return

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
        context.registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        update()
    }

    fun stop() {
        if (!receiverRegistered) return

        context.unregisterReceiver(timeReceiver)
        receiverRegistered = false
    }

    fun update() {
        val now = Date()
        val is24Hour = DateFormat.is24HourFormat(context)
        timeView.text = DateFormat.format(if (is24Hour) "kk:mm" else "hh:mm", now)

        if (is24Hour) {
            amPmView.visibility = View.GONE
        } else {
            amPmView.setText(
                if (Calendar.getInstance().get(Calendar.AM_PM) == Calendar.AM) {
                    R.string.sos_keyguard_am
                } else {
                    R.string.sos_keyguard_pm
                }
            )
            amPmView.visibility = View.VISIBLE
        }

        weekDayView.text = DateFormat.format("E", now)
        dateView.text = getDateString(now)
    }

    /** Mirrors Smartisan's localized month + separator + day assembly. */
    private fun getDateString(now: Date): String {
        val month = DateFormat.format("M", now).toString().toInt()
        val monthText = context.resources.getStringArray(R.array.sos_keyguard_months)[month - 1]
        val day = DateFormat.format("d", now).toString()
        val separator = context.getString(R.string.sos_keyguard_date_separator)
        val daySuffix = context.getString(R.string.sos_keyguard_day_suffix)
        val paddedDay =
            if (day.toInt() < 10 && monthText.toIntOrNull() == month) "0$day" else day
        return monthText + separator + paddedDay + daySuffix
    }
}
