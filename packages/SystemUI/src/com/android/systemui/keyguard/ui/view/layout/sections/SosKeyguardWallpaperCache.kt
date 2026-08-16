/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Device-protected copy of the last successfully decoded R2 lockscreen wallpaper.
 *
 * Smartisan's original Keyguard saves the current wallpaper to DES while credentials are
 * available, then loads that copy before the keystore is unlocked on the next boot. SystemUI is a
 * single system-user process on modern Android, so the current user id is part of the filename.
 */
internal class SosKeyguardWallpaperCache private constructor(private val directory: File) {
    constructor(context: Context) :
        this(context.createDeviceProtectedStorageContext().filesDir)

    internal constructor(directory: File, createDirectory: Boolean) : this(directory) {
        if (createDirectory && !directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Unable to create wallpaper cache directory: $directory")
        }
    }

    fun load(userId: Int): Bitmap? {
        val file = AtomicFile(fileForUser(userId))
        if (!file.baseFile.isFile) return null
        return runCatching {
                file.openRead().use { input -> BitmapFactory.decodeStream(input) }
            }
            .onFailure { Log.w(TAG, "Unable to read DE wallpaper cache for user=$userId", it) }
            .getOrNull()
            ?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
    }

    fun save(userId: Int, bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val file = AtomicFile(fileForUser(userId))
        val output =
            try {
                file.startWrite()
            } catch (e: IOException) {
                Log.w(TAG, "Unable to open DE wallpaper cache for user=$userId", e)
                return false
            }
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Bitmap compression returned false")
            }
            file.finishWrite(output)
            true
        } catch (e: Exception) {
            file.failWrite(output)
            Log.w(TAG, "Unable to update DE wallpaper cache for user=$userId", e)
            false
        }
    }

    internal fun fileForUser(userId: Int): File =
        File(directory, "$CACHE_FILE_PREFIX$userId.png")

    private companion object {
        private const val TAG = "SosKeyguardWallpaperCache"
        private const val CACHE_FILE_PREFIX = "sos_r2_current_wallpaper_u"
    }
}
