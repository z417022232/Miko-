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
import com.example.worktimetracker.domain.engine.ShiftProfileLearner
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.model.WorkSession
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import com.example.worktimetracker.location.recovery.ServiceRecovery

class ForegroundLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processor = LocationEventProcessor()
    private val samplingPolicy = LocationSamplingPolicy()
    private val locationAnalyzer = LocationStatusAnalyzer()
    private val sessionEngine = WorkSessionEngine(ZoneId.systemDefault())
    private val fixGate = LocationFixGate(LAST_KNOWN_MAX_AGE_MILLIS)
    private val profileLearner = ShiftProfileLearner()
    private val companyFallback = CompanyPresenceFallback(ZoneId.systemDefault())
    private val processingGate = LocationProcessingGate<Location>()
    private val safeProcessor = SafeLocationProcessor<Location>()
    private val failureLimiter = LocationFailureLimiter()
    private val processingSignal = Channel<Unit>(Channel.CONFLATED)
    private val learnedCache = RevisionCache<Pair<WorkSettings, ShiftProfileLearner.Profile>>()
    @Volatile private var cachedSettings: com.example.worktimetracker.data.entity.UserSettingsEntity? = null
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
    private val serviceHeartbeat = object : Runnable {
        override fun run() {
            ServiceRecovery.heartbeat(this@ForegroundLocationService)
            watchdogHandler.postDelayed(this, 5 * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        startForeground(NOTIFICATION_ID, buildNotification("正在记录工时"))
        val app = application as WorkTimeApplication
        scope.launch {
            app.database.userSettingsDao().observeSettings().collectLatest { cachedSettings = it }
        }
        scope.launch {
            for (ignored in processingSignal) {
                var pending = processingGate.takePending()
                while (pending != null) {
                    val error = safeProcessor.process(pending.value) { processLocation(it) }
                    if (error == null) {
                        failureLimiter.success()
                    } else {
                        val key = "${error::class.java.simpleName}:${error.stackTrace.firstOrNull()?.lineNumber ?: 0}"
                        when (failureLimiter.record(key, System.currentTimeMillis())) {
                            LocationFailureLimiter.Action.LOG -> logEvent("LOCATION_PROCESSING_ERROR", key)
                            LocationFailureLimiter.Action.NOTIFY_AND_THROTTLE -> {
                                logEvent("LOCATION_PROCESSING_ERROR", "$key repeated")
                                sendSimpleNotification("自动记录异常", "单条定位处理失败，服务仍在运行；相关记录已保留待确认")
                            }
                            LocationFailureLimiter.Action.SUPPRESS -> Unit
                        }
                    }
                    pending = processingGate.takePending()
                }
            }
        }
        startLocationUpdates()
        watchdogHandler.postDelayed(locationWatchdog, WATCHDOG_INTERVAL_MILLIS)
        watchdogHandler.post(serviceHeartbeat)
        logEvent("SERVICE", "前台定位服务已启动")
        ServiceRecovery.heartbeat(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(locationWatchdog)
        watchdogHandler.removeCallbacks(serviceHeartbeat)
        locationManager?.removeUpdates(this)
        processingSignal.close()
        scope.cancel()
        logEvent("SERVICE", "前台定位服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        ServiceRecovery.heartbeat(this@ForegroundLocationService, now)
        val fixTime = location.time
        ServiceRecovery.locationCallback(this, location.accuracy <= 100f, now)
        if (!fixGate.shouldAccept(location.provider, fixTime, now)) return
        lastFixReceivedAt = now
        processingGate.offer(location.provider ?: "unknown", fixTime, Location(location))
        processingSignal.trySend(Unit)
    }

    private suspend fun processLocation(location: Location) {
        val app = application as WorkTimeApplication
        val now = System.currentTimeMillis()
        val fixTime = location.time
        val settings = cachedSettings ?: app.database.userSettingsDao().getSettings()
            ?.also { cachedSettings = it } ?: com.example.worktimetracker.data.entity.UserSettingsEntity()
        val previous = app.database.workStateDao().getState() ?: com.example.worktimetracker.data.entity.WorkStateEntity()
        val persistedFixTime = if (location.provider == LocationManager.GPS_PROVIDER) previous.lastGpsFixTime else previous.lastNetworkFixTime
        if (persistedFixTime != null && fixTime <= persistedFixTime) return
        val type = processor.classify(location.latitude, location.longitude, location.accuracy, settings)
        val companyDistance = if (settings.companyLat != null && settings.companyLng != null) {
            locationAnalyzer.distanceMeters(location.latitude, location.longitude, settings.companyLat, settings.companyLng)
        } else null
        val movingAway = type == com.example.worktimetracker.domain.model.LocationType.HOME ||
            (location.hasSpeed() && location.speed >= 1.5f) ||
            (companyDistance != null && previous.lastCompanyDistanceMeters != null &&
                companyDistance >= previous.lastCompanyDistanceMeters + 50.0)
        app.database.locationLogDao().insert(
            LocationLogEntity(
                time = fixTime,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                locationType = type.name,
                provider = location.provider
            )
        )
        val next = processor.nextState(previous, type, fixTime, settings, companyDistance, movingAway).copy(
            lastLatitude = location.latitude,
            lastLongitude = location.longitude,
            lastGpsFixTime = if (location.provider == LocationManager.GPS_PROVIDER) fixTime else previous.lastGpsFixTime,
            lastNetworkFixTime = if (location.provider == LocationManager.NETWORK_PROVIDER) fixTime else previous.lastNetworkFixTime
        )
        updateSamplingPolicy(location, type.name, next.currentState, settings)
        maybeCreateOutsideRecord(app, previous, next, now, settings)
        if (type == com.example.worktimetracker.domain.model.LocationType.COMPANY && previous.currentState == "REST") {
            applyCompanyPresenceFallback(app, fixTime, now, settings)
        }
        if (previous.currentState != "WORKING" && next.currentState == "WORKING" && next.sessionStart != null) {
            saveDraftRecord(app, next, settings)
        }
        if (previous.currentState != "FINISHED" && next.currentState == "FINISHED" && previous.sessionStart != null) {
            val learned = learnedSettings(app, settings)
            val typicalDuration = if (detectShift(previous.sessionStart, learned.first) == ShiftType.NIGHT_SHIFT) learned.second.nightTypicalDurationMinutes else learned.second.dayTypicalDurationMinutes
            val maximumEnd = previous.sessionStart + profileLearner.maximumDurationMinutes(typicalDuration) * 60_000L
            val confirmedEnd = next.confirmedDepartureTime ?: fixTime
            val effectiveEnd = minOf(confirmedEnd, maximumEnd)
            val capped = confirmedEnd > maximumEnd
            val session = sessionEngine.buildSession(previous.sessionStart, effectiveEnd, learned.first)
            val existing = app.database.workRecordDao().getByDate(session.assignedDate)
            val recordToSave = ConfirmedSession.merge(
                existing = existing ?: WorkRecordEntity(workDate = session.assignedDate, status = "WORK"),
                shift = session.shiftType.name,
                companyArrival = previous.sessionStart,
                companyDeparture = effectiveEnd,
                homeDeparture = next.homeDepartureTime,
                homeArrival = next.homeArrivalTime,
                actualMinutes = session.actualMinutes,
                calculatedMinutes = session.finalMinutes,
                needsReview = session.needsReview || capped
            )
            app.database.workRecordDao().upsert(
                if (existing != null) ProtectedRecordMerge.merge(existing, recordToSave) else recordToSave
            )
            learnedCache.clear()
            sendWorkRecordNotification(session)
        }
        app.database.workStateDao().save(next)
        if (previous.currentState != next.currentState) {
            app.database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(type = "STATE", content = "${previous.currentState} → ${next.currentState}（${type.name}）"))
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
            ServiceRecovery.providerAvailable(this, false)
            logEvent("LOCATION_DISABLED", "没有可用的定位提供器")
            return
        }
        ServiceRecovery.providerAvailable(this, true)
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, currentSamplingIntervalMillis, 50f, this)
        }
    }

    private suspend fun learnedSettings(
        app: WorkTimeApplication,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ): Pair<WorkSettings, ShiftProfileLearner.Profile> {
        val revision = app.database.workRecordDao().learningRevision() * 31L + settings.updatedAt
        learnedCache.get(revision)?.let { return it }
        val samples = app.database.workRecordDao().latestValidForLearning().mapNotNull { row ->
            val start = row.startTime ?: return@mapNotNull null
            val end = row.endTime ?: return@mapNotNull null
            val shift = runCatching { ShiftType.valueOf(row.shift ?: "") }.getOrNull() ?: return@mapNotNull null
            val minute = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay() / 60
            ShiftProfileLearner.Sample(shift, minute, ((end - start) / 60_000L).toInt(), true)
        }
        val profile = profileLearner.learn(samples, settings.workStartMinutes, settings.workEndMinutes)
        val domain = WorkSettings(
            profile.dayStartMinutes,
            profile.nightStartMinutes,
            settings.hasDefaultHours,
            settings.defaultWorkMinutes,
            settings.restDeductionMinutes,
            settings.outsideThresholdMinutes,
            settings.leaveCompanyConfirmMinutes,
            settings.earlyLeaveToleranceMinutes
        )
        return (domain to profile).also { learnedCache.put(revision, it) }
    }

    private fun detectShift(startMillis: Long, settings: WorkSettings): ShiftType =
        com.example.worktimetracker.domain.engine.ShiftDetector(ZoneId.systemDefault()).detectShift(startMillis, settings)

    private suspend fun saveDraftRecord(
        app: WorkTimeApplication,
        state: com.example.worktimetracker.data.entity.WorkStateEntity,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ) {
        val start = state.sessionStart ?: return
        val learned = learnedSettings(app, settings)
        val session = sessionEngine.buildSession(start, null, learned.first)
        val existing = app.database.workRecordDao().getByDate(session.assignedDate)
        if (existing?.isManual == true || (existing?.needsReview == true && existing.note == CompanyPresenceFallback.FALLBACK_NOTE)) return
        app.database.workRecordDao().upsert(
            (existing ?: WorkRecordEntity(workDate = session.assignedDate, status = "WORK")).copy(
                status = "WORK",
                shift = session.shiftType.name,
                startTime = start,
                homeDepartureTime = state.homeDepartureTime,
                needsReview = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun applyCompanyPresenceFallback(
        app: WorkTimeApplication,
        companyFixAt: Long,
        now: Long,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ) {
        val domain = settings.fallbackDomain()
        val candidate = companyFallback.evaluate(companyFixAt, now, null, domain)
        val candidateRecord = when (candidate) {
            is CompanyPresenceFallback.Action.Draft -> candidate.record
            is CompanyPresenceFallback.Action.UpsertReview -> candidate.record
            CompanyPresenceFallback.Action.None -> return
        }
        val existing = app.database.workRecordDao().getByDate(candidateRecord.workDate)
        when (val action = companyFallback.evaluate(companyFixAt, now, existing, domain)) {
            is CompanyPresenceFallback.Action.Draft -> app.database.workRecordDao().upsert(action.record)
            is CompanyPresenceFallback.Action.UpsertReview -> app.database.workRecordDao().upsert(action.record)
            CompanyPresenceFallback.Action.None -> Unit
        }
    }

    private fun com.example.worktimetracker.data.entity.UserSettingsEntity.fallbackDomain() = WorkSettings(
        workStartMinutes = workStartMinutes,
        workEndMinutes = workEndMinutes,
        hasDefaultHours = hasDefaultHours,
        defaultWorkMinutes = defaultWorkMinutes,
        restDeductionMinutes = restDeductionMinutes,
        outsideThresholdMinutes = outsideThresholdMinutes,
        leaveCompanyConfirmMinutes = leaveCompanyConfirmMinutes,
        earlyLeaveToleranceMinutes = earlyLeaveToleranceMinutes
    )

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


