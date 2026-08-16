/*
 * Copyright (C) 2026 OpenSmartisanOS
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import com.android.systemui.res.R
import org.json.JSONArray
import org.json.JSONObject

/** Persisted, vendor-independent identity for one R2 quick-launch slot. */
data class SosQuickLaunchTarget(
    val kind: Kind,
    val component: ComponentName? = null,
) {
    enum class Kind {
        SECURE_CAMERA,
        ROLE_DIALER,
        ROLE_SMS,
        ROLE_NOTES,
        ACTIVITY,
    }

    init {
        require((kind == Kind.ACTIVITY) == (component != null)) {
            "Only ACTIVITY targets carry a component"
        }
    }

    companion object {
        val DEFAULT_SLOTS =
            listOf(
                SosQuickLaunchTarget(Kind.ROLE_DIALER),
                SosQuickLaunchTarget(Kind.ROLE_SMS),
                SosQuickLaunchTarget(Kind.SECURE_CAMERA),
            )
    }
}

/** Pure JSON codec kept separate so malformed per-user state can never break keyguard inflation. */
object SosQuickLaunchConfigCodec {
    const val VERSION = 1
    const val SLOT_COUNT = 3

    fun encode(slots: List<SosQuickLaunchTarget?>): String {
        require(slots.size == SLOT_COUNT) { "Exactly $SLOT_COUNT slots are required" }
        val seen = HashSet<SosQuickLaunchTarget>()
        val entries = JSONArray()
        slots.forEach { target ->
            require(target == null || seen.add(target)) { "Quick-launch targets must be unique" }
            entries.put(
                target?.let {
                    JSONObject()
                        .put(KEY_KIND, it.kind.name)
                        .put(KEY_COMPONENT, it.component?.flattenToString())
                } ?: JSONObject.NULL
            )
        }
        return JSONObject().put(KEY_VERSION, VERSION).put(KEY_SLOTS, entries).toString()
    }

    fun decode(value: String?): List<SosQuickLaunchTarget?>? {
        if (value.isNullOrBlank()) return null
        return runCatching {
                val root = JSONObject(value)
                if (root.optInt(KEY_VERSION, -1) != VERSION) return null
                val entries = root.getJSONArray(KEY_SLOTS)
                if (entries.length() != SLOT_COUNT) return null
                val seen = HashSet<SosQuickLaunchTarget>()
                List(SLOT_COUNT) { index ->
                    if (entries.isNull(index)) {
                        null
                    } else {
                        val entry = entries.getJSONObject(index)
                        val kind = SosQuickLaunchTarget.Kind.valueOf(entry.getString(KEY_KIND))
                        val component =
                            entry.optString(KEY_COMPONENT).takeIf(String::isNotBlank)?.let {
                                ComponentName.unflattenFromString(it)
                                    ?: error("Invalid component")
                            }
                        SosQuickLaunchTarget(kind, component).also {
                            check(seen.add(it)) { "Duplicate target" }
                        }
                    }
                }
            }
            .getOrNull()
    }

    private const val KEY_VERSION = "version"
    private const val KEY_SLOTS = "slots"
    private const val KEY_KIND = "kind"
    private const val KEY_COMPONENT = "component"
}

/** Device-protected and user-scoped storage; no provider or manifest component is involved. */
class SosQuickLaunchStore(private val context: Context) {
    fun load(userId: Int = ActivityManager.getCurrentUser()): List<SosQuickLaunchTarget?> {
        val encoded = preferences(userId).getString(KEY_CONFIG, null)
        return SosQuickLaunchConfigCodec.decode(encoded) ?: SosQuickLaunchTarget.DEFAULT_SLOTS
    }

    fun save(userId: Int, slots: List<SosQuickLaunchTarget?>) {
        preferences(userId)
            .edit()
            .putString(KEY_CONFIG, SosQuickLaunchConfigCodec.encode(slots))
            .apply()
    }

    private fun preferences(userId: Int) =
        context
            .createContextAsUser(UserHandle.of(userId), 0)
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES = "sos_keyguard_quick_launch"
        const val KEY_CONFIG = "configuration"
    }
}

