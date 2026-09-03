package com.example.worktimetracker.location.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.PowerManager

data class PermissionStatus(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val nearbyDevices: Boolean = true,
    val activityRecognition: Boolean = true,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean
) {
    val ready: Boolean get() = fineLocation && backgroundLocation && nearbyDevices &&
        activityRecognition && notifications && batteryUnrestricted
}

object PermissionManager {
    fun check(context: Context): PermissionStatus {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        // Android 12 以下附近设备视为已满足；NEARBY_WIFI_DEVICES 从 Android 13 起才存在
        val nearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)
        } else true
        // Android 10 以下活动识别视为已满足
        val activityRecognition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return PermissionStatus(fine, bg, nearbyDevices, activityRecognition, notifications,
            power.isIgnoringBatteryOptimizations(context.packageName))
    }
}
