package com.example.worktimetracker.location.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.AppLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 精确闹钟看门狗（心跳喂狗式）：前台服务每 5 分钟写一次心跳，
 * 每次心跳都把闹钟推迟到「心跳时刻 + 10 分钟」。因此：
 * - 服务存活时闹钟被不断后推，永远不会真正触发（零额外唤醒）；
 * - 服务死亡（连续错过 2 个心跳周期）后，闹钟在最后一次心跳后
 *   10 分钟内准时触发，此时应用处于系统临时白名单窗口，
 *   可直接启动前台定位服务，绕过 Android 12+ 的后台启动限制。
 * 闹钟完全静默：无铃声、无界面、无通知；应用进程被杀也照常触发。
 */
object AlarmWatchdog {
    const val ACTION = "com.example.worktimetracker.action.ALARM_WATCHDOG"
    private const val REQUEST_CODE = 4001
    private const val INTERVAL_MINUTES = 10L
    const val INTERVAL_MILLIS = INTERVAL_MINUTES * 60_000L

    /** 心跳年龄超过此值即判定服务死亡（心跳周期 5 分钟 × 1.6 余量） */
    const val DEAD_AFTER_MILLIS = 8 * 60_000L

    fun scheduleNext(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val next = nowMillis + INTERVAL_MILLIS
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                // 无精确闹钟权限时退化为非精确闹钟，窗口放宽到 ±5 分钟仍可自愈
                am.setWindow(AlarmManager.RTC_WAKEUP, next, 5 * 60_000L, pi)
            }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context.applicationContext, AlarmWatchdogReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class AlarmWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AlarmWatchdog.ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 先续期下一次闹钟（重试链兜底），再检查心跳年龄
                AlarmWatchdog.scheduleNext(app)
                val now = System.currentTimeMillis()
                val heartbeatAge = ServiceRecovery.heartbeatAge(app, now)
                if (heartbeatAge > AlarmWatchdog.DEAD_AFTER_MILLIS) {
                    val started = ServiceRecovery.start(
                        app,
                        ServiceRecoveryPolicy.RecoveryTrigger.ALARM
                    )
                    (app as? WorkTimeApplication)?.database?.appLogDao()?.insert(
                        AppLogEntity(
                            type = "ALARM_WATCHDOG",
                            content = if (started) {
                                "心跳失效 ${heartbeatAge / 60_000} 分钟，精确闹钟看门狗已重新拉起定位服务"
                            } else {
                                "心跳失效 ${heartbeatAge / 60_000} 分钟，但看门狗拉起服务未成功（权限可能被回收）"
                            }
                        )
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
