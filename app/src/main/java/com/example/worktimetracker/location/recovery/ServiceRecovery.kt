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
    private const val LOCATION_ALERT_EPISODE = "system_location_alert_episode"
    private const val LOCATION_ALERT_NOTIFIED = "system_location_alert_notified"

    /**
     * 判定「定位服务还活着」的心跳窗口。
     *
     * 服务每 5 分钟喂一次心跳（`ForegroundLocationService.serviceHeartbeat`），
     * 这里留到 12 分钟：既容得下一次丢拍，又不会把「早就被杀掉的服务」当成在运行 ——
     * 误判成「在运行」的代价是把定位服务重新拉起来（见 [invalidateSiteCache]）。
     */
    private const val SERVICE_ALIVE_WINDOW_MILLIS = 12 * 60_000L

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

    /**
     * 用户手动「立即刷新一次」：以 [ServiceRecoveryPolicy.RecoveryTrigger.USER_VISIBLE] 拉起服务，
     * 并通过 Intent action 让服务立刻重取一次定位 + 环境三源（Wi-Fi / 蓝牙 / 基站）。
     *
     * 服务已在运行时 `onStartCommand` 会照常收到该 action，因此不需要先判断是否已启动。
     *
     * @return false = 权限不足或系统不允许前台服务，调用方应如实提示用户
     */
    fun startRefresh(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.USER_VISIBLE, fine, coarse)) return false
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundLocationService::class.java)
                    .setAction(ForegroundLocationService.ACTION_REFRESH_NOW)
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 用户改了**学习校准开关**（DB v16）：让正在运行的定位服务丢掉生效地点缓存。
     *
     * 与 [startRefresh] 的两处刻意差异：
     *  1. **只在服务已在运行时下发**。`startForegroundService` 会把定位服务整个拉起来
     *     （含持续定位与传感器订阅）。用户只是点了一个「停用学习校准」复选框，
     *     绝不能因此把定位打开 —— 那是比缓存过期严重得多的副作用。
     *     判据用服务自己的心跳（每 5 分钟一次，见 `ForegroundLocationService.serviceHeartbeat`）；
     *  2. **不采样**，只失效缓存。
     *
     * 失败是安全的：缓存本身 60 秒自然过期，最坏就是「停用」晚 60 秒在判定侧生效。
     * 返回值只用于日志，调用方不必据此提示用户。
     */
    fun invalidateSiteCache(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (heartbeatAge(context, now) > SERVICE_ALIVE_WINDOW_MILLIS) return false
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundLocationService::class.java)
                    .setAction(ForegroundLocationService.ACTION_INVALIDATE_SITE_CACHE)
            )
            true
        }.getOrDefault(false)
    }

    fun schedule(context: Context): Boolean = runCatching {        // 精确闹钟看门狗：与 WorkManager 健康巡检同时布防，
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

    /** 记录系统定位状态变化；通知领取由 [claimSystemLocationNotification] 单独完成。 */
    @Synchronized
    fun recordSystemLocationState(context: Context, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val transition = SystemLocationStatePolicy.transition(
            prefs.getLong(SYSTEM_LOCATION_DISABLED_AT, 0L),
            prefs.getLong(SYSTEM_LOCATION_RECOVERED_AT, 0L),
            enabled
        )
        val edit = prefs.edit()
        when (transition) {
            SystemLocationTransition.DISABLED -> edit
                .putLong(SYSTEM_LOCATION_DISABLED_AT, now)
                .putLong(LOCATION_ALERT_EPISODE, now)
            SystemLocationTransition.RECOVERED -> edit
                .putLong(SYSTEM_LOCATION_RECOVERED_AT, now)
                .putLong(LOCATION_ALERT_EPISODE, 0L)
                .putLong(LOCATION_ALERT_NOTIFIED, 0L)
            SystemLocationTransition.NONE -> Unit
        }
        edit.commit()
    }

    /** 原子领取当前关闭周期的唯一一次用户通知。 */
    @Synchronized
    fun claimSystemLocationNotification(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val episode = prefs.getLong(LOCATION_ALERT_EPISODE, 0L)
        val notified = prefs.getLong(LOCATION_ALERT_NOTIFIED, 0L)
        if (episode <= 0L || episode == notified) return false
        prefs.edit().putLong(LOCATION_ALERT_NOTIFIED, episode).commit()
        return true
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

}
