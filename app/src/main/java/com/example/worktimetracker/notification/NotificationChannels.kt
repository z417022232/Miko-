package com.example.worktimetracker.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val LOCATION_CHANNEL_ID = "work_time_location"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                LOCATION_CHANNEL_ID,
                "工时自动记录",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "用于持续显示正在记录工时的前台服务" }
            manager.createNotificationChannel(channel)
        }
    }
}
