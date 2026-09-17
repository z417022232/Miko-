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
import androidx.room.withTransaction
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
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.domain.engine.SiteResolver
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
import com.example.worktimetracker.location.permission.LocationCalibrationStore
import com.example.worktimetracker.location.evidence.AmbientScanPolicy
import com.example.worktimetracker.location.evidence.BluetoothEvidenceCollector
import com.example.worktimetracker.location.evidence.CellEvidenceCollector
import com.example.worktimetracker.location.evidence.EnvironmentSaltStore
import com.example.worktimetracker.location.evidence.EvidenceCoordinator
import com.example.worktimetracker.location.evidence.GnssInput
import com.example.worktimetracker.location.evidence.MotionEvidenceController
import com.example.worktimetracker.location.evidence.ScanDecision
import com.example.worktimetracker.location.evidence.ScanPolicyInput
import com.example.worktimetracker.location.evidence.WifiEvidenceCollector
import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.evidence.FingerprintLearningPolicy
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedEvidence
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.model.LocationType
import java.time.Clock

class ForegroundLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processor = LocationEventProcessor()
    private val anchorEngine = TrajectoryAnchorEngine()
    private val samplingPolicy = LocationSamplingPolicy()
    private val locationAnalyzer = LocationStatusAnalyzer()
    private val sessionEngine = WorkSessionEngine(ZoneId.systemDefault())
    private val fixGate = LocationFixGate(LAST_KNOWN_MAX_AGE_MILLIS)
    private val profileLearner = ShiftProfileLearner()
    private val companyFallback = CompanyPresenceFallback(ZoneId.systemDefault())
    private val processingGate = LocationProcessingGate<Location>()
    private val safeProcessor = SafeLocationProcessor<Location>()
    private val failureLimiter = LocationFailureLimiter()
    private val providerRecoveryGate = ProviderRecoveryGate()
    private val providerAlerts = ProviderAlertAggregator()
    private val processingSignal = Channel<Unit>(Channel.CONFLATED)
    private val registrationState = SourceRegistrationState()
    private val ambientScanPolicy = AmbientScanPolicy()
    private var evidenceCoordinator: EvidenceCoordinator? = null
    private var motionController: MotionEvidenceController? = null
    private var wifiCollector: WifiEvidenceCollector? = null
    private var bluetoothCollector: BluetoothEvidenceCollector? = null
    private var cellCollector: CellEvidenceCollector? = null
    @Volatile private var lastAmbientScanAt = 0L
    @Volatile private var ambientScanRequested = false
    @Volatile private var ambientScanMayStartWifiScan = false
    @Volatile private var motionBurstUntil: Long = 0L

    /** Burst 起始时间：硬顶上限从这里起算，顺延不能突破（方案二修正） */
    private var burstStartedAt: Long = 0L

    /** 硬顶阶段标记：0~10 分钟 1 分钟档，之后仍在移动降为 5 分钟档 */
    private var burstMediumPhase = false
    private var burstConfirmPlace: LocationType? = null
    private var burstConfirmCount = 0

    /** 最近一次定位：Burst 结束后供常规采样策略重算距离/速度 */
    @Volatile private var lastLocation: Location? = null
    private val endMotionBurstRunnable = Runnable { endMotionBurst("窗口结束") }
    @Volatile private var lastGnssCallbackWallClock = 0L
    @Volatile private var lastResolvedPlace = ResolvedPlace.UNKNOWN
    @Volatile private var lastClassifiedPlace: LocationType? = null
    @Volatile private var classifiedPlaceSince = 0L
    private val learnedCache = RevisionCache<Pair<WorkSettings, ShiftProfileLearner.Profile>>()
    @Volatile private var cachedSettings: com.example.worktimetracker.data.entity.UserSettingsEntity? = null

    /**
     * 生效地点集合缓存（v4.3 多地点）。
     *
     * 站点是低频变更数据，但每次定位回调都要用；60 秒 TTL 既避免每帧查库，
     * 又能在用户刚改完地点后很快跟上（改地点不需要重启服务）。
     */
    @Volatile private var cachedSitePoints: List<com.example.worktimetracker.domain.engine.SitePoint> = emptyList()
    @Volatile private var cachedSitePointsAt: Long = 0L

    /** 本轮 Movement Burst 的上限（毫秒）。在 Burst 起始时定死，期间改设置不影响本轮。 */
    private var burstCapMillisAtStart: Long = MOTION_BURST_MILLIS

    /** 「常规采集间隔」（毫秒）：用户档位 → 毫秒的换算与夹取统一在 SamplingTuning。 */
    private fun normalIntervalMillis(): Long =
        SamplingTuning.normalIntervalMillis(cachedSettings?.samplingIntervalMinutes)

    /** Movement Burst 的实际上限（毫秒）：只能调低，永远不突破 10 分钟硬顶。 */
    private fun motionBurstMillis(): Long =
        SamplingTuning.burstCapMillis(cachedSettings?.burstCapMinutes)
    private var locationManager: LocationManager? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastFixReceivedAt: Long = 0L
    private var currentSamplingIntervalMillis = LocationSamplingPolicy.WORK_WINDOW_INTERVAL_MILLIS
    private var pendingSamplingIntervalMillis = currentSamplingIntervalMillis
    private val applySamplingInterval = Runnable {
        val interval = pendingSamplingIntervalMillis
        if (interval != currentSamplingIntervalMillis) {
            currentSamplingIntervalMillis = interval
            logEvent("SAMPLING", "定位采样间隔调整为${interval / 60_000}分钟")
            startLocationUpdates()
        }
    }
    private val locationWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val lastCallback = registrationState.lastCallback(SOURCE_LOCATION) ?: 0L
            val staleAfter = maxOf(LOCATION_STALE_MILLIS, currentSamplingIntervalMillis + 5 * 60_000L)
            if (lastCallback == 0L || now - lastCallback >= staleAfter) {
                // 静止且设置了 50 米最小距离时无回调是正常现象：先请求环境快照补充证据
                requestAmbientScan(significantMotion = false, now = now)
                val hardStaleAfter = maxOf(staleAfter, currentSamplingIntervalMillis * 2)
                if (lastCallback == 0L || now - lastCallback >= hardStaleAfter) {
                    logEvent("LOCATION_WATCHDOG", "长时间未收到定位，正在重新注册定位监听")
                    registrationState.invalidate(SOURCE_LOCATION)
                    startLocationUpdates()
                }
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
    private val providerSummary = Runnable {
        val providers = providerAlerts.disabledProviders()
        if (providers.isNotEmpty()) logEvent("LOCATION_DISABLED", "系统定位Provider暂停：${providers.joinToString()}")
    }
    private val providerGlobalCheck = Runnable {
        val enabled = locationManager?.isLocationEnabled ?: true
        if (providerAlerts.shouldNotifyGlobal(enabled, System.currentTimeMillis())) {
            ServiceRecovery.systemLocationDisabled(this, System.currentTimeMillis())
            sendSimpleNotification("系统定位已暂停", "系统睡眠模式暂停定位，恢复后将自动继续")
        }
    }
    private val departureConfirmation = Runnable { scope.launch { confirmDepartureIfDue() } }

    /**
     * 一次性刷新进行中标记。
     *
     * 由 [requestImmediateRefresh] 置位；`onLocationChanged` 收到**任何**一次回调即复位并恢复常规档。
     * 看门狗兜底复位（[restoreAfterOneShot]）。
     */
    @Volatile private var oneShotRefreshInFlight = false
    private val restoreAfterOneShot = Runnable {
        if (!oneShotRefreshInFlight) return@Runnable
        oneShotRefreshInFlight = false
        logEvent("REFRESH", "一次性刷新超时未取到新定位，恢复常规采样档")
        registrationState.invalidate(SOURCE_LOCATION)
        startLocationUpdates()
    }

    /**
     * 用户手动「立即刷新一次」：强制定位源立刻回调一次 + 一次环境扫描（四源）。
     *
     * ⚠️ 只做**重取样**，不碰状态机、不写工时记录 —— 结果该不该改状态仍由
     * `TrajectoryAnchorEngine` 依弱证据/强证据规则决定（弱证据单源不得改状态机）。
     */
    private fun requestImmediateRefresh() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            logEvent("REFRESH", "手动刷新失败：缺少定位权限")
            return
        }
        oneShotRefreshInFlight = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            oneShotRefreshInFlight = false
            logEvent("REFRESH", "手动刷新失败：没有可用的定位提供器")
            return
        }
        runCatching {
            manager.removeUpdates(this)
            // 0ms / 0m 是 Android 规定的「尽快回调一次」，会带上系统缓存的最新定位
            providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, this) }
        }.onFailure {
            oneShotRefreshInFlight = false
            logEvent("REFRESH", "手动刷新注册失败：${it.message}")
            return
        }
        // 环境三源（Wi-Fi / 蓝牙 / 基站）也刷一次；BURST 才允许主动发起 Wi-Fi 扫描
        ambientScanMayStartWifiScan = true
        ambientScanRequested = true
        processingSignal.trySend(Unit)
        watchdogHandler.removeCallbacks(restoreAfterOneShot)
        watchdogHandler.postDelayed(restoreAfterOneShot, ONE_SHOT_REFRESH_TIMEOUT_MILLIS)
        logEvent("REFRESH", "收到手动刷新请求：定位与环境证据各重取一次")
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        startForeground(NOTIFICATION_ID, buildNotification("正在记录工时"))
        val app = application as WorkTimeApplication
        setupEvidenceComponents()
        scope.launch { runCatching { evidenceCoordinator?.onServiceStart() } }
        motionController?.start()
        scope.launch {
            app.database.userSettingsDao().observeSettings().collectLatest {
                cachedSettings = it
                if (it != null) rescheduleDepartureConfirmation(it)
            }
        }
        scope.launch {
            reconcileIncompleteSession(app)
            app.database.userSettingsDao().getSettings()?.let { rescheduleDepartureConfirmation(it) }
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
                // Motion 不再产生地点证据（方案一）：它只负责 Movement Burst 唤醒重新取证，
                // 已在 onSignificantMotionDetected 中处理
                if (ambientScanRequested) {
                    ambientScanRequested = false
                    runCatching { runAmbientScan() }
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
        // 手动「立即刷新一次」：由 UI 以 startForegroundService(ACTION_REFRESH_NOW) 触发
        if (intent?.action == ACTION_REFRESH_NOW) {
            requestImmediateRefresh()
        }
        // 用户改了「学习校准开关」：丢掉生效地点缓存，让下一次融合立刻按新偏好取锚点。
        // 不走 ACTION_REFRESH_NOW 是因为那会顺带重取一次定位（多耗一次电），
        // 而这里只是「让缓存失效」，不做任何采样。
        if (intent?.action == ACTION_INVALIDATE_SITE_CACHE) {
            invalidateSiteCache()
        }
        return START_STICKY
    }

    /**
     * 丢掉生效地点缓存。
     *
     * 缓存本身是 60 秒的省电优化（见 [effectiveSites]），但用户按「停用学习校准」
     * 时若还要等最多 60 秒才真正停用，界面就会说一套、判定做另一套 ——
     * 这正是本项目最不能出现的一类问题，所以给一条显式的失效通道。
     */
    private fun invalidateSiteCache() {
        cachedSitePointsAt = 0L
        logEvent("LEARNING", "收到学习校准开关变更：已丢弃生效地点缓存")
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(locationWatchdog)
        watchdogHandler.removeCallbacks(serviceHeartbeat)
        watchdogHandler.removeCallbacks(applySamplingInterval)
        watchdogHandler.removeCallbacks(providerSummary)
        watchdogHandler.removeCallbacks(providerGlobalCheck)
        watchdogHandler.removeCallbacks(departureConfirmation)
        watchdogHandler.removeCallbacks(endMotionBurstRunnable)
        watchdogHandler.removeCallbacks(restoreAfterOneShot)
        oneShotRefreshInFlight = false
        locationManager?.removeUpdates(this)
        // 停止传感器、Wi-Fi/蓝牙扫描及所有定位监听
        motionController?.stop()
        wifiCollector?.stop()
        bluetoothCollector?.stop()
        cellCollector?.stop()
        motionController = null
        evidenceCoordinator = null
        // 独立协程写入运动来源停止状态，避免被随后的 scope.cancel() 取消
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                (application as WorkTimeApplication).database.environmentEvidenceDao().upsertHealth(
                    com.example.worktimetracker.data.entity.LocationHealthEntity(
                        name = "motion", lastCallbackAt = 0L, lastSuccessAt = 0L,
                        registered = false, recoveryCount = 0, lastFailure = null
                    )
                )
            }
        }
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
        val provider = location.provider ?: "unknown"
        ServiceRecovery.locationCallback(this, location.accuracy <= 100f, now)
        registrationState.recordCallback(provider, now)
        // 同步更新聚合键：定位看护检查读取 SOURCE_LOCATION 的回调时间，
        // 与各 Provider（gps/network）分开记录，缺少会导致看护一直误判陈旧并反复重注册
        registrationState.recordCallback(SOURCE_LOCATION, now)
        // 手动一次性刷新：任何一次回调都算「服务已响应」，立刻回到常规采样档
        // （放在最前面，基线回调/被 fixGate 拦掉的回调也会复位，避免 8 秒兜底才恢复）
        if (oneShotRefreshInFlight) {
            oneShotRefreshInFlight = false
            watchdogHandler.removeCallbacks(restoreAfterOneShot)
            logEvent("REFRESH", "手动刷新已取到定位，恢复常规采样档")
            registrationState.invalidate(SOURCE_LOCATION)
            startLocationUpdates()
        }
        // Provider 恢复后的首次回调仅用于建立基线，不作为证据
        if (!registrationState.mayEmitEvidence(provider)) {
            lastFixReceivedAt = now
            lastGnssCallbackWallClock = now
            logEvent("PROVIDER_BASELINE", "$provider 恢复后的首个定位仅用于建立基线")
            return
        }
        if (!fixGate.shouldAccept(provider, fixTime, now)) return
        lastFixReceivedAt = now
        lastGnssCallbackWallClock = now
        processingGate.offer(provider, fixTime, Location(location))
        processingSignal.trySend(Unit)
    }

    private suspend fun processLocation(location: Location) {
        val app = application as WorkTimeApplication
        val now = System.currentTimeMillis()
        val fixTime = location.time
        lastLocation = location
        val settings = cachedSettings ?: app.database.userSettingsDao().getSettings()
            ?.also { cachedSettings = it } ?: com.example.worktimetracker.data.entity.UserSettingsEntity()
        app.database.withTransaction {
        val previous = app.database.workStateDao().getState() ?: com.example.worktimetracker.data.entity.WorkStateEntity()
        val persistedFixTime = if (location.provider == LocationManager.GPS_PROVIDER) previous.lastGpsFixTime else previous.lastNetworkFixTime
        if (persistedFixTime != null && fixTime <= persistedFixTime) return@withTransaction
        // GNSS 来源健康状态：回调时间与可靠成功时间分开记录
        runCatching {
            val previousHealth = app.database.environmentEvidenceDao().health("gnss")
            app.database.environmentEvidenceDao().upsertHealth(
                com.example.worktimetracker.data.entity.LocationHealthEntity(
                    name = "gnss",
                    lastCallbackAt = now,
                    lastSuccessAt = if (location.accuracy <= 100f) now else previousHealth?.lastSuccessAt ?: 0L,
                    registered = true,
                    recoveryCount = 0,
                    lastFailure = null
                )
            )
        }
        // 多地点（v4.3）：分类与距离都从生效地点集合算，状态机拿到的仍是
        // companyDistance / homeDistance 两个标量，[TrajectoryAnchorEngine] 完全不需要改
        val sites = effectiveSites(app, settings)
        val classified = processor.classify(location.latitude, location.longitude, location.accuracy, settings, sites)
        val workMatch = SiteResolver.nearest(location.latitude, location.longitude, sites, SiteResolver.TYPE_WORK)
        val nonWorkMatch = SiteResolver.nearest(location.latitude, location.longitude, sites, SiteResolver.TYPE_NON_WORK)
        val companyDistance = workMatch?.distanceMeters
        val homeDistance = nonWorkMatch?.distanceMeters
        // REST + HOME 永远不设置 movingAway：到家类型本身不是离开公司的通用移动证据
        val movingAway = classified != LocationType.HOME &&
            ((location.hasSpeed() && location.speed >= MOVING_SPEED_METERS_PER_SECOND) ||
                (companyDistance != null && previous.lastCompanyDistanceMeters != null &&
                    companyDistance >= previous.lastCompanyDistanceMeters + 50.0))
        app.database.locationLogDao().insert(
            LocationLogEntity(
                time = fixTime,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                locationType = classified.name,
                provider = location.provider
            )
        )
        val calibration = LocationCalibrationStore(this)
        val calibrated = calibration.companyCalibratedAt() > 0L
        val coordinator = evidenceCoordinator
        val fused: FusedEvidence? = if (coordinator != null) {
            // GNSS 观察交给协调器学习与融合；异常上抛由 safeProcessor 记录
            coordinator.onGnss(buildGnssInput(calibration, calibrated, classified, fixTime,
                location.accuracy, companyDistance, homeDistance, settings,
                location.provider ?: "gps"))
        } else null
        if (fused != null) publishFusedStatus(fused, now)
        // Movement Burst 管理（方案二）：确认正在移动→顺延窗口保持 1 分钟档；
        // 连续 2 个可靠 CONFIRMED 结果→提前收敛回常规档
        if (motionBurstUntil > 0L && fused != null) {
            if (location.hasSpeed() && location.speed >= MOVING_SPEED_METERS_PER_SECOND) {
                extendMotionBurst(now)
            } else if (fused.decision == FusedDecision.CONFIRMED && location.accuracy <= 100f) {
                // 语义是「连续两个可靠融合结果」：以融合地点为准，而不是单次经纬度分类
                val confirmedPlace = locationTypeOf(fused.place)
                if (burstConfirmPlace == confirmedPlace) burstConfirmCount++ else {
                    burstConfirmPlace = confirmedPlace
                    burstConfirmCount = 1
                }
                if (burstConfirmCount >= 2) endMotionBurst("连续确认 $confirmedPlace")
            }
        }
        if (fused != null && fused.decision != FusedDecision.CONFIRMED) {
            if (fused.decision == FusedDecision.UNKNOWN) {
                // 融合结果不确定：低精度定位只保留日志与去重字段，不得改变工时状态
                lastResolvedPlace = ResolvedPlace.UNKNOWN
            }
            // MAINTAINED（弱证据/连续性维持）：只能维持状态（方案三/四/八），
            // 不得触发状态转换，也不打断已确认会话
            app.database.workStateDao().save(previous.copy(
                lastLatitude = location.latitude,
                lastLongitude = location.longitude,
                lastGpsFixTime = if (location.provider == LocationManager.GPS_PROVIDER) fixTime else previous.lastGpsFixTime,
                lastNetworkFixTime = if (location.provider == LocationManager.NETWORK_PROVIDER) fixTime else previous.lastNetworkFixTime,
                updatedAt = now
            ))
            return@withTransaction
        }
        val type = if (fused != null) locationTypeOf(fused.place) else classified
        if (fused != null) lastResolvedPlace = fused.place
        // 唯一工时状态机：TrajectoryAnchorEngine。校准只影响公司稳定半径（未校准用 100m 默认值）
        // 与证据可信度，不再切换到第二套降级状态机
        val stateDecision = anchorEngine.next(previous, TrajectoryAnchorEngine.Fix(
            time = fixTime, type = type, accuracyMeters = location.accuracy,
            provider = location.provider ?: "unknown", companyDistanceMeters = companyDistance,
            companyAnchorDistanceMeters = companyDistance, homeDistanceMeters = homeDistance,
            homeAnchorDistanceMeters = homeDistance, speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
            movingAway = movingAway,
            // 融合层已判为可靠绝对定位（质量 >= 0.80 的 GNSS）时单次即确认到岗；
            // 环境证据（CONFIRMED_AMBIENT）不享受该待遇，仍需连续两次
            strongEvidence = fused?.reason?.startsWith(CONFIRMED_GNSS_PREFIX) == true
        ), TrajectoryAnchorEngine.Config(
            // 半径取「命中的那个地点」自己的半径：多车间半径不同时，离岗半径必须跟着实际地点走
            workMatch?.site?.radiusMeters ?: settings.companyRadiusMeters,
            nonWorkMatch?.site?.radiusMeters ?: settings.homeRadiusMeters,
            calibration.companyStableRadius(), HOME_STABLE_RADIUS_METERS, settings.leaveCompanyConfirmMinutes
        )).nextState
        val next = stateDecision.copy(
            lastLatitude = location.latitude,
            lastLongitude = location.longitude,
            lastGpsFixTime = if (location.provider == LocationManager.GPS_PROVIDER) fixTime else previous.lastGpsFixTime,
            lastNetworkFixTime = if (location.provider == LocationManager.NETWORK_PROVIDER) fixTime else previous.lastNetworkFixTime
        )
        persistStateTransition(app, previous, next, fixTime, now, settings, type, location)
        }
    }

    /** 状态机决策后的共享收尾：采样、外出标记、草稿与完结记录、状态保存与日志。 */
    private suspend fun persistStateTransition(
        app: WorkTimeApplication,
        previous: com.example.worktimetracker.data.entity.WorkStateEntity,
        next: com.example.worktimetracker.data.entity.WorkStateEntity,
        fixTime: Long,
        now: Long,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity,
        type: LocationType,
        location: Location?
    ) {
        if (location != null) updateSamplingPolicy(location, type.name, next.currentState, settings)
        maybeCreateOutsideRecord(app, previous, next, now, settings)
        if (type == LocationType.COMPANY && previous.currentState == "REST") {
            applyCompanyPresenceFallback(app, fixTime, now, settings)
        }
        if (previous.currentState != "WORKING" && next.currentState == "WORKING" && next.sessionStart != null) {
            saveDraftRecord(app, next, settings)
        }
        if (previous.currentState != "FINISHED" && next.currentState == "FINISHED" && previous.sessionStart != null) {
            finalizeSessionRecord(app, previous, next, fixTime, settings)
        }
        if (previous.currentState == "FINISHED" && next.currentState == "REST" && next.homeArrivalTime != null) {
            // 迟到家证据：工时记录已在 FINISHED 时落库，必须把到家时间补写进当天 WorkRecord，
            // 否则记录永远缺 homeArrivalTime 被标记 needsReview
            backfillHomeArrival(app, next.homeArrivalTime)
        }
        app.database.workStateDao().save(next)
        scheduleDepartureConfirmation(next, settings)
        if (previous.currentState != next.currentState) {
            app.database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(type = "STATE", content = "${previous.currentState} → ${next.currentState}（${type.name}）"))
            // 真机轨迹验证 TRACE（测试工具）：状态变化时一行记录融合判断/状态前后/采样档位/Burst 阶段
            app.database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(type = "TRACE", content = VerificationTrace.stateLine(
                previous, next, lastResolvedPlace.name, currentSamplingIntervalMillis,
                motionBurstUntil, burstMediumPhase, now)))
        }
    }

    /**
     * 生效地点集合（数据库站点 + 旧设置兜底），带 60 秒缓存。
     *
     * 兜底来自 [com.example.worktimetracker.location.service.effectiveSites]：
     * 站点表里某一类型没有带坐标的地点时，用 user_settings 的 companyLat/homeLat 合成一条。
     *
     * [withLearnedAnchors] 自 DB v16 起还要吃「用户是否停用学习校准」这一行 ——
     * 缺行 = 允许，所以老库升上来行为不变。
     */
    private suspend fun effectiveSites(
        app: WorkTimeApplication,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ): List<SitePoint> {
        val now = System.currentTimeMillis()
        if (cachedSitePointsAt > 0L && now - cachedSitePointsAt < SITES_CACHE_MILLIS) return cachedSitePoints
        cachedSitePoints = runCatching {
            val learningDao = app.database.learningModelDao()
            settings.effectiveSites(app.database.siteDao().all())
                .withLearnedAnchors(
                    models = learningDao.allPlaces(),
                    preferences = learningDao.allPreferences()
                )
        }.getOrDefault(emptyList())
        cachedSitePointsAt = now
        return cachedSitePoints
    }

    /** 采样策略用的围栏：所有启用地点各一条「(距离, 半径)」。 */
    private fun siteFences(location: Location): List<Pair<Double, Int>> =
        cachedSitePoints.filter { it.enabled }.mapNotNull { site ->
            SiteResolver.distanceTo(location.latitude, location.longitude, site)
                ?.let { distance -> distance to site.radiusMeters }
        }

    private fun buildGnssInput(
        calibration: LocationCalibrationStore,
        calibrated: Boolean,
        classified: LocationType,
        fixTime: Long,
        accuracyMeters: Float,
        companyDistance: Double?,
        homeDistance: Double?,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity,
        provider: String
    ): GnssInput {
        if (classified != lastClassifiedPlace) {
            lastClassifiedPlace = classified
            classifiedPlaceSince = fixTime
        }
        val companyStableRadius = if (calibrated) calibration.companyStableRadius() else settings.companyRadiusMeters
        val inCore = (classified == LocationType.COMPANY && companyDistance != null && companyDistance <= companyStableRadius) ||
            (classified == LocationType.HOME && homeDistance != null && homeDistance <= HOME_STABLE_RADIUS_METERS)
        return GnssInput(
            eventTime = fixTime,
            place = resolvedPlaceOf(classified),
            accuracyMeters = accuracyMeters,
            inCore = inCore,
            stableSince = classifiedPlaceSince,
            inferred = false,
            manualReplay = false,
            anomalousShift = isAnomalousShiftTime(fixTime, settings),
            provider = provider
        )
    }

    private fun resolvedPlaceOf(type: LocationType): ResolvedPlace = when (type) {
        LocationType.HOME -> ResolvedPlace.HOME
        LocationType.COMPANY -> ResolvedPlace.COMPANY
        LocationType.OTHER -> ResolvedPlace.OTHER
        LocationType.UNKNOWN -> ResolvedPlace.UNKNOWN
    }

    private fun locationTypeOf(place: ResolvedPlace): LocationType = when (place) {
        ResolvedPlace.HOME -> LocationType.HOME
        ResolvedPlace.COMPANY -> LocationType.COMPANY
        ResolvedPlace.OTHER, ResolvedPlace.MOVING -> LocationType.OTHER
        ResolvedPlace.UNKNOWN -> LocationType.UNKNOWN
    }

    private fun isAnomalousShiftTime(time: Long, settings: com.example.worktimetracker.data.entity.UserSettingsEntity): Boolean {
        val minuteOfDay = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        val start = settings.workStartMinutes - SHIFT_WINDOW_MARGIN_MINUTES
        val end = settings.workEndMinutes + SHIFT_WINDOW_MARGIN_MINUTES
        return if (start <= end) minuteOfDay < start || minuteOfDay > end
        else minuteOfDay < start && minuteOfDay > end
    }

    private fun scheduleDepartureConfirmation(
        state: com.example.worktimetracker.data.entity.WorkStateEntity,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ) {
        watchdogHandler.removeCallbacks(departureConfirmation)
        val candidate = state.candidateCompanyDepartureTime ?: state.tempLeaveStart
        if (state.currentState != "TEMP_LEAVE" || candidate == null) return
        val deadline = candidate + settings.leaveCompanyConfirmMinutes.coerceAtLeast(5) * 60_000L
        watchdogHandler.postDelayed(departureConfirmation, (deadline - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private suspend fun rescheduleDepartureConfirmation(settings: com.example.worktimetracker.data.entity.UserSettingsEntity) {
        val state = (application as WorkTimeApplication).database.workStateDao().getState() ?: return
        scheduleDepartureConfirmation(state, settings)
    }

    private suspend fun confirmDepartureIfDue() {
        val app = application as WorkTimeApplication
        app.database.withTransaction {
            val state = app.database.workStateDao().getState() ?: return@withTransaction
            val settings = app.database.userSettingsDao().getSettings() ?: return@withTransaction
            val candidate = state.candidateCompanyDepartureTime ?: state.tempLeaveStart
            when (DepartureConfirmationPolicy.evaluate(
                state.currentState, candidate, state.candidateHomeArrivalTime,
                state.movingAwayCount, state.lastCompanyDistanceMeters,
                settings.companyRadiusMeters, settings.leaveCompanyConfirmMinutes,
                System.currentTimeMillis()
            )) {
                DepartureConfirmationPolicy.Action.CANCEL -> watchdogHandler.removeCallbacks(departureConfirmation)
                DepartureConfirmationPolicy.Action.WAIT -> scheduleDepartureConfirmation(state, settings)
                DepartureConfirmationPolicy.Action.WAIT_FOR_EVIDENCE -> Unit
                DepartureConfirmationPolicy.Action.CONFIRM -> {
                    val now = System.currentTimeMillis()
                    val firstHome = state.candidateHomeArrivalTime
                    val next = state.copy(
                        currentState = if (firstHome != null) "REST" else "FINISHED",
                        confirmedDepartureTime = candidate,
                        companyDepartureConfirmedAt = now,
                        homeArrivalTime = firstHome,
                        homeArrivalConfirmedAt = if (firstHome != null) now else null,
                        sessionStart = if (firstHome != null) null else state.sessionStart,
                        tempLeaveStart = null,
                        updatedAt = now
                    )
                    finalizeSessionRecord(app, state, next, now, settings)
                    app.database.workStateDao().save(next)
                    app.database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(
                        type = "STATE", content = "TEMP_LEAVE → ${next.currentState}（离岗计时确认）"))
                }
            }
        }
    }

    private suspend fun finalizeSessionRecord(
        app: WorkTimeApplication,
        previous: com.example.worktimetracker.data.entity.WorkStateEntity,
        next: com.example.worktimetracker.data.entity.WorkStateEntity,
        confirmedAt: Long,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity
    ) {
        val start = previous.sessionStart ?: return
        val learned = learnedSettings(app, settings)
        val typicalDuration = if (detectShift(start, learned.first) == ShiftType.NIGHT_SHIFT) {
            learned.second.nightTypicalDurationMinutes
        } else learned.second.dayTypicalDurationMinutes
        val maximumEnd = start + profileLearner.maximumDurationMinutes(typicalDuration) * 60_000L
        val confirmedEnd = next.confirmedDepartureTime ?: confirmedAt
        val effectiveEnd = minOf(confirmedEnd, maximumEnd)
        val capped = confirmedEnd > maximumEnd
        val session = sessionEngine.buildSession(start, effectiveEnd, learned.first)
        val existing = app.database.workRecordDao().getByDate(session.assignedDate)
        val recordToSave = ConfirmedSession.merge(
            existing = existing ?: WorkRecordEntity(workDate = session.assignedDate, status = session.status.name),
            shift = session.shiftType.name,
            companyArrival = start,
            companyDeparture = effectiveEnd,
            homeDeparture = next.homeDepartureTime,
            homeArrival = next.homeArrivalTime,
            actualMinutes = session.actualMinutes,
            calculatedMinutes = session.finalMinutes,
            needsReview = session.needsReview || capped,
            status = session.status.name,
            mode = MergeMode.FINALIZE_SESSION
        )
        app.database.workRecordDao().upsert(
            if (existing != null) ProtectedRecordMerge.merge(existing, recordToSave, MergeMode.FINALIZE_SESSION) else recordToSave
        )
        learnedCache.clear()
        sendWorkRecordNotification(session)
    }

    /** 迟到家证据补写：把 FINISHED→REST 时才拿到的 homeArrivalTime 写回当天已完结的工时记录。 */
    private suspend fun backfillHomeArrival(app: WorkTimeApplication, arrival: Long) {
        val record = app.database.workRecordDao().latestFinishedWithoutHomeArrival(arrival) ?: return
        if (com.example.worktimetracker.data.entity.ManualFieldMask.contains(
                record.manualFieldsMask, com.example.worktimetracker.data.entity.ManualField.HOME_ARRIVAL)) return
        app.database.workRecordDao().upsert(record.copy(
            homeArrivalTime = arrival, updatedAt = System.currentTimeMillis()))
        logEvent("RECORD", "迟到到家证据：已补写 " + record.workDate + " 记录的到家时间")
    }

    override fun onProviderEnabled(provider: String) {
        registrationState.providerRecovered(provider)
        providerRecoveryGate.providerEnabled(provider)
        ServiceRecovery.providerAvailable(this, true)
        val notifiedGlobalPause = providerAlerts.wasGlobalNotified()
        val recovered = providerAlerts.recovered(locationManager?.isLocationEnabled ?: true, System.currentTimeMillis())
        if (recovered) {
            watchdogHandler.removeCallbacks(providerSummary)
            watchdogHandler.removeCallbacks(providerGlobalCheck)
            if (notifiedGlobalPause) {
                ServiceRecovery.systemLocationRecovered(this, System.currentTimeMillis())
                logEvent("LOCATION_ENABLED", "系统定位已恢复，自动继续记录")
            }
            startLocationUpdates()
        }
    }

    override fun onProviderDisabled(provider: String) {
        val manager = locationManager
        val anyAvailable = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .any { manager?.isProviderEnabled(it) == true }
        ServiceRecovery.providerAvailable(this, anyAvailable)
        providerAlerts.disabled(provider, System.currentTimeMillis())
        watchdogHandler.removeCallbacks(providerSummary)
        watchdogHandler.postDelayed(providerSummary, 5_000L)
        watchdogHandler.removeCallbacks(providerGlobalCheck)
        watchdogHandler.postDelayed(providerGlobalCheck, 60_000L)
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
        val activeProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        val providers = activeProviders + listOf(LocationManager.PASSIVE_PROVIDER).filter { manager.isProviderEnabled(it) }
        if (activeProviders.isEmpty()) {
            ServiceRecovery.providerAvailable(this, false)
            logEvent("LOCATION_DISABLED", "没有可用的定位提供器")
        } else {
            ServiceRecovery.providerAvailable(this, true)
        }
        // 同配置已注册时不重复注册，避免定位风暴与多 Provider 累积监听
        if (!registrationState.begin(SOURCE_LOCATION, currentSamplingIntervalMillis)) return
        // 重新配置前统一移除旧监听，每类来源至多一个活动监听
        manager.removeUpdates(this)
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, currentSamplingIntervalMillis, 50f, this)
        }
    }

    private fun setupEvidenceComponents() {
        if (evidenceCoordinator != null) return
        val app = application as WorkTimeApplication
        val saltStore = EnvironmentSaltStore(this)
        val saltProvider = { saltStore.getOrCreate() }
        val wifi = WifiEvidenceCollector(this, saltProvider) { ambientScanMayStartWifiScan }
        val bluetooth = BluetoothEvidenceCollector(this, saltProvider, scope)
        val cell = CellEvidenceCollector(this, saltProvider)
        evidenceCoordinator = EvidenceCoordinator(
            store = app.database.environmentEvidenceDao(),
            wifiCollector = wifi,
            bluetoothCollector = bluetooth,
            cellCollector = cell,
            learningPolicy = FingerprintLearningPolicy(),
            fusionEngine = EvidenceFusionEngine(),
            clock = Clock.systemDefaultZone(),
            diagnosticLogger = { type, content -> logEvent(type, content) },
            // 用户在地点管理里选的 Wi-Fi 哈希（v4.3）：只作为最弱一档的补充证据
            declaredSourcePlaces = { app.database.siteDao().declaredHashPlaces() }
        )
        wifiCollector = wifi
        bluetoothCollector = bluetooth
        cellCollector = cell
        motionController = MotionEvidenceController(this) { onSignificantMotionDetected(it) }
        scope.launch {
            runCatching {
                app.database.environmentEvidenceDao().upsertHealth(
                    com.example.worktimetracker.data.entity.LocationHealthEntity(
                        name = "motion", lastCallbackAt = 0L, lastSuccessAt = 0L,
                        registered = true, recoveryCount = 0, lastFailure = null
                    )
                )
            }
        }
    }

    /** 显著运动：触发 Movement Burst——环境 BURST + GPS 临时 1 分钟档（方案二）。 */
    private fun onSignificantMotionDetected(eventTime: Long) {
        val now = System.currentTimeMillis()
        requestAmbientScan(significantMotion = true, now = now)
        startMotionBurst(now)
    }

    /**
     * Movement Burst：立即把定位切到 1 分钟档，硬顶 10 分钟（从首次触发起算）。
     * - 确认还在原地点（连续 2 个可靠 CONFIRMED 结果）→ 提前回到常规档；
     * - 确认正在移动（速度 ≥1.5m/s）→ 顺延窗口，但不能突破 10 分钟硬顶；
     *   硬顶后仍在移动 → 降为 5 分钟档继续跟踪（长途不再保持 1 分钟档）；
     * - Burst 结束 → 重新执行 LocationSamplingPolicy，由唯一采样策略决定 1/5/10/30 分钟档。
     */
    private fun startMotionBurst(now: Long) {
        watchdogHandler.removeCallbacks(endMotionBurstRunnable)
        burstStartedAt = now
        // 上限在本轮起始时定死：既读用户档位，又把值锁住，避免顺延口径随设置漂移
        val cap = motionBurstMillis()
        burstCapMillisAtStart = cap
        burstMediumPhase = false
        motionBurstUntil = now + cap
        burstConfirmPlace = null
        burstConfirmCount = 0
        watchdogHandler.postDelayed(endMotionBurstRunnable, cap)
        if (currentSamplingIntervalMillis != LocationSamplingPolicy.FAST_INTERVAL_MILLIS) {
            currentSamplingIntervalMillis = LocationSamplingPolicy.FAST_INTERVAL_MILLIS
            pendingSamplingIntervalMillis = currentSamplingIntervalMillis
            registrationState.invalidate(SOURCE_LOCATION)
            startLocationUpdates()
        }
        logEvent("MOTION_BURST", "检测到移动：定位切至1分钟档，环境扫描BURST（硬顶${cap / 60_000}分钟）")
    }

    private fun extendMotionBurst(now: Long) {
        val hardEnd = burstStartedAt + burstCapMillisAtStart
        if (now >= hardEnd) {
            // 10 分钟硬顶已到：仍在移动不再顺延 1 分钟档，降为 5 分钟档继续跟踪（MOVING_TRACK 阶段）
            if (!burstMediumPhase) {
                burstMediumPhase = true
                if (currentSamplingIntervalMillis != LocationSamplingPolicy.WORK_WINDOW_INTERVAL_MILLIS) {
                    currentSamplingIntervalMillis = LocationSamplingPolicy.WORK_WINDOW_INTERVAL_MILLIS
                    pendingSamplingIntervalMillis = currentSamplingIntervalMillis
                    registrationState.invalidate(SOURCE_LOCATION)
                    startLocationUpdates()
                }
                logEvent("MOTION_BURST", "Burst达${burstCapMillisAtStart / 60_000}分钟硬顶：仍在移动，降为5分钟档继续跟踪")
            }
            // 关键：MOVING_TRACK 阶段每次移动证据都要重排结束回调，
            // 否则硬顶时刻的旧回调触发 endMotionBurst → 重算策略 → 移动又被映射回 1 分钟档
            motionBurstUntil = now + burstCapMillisAtStart
            burstConfirmPlace = null
            burstConfirmCount = 0
            watchdogHandler.removeCallbacks(endMotionBurstRunnable)
            watchdogHandler.postDelayed(endMotionBurstRunnable, burstCapMillisAtStart)
            return
        }
        // 硬顶内顺延：上限锁死在硬顶时间点，而不是 now+10 分钟
        motionBurstUntil = hardEnd
        burstConfirmPlace = null
        burstConfirmCount = 0
        watchdogHandler.removeCallbacks(endMotionBurstRunnable)
        watchdogHandler.postDelayed(endMotionBurstRunnable, hardEnd - now)
    }

    private fun endMotionBurst(reason: String) {
        if (motionBurstUntil == 0L) return
        motionBurstUntil = 0L
        burstStartedAt = 0L
        burstMediumPhase = false
        burstConfirmPlace = null
        burstConfirmCount = 0
        watchdogHandler.removeCallbacks(endMotionBurstRunnable)
        // 不自建第二套采样规则：Burst 结束后按常规 LocationSamplingPolicy 重算，
        // 让下班窗口回 5 分钟、稳定家/公司回 30 分钟、其他回 10 分钟
        scope.launch {
            runCatching { recomputeSamplingPolicyAfterBurst(reason) }
                .onFailure { logEvent("MOTION_BURST", "恢复常规采样失败：${it.message}") }
        }
    }

    /** Burst 结束后用最近一次定位与当前状态重算常规采样间隔。 */
    private suspend fun recomputeSamplingPolicyAfterBurst(reason: String) {
        val app = application as WorkTimeApplication
        val settings = cachedSettings ?: app.database.userSettingsDao().getSettings()
            ?.also { cachedSettings = it } ?: return
        val state = app.database.workStateDao().getState() ?: return
        val location = lastLocation
        val locationType = locationTypeOf(lastResolvedPlace).name
        effectiveSites(app, settings)
        val fences = if (location == null) emptyList() else siteFences(location)
        val nearestFence = fences.minByOrNull { kotlin.math.abs(it.first - it.second) }
        val speed = if (location != null && location.hasSpeed()) location.speed else 0f
        val interval = samplingPolicy.intervalMillis(
            currentState = state.currentState,
            locationType = locationType,
            distanceToFenceMeters = nearestFence?.first,
            fenceRadiusMeters = nearestFence?.second ?: 0,
            speedMetersPerSecond = speed,
            nowMillis = System.currentTimeMillis(),
            workStartMinutes = settings.workStartMinutes,
            workEndMinutes = settings.workEndMinutes,
            normalIntervalMillis = normalIntervalMillis()
        )
        // MOVING_TRACK 保护：Burst 结束时若判定仍在移动，普通策略会把移动映射回 1 分钟档；
        // 长途移动最低保持 5 分钟档，只有停止移动后才交回普通策略的 1/5/10/30 分钟档
        val movingFloor = SamplingTuning.movingTrackFloorMillis(cachedSettings?.samplingIntervalMinutes)
        val effectiveInterval = if (speed >= MOVING_SPEED_METERS_PER_SECOND && interval < movingFloor) {
            logEvent("MOTION_BURST", "结束时机仍在移动：最低保持${movingFloor / 60_000}分钟档，不回1分钟档")
            movingFloor
        } else interval
        val decision = LocationRegistrationPolicy.intervalChange(currentSamplingIntervalMillis, effectiveInterval)
        pendingSamplingIntervalMillis = effectiveInterval
        watchdogHandler.removeCallbacks(applySamplingInterval)
        if (decision.reconfigure) {
            if (decision.delayMillis == 0L) watchdogHandler.post(applySamplingInterval)
            else watchdogHandler.postDelayed(applySamplingInterval, decision.delayMillis)
        }
        logEvent("MOTION_BURST", "恢复常规采样${effectiveInterval / 60_000}分钟档（$reason）")
    }

    private fun requestAmbientScan(significantMotion: Boolean, now: Long = System.currentTimeMillis()) {
        val decision = ambientScanPolicy.evaluate(
            ScanPolicyInput(
                now = now,
                lastScanAt = lastAmbientScanAt,
                significantMotion = significantMotion,
                gnssStale = lastGnssCallbackWallClock == 0L ||
                    now - lastGnssCallbackWallClock >= GNSS_STALE_SCAN_MILLIS,
                nearShiftWindow = nearShiftWindow(now),
                stableKnownPlace = lastResolvedPlace == ResolvedPlace.HOME ||
                    lastResolvedPlace == ResolvedPlace.COMPANY
            ),
            cooldownMillis = normalIntervalMillis()
        )
        if (decision == ScanDecision.NONE) return
        // BURST 才允许主动 Wi-Fi 扫描；SNAPSHOT 只读取系统已有快照
        ambientScanMayStartWifiScan = decision == ScanDecision.BURST
        ambientScanRequested = true
        processingSignal.trySend(Unit)
    }

    private fun nearShiftWindow(now: Long): Boolean {
        val settings = cachedSettings ?: return false
        val minuteOfDay = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        val start = settings.workStartMinutes - SHIFT_WINDOW_MARGIN_MINUTES
        val end = settings.workEndMinutes + SHIFT_WINDOW_MARGIN_MINUTES
        return if (start <= end) minuteOfDay in start..end
        else minuteOfDay >= start || minuteOfDay <= end
    }

    /** 环境扫描在串行消费者协程中执行，结果通过同一 Channel 串行处理，不并发写 Room。 */
    private suspend fun runAmbientScan() {
        val coordinator = evidenceCoordinator ?: return
        val now = System.currentTimeMillis()
        lastAmbientScanAt = now
        val fused = runCatching { coordinator.collectAmbient(now) }.getOrNull()
        ambientScanMayStartWifiScan = false
        fused ?: return
        lastResolvedPlace = fused.place
        publishFusedStatus(fused, now)
        applyFusedEvidence(fused, now)
    }

    /** 融合状态实时发布（方案十 UI）：GPS 与环境两条路径每次融合后都覆盖最新快照。 */
    private fun publishFusedStatus(fused: FusedEvidence, now: Long) {
        (application as WorkTimeApplication).fusedStatus.value = FusedStatusSnapshot(
            place = fused.place,
            decision = fused.decision,
            reason = fused.reason,
            confidence = fused.confidence,
            sources = fused.sources,
            sourceBreakdown = evidenceCoordinator?.lastSourceBreakdown,
            updatedAt = now
        )
    }

    /** 环境融合确认的地点进入状态机：只在锚定距离上标记核心区，坐标距离未知。 */
    private suspend fun applyFusedEvidence(fused: FusedEvidence, now: Long) {
        // 与 GPS 路径同一原则：只有 CONFIRMED 才能进入状态机（方案三）。
        // MAINTAINED/UNKNOWN 由协调器更新 lastResolvedPlace 与诊断，这里直接放弃，
        // 防止 TEMP_LEAVE 中弱公司证据累计两次就把状态推回 WORKING。
        if (fused.decision != FusedDecision.CONFIRMED) return
        if (fused.place == ResolvedPlace.UNKNOWN) return
        val app = application as WorkTimeApplication
        val settings = cachedSettings ?: app.database.userSettingsDao().getSettings()
            ?.also { cachedSettings = it } ?: return
        app.database.withTransaction {
            val previous = app.database.workStateDao().getState()
                ?: return@withTransaction
            // 校准只影响公司稳定半径（未校准回落 100m 默认值），不再禁用环境证据路径——
            // 与 GPS 路径收敛原则一致：校准改可信度，不切路径（2026-09-05）
            val calibration = LocationCalibrationStore(this@ForegroundLocationService)
            val type = locationTypeOf(fused.place)
            val fix = TrajectoryAnchorEngine.Fix(
                time = now,
                type = type,
                accuracyMeters = AMBIENT_NOMINAL_ACCURACY_METERS,
                provider = "ambient:" + fused.sources.joinToString("+") { it.name } + "/${fused.reason}",
                companyDistanceMeters = null,
                companyAnchorDistanceMeters = if (fused.place == ResolvedPlace.COMPANY) AMBIENT_CORE_DISTANCE_METERS else null,
                homeDistanceMeters = null,
                homeAnchorDistanceMeters = if (fused.place == ResolvedPlace.HOME) AMBIENT_CORE_DISTANCE_METERS else null,
                speedMetersPerSecond = 0f,
                movingAway = false
            )
            val decision = anchorEngine.next(previous, fix, TrajectoryAnchorEngine.Config(
                settings.companyRadiusMeters, settings.homeRadiusMeters,
                calibration.companyStableRadius(), HOME_STABLE_RADIUS_METERS, settings.leaveCompanyConfirmMinutes))
            persistStateTransition(app, previous, decision.nextState, now, now, settings, type, null)
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
            // ShiftType.parse 容忍中文标签（"白班"/"夜班"）与旧数据；无法识别才跳过该样本
            val shift = ShiftType.parse(row.shift) ?: return@mapNotNull null
            val minute = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay() / 60
            ShiftProfileLearner.Sample(shift, minute, ((end - start) / 60_000L).toInt(), true)
        }
        val profile = profileLearner.learn(samples, settings.workStartMinutes, settings.workEndMinutes)
        // 到岗异常/班次判定必须以用户声明的上下班时间为准；
        // 学习值是历史到达时刻的中位数，用它判定迟到会天然误报约一半的日子。
        // Profile 仅用于 typicalDuration 推导最长在场时长上限（见 maximumDurationMinutes）。
        val domain = WorkSettings(
            settings.workStartMinutes,
            settings.workEndMinutes,
            settings.hasDefaultHours,
            settings.defaultWorkMinutes,
            settings.restDeductionMinutes,
            settings.outsideThresholdMinutes,
            settings.leaveCompanyConfirmMinutes,
            settings.earlyLeaveToleranceMinutes
        )
        return (domain to profile).also { learnedCache.put(revision, it) }
    }

    private suspend fun reconcileIncompleteSession(app: WorkTimeApplication) {
        app.database.withTransaction {
            val state = app.database.workStateDao().getState() ?: return@withTransaction
            if (state.currentState == "TEMP_LEAVE") return@withTransaction
            val start = state.sessionStart ?: return@withTransaction
            val date = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val record = app.database.workRecordDao().getByDate(date) ?: return@withTransaction
            when (val plan = SessionReconciler.plan(state, record, state.sessionId)) {
                SessionReconciler.Plan.None -> Unit
                is SessionReconciler.Plan.Fill -> {
                    val automatic = record.copy(endTime = plan.companyDeparture,
                        homeArrivalTime = plan.homeArrival, needsReview = true,
                        note = record.note ?: "服务恢复后补齐离岗证据；到家时间请核对",
                        updatedAt = System.currentTimeMillis())
                    app.database.workRecordDao().upsert(ProtectedRecordMerge.merge(record, automatic))
                    app.database.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(
                        type = "SESSION_RECONCILE", content = "已恢复半完成会话 $date review=${plan.needsReview}"))
                }
            }
        }
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
        // Movement Burst 期间采样间隔由 Burst 管理，常规策略不得降档
        if (motionBurstUntil > System.currentTimeMillis()) return
        // 围栏 = 所有启用地点（v4.3）：多车间/多地点时，采样节奏跟着最近的那个地点走
        val fences = siteFences(location)
        val nearestFence = fences.minByOrNull { kotlin.math.abs(it.first - it.second) }
        val interval = samplingPolicy.intervalMillis(
            currentState = currentState,
            locationType = locationType,
            distanceToFenceMeters = nearestFence?.first,
            fenceRadiusMeters = nearestFence?.second ?: 0,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
            nowMillis = System.currentTimeMillis(),
            workStartMinutes = settings.workStartMinutes,
            workEndMinutes = settings.workEndMinutes,
            normalIntervalMillis = normalIntervalMillis()
        )
        val decision = LocationRegistrationPolicy.intervalChange(currentSamplingIntervalMillis, interval)
        if (!decision.reconfigure) return
        pendingSamplingIntervalMillis = interval
        watchdogHandler.removeCallbacks(applySamplingInterval)
        if (decision.delayMillis == 0L) watchdogHandler.post(applySamplingInterval)
        else watchdogHandler.postDelayed(applySamplingInterval, decision.delayMillis)
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
            .setSmallIcon(com.example.worktimetracker.R.drawable.ic_stat_worktime)
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
            .setSmallIcon(com.example.worktimetracker.R.drawable.ic_stat_worktime)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        // 2026-09-16：原先 15 分钟一轮 + 15 分钟阈值，最坏要半小时才自愈；
        // 9/15 夜班实测断流 25 分钟才重新注册，直接导致到岗时刻被推迟。
        private const val WATCHDOG_INTERVAL_MILLIS = 3 * 60_000L
        private const val LOCATION_STALE_MILLIS = 5 * 60_000L
        private const val LAST_KNOWN_MAX_AGE_MILLIS = 10 * 60_000L
        private const val SOURCE_LOCATION = "location"
        private const val GNSS_STALE_SCAN_MILLIS = 20 * 60_000L

        /** Movement Burst 窗口：Motion 触发后定位 1 分钟档最长保持时长（方案二） */
        const val MOTION_BURST_MILLIS = 10 * 60_000L

        const val MOVING_SPEED_METERS_PER_SECOND = 1.5f
        private const val HOME_STABLE_RADIUS_METERS = 100

        /** 生效地点集合的缓存时长：短到用户改完地点能马上生效，长到不会每次定位都查库 */
        private const val SITES_CACHE_MILLIS = 60_000L
        private const val SHIFT_WINDOW_MARGIN_MINUTES = 180
        /** 融合层「可靠绝对定位」的原因前缀；只有它才算强证据。 */
        private const val CONFIRMED_GNSS_PREFIX = "CONFIRMED_GNSS"

        private const val AMBIENT_NOMINAL_ACCURACY_METERS = 50f
        private const val AMBIENT_CORE_DISTANCE_METERS = 30.0

        /**
         * 「立即刷新一次」：UI 手动取证（地点管理的「刷新」、当日记录的四源图标点击）。
         *
         * 语义是**一次性重取样**，不改变任何判定口径：强制定位源立刻回调一次
         * （0ms / 0m，含系统缓存的最新定位），同时请求一次环境扫描（Wi-Fi/蓝牙/基站）。
         * 拿到回调后立即恢复常规采样档，避免把高频定位一直挂着。
         */
        const val ACTION_REFRESH_NOW = "com.example.worktimetracker.action.REFRESH_NOW"

        /**
         * 「丢掉生效地点缓存」：用户在界面改了**学习校准开关**（DB v16）后由 UI 触发。
         *
         * 只失效缓存、不采样 —— 与 [ACTION_REFRESH_NOW] 的区别就在这里：
         * 改一个开关不该顺带多打一次 GPS。
         */
        const val ACTION_INVALIDATE_SITE_CACHE =
            "com.example.worktimetracker.action.INVALIDATE_SITE_CACHE"

        /** 一次性刷新的兜底时限：这么久还没回调就直接恢复常规档（低精度/室内可能拿不到） */
        private const val ONE_SHOT_REFRESH_TIMEOUT_MILLIS = 8_000L
    }
}


