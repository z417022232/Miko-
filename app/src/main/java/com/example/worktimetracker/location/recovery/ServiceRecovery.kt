package com.example.worktimetracker.location.recovery

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.worktimetracker.location.service.ForegroundLocationService
import java.util.concurrent.TimeUnit

object ServiceRecovery {
    private const val UNIQUE_WORK = "work-time-location-health"
    private const val PREFS = "location_service_health"
    private const val HEARTBEAT = "service_heartbeat"
    private const val CALLBACK = "last_location_callback"
    private const val RELIABLE = "last_reliable_location"
    private const val PROVIDER = "provider_available"
    private const val SYSTEM_LOCATION_DISABLED_AT = "system_location_disabled_at"
    private const val SYSTEM_LOCATION_RECOVERED_AT = "system_location_recovered_at"
    private const val NOTIFY_PREFIX = "health_notified_"

    fun start(context: Context, trigger: ServiceRecoveryPolicy.RecoveryTrigger): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ServiceRecoveryPolicy.canStartLocationService(trigger, fine, coarse, background)) return false
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ForegroundLocationService::class.java))
            true
        }.getOrDefault(false)
    }

    fun schedule(context: Context): Boolean = runCatching {
        // 精确闹钟看门狗：与 WorkManager 健康巡检同时布防，
        // 闹钟触发时应用处于临时白名单窗口，可直接拉起前台服务
        AlarmWatchdog.scheduleNext(context)
        val request = PeriodicWorkRequestBuilder<LocationHealthWorker>(
            ServiceRecoveryPolicy.healthCheckMinutes,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        true
    }.getOrDefault(false)

    fun heartbeat(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(HEARTBEAT, now).apply()
        // 心跳喂狗：每次心跳把看门狗闹钟推到 10 分钟后，服务存活时闹钟永不触发
        runCatching { AlarmWatchdog.scheduleNext(context, now) }
    }

    /** 最近一次心跳距今的毫秒数；无心跳记录返回 Long.MAX_VALUE。 */
    fun heartbeatAge(context: Context, now: Long = System.currentTimeMillis()): Long {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(HEARTBEAT, 0L)
        return if (last <= 0L) Long.MAX_VALUE else now - last
    }

    fun locationCallback(context: Context, reliable: Boolean, now: Long = System.currentTimeMillis()) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(CALLBACK, now)
        if (reliable) edit.putLong(RELIABLE, now)
        edit.apply()
    }

    fun providerAvailable(context: Context, available: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PROVIDER, available).apply()
    }

    fun systemLocationDisabled(context: Context, now: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SYSTEM_LOCATION_DISABLED_AT, now).apply()
    }

    fun systemLocationRecovered(context: Context, now: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SYSTEM_LOCATION_RECOVERED_AT, now).apply()
    }

    fun lastSystemLocationDisabled(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SYSTEM_LOCATION_DISABLED_AT, 0L)

    fun lastSystemLocationRecovered(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SYSTEM_LOCATION_RECOVERED_AT, 0L)

    fun snapshot(context: Context, sourceHealth: Map<String, SourceHealth> = emptyMap()): ServiceHealthSnapshot =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
            ServiceHealthSnapshot(it.getLong(HEARTBEAT, 0), it.getLong(CALLBACK, 0),
                it.getLong(RELIABLE, 0), it.getBoolean(PROVIDER, true), sourceHealth)
        }

    /** 相同失败在 60 分钟内只通知一次；返回 true 表示本次需要通知。 */
    fun shouldNotify(context: Context, key: String, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(NOTIFY_PREFIX + key, 0L)
        if (last > 0L && now - last < HealthNotificationGate.DEFAULT_WINDOW_MILLIS) return false
        prefs.edit().putLong(NOTIFY_PREFIX + key, now).apply()
        return true
    }

    fun isHealthy(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(HEARTBEAT, 0L)
        return last > 0L && now - last < 25 * 60_000L
    }
}
