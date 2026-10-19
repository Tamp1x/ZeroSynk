package com.buffersynk.app.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Best-effort direct jumps into MIUI/HyperOS permission screens. */
object Xiaomi {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("xiaomi", ignoreCase = true) ||
            Build.DEVICE.startsWith("xiaomi", ignoreCase = true)

    /** Screen "Автозапуск" in MIUI security center. */
    fun openAutoStart(context: Context): Boolean = try {
        val intent = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** MIUI battery saver: remove restrictions for the app. */
    fun openBatterySaver(context: Context): Boolean = try {
        val intent = Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.PowerHiddenSettingsActivity",
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** MIUI "Скрытые приложения" (background activity restrictions). */
    fun openHiddenApps(context: Context): Boolean = try {
        val intent = Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.HiddenAppsConfigActivity",
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("package_name", context.packageName)
        intent.putExtra("package_label", "ZeroSynk")
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Stock Android: request exemption from battery optimizations (works on most OEMs). */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = try {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    fun openAppSettings(context: Context): Boolean = try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** App notification settings (works on stock and MIUI). */
    fun openNotificationSettings(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        openAppSettings(context)
    }
}
