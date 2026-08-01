package com.example.worktimetracker.location.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

enum class PermissionItem { FINE_LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY_UNRESTRICTED, VIVO_AUTOSTART }

object PermissionRepairPriority {
    fun next(status: PermissionStatus): PermissionItem = when {
        !status.fineLocation -> PermissionItem.FINE_LOCATION
        !status.backgroundLocation -> PermissionItem.BACKGROUND_LOCATION
        !status.notifications -> PermissionItem.NOTIFICATIONS
        !status.batteryUnrestricted -> PermissionItem.BATTERY_UNRESTRICTED
        else -> PermissionItem.VIVO_AUTOSTART
    }
}

object PermissionSettingsRouter {
    fun open(item: PermissionItem, context: Context): String {
        val intents = candidates(item, context.packageName)
        val selected = intents.firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
            ?: appDetails(context.packageName)
        return runCatching {
            context.startActivity(selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (selected.component != null) "已打开 Vivo 自启动设置" else "已打开对应系统设置"
        }.getOrElse {
            context.startActivity(appDetails(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "系统无法直达该页面，请在应用详情中手动开启"
        }
    }

    fun candidates(item: PermissionItem, packageName: String): List<Intent> = when (item) {
        PermissionItem.FINE_LOCATION,
        PermissionItem.BACKGROUND_LOCATION,
        PermissionItem.NOTIFICATIONS -> listOf(appDetails(packageName))
        PermissionItem.BATTERY_UNRESTRICTED -> listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(packageName)
        )
        PermissionItem.VIVO_AUTOSTART -> listOf(
            explicit("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            explicit("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            explicit("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"),
            appDetails(packageName)
        )
    }

    private fun appDetails(packageName: String) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    )

    private fun explicit(packageName: String, className: String) = Intent().setComponent(ComponentName(packageName, className))
}
