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
        val working = ActiveWorkSessionReader.hasActive(context)
        val intent = PendingIntent.getActivity(
            context, 2, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, text) = if (working) {
            "正在记录的工时可能中断" to "系统定位被关闭（多半是省电策略），当前正处于工作时段，点击打开"
        } else {
            "系统定位已暂停" to "记录已暂停；多半是省电策略（如睡眠待机优化）关闭了定位，点击打开"
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.RECOVERY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_worktime)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 定位恢复后撤掉还挂着的「系统定位已暂停」——它引导用户去打开定位，
     * 定位已经恢复时它就是误导；与 [systemLocationDisabled] 同用 id [NOTIFICATION_ID]，
     * 也顺带清掉健康巡检同 id 的恢复提示。未显示时取消是幂等的。
     */
    fun cancelSystemLocationDisabled(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }
}