data class SosResolvedQuickLaunchTarget(
    val target: SosQuickLaunchTarget,
    val label: CharSequence,
    val icon: Drawable?,
    val selectorResource: Int,
    val available: Boolean,
    val launchIntent: Intent?,
)

/** Resolves roles and installed activities for the current user at the moment they are used. */
class SosQuickLaunchResolver(private val context: Context) {
    private val roleManager = context.getSystemService(RoleManager::class.java)

    fun resolve(
        target: SosQuickLaunchTarget,
        userId: Int,
        lightTheme: Boolean,
    ): SosResolvedQuickLaunchTarget {
        val userContext = context.createContextAsUser(UserHandle.of(userId), 0)
        val packageManager = userContext.packageManager
        val rolePackage = rolePackage(target.kind, userId)
        val intent = createIntent(target, rolePackage)
        val activityInfo = resolveActivity(packageManager, intent, userId, target.component)
        val available =
            target.kind == SosQuickLaunchTarget.Kind.SECURE_CAMERA || activityInfo != null
        val label =
            when (target.kind) {
                SosQuickLaunchTarget.Kind.SECURE_CAMERA ->
                    context.getString(R.string.sos_quick_launch_camera)
                SosQuickLaunchTarget.Kind.ROLE_DIALER ->
                    context.getString(R.string.sos_quick_launch_phone)
                SosQuickLaunchTarget.Kind.ROLE_SMS ->
                    context.getString(R.string.sos_quick_launch_sms)
                SosQuickLaunchTarget.Kind.ROLE_NOTES ->
                    context.getString(R.string.sos_quick_launch_notes)
                SosQuickLaunchTarget.Kind.ACTIVITY ->
                    activityInfo?.loadLabel(packageManager)
                        ?: target.component?.packageName.orEmpty()
            }
        val icon =
            when (target.kind) {
                SosQuickLaunchTarget.Kind.SECURE_CAMERA ->
                    context.getDrawable(R.drawable.buttom_framepop_icon_camera)
                SosQuickLaunchTarget.Kind.ROLE_DIALER ->
                    context.getDrawable(R.drawable.buttom_framepop_icon_phone)
                SosQuickLaunchTarget.Kind.ROLE_SMS ->
                    context.getDrawable(R.drawable.buttom_framepop_icon_sms)
                SosQuickLaunchTarget.Kind.ROLE_NOTES ->
                    context.getDrawable(R.drawable.buttom_framepop_icon_notes)
                SosQuickLaunchTarget.Kind.ACTIVITY ->
                    activityInfo?.loadIcon(packageManager)
            }
        return SosResolvedQuickLaunchTarget(
            target = target,
            label = label,
            icon = icon,
            selectorResource = selectorResource(target.kind, lightTheme),
            available = available,
            launchIntent = if (available) intent else null,
        )
    }

