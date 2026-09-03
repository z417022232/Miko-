package com.example.worktimetracker.location.recovery

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.worktimetracker.MainActivity
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.location.service.CompanyPresenceFallback
import com.example.worktimetracker.notification.NotificationChannels
import java.time.ZoneId

/**
 * 后台健康任务：只更新健康状态、写限流日志、发布可点击恢复通知。
 * 禁止从 BACKGROUND_HEALTH_CHECK 调用 startForegroundService；
 * 恢复入口是通知/界面点击后的 USER_VISIBLE 触发。
 */
class LocationHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WorkTimeApplication
        val now = System.currentTimeMillis()
        evaluateHealth(app, now)
        repairIncompleteFallbackRecords(app)
        return Result.success()
    }

    private suspend fun evaluateHealth(app: WorkTimeApplication, now: Long) {
        val sourceHealth = app.database.environmentEvidenceDao().allHealth().associate { entity ->
            entity.name to SourceHealth(
                lastCallbackAt = entity.lastCallbackAt,
                lastSuccessAt = entity.lastSuccessAt,
                registered = entity.registered,
                recoveryCount = entity.recoveryCount,
                lastFailure = entity.lastFailure
            )
        }
        val snapshot = ServiceRecovery.snapshot(applicationContext, sourceHealth)
        when (val action = ServiceHealthPolicy.evaluate(snapshot, now)) {
            HealthAction.HEALTHY -> Unit
            HealthAction.NOTIFY_TAP_TO_RECOVER -> if (ServiceRecovery.shouldNotify(applicationContext, action.name, now)) {
                app.database.appLogDao().insert(AppLogEntity(
                    type = "RECOVERY_BLOCKED",
                    content = "定位服务心跳超过25分钟；Android 不允许后台任务直接启动定位前台服务，请点击通知恢复"
                ))
                sendRecoveryNotification("工时记录服务已停止",
                    "自动记录暂停了，点击这里恢复自动记录")
            }
            HealthAction.REREGISTER_LOCATION,
            HealthAction.REREGISTER_GNSS,
            HealthAction.REREGISTER_MOTION,
            HealthAction.PROVIDER_UNAVAILABLE -> if (ServiceRecovery.shouldNotify(applicationContext, action.name, now)) {
                app.database.appLogDao().insert(AppLogEntity(
                    type = "LOCATION_HEALTH",
                    content = "定位链路健康检查：${action.name}（来源状态=${sourceHealth.keys.sorted().joinToString()}）"
                ))
                sendRecoveryNotification("定位记录可能中断",
                    "检测到定位链路异常（${actionLabel(action)}），点击查看恢复方法")
            }
            HealthAction.AUXILIARY_DEGRADED -> if (ServiceRecovery.shouldNotify(applicationContext, action.name, now)) {
                // 辅助来源失效只降级环境证据，不算定位服务故障
                app.database.appLogDao().insert(AppLogEntity(
                    type = "LOCATION_HEALTH",
                    content = "辅助环境证据来源降级：${sourceHealth.filterValues { it.lastFailure != null }.keys.joinToString()}"
                ))
            }
        }
    }

    private suspend fun repairIncompleteFallbackRecords(app: WorkTimeApplication) {
        val settings = app.database.userSettingsDao().getSettings() ?: return
        val domain = WorkSettings(
            settings.workStartMinutes, settings.workEndMinutes, settings.hasDefaultHours,
            settings.defaultWorkMinutes, settings.restDeductionMinutes, settings.outsideThresholdMinutes,
            settings.leaveCompanyConfirmMinutes, settings.earlyLeaveToleranceMinutes
        )
        val policy = CompanyPresenceFallback(ZoneId.systemDefault())
        val now = System.currentTimeMillis()
        app.database.workRecordDao().incompleteFallbackRecords(CompanyPresenceFallback.FALLBACK_NOTE).forEach { record ->
            when (val action = policy.evaluate(record.createdAt, now, record, domain)) {
                is CompanyPresenceFallback.Action.UpsertReview -> app.database.workRecordDao().upsert(action.record)
                is CompanyPresenceFallback.Action.Draft, CompanyPresenceFallback.Action.None -> Unit
            }
        }
    }

    private fun actionLabel(action: HealthAction): String = when (action) {
        HealthAction.REREGISTER_LOCATION -> "定位监听待恢复"
        HealthAction.REREGISTER_GNSS -> "卫星定位待恢复"
        HealthAction.REREGISTER_MOTION -> "运动传感器待恢复"
        HealthAction.PROVIDER_UNAVAILABLE -> "系统定位提供器不可用"
        else -> "未知"
    }

    private fun sendRecoveryNotification(title: String, text: String) {
        NotificationChannels.ensure(applicationContext)
        val intent = PendingIntent.getActivity(
            applicationContext, 2,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.LOCATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.example.worktimetracker.R.drawable.ic_stat_worktime)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(RECOVERY_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val RECOVERY_NOTIFICATION_ID = 2002
    }
}
