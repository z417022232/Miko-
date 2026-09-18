package com.example.worktimetracker.location.recovery

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.worktimetracker.R
import com.example.worktimetracker.notification.NotificationChannels

object RecoveryNotifier {
    const val NOTIFICATION_ID = 2002

    fun systemLocationDisabled(context: Context) {
        NotificationChannels.ensure(context)
        val intent = PendingIntent.getActivity(
            context, 2, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.RECOVERY_CHANNEL_ID)
            .setContentTitle("系统定位已暂停")
            .setContentText("定位记录可能中断，点击打开系统定位")
            .setSmallIcon(R.drawable.ic_stat_worktime)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }
}