    fun candidates(userId: Int): List<SosResolvedQuickLaunchTarget> {
        val user = UserHandle.of(userId)
        val userContext = context.createContextAsUser(user, 0)
        val packageManager = userContext.packageManager
        val homePackages =
            buildSet {
                roleManager
                    ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                    ?.getRoleHoldersAsUser(RoleManager.ROLE_HOME, user)
                    ?.let(::addAll)
                packageManager
                    .resolveActivityAsUser(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                        PackageManager.MATCH_DEFAULT_ONLY,
                        userId,
                    )
                    ?.activityInfo
                    ?.packageName
                    ?.let(::add)
            }
        val result = ArrayList<SosResolvedQuickLaunchTarget>()
        listOf(
                SosQuickLaunchTarget(SosQuickLaunchTarget.Kind.ROLE_DIALER),
                SosQuickLaunchTarget(SosQuickLaunchTarget.Kind.ROLE_SMS),
                SosQuickLaunchTarget(SosQuickLaunchTarget.Kind.ROLE_NOTES),
                SosQuickLaunchTarget(SosQuickLaunchTarget.Kind.SECURE_CAMERA),
            )
            .mapTo(result) { resolve(it, userId, lightTheme = false) }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags =
            PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        packageManager
            .queryIntentActivitiesAsUser(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
                userId,
            )
            .asSequence()
            .mapNotNull { it.activityInfo }
            .filter(::isEnabledExported)
            .filterNot { it.packageName == context.packageName || it.packageName in homePackages }
            .distinctBy { it.componentName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .map { info ->
                resolve(
                    SosQuickLaunchTarget(
                        SosQuickLaunchTarget.Kind.ACTIVITY,
                        info.componentName,
                    ),
                    userId,
                    lightTheme = false,
                )
            }
            .filterTo(result) { it.available }
        return result
    }

    fun validate(slots: List<SosQuickLaunchTarget?>, userId: Int): Boolean {
        if (slots.size != SosQuickLaunchConfigCodec.SLOT_COUNT) return false
        if (slots.filterNotNull().distinct().size != slots.count { it != null }) return false
        return slots.filterNotNull().all { target ->
            target.kind != SosQuickLaunchTarget.Kind.ACTIVITY ||
                resolve(target, userId, lightTheme = false).available
        }
    }

    private fun rolePackage(kind: SosQuickLaunchTarget.Kind, userId: Int): String? {
        val role =
            when (kind) {
                SosQuickLaunchTarget.Kind.ROLE_DIALER -> RoleManager.ROLE_DIALER
                SosQuickLaunchTarget.Kind.ROLE_SMS -> RoleManager.ROLE_SMS
                SosQuickLaunchTarget.Kind.ROLE_NOTES -> RoleManager.ROLE_NOTES
                else -> return null
            }
        val manager = roleManager ?: return null
        if (!manager.isRoleAvailable(role)) return null
        return manager.getRoleHoldersAsUser(role, UserHandle.of(userId)).firstOrNull()
    }

    private fun createIntent(target: SosQuickLaunchTarget, rolePackage: String?): Intent? =
        when (target.kind) {
            SosQuickLaunchTarget.Kind.SECURE_CAMERA -> null
            SosQuickLaunchTarget.Kind.ROLE_DIALER ->
                rolePackage?.let {
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                        .setPackage(it)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            SosQuickLaunchTarget.Kind.ROLE_SMS ->
                rolePackage?.let {
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                        .setPackage(it)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            SosQuickLaunchTarget.Kind.ROLE_NOTES ->
                rolePackage?.let {
                    Intent(Intent.ACTION_CREATE_NOTE)
                        .setPackage(it)
                        .putExtra(Intent.EXTRA_USE_STYLUS_MODE, false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            SosQuickLaunchTarget.Kind.ACTIVITY ->
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(target.component)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
        }

    private fun resolveActivity(
        packageManager: PackageManager,
        intent: Intent?,
        userId: Int,
        component: ComponentName?,
    ): ActivityInfo? {
        if (intent == null) return null
        val info =
            if (component != null) {
                runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
            } else {
                packageManager
                    .resolveActivityAsUser(intent, PackageManager.MATCH_DEFAULT_ONLY, userId)
                    ?.activityInfo
            }
        return info?.takeIf(::isEnabledExported)
    }

    private fun isEnabledExported(info: ActivityInfo): Boolean =
        info.exported && info.enabled && info.applicationInfo.enabled

    private fun selectorResource(kind: SosQuickLaunchTarget.Kind, lightTheme: Boolean): Int =
        when (kind) {
            SosQuickLaunchTarget.Kind.ROLE_DIALER ->
                if (lightTheme) R.drawable.secletor_slide_bar_btn_phone_light
                else R.drawable.secletor_slide_bar_btn_phone
            SosQuickLaunchTarget.Kind.ROLE_SMS ->
                if (lightTheme) R.drawable.secletor_slide_bar_btn_sms_light
                else R.drawable.secletor_slide_bar_btn_sms
            SosQuickLaunchTarget.Kind.SECURE_CAMERA ->
                if (lightTheme) R.drawable.secletor_slide_bar_btn_camera_light
                else R.drawable.secletor_slide_bar_btn_camera
            else -> 0
        }
}
