package com.example.worktimetracker.location.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.worktimetracker.MainActivity
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.notification.NotificationChannels
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.model.WorkSession
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ForegroundLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processor = LocationEventProcessor()
    private val samplingPolicy = LocationSamplingPolicy()
    private val locationAnalyzer = LocationStatusAnalyzer()
    private val sessionEngine = WorkSessionEngine(ZoneId.systemDefault())
    private var locationManager: LocationManager? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastFixReceivedAt: Long = 0L
    private var currentSamplingIntervalMillis = LocationSamplingPolicy.WORK_WINDOW_INTERVAL_MILLIS
    private val locationWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val staleAfter = maxOf(LOCATION_STALE_MILLIS, currentSamplingIntervalMillis + 5 * 60_000L)
            if (lastFixReceivedAt == 0L || now - lastFixReceivedAt >= staleAfter) {
                logEvent("LOCATION_WATCHDOG", "超过15分钟未收到定位，正在重新注册定位监听")
                startLocationUpdates()
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        startForeground(NOTIFICATION_ID, buildNotification("正在记录工时"))
        startLocationUpdates()
        watchdogHandler.postDelayed(locationWatchdog, WATCHDOG_INTERVAL_MILLIS)
        logEvent("SERVICE", "前台定位服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(locationWatchdog)
        locationManager?.removeUpdates(this)
        logEvent("SERVICE", "前台定位服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val app = application as WorkTimeApplication
        val now = System.currentTimeMillis()
        lastFixReceivedAt = now
        scope.launch {
            val settings = app.database.userSettingsDao().getSettings() ?: com.example.worktimetracker.data.entity.UserSettingsEntity()
            val type = processor.classify(location.latitude, location.longitude, location.accuracy, settings)
            app.database.locationLogDao().insert(
                LocationLogEntity(
                    time = now,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    locationType = type.name,
                    provider = location.provider
                )
            )
            val previous = app.database.workStateDao().getState() ?: com.example.worktimetracker.data.entity.WorkStateEntity()
            val next = processor.nextState(previous, type, now, settings).copy(lastLatitude = location.latitude, lastLongitude = location.longitude)
            app.database.workStateDao().save(next)
            updateSamplingPolicy(location, type.name, next.currentState, settings)
            maybeCreateOutsideRecord(app, previous, next, now, settings)
            if (previous.currentState != "FINISHED" && next.currentState == "FINISHED" && previous.sessionStart != null) {
                val domainSettings = WorkSettings(settings.workStartMinutes, settings.workEndMinutes, settings.hasDefaultHours, settings.defaultWorkMinutes, settings.restDeductionMinutes, settings.outsideThresholdMinutes, settings.leaveCompanyConfirmMinutes, settings.earlyLeaveToleranceMinutes)
                val session = sessionEngine.buildSession(previous.sessionStart, now, domainSettings)
                val existing = app.database.workRecordDao().getByDate(session.assignedDate)
                val automaticRecord = WorkRecordEntity(
                        workDate = session.assignedDate,
                        status = when (session.status.name) { "EARLY_LEAVE" -> "EARLY_LEAVE"; "ARRIVAL_EXCEPTION" -> "ARRIVAL_EXCEPTION"; else -> "WORK" },
                        shift = session.shiftType.name,
                        startTime = session.startMillis,
                        endTime = session.endMillis,
                        actualMinutes = session.actualMinutes,
                        finalMinutes = session.finalMinutes,
                        needsReview = session.needsReview
                    )
                val recordToSave = if (existing?.isManual == true) {
                    existing.copy(
                        shift = automaticRecord.shift,
                        startTime = automaticRecord.startTime,
                        endTime = automaticRecord.endTime,
                        actualMinutes = automaticRecord.actualMinutes,
                        needsReview = existing.needsReview || automaticRecord.needsReview,
                        updatedAt = now
                    )
                } else {
                    automaticRecord
                }
                app.database.workRecordDao().upsert(recordToSave)
                sendWorkRecordNotification(session)
            }
            app.database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(type = "LOCATION", content = "${type.name} ${next.currentState}"))
        }
    }

    override fun onProviderEnabled(provider: String) {
        logEvent("LOCATION_ENABLED", "$provider 已开启，重新注册定位监听")
        startLocationUpdates()
    }

    override fun onProviderDisabled(provider: String) {
        sendSimpleNotification("定位异常", "${provider} 已关闭，可能需要按参考上下班时间自动补全")
        logEvent("LOCATION_DISABLED", "$provider 已关闭")
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            scope.launch { (application as WorkTimeApplication).database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(type = "PERMISSION", content = "缺少定位权限")) }
            sendSimpleNotification("定位权限异常", "缺少定位权限，工时记录可能需要自动补全")
            return
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val manager = locationManager ?: return
        manager.removeUpdates(this)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            logEvent("LOCATION_DISABLED", "没有可用的定位提供器")
            return
        }
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, currentSamplingIntervalMillis, 50f, this)
            manager.getLastKnownLocation(provider)
                ?.takeIf { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MILLIS }
                ?.let { onLocationChanged(it) }
        }
    }

    private fun updateSamplingPolicy(
        location: Location,
        locationType: String,
        currentState: String,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ) {
        val fences = listOfNotNull(
            if (settings.companyLat != null && settings.companyLng != null) {
                locationAnalyzer.distanceMeters(location.latitude, location.longitude, settings.companyLat, settings.companyLng) to
                    settings.companyRadiusMeters
            } else null,
            if (settings.homeLat != null && settings.homeLng != null) {
                locationAnalyzer.distanceMeters(location.latitude, location.longitude, settings.homeLat, settings.homeLng) to
                    settings.homeRadiusMeters
            } else null
        )
        val nearestFence = fences.minByOrNull { kotlin.math.abs(it.first - it.second) }
        val interval = samplingPolicy.intervalMillis(
            currentState = currentState,
            locationType = locationType,
            distanceToFenceMeters = nearestFence?.first,
            fenceRadiusMeters = nearestFence?.second ?: 0,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
            nowMillis = System.currentTimeMillis(),
            workStartMinutes = settings.workStartMinutes,
            workEndMinutes = settings.workEndMinutes
        )
        if (interval == currentSamplingIntervalMillis) return
        watchdogHandler.post {
            if (interval != currentSamplingIntervalMillis) {
                currentSamplingIntervalMillis = interval
                logEvent("SAMPLING", "定位采样间隔调整为${interval / 60_000}分钟")
                startLocationUpdates()
            }
        }
    }

    private fun logEvent(type: String, content: String) {
        scope.launch {
            (application as WorkTimeApplication).database.appLogDao().insert(
                com.example.worktimetracker.data.entity.AppLogEntity(type = type, content = content)
            )
        }
    }


    private suspend fun maybeCreateOutsideRecord(
        app: WorkTimeApplication,
        previous: com.example.worktimetracker.data.entity.WorkStateEntity,
        next: com.example.worktimetracker.data.entity.WorkStateEntity,
        now: Long,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ) {
        if (previous.currentState != "LEAVING_HOME" || next.currentState != "LEAVING_HOME") return
        val leftAt = previous.sessionStart ?: previous.lastLocationTime ?: return
        if (now - leftAt < settings.outsideThresholdMinutes * 60_000L) return
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val existing = app.database.workRecordDao().getByDate(date)
        if (existing == null || existing.status == "REST") {
            app.database.workRecordDao().upsert(
                WorkRecordEntity(
                    workDate = date,
                    status = "OUTSIDE",
                    finalMinutes = 0,
                    note = "离家超过${settings.outsideThresholdMinutes}分钟且未进入公司"
                )
            )
            sendSimpleNotification("已标记外出", "离家超过${settings.outsideThresholdMinutes}分钟且未进入公司")
        }
    }

    private fun sendWorkRecordNotification(session: WorkSession) {
        val title = if (session.needsReview) "工时记录需要确认" else "今日工时已记录"
        val shift = if (session.shiftType.name == "NIGHT_SHIFT") "夜班" else "白班"
        val hours = "${session.finalMinutes / 60}小时" + if (session.finalMinutes % 60 == 0) "" else "${session.finalMinutes % 60}分"
        val suffix = when (session.status.name) {
            "EARLY_LEAVE" -> "，检测到下早班，请核对工时"
            "ARRIVAL_EXCEPTION" -> "，到岗时间异常，请确认迟到/请假/调班"
            else -> ""
        }
        sendSimpleNotification(title, "$shift 已计入 $hours$suffix")
    }

    private fun sendSimpleNotification(title: String, text: String) {
        val pendingIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, NotificationChannels.LOCATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }
    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NotificationChannels.LOCATION_CHANNEL_ID)
            .setContentTitle("工时记录助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MILLIS = 15 * 60_000L
        private const val LOCATION_STALE_MILLIS = 15 * 60_000L
        private const val LAST_KNOWN_MAX_AGE_MILLIS = 10 * 60_000L
    }
}


