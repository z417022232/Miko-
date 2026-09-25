package com.example.worktimetracker.location.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.AppLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 系统定位开关变化接收器（manifest 常驻，见 AndroidManifest）。
 *
 * vivo/iQOO 的省电策略（睡眠待机优化等）会在夜间把系统定位整个关掉、过段时间又打开。
 * 「关闭」无法阻拦；真正的损失在「重新打开之后」：服务内的 `onProviderEnabled`
 * 只在进程活着时有效，进程夜间被冻结/杀死后就只能等 AlarmWatchdog 每 10 分钟
 * 一跳的轮询，早晨的通勤段可能整段丢证据。本接收器把「开关重新打开」这一事件
 * 直接变成自愈触发：记录状态转移，若服务心跳已失效，立刻安排一次即时看门狗闹钟
 * （闹钟触发时应用处于临时白名单窗口，可直接拉起前台服务）。
 *
 * 两个 action 均为受保护的系统广播，第三方应用无法伪造；receiver 仍按 action 白名单过滤。
 */
class LocationSwitchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!LocationSwitchRecoveryPolicy.isSwitchAction(action)) return
        val app = context.applicationContext ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                // 与服务内 / 闹钟看门狗共用同一份状态转移与一次性通知领取
                val check = SystemLocationStateChecker.checkAndRecord(app, now)
                if (check.enabled) {
                    // 覆盖服务已死的场景：进程由本广播拉起，没人替它撤旧通知
                    RecoveryNotifier.cancelSystemLocationDisabled(app)
                } else if (check.transition == SystemLocationTransition.DISABLED) {
                    (app as? WorkTimeApplication)?.database?.appLogDao()?.insert(
                        AppLogEntity(
                            type = "SYSTEM_LOCATION_DISABLED",
                            content = "系统开关广播：定位已被关闭（$action）"
                        )
                    )
                }
                val decision = LocationSwitchRecoveryPolicy.evaluate(
                    enabled = check.enabled,
                    heartbeatAge = ServiceRecovery.heartbeatAge(app, now),
                    deadAfterMillis = AlarmWatchdog.DEAD_AFTER_MILLIS
                )
                if (decision == LocationSwitchRecoveryPolicy.Decision.KICK_ALARM_NOW) {
                    AlarmWatchdog.scheduleKick(app, now)
                    (app as? WorkTimeApplication)?.database?.appLogDao()?.insert(
                        AppLogEntity(
                            type = "SYSTEM_LOCATION_RECOVERED",
                            content = "系统开关广播：定位已恢复且服务心跳失效，已安排即时看门狗"
                        )
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}

object LocationSwitchRecoveryPolicy {
    const val ACTION_PROVIDERS_CHANGED = "android.location.PROVIDERS_CHANGED"
    const val ACTION_MODE_CHANGED = "android.location.MODE_CHANGED"

    enum class Decision { KICK_ALARM_NOW, RECORD_ONLY }

    fun isSwitchAction(action: String): Boolean =
        action == ACTION_PROVIDERS_CHANGED || action == ACTION_MODE_CHANGED

    /**
     * 开关重新打开且服务心跳已失效 ⇒ 立即踢一次看门狗闹钟；其余情况只记录状态。
     *
     * 心跳仍新鲜说明服务活着，恢复注册由服务自身的 `onProviderEnabled` 负责，
     * 这里再拉服务只会多启动一次前台服务。
     */
    fun evaluate(enabled: Boolean, heartbeatAge: Long, deadAfterMillis: Long): Decision =
        if (enabled && heartbeatAge > deadAfterMillis) Decision.KICK_ALARM_NOW
        else Decision.RECORD_ONLY
}
