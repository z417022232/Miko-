package com.example.worktimetracker.ui.app

import android.app.Application
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.data.entity.ManualOverrideEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.data.entity.SiteEvidenceSourceEntity
import com.example.worktimetracker.data.entity.WorkSegmentEntity
import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.importer.LegacyAttendanceCsvImporter
import com.example.worktimetracker.data.remote.HolidaySyncStore
import com.example.worktimetracker.data.repository.HolidayRepository
import com.example.worktimetracker.domain.engine.HolidayCalendar
import com.example.worktimetracker.domain.engine.WorkdayClock
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.engine.ShiftDetector
import com.example.worktimetracker.domain.engine.WorkHourCalculator
import com.example.worktimetracker.domain.engine.PayrollPeriodRules
import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.domain.payroll.PayRateResolver
import com.example.worktimetracker.domain.payroll.PayrollBreakdown
import com.example.worktimetracker.domain.payroll.PayrollEngine
import com.example.worktimetracker.domain.payroll.PayrollInputs
import com.example.worktimetracker.data.entity.PayRateSegmentEntity
import com.example.worktimetracker.data.entity.MonthlyPayParamsEntity
import com.example.worktimetracker.ui.PayrollPresenter
import com.example.worktimetracker.ui.YearStatsPresenter
import com.example.worktimetracker.domain.engine.ManualRecordEditor
import com.example.worktimetracker.domain.engine.ReviewRecordEditor
import com.example.worktimetracker.domain.engine.ReviewAcknowledger
import com.example.worktimetracker.domain.engine.LocationAnchorCalibration
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.location.permission.LocationCalibrationStore
import com.example.worktimetracker.location.evidence.EnvironmentSaltStore
import com.example.worktimetracker.location.evidence.ScannedWifi
import com.example.worktimetracker.location.evidence.SiteWifiScanner
import com.example.worktimetracker.location.evidence.WifiScanOutcome
import com.example.worktimetracker.location.service.toSitePoint
import com.example.worktimetracker.location.service.PlaceLearningPreferenceService
import com.example.worktimetracker.location.service.PlaceLearningReport
import com.example.worktimetracker.domain.location.PlaceLearningStatus
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.domain.evidence.EvidenceSourceKind
import com.example.worktimetracker.domain.evidence.SourceHealthJudge
import com.example.worktimetracker.domain.evidence.SourceStatus
import com.example.worktimetracker.domain.engine.SiteResolver
import com.example.worktimetracker.ui.CompanyCalibrationProposal
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.domain.model.WorkCalculationInput
import com.example.worktimetracker.domain.model.WorkSegment
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.export.ExportManager
import com.example.worktimetracker.ui.UiDayRecord
import com.example.worktimetracker.ui.MonthlyRecordIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlinx.coroutines.withContext

/**
 * 「补录时段」的一行草稿（界面稿 06）。
 *
 * 用「当天第几分钟」（0..1439）而不是时间戳：界面用时间轮选，草稿本身与日期无关，
 * 转成时间戳由 [WorkTimeViewModel.saveDaySegments] 统一做，跨夜判断也集中在那一处。
 */
data class DaySegmentDraft(
    val startMinutes: Int,
    val endMinutes: Int,
    /** [WorkSegmentEntity.TYPE_WORK] = 在岗（计入）/ [WorkSegmentEntity.TYPE_OFF_SITE] = 离厂（不计入）。 */
    val segmentType: String = WorkSegmentEntity.TYPE_WORK,
    val deductRest: Boolean = false,
    val siteId: Long? = null,
    val siteLabel: String? = null,
    val note: String? = null
)

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/**
 * 手动「立即刷新一次」的等待上限。
 *
 * 服务侧的一次性刷新兜底时限是 8 秒（`ONE_SHOT_REFRESH_TIMEOUT_MILLIS`），
 * 这里多留 4 秒给环境扫描（Wi-Fi/BLE 扫描本身要 1~3 秒）落到 `location_health`。
 */
private const val REFRESH_WAIT_MILLIS = 12_000L

class WorkTimeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as WorkTimeApplication).database
    private val zone = ZoneId.systemDefault()
    private val engine = WorkSessionEngine(zone)
    private val payrollRules = PayrollPeriodRules()
    private var monthJob: Job? = null
    private var observedMonth: YearMonth? = null

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate
    /**
     * 「当前工作日」：夜班没下班之前它仍是**上班那一天**（2026-09-16）。
     * 只有不存在未结束的在岗会话时，它才等于自然日，判定见 [WorkdayClock]。
     */
    private val _workday = MutableStateFlow(LocalDate.now())
    val workday: StateFlow<LocalDate> = _workday
    private val _settings = MutableStateFlow(UserSettingsEntity())
    val settings: StateFlow<UserSettingsEntity> = _settings
    private val _records = MutableStateFlow<List<UiDayRecord>>(emptyList())
    val records: StateFlow<List<UiDayRecord>> = _records
    private val _reviewRecords = MutableStateFlow<List<UiDayRecord>>(emptyList())
    val reviewRecords: StateFlow<List<UiDayRecord>> = _reviewRecords
    private val _lastKnownLocationText = MutableStateFlow("暂无定位")
    val lastKnownLocationText: StateFlow<String> = _lastKnownLocationText
    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs
    private val _journeyShadowStatus = MutableStateFlow("新行程状态尚未建立")
    val journeyShadowStatus: StateFlow<String> = _journeyShadowStatus
    private val _lastManualHoursText = MutableStateFlow("")
    val lastManualHoursText: StateFlow<String> = _lastManualHoursText
    private val _placeSearchMessage = MutableStateFlow("")
    val placeSearchMessage: StateFlow<String> = _placeSearchMessage
    private val _legacyImportMessage = MutableStateFlow("")
    val legacyImportMessage: StateFlow<String> = _legacyImportMessage
    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone
    private val _monthlySalaryCents = MutableStateFlow<Long?>(null)
    val monthlySalaryCents: StateFlow<Long?> = _monthlySalaryCents
    private val _monthlySalaryPaymentDate = MutableStateFlow<String?>(null)
    val monthlySalaryPaymentDate: StateFlow<String?> = _monthlySalaryPaymentDate
    /** 计薪参数分段常量（DB v12）。 */
    private val _payRateSegments = MutableStateFlow<List<PayRateSegmentEntity>>(emptyList())
    val payRateSegments: StateFlow<List<PayRateSegmentEntity>> = _payRateSegments
    /** 月度浮动参数，key = 计薪月 `YYYY-MM`。 */
    private val _payParams = MutableStateFlow<Map<String, MonthlyPayParamsEntity>>(emptyMap())
    val payParams: StateFlow<Map<String, MonthlyPayParamsEntity>> = _payParams
    /** 当月推算明细（**纯展示，永不落库**）。 */
    private val _monthPayroll = MutableStateFlow<PayrollBreakdown?>(null)
    val monthPayroll: StateFlow<PayrollBreakdown?> = _monthPayroll
    /** 日工资基准月（最近一个已录入实发的完整月）。 */
    private val _payBaseline = MutableStateFlow<PayBaseline?>(null)
    val payBaseline: StateFlow<PayBaseline?> = _payBaseline
    /** 整月预估（未记录日子按标准工时补足后 × 基准月单价）。月已走完 / 无基准月时为 null。 */
    private val _monthProjection = MutableStateFlow<MonthProjection?>(null)
    val monthProjection: StateFlow<MonthProjection?> = _monthProjection
    private val _companyCalibrationProposal = MutableStateFlow<CompanyCalibrationProposal?>(null)
    val companyCalibrationProposal: StateFlow<CompanyCalibrationProposal?> = _companyCalibrationProposal

    // ---- 节假日数据（国务院公告）：远端同步 + 本地缓存 + 算法兜底 ----
    private val holidayStore = HolidaySyncStore(application)
    private val holidayRepository = HolidayRepository(db.holidayDao(), holidayStore)
    private val _holidayStatus = MutableStateFlow(HolidayStatusUi())
    val holidayStatus: StateFlow<HolidayStatusUi> = _holidayStatus

    /** 融合定位判断实时通道：前台服务每次融合后覆盖，UI 展示"当前判断" */
    val fusedStatus: StateFlow<com.example.worktimetracker.domain.evidence.FusedStatusSnapshot?> =
        (application as WorkTimeApplication).fusedStatus

    init {
        viewModelScope.launch {
            val saved = db.userSettingsDao().getSettings() ?: UserSettingsEntity().also { db.userSettingsDao().save(it) }
            _settings.value = saved
            _onboardingDone.value = saved.onboardingDone
            // 先把节假日缓存灌进运行时合并层，再生成日历，避免首屏丢标签
            val cachedHolidays = holidayRepository.restoreCache()
            if (cachedHolidays.isNotEmpty()) HolidayCalendar.apply(cachedHolidays)
            refreshHolidayStatus()
            // 「今天」必须先定下来：夜班跨零点时它是上班日，日历与当日卡片都按它取数
            refreshWorkday()
            loadMonth()
            refreshLastKnownLocation()
            refreshLogsOnce()
            refreshJourneyShadowStatus()
            refreshLastManualHours()
            refreshToday()
            reloadSites()
            // 主地点唯一这条不变量要在启动时收敛一次：v11 迁移留下的「公司+家都是主地点」
            // 只靠保存/启停动作是修不到的（那些动作用户可能几个月都不碰）
            ensurePrimarySite()
            reloadPayrollConfig()
            reloadSourceHealthNow()
        }
        // 自动同步放在**独立协程**里：它要联网（可能耗时数秒到数十秒），
        // 既不能拖慢首屏，也不能因为前序初始化步骤异常而被整体跳过。
        maybeAutoSyncHolidays()
    }

    fun finishOnboarding() {
        _onboardingDone.value = true
        viewModelScope.launch { saveSettings(_settings.value.copy(onboardingDone = true)) }
    }
    fun previousMonth() { moveToMonth(_month.value.minusMonths(1)) }
    fun nextMonth() { moveToMonth(_month.value.plusMonths(1)) }
    fun today() {
        viewModelScope.launch {
            val day = refreshWorkday()
            _month.value = YearMonth.from(day)
            _selectedDate.value = day
            loadMonth()
        }
    }
    fun jumpToMonth(yearText: String, monthText: String) {
        val year = yearText.toIntOrNull()?.coerceIn(2000, 2100) ?: return
        val month = monthText.toIntOrNull()?.coerceIn(1, 12) ?: return
        _month.value = YearMonth.of(year, month)
        _selectedDate.value = _month.value.atDay(1)
        loadMonth()
    }
    fun select(date: LocalDate) { _selectedDate.value = date }

    private fun moveToMonth(target: YearMonth) {
        _month.value = target
        _selectedDate.value = target.atDay(
            _selectedDate.value.dayOfMonth.coerceAtMost(target.lengthOfMonth())
        )
        loadMonth()
    }

    fun loadMonth() = loadMonth(force = false)

    /**
     * @param force 强制重建当前月份（节假日数据更新后需要，否则会被"同月已加载"短路）。
     */
    fun loadMonth(force: Boolean) {
        val requestedMonth = _month.value
        if (!force && monthJob?.isActive == true && observedMonth == requestedMonth) return
        monthJob?.cancel()
        observedMonth = requestedMonth
        monthJob = viewModelScope.launch {
            val m = requestedMonth
            val start = m.atDay(1).toString()
            val end = m.atEndOfMonth().toString()
            val salary = db.monthlySalaryDao().getForPayrollMonth(m.toString())
            _monthlySalaryCents.value = salary?.netSalaryCents
            _monthlySalaryPaymentDate.value = salary?.paymentDate
            db.workRecordDao().observeMonthRecords(start, end).collectLatest { rows ->
                // 构建 30 天展示模型要遍历整月（含节假日判定），挪到 Default 线程，
                // 避免 Room 每次发射都在主线程重算一遍。
                val built = withContext(Dispatchers.Default) {
                    MonthlyRecordIndex.build(m, rows, _workday.value, zone)
                }
                _records.value = built
                _reviewRecords.value = built.filter { it.needsReview }
                recomputeMonthPayroll(m, built)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 节假日数据（国务院公告）：联网拉取 + 本地缓存 + 算法兜底
    // ---------------------------------------------------------------------

    private suspend fun refreshHolidayStatus() {
        _holidayStatus.value = _holidayStatus.value.copy(
            lastSuccessAt = holidayStore.lastSuccessAt(),
            lastAttemptAt = holidayStore.lastAttemptAt(),
            host = holidayStore.lastHost(),
            coveredYears = holidayStore.coveredYears(),
            cachedYears = holidayRepository.cachedYears(),
            error = holidayStore.lastError()
        )
    }

    /** 启动时按需自动同步：缓存过期、或当前年份还没拿到公告数据。 */
    private fun maybeAutoSyncHolidays() {
        viewModelScope.launch {
            runCatching {
                val currentYear = LocalDate.now().year
                val needed = HolidayRepository.needsSync(
                    holidayStore.lastSuccessAt(),
                    holidayRepository.cachedYears(),
                    currentYear,
                    System.currentTimeMillis()
                )
                if (needed) {
                    syncHolidaysNow()
                } else {
                    // 启动时也做了一次检查：缓存没过期 ⇒ 明确告诉用户「已是最新」，
                    // 而不是静默什么都不做（用户会以为这个功能没生效）。
                    refreshHolidayStatus()
                    _holidayStatus.value = _holidayStatus.value.copy(
                        message = "数据已经是最新的（已缓存 ${holidayRepository.cachedYears().size} 年）",
                        resultOk = true
                    )
                }
            }.onFailure { error ->
                _holidayStatus.value = _holidayStatus.value.copy(updating = false)
                runCatching {
                    db.appLogDao().insert(
                        AppLogEntity(
                            type = "HOLIDAY_SYNC",
                            content = "自动同步失败：" + (error.message ?: error.javaClass.simpleName)
                        )
                    )
                }
            }
        }
    }

    /** 手动触发（设置页「立即更新」）。联网失败自动回退本地数据，不抛异常。 */
    fun refreshHolidays() {
        viewModelScope.launch { syncHolidaysNow() }
    }

    private suspend fun syncHolidaysNow() {
        _holidayStatus.value = _holidayStatus.value.copy(updating = true, message = "", resultOk = null)
        val outcome = runCatching { holidayRepository.sync(HolidayRepository.targetYears()) }
            .getOrElse { error ->
                _holidayStatus.value = _holidayStatus.value.copy(
                    updating = false,
                    message = "更新失败，已沿用本地数据",
                    resultOk = false
                )
                runCatching {
                    db.appLogDao().insert(
                        AppLogEntity(
                            type = "HOLIDAY_SYNC",
                            content = "同步异常：" + (error.message ?: error.javaClass.simpleName)
                        )
                    )
                }
                return
            }
        val restored = holidayRepository.restoreCache()
        if (restored.isNotEmpty()) HolidayCalendar.apply(restored)
        // 标签变了，必须强制重建当前月份
        loadMonth(force = true)
        val refreshed = _holidayStatus.value.copy(
            updating = false,
            lastSuccessAt = holidayStore.lastSuccessAt(),
            lastAttemptAt = holidayStore.lastAttemptAt(),
            host = holidayStore.lastHost()?.takeIf { it.isNotBlank() } ?: outcome.host,
            coveredYears = holidayStore.coveredYears(),
            cachedYears = holidayRepository.cachedYears(),
            error = holidayStore.lastError()
        )
        _holidayStatus.value = refreshed.copy(
            resultOk = outcome.ok && outcome.failures.isEmpty(),
            message = HolidayStatusPresenter.resultText(
                refreshed, outcome.succeededYears, outcome.failures.keys, outcome.host
            )
        )
    }

    fun confirmReview(
        date: LocalDate,
        shift: String,
        startMillis: Long?,
        endMillis: Long?,
        hoursText: String,
        note: String,
        onResult: (String?) -> Unit
    ) {
        val minutes = hoursText.toDoubleOrNull()?.let { (it * 60).toInt() }
        if (minutes == null) {
            onResult("请输入有效工时")
            return
        }
        viewModelScope.launch {
            val old = db.workRecordDao().getByDate(date.toString())
            if (old == null || !old.needsReview) {
                onResult("该记录已不需要确认")
                return@launch
            }
            ReviewRecordEditor.confirm(old, shift, startMillis, endMillis, minutes, note).fold(
                onSuccess = { confirmed ->
                    db.workRecordDao().upsert(confirmed)
                    db.manualOverrideDao().insert(
                        ManualOverrideEntity(
                            recordId = confirmed.id,
                            oldValue = "${old.shift}:${old.startTime}:${old.endTime}:${old.finalMinutes}",
                            newValue = "$shift:$startMillis:$endMillis:$minutes",
                            reason = note.ifBlank { "统计页人工确认" }
                        )
                    )
                    onResult(null)
                },
                onFailure = { onResult(it.message ?: "确认失败") }
            )
        }
    }

    /**
     * A6: 认可系统判定（灰区/异常记录），**不改任何值**。
     *
     * 仅清除 needsReview 并写 NEEDS_REVIEW_ACK 位；不动 finalMinutes/isManual，
     * 因此不会像"编辑确认"那样把自动结果锁死（详见 [ReviewAcknowledger]）。
     */
    fun acknowledgeReview(
        date: LocalDate,
        note: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            val old = db.workRecordDao().getByDate(date.toString())
            if (old == null || !old.needsReview) {
                onResult("该记录已不需要确认")
                return@launch
            }
            ReviewAcknowledger.acknowledge(old, note).fold(
                onSuccess = { acknowledged ->
                    db.workRecordDao().upsert(acknowledged)
                    db.manualOverrideDao().insert(
                        ManualOverrideEntity(
                            recordId = acknowledged.id,
                            oldValue = "needsReview=${old.needsReview};reason=${old.reviewReason ?: "-"}",
                            newValue = "ACK;final=${acknowledged.finalMinutes}",
                            reason = note.ifBlank { "统计页认可系统判定" }
                        )
                    )
                    onResult(null)
                },
                onFailure = { onResult(it.message ?: "确认失败") }
            )
        }
    }

    fun saveRecordEdit(
        date: LocalDate,
        shift: String,
        startMillis: Long?,
        endMillis: Long?,
        hoursText: String,
        note: String,
        onResult: (String?) -> Unit
    ) {
        val minutes = hoursText.toDoubleOrNull()?.let { (it * 60).toInt() }
        if (minutes == null) {
            onResult("请输入有效工时")
            return
        }
        viewModelScope.launch {
            val old = db.workRecordDao().getByDate(date.toString())
            if (old == null) {
                onResult("记录不存在")
                return@launch
            }
            ReviewRecordEditor.confirm(old, shift, startMillis, endMillis, minutes, note).fold(
                onSuccess = { edited ->
                    db.workRecordDao().upsert(edited)
                    db.manualOverrideDao().insert(
                        ManualOverrideEntity(
                            recordId = edited.id,
                            oldValue = "${old.shift}:${old.startTime}:${old.endTime}:${old.finalMinutes}",
                            newValue = "$shift:$startMillis:$endMillis:$minutes",
                            reason = note.ifBlank { "统计页人工修改" }
                        )
                    )
                    onResult(null)
                },
                onFailure = { onResult(it.message ?: "修改失败") }
            )
        }
    }

    fun saveMonthlySalary(text: String, paymentDateText: String) {
        val cents = runCatching {
            BigDecimal(text.trim().replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull() ?: return
        if (cents < 0) return
        val payrollMonth = _month.value
        val paymentDate = runCatching { LocalDate.parse(paymentDateText) }.getOrNull()
            ?: payrollRules.defaultPaymentDateForPayrollMonth(payrollMonth)
        viewModelScope.launch {
            val entry = payrollRules.createEntry(payrollMonth, paymentDate, cents)
            db.monthlySalaryDao().save(entry)
            _monthlySalaryCents.value = cents
            _monthlySalaryPaymentDate.value = entry.paymentDate
        }
    }
    /**
     * 指定计薪月保存月度实发（工资条录入页把「条上实发」一键存为计薪基准时用）。
     *
     * 与 [saveMonthlySalary] 的区别只有一处：计薪月由调用方显式给出 ——
     * 录入页可能正停在别的月份上，不能拿日历当前月顶替。
     *
     * @return 金额文本不合法（空 / 非数字 / 负数）时为 false，调用方可据此提示
     */
    fun saveMonthlySalaryFor(payrollMonth: YearMonth, text: String, paymentDateText: String): Boolean {
        val cents = runCatching {
            BigDecimal(text.trim().replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull() ?: return false
        if (cents < 0) return false
        val paymentDate = runCatching { LocalDate.parse(paymentDateText) }.getOrNull()
            ?: payrollRules.defaultPaymentDateForPayrollMonth(payrollMonth)
        viewModelScope.launch {
            val entry = payrollRules.createEntry(payrollMonth, paymentDate, cents)
            db.monthlySalaryDao().save(entry)
            // 只有正好停在那个月时才刷新月卡上的缓存值
            if (payrollMonth == _month.value) {
                _monthlySalaryCents.value = cents
                _monthlySalaryPaymentDate.value = entry.paymentDate
            }
        }
        return true
    }

    fun refreshLastKnownLocation() {
        viewModelScope.launch {
            val last = db.locationLogDao().latest()
            _lastKnownLocationText.value = if (last == null) "暂无定位" else "${last.latitude}, ${last.longitude}（${last.locationType}）"
        }
    }

    fun refreshLogsOnce() {
        viewModelScope.launch {
            val recent = db.appLogDao().latestLogs(30)
            _recentLogs.value = recent.map { "${it.type}：${it.content}" }
            refreshJourneyShadowStatusNow()
        }
    }

    fun refreshJourneyShadowStatus() {
        viewModelScope.launch { refreshJourneyShadowStatusNow() }
    }

    private suspend fun refreshJourneyShadowStatusNow() {
        val row = db.journeyShadowStateDao().get()
        _journeyShadowStatus.value = if (row == null) {
            "新行程状态尚未建立；启动后会先读取最近30天历史记录进行预学习"
        } else {
            buildString {
                append("当前阶段：").append(row.phase)
                append("\n上次确认：").append(row.lastConfirmedPhase ?: "暂无")
                append("\n候选阶段：").append(row.candidatePhase ?: "无")
                if (row.candidatePhase != null) {
                    append(" · 支持").append(row.supportCount).append("拍")
                    append(" · 稳定").append(row.accumulatedStableMillis / 1_000).append("秒")
                }
                append("\n理论采样：失败次数").append(row.samplingAttempt)
                append(" · 模型v").append(row.modelVersion)
            }
        }
    }

    fun refreshLastManualHours() {
        viewModelScope.launch {
            _lastManualHoursText.value = db.workRecordDao().latestManualFinalMinutes()?.let { minutes ->
                if (minutes % 60 == 0) (minutes / 60).toString() else "%.1f".format(minutes / 60.0)
            } ?: ""
        }
    }

    fun saveWorkTimes(startHour: String, startMinute: String, endHour: String, endMinute: String) {
        val start = (startHour.toIntOrNull() ?: 9) * 60 + (startMinute.toIntOrNull() ?: 0)
        val end = (endHour.toIntOrNull() ?: 21) * 60 + (endMinute.toIntOrNull() ?: 0)
        viewModelScope.launch { saveSettings(_settings.value.copy(workStartMinutes = start, workEndMinutes = end)) }
    }

    fun saveLocations(companyLat: String, companyLng: String, companyRadius: String, homeLat: String, homeLng: String, homeRadius: String) {
        viewModelScope.launch {
            saveSettings(_settings.value.copy(
                companyLat = companyLat.toDoubleOrNull() ?: _settings.value.companyLat,
                companyLng = companyLng.toDoubleOrNull() ?: _settings.value.companyLng,
                companyRadiusMeters = companyRadius.toIntOrNull() ?: 150,
                homeLat = homeLat.toDoubleOrNull() ?: _settings.value.homeLat,
                homeLng = homeLng.toDoubleOrNull() ?: _settings.value.homeLng,
                homeRadiusMeters = homeRadius.toIntOrNull() ?: 150
            ))
            saveSettings(_settings.value.copy(onboardingDone = true))
            _onboardingDone.value = true
        }
    }

    fun useLastLocationForCompany() {
        viewModelScope.launch {
            val last = db.locationLogDao().latest() ?: return@launch
            saveSettings(_settings.value.copy(companyLat = last.latitude, companyLng = last.longitude))
            refreshLastKnownLocation()
        }
    }

    fun useLastLocationForHome() {
        viewModelScope.launch {
            val last = db.locationLogDao().latest() ?: return@launch
            saveSettings(_settings.value.copy(homeLat = last.latitude, homeLng = last.longitude))
            refreshLastKnownLocation()
        }
    }

    fun saveDefaultHours(hours: String) {
        val minutes = ((hours.toDoubleOrNull() ?: return) * 60).toInt()
        viewModelScope.launch { saveSettings(_settings.value.copy(hasDefaultHours = true, defaultWorkMinutes = minutes)); loadMonth() }
    }

    fun setDefaultHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val fallbackMinutes = _settings.value.defaultWorkMinutes ?: 12 * 60
            saveSettings(
                _settings.value.copy(
                    hasDefaultHours = enabled,
                    defaultWorkMinutes = fallbackMinutes
                )
            )
            loadMonth()
        }
    }

    fun saveAutoRules(restMinutes: String, outsideMinutes: String, leaveConfirmMinutes: String, earlyToleranceMinutes: String) {
        val rest = restMinutes.toIntOrNull()?.coerceIn(0, 240) ?: _settings.value.restDeductionMinutes
        val outside = outsideMinutes.toIntOrNull()?.coerceIn(15, 24 * 60) ?: _settings.value.outsideThresholdMinutes
        val leaveConfirm = leaveConfirmMinutes.toIntOrNull()?.coerceIn(5, 6 * 60) ?: _settings.value.leaveCompanyConfirmMinutes
        val earlyTolerance = earlyToleranceMinutes.toIntOrNull()?.coerceIn(0, 60) ?: _settings.value.earlyLeaveToleranceMinutes
        viewModelScope.launch {
            saveSettings(
                _settings.value.copy(
                    restDeductionMinutes = rest,
                    outsideThresholdMinutes = outside,
                    leaveCompanyConfirmMinutes = leaveConfirm,
                    earlyLeaveToleranceMinutes = earlyTolerance
                )
            )
            loadMonth()
        }
    }

    fun saveManualHours(date: LocalDate, hoursText: String, setAsDefault: Boolean, note: String, shift: String? = null) {
        val minutes = ((hoursText.toDoubleOrNull() ?: return) * 60).toInt()
        viewModelScope.launch {
            val old = db.workRecordDao().getByDate(date.toString())
            val record = ManualRecordEditor.apply(old, date.toString(), shift ?: old?.shift ?: "DAY_SHIFT", minutes, note)
            val id = db.workRecordDao().upsert(record)
            db.manualOverrideDao().insert(ManualOverrideEntity(recordId = if (record.id == 0L) id else record.id, oldValue = old?.finalMinutes?.toString(), newValue = minutes.toString(), reason = note))
            _lastManualHoursText.value = if (minutes % 60 == 0) (minutes / 60).toString() else "%.1f".format(minutes / 60.0)
            if (setAsDefault) saveDefaultHours(hoursText) else loadMonth()
        }
    }

    fun saveBatchManualHours(dates: Set<LocalDate>, hoursText: String, shift: String, note: String) {
        val minutes = ((hoursText.toDoubleOrNull() ?: return) * 60).toInt()
        if (dates.isEmpty()) return
        viewModelScope.launch {
            dates.sorted().forEach { date ->
                val old = db.workRecordDao().getByDate(date.toString())
                val record = ManualRecordEditor.apply(old, date.toString(), shift, minutes, note)
                val id = db.workRecordDao().upsert(record)
                db.manualOverrideDao().insert(
                    ManualOverrideEntity(
                        recordId = if (record.id == 0L) id else record.id,
                        oldValue = old?.let { "${it.shift}:${it.finalMinutes}" },
                        newValue = "$shift:$minutes",
                        reason = note.ifBlank { "批量修正班次和工时" }
                    )
                )
            }
            _lastManualHoursText.value = if (minutes % 60 == 0) (minutes / 60).toString() else "%.1f".format(minutes / 60.0)
            loadMonth()
        }
    }

    fun searchPlaceAndSet(keyword: String, target: String) {
        val query = keyword.trim()
        if (query.isBlank()) {
            _placeSearchMessage.value = "请输入地点名称"
            return
        }
        viewModelScope.launch {
            runCatching {
                @Suppress("DEPRECATION")
                val result = Geocoder(getApplication(), Locale.CHINA).getFromLocationName(query, 1)?.firstOrNull()
                if (result == null) {
                    _placeSearchMessage.value = "未搜索到地点，可在地图中打开后用当前位置设置"
                    return@launch
                }
                if (target == "company") {
                    saveSettings(_settings.value.copy(companyLat = result.latitude, companyLng = result.longitude))
                    _placeSearchMessage.value = "已将搜索结果设为公司：${"%.6f".format(result.latitude)}, ${"%.6f".format(result.longitude)}"
                } else {
                    saveSettings(_settings.value.copy(homeLat = result.latitude, homeLng = result.longitude))
                    _placeSearchMessage.value = "已将搜索结果设为家庭：${"%.6f".format(result.latitude)}, ${"%.6f".format(result.longitude)}"
                }
                refreshLastKnownLocation()
            }.onFailure {
                _placeSearchMessage.value = "搜索失败：${it.message ?: "请稍后重试"}"
            }
        }
    }

    fun saveSplitSegments(date: LocalDate, firstStart: String, firstEnd: String, secondStart: String, secondEnd: String, deductRest: Boolean) {
        val minutes = parseRangeMinutes(firstStart, firstEnd) + parseRangeMinutes(secondStart, secondEnd) - if (deductRest) _settings.value.restDeductionMinutes else 0
        saveManualHours(date, (minutes.coerceAtLeast(0) / 60.0).toString(), false, "手动拆分时间段")
    }

    fun updateStatus(date: LocalDate, status: String) {
        viewModelScope.launch {
            val old = db.workRecordDao().getByDate(date.toString())
            val finalMinutes = if (status == "LEAVE" || status == "REST" || status == "OUTSIDE") 0 else old?.finalMinutes ?: 0
            val record = (old ?: WorkRecordEntity(workDate = date.toString(), status = status, finalMinutes = finalMinutes)).copy(status = status, finalMinutes = finalMinutes, isManual = true, updatedAt = System.currentTimeMillis())
            db.workRecordDao().upsert(record)
            loadMonth()
        }
    }

    fun restoreBackupJson(json: String) {
        viewModelScope.launch {
            runCatching {
                val backup = ExportManager.restoreFullBackupJsonText(json)
                backup.settings?.let { s ->
                    saveSettings(
                        _settings.value.copy(
                            companyLat = s.companyLat,
                            companyLng = s.companyLng,
                            companyRadiusMeters = s.companyRadiusMeters,
                            homeLat = s.homeLat,
                            homeLng = s.homeLng,
                            homeRadiusMeters = s.homeRadiusMeters,
                            workStartMinutes = s.workStartMinutes,
                            workEndMinutes = s.workEndMinutes,
                            hasDefaultHours = s.hasDefaultHours,
                            defaultWorkMinutes = s.defaultWorkMinutes,
                            restDeductionMinutes = s.restDeductionMinutes,
                            outsideThresholdMinutes = s.outsideThresholdMinutes,
                            leaveCompanyConfirmMinutes = s.leaveCompanyConfirmMinutes,
                            earlyLeaveToleranceMinutes = s.earlyLeaveToleranceMinutes,
                            notificationEnabled = s.notificationEnabled,
                            onboardingDone = s.onboardingDone
                        )
                    )
                }
                backup.records.forEach { r ->
                    db.workRecordDao().upsert(WorkRecordEntity(workDate = r.date, status = if (r.status == "手动") "MANUAL" else r.status, shift = r.shift, finalMinutes = r.finalMinutes, isManual = true, note = r.note))
                }
                loadMonth()
            }.onFailure { db.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(type = "RESTORE", content = it.message ?: "恢复失败")) }
        }
    }

    fun importLegacyAttendanceCsv(csvText: String) {
        viewModelScope.launch {
            _legacyImportMessage.value = "正在导入旧考勤记录…"
            runCatching {
                val defaultMinutes = _settings.value.defaultWorkMinutes ?: 11 * 60
                val plan = LegacyAttendanceCsvImporter.createImportPlan(csvText, defaultMinutes)
                var importedLocations = 0
                plan.events.forEach { event ->
                    if ((event.latitude != 0.0 || event.longitude != 0.0) &&
                        db.locationLogDao().countByTime(event.timeMillis) == 0
                    ) {
                        db.locationLogDao().insert(
                            LocationLogEntity(
                                time = event.timeMillis,
                                latitude = event.latitude,
                                longitude = event.longitude,
                                locationType = when (event.eventType) {
                                    "WORK" -> "COMPANY"
                                    "HOME" -> "HOME"
                                    else -> "OTHER"
                                },
                                provider = "legacy_csv"
                            )
                        )
                        importedLocations++
                    }
                }

                var importedDays = 0
                var keptExistingDays = 0
                plan.dailyRecords.forEach { imported ->
                    val date = imported.date.toString()
                    if (db.workRecordDao().getByDate(date) != null) {
                        keptExistingDays++
                    } else {
                        db.workRecordDao().upsert(
                            WorkRecordEntity(
                                workDate = date,
                                status = imported.status,
                                shift = imported.shift,
                                startTime = imported.startTime,
                                finalMinutes = imported.finalMinutes,
                                isManual = true,
                                note = "从旧软件导入（${imported.sourceEventCount}条事件）"
                            )
                        )
                        importedDays++
                    }
                }
                db.appLogDao().insert(
                    AppLogEntity(
                        type = "LEGACY_IMPORT",
                        content = "旧考勤CSV：${plan.events.size}条，新增${importedDays}天，保留${keptExistingDays}天现有记录"
                    )
                )
                loadMonth()
                refreshLastKnownLocation()
                "导入完成：读取${plan.events.size}条，新增${importedDays}天；${keptExistingDays}天已有记录已保留"
            }.onSuccess {
                _legacyImportMessage.value = it
            }.onFailure {
                _legacyImportMessage.value = "导入失败：${it.message ?: "文件格式不正确"}"
                db.appLogDao().insert(AppLogEntity(type = "LEGACY_IMPORT", content = it.message ?: "导入失败"))
            }
        }
    }

    fun clearAllLocalData() {
        viewModelScope.launch {
            db.workSegmentDao().deleteAll()
            db.manualOverrideDao().deleteAll()
            db.workRecordDao().deleteAll()
            db.locationLogDao().deleteAll()
            holidayRepository.clearAll()
            HolidayCalendar.reset()
            db.appLogDao().deleteAll()
            db.monthlySalaryDao().deleteAll()
            _lastKnownLocationText.value = "暂无定位"
            _recentLogs.value = emptyList()
            refreshHolidayStatus()
            loadMonth(force = true)
        }
    }

    private suspend fun saveSettings(settings: UserSettingsEntity) {
        val updated = settings.copy(updatedAt = System.currentTimeMillis())
        db.userSettingsDao().save(updated)
        _settings.value = updated
    }

    private fun WorkRecordEntity.toUi(date: LocalDate): UiDayRecord {
        val dayInfo = HolidayCalendar.info(date)
        return UiDayRecord(
            date = date,
            status = when (status) { "WORK" -> if (shift == "NIGHT_SHIFT") "夜班" else "白班"; "REST" -> "休息"; "OUTSIDE" -> "外出"; "EARLY_LEAVE" -> "下早班"; "ARRIVAL_EXCEPTION" -> "到岗异常"; "MANUAL" -> "手动"; "LEAVE" -> "请假"; else -> status },
            shift = when (shift) { "DAY_SHIFT", "白班" -> "白班"; "NIGHT_SHIFT", "夜班" -> "夜班"; else -> null },
            startText = startTime?.timeText(),
            endText = endTime?.timeText(startTime),
            actualMinutes = actualMinutes,
            finalMinutes = finalMinutes,
            needsReview = needsReview,
            note = note,
            holidayName = dayInfo.festivalName,
            dayKind = dayInfo.kind,
            dayBadge = dayInfo.festivalName ?: dayInfo.kind.shortLabel,
            companyArrivalText = startTime?.timeText(),
            companyDepartureText = endTime?.timeText(startTime),
            homeDepartureText = homeDepartureTime?.timeText(),
            homeArrivalText = homeArrivalTime?.timeText(startTime)
        )
    }

    private fun UserSettingsEntity.toDomain(): WorkSettings = WorkSettings(workStartMinutes, workEndMinutes, hasDefaultHours, defaultWorkMinutes, restDeductionMinutes, outsideThresholdMinutes, leaveCompanyConfirmMinutes, earlyLeaveToleranceMinutes)
    private fun LocalDateTime.ms(): Long = atZone(zone).toInstant().toEpochMilli()
    private fun Long.timeText(start: Long? = null): String { val t = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime(); val prefix = if (start != null && Instant.ofEpochMilli(start).atZone(zone).toLocalDate() != t.toLocalDate()) "次日" else ""; return prefix + "%02d:%02d".format(t.hour, t.minute) }
    private fun parseRangeMinutes(start: String, end: String): Int { val s = start.split(":").mapNotNull { it.toIntOrNull() }; val e = end.split(":").mapNotNull { it.toIntOrNull() }; if (s.size != 2 || e.size != 2) return 0; val sm = s[0] * 60 + s[1]; var em = e[0] * 60 + e[1]; if (em < sm) em += 24 * 60; return (em - sm).coerceAtLeast(0) }

    fun prepareCompanyCalibration() {
        viewModelScope.launch {
            val points = db.locationLogDao().recentAccurate(20).map {
                LocationAnchorCalibration.Point(it.latitude, it.longitude, it.accuracyMeters ?: 999f, it.time)
            }
            val result = LocationAnchorCalibration().calculate(points)
            val current = _settings.value
            if (result == null || current.companyLat == null || current.companyLng == null) {
                _placeSearchMessage.value = "高精度样本不足，请在公司实际工作位置保持定位后重试"
                return@launch
            }
            val offset = LocationStatusAnalyzer().distanceMeters(current.companyLat, current.companyLng, result.centerLat, result.centerLng)
            if (offset > current.companyRadiusMeters + 350) {
                _placeSearchMessage.value = "当前定位不像公司位置，未生成校准建议"
                return@launch
            }
            _companyCalibrationProposal.value = CompanyCalibrationProposal(result.centerLat, result.centerLng,
                result.stableRadiusMeters, offset.toInt(), result.acceptedCount)
        }
    }

    fun acceptCompanyCalibration() {
        val proposal = _companyCalibrationProposal.value ?: return
        viewModelScope.launch {
            saveSettings(_settings.value.copy(companyLat = proposal.latitude, companyLng = proposal.longitude))
            LocationCalibrationStore(getApplication()).saveCompany(proposal.stableRadiusMeters, System.currentTimeMillis())
            _companyCalibrationProposal.value = null
            _placeSearchMessage.value = "公司位置校准完成"
        }
    }

    fun cancelCompanyCalibration() { _companyCalibrationProposal.value = null }

    // ---------------------------------------------------------------------
    // 今日（实时页）
    // ---------------------------------------------------------------------

    private val _todayRecord = MutableStateFlow<UiDayRecord?>(null)
    val todayRecord: StateFlow<UiDayRecord?> = _todayRecord
    private val _todaySegments = MutableStateFlow<List<WorkSegmentEntity>>(emptyList())
    val todaySegments: StateFlow<List<WorkSegmentEntity>> = _todaySegments

    /** 只读刷新「今天」。今日页在进入、回到前台、以及每 30 秒心跳时调用。 */
    fun refreshToday() {
        viewModelScope.launch {
            val before = _workday.value
            val day = refreshWorkday()
            // 跨过零点（或会话结束）导致工作日变化时，整月模型要跟着重建
            if (day != before) loadMonth()
            refreshDay(day)
        }
    }

    /**
     * 重新解析「当前工作日」并同步「今天」的语义。
     *
     * 只在工作日真的变化时改动视图状态，且**只跟随、不抢夺**：
     * 用户若已经手动选了别的日期或月份，这里不去覆盖他。
     */
    private suspend fun refreshWorkday(): LocalDate {
        val natural = LocalDate.now()
        val state = runCatching { db.workStateDao().getState() }.getOrNull()
        val day = WorkdayClock.today(state, natural, zone, _settings.value.toDomain())
        val previous = _workday.value
        if (day != previous) {
            _workday.value = day
            if (_selectedDate.value == previous) _selectedDate.value = day
            if (_month.value == YearMonth.from(previous)) _month.value = YearMonth.from(day)
        }
        return day
    }

    /** 指定日期的单日刷新（补录保存后要立刻反映到当日卡片）。 */
    fun refreshToday(date: LocalDate) {
        viewModelScope.launch { refreshDay(date) }
    }

    private suspend fun refreshDay(date: LocalDate) {
        val row = db.workRecordDao().getByDate(date.toString())
        _todayRecord.value = row?.let { MonthlyRecordIndex.toUi(date, it, zone) }
        _todaySegments.value = row?.let { db.workSegmentDao().forRecord(it.id) }.orEmpty()
    }

    /**
     * 手动打卡（界面稿「手动打卡」）。
     *
     * 只写当天的到岗 / 离岗时刻，然后**复用自动路径同一套 v1 规则**重算工时
     * （[WorkSessionEngine.buildSession]），所以手动打卡与自动判定的计薪口径完全一致。
     * 记录会被标记为人工修正（isManual + ManualFieldMask 位），自动合并不会再覆盖。
     *
     * 只打上班卡（还没有下班时间）时**不重算工时**——否则 buildSession 会拿排班窗口的
     * expectedEnd 当结束时间，凭空产生一整天的工时。
     */
    fun manualPunch(
        date: LocalDate,
        clockIn: Boolean,
        minutesOfDay: Int,
        note: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val settings = _settings.value.toDomain()
            val dayStart = date.atStartOfDay(zone)
            val millis = dayStart.plusMinutes(minutesOfDay.toLong().coerceIn(0L, 24L * 60L - 1))
                .toInstant().toEpochMilli()
            val old = db.workRecordDao().getByDate(date.toString())
            val start = if (clockIn) millis else old?.startTime
            var end = if (clockIn) old?.endTime else millis
            // 跨夜保护：下班时刻不晚于上班时刻 → 视为次日（夜班 21:00 → 次日 09:00）
            if (start != null && end != null && end <= start) end = end + DAY_MILLIS
            if (start != null && end != null && end <= start) {
                onResult("下班时间必须晚于上班时间")
                return@launch
            }
            if (start == null && end == null) {
                onResult("请先打卡上班时间")
                return@launch
            }
            val shiftType = ShiftDetector(zone).detectShift(start ?: end!!, settings)
            val addedBit = if (clockIn) ManualField.COMPANY_ARRIVAL.bit else ManualField.COMPANY_DEPARTURE.bit
            var mask = (old?.manualFieldsMask ?: 0) or addedBit
            var updated = (old ?: WorkRecordEntity(workDate = date.toString(), status = "WORK", createdAt = now))
                .copy(
                    startTime = start,
                    endTime = end,
                    shift = ShiftType.normalize(shiftType.name),
                    isManual = true,
                    manualFieldsMask = mask,
                    note = note.ifBlank { old?.note },
                    updatedAt = now
                )
            if (start != null && end != null) {
                val session = engine.buildSession(start, end, settings)
                mask = mask or ManualField.FINAL_MINUTES.bit or ManualField.AUTO_NEEDS_REVIEW.bit
                updated = updated.copy(
                    status = session.status.name,
                    finalMinutes = session.finalMinutes,
                    actualMinutes = session.actualMinutes,
                    needsReview = session.needsReview,
                    reviewReason = session.reviewReason,
                    manualFieldsMask = mask
                )
            } else if (updated.status.isBlank() || updated.status == "REST" || updated.status == "MANUAL") {
                updated = updated.copy(status = "WORK")
            }
            val id = db.workRecordDao().upsert(updated)
            db.manualOverrideDao().insert(
                ManualOverrideEntity(
                    recordId = if (updated.id == 0L) id else updated.id,
                    oldValue = "${old?.startTime}:${old?.endTime}:${old?.finalMinutes}",
                    newValue = "$start:$end:${updated.finalMinutes}",
                    reason = note.ifBlank { if (clockIn) "手动打卡 · 上班" else "手动打卡 · 下班" }
                )
            )
            db.appLogDao().insert(
                AppLogEntity(
                    type = "MANUAL",
                    content = "手动打卡" + (if (clockIn) "·上班 " else "·下班 ") +
                        "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60) +
                        " 计入 ${updated.finalMinutes} 分钟"
                )
            )
            refreshDay(date)
            onResult(null)
        }
    }

    /**
     * 补录时段（界面稿「补录时段」）：整天替换式写入 work_segments，再按 v1 的
     * `manualSegments` 口径重算当天计入工时。
     *
     * 只有 [WorkSegmentEntity.TYPE_WORK] 的时段计入工时；[WorkSegmentEntity.TYPE_OFF_SITE]
     * （离厂）只落库留痕，不参与计算——这正是界面稿里「不计入」的含义。
     */
    fun saveDaySegments(
        date: LocalDate,
        entries: List<DaySegmentDraft>,
        note: String,
        onResult: (String?) -> Unit
    ) {
        if (entries.isEmpty()) {
            onResult("请至少填写一个时段")
            return
        }
        entries.forEach {
            if (it.endMinutes == it.startMinutes) {
                onResult("时段的开始与结束时间不能相同")
                return
            }
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val settings = _settings.value.toDomain()
            val dayStart = date.atStartOfDay(zone)
            fun at(minutes: Int): Long =
                dayStart.plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

            val mapped = entries.map { entry ->
                // end <= start 视为跨夜（例如 21:00 → 次日 06:00）
                val endMinutes = if (entry.endMinutes > entry.startMinutes) entry.endMinutes
                else entry.endMinutes + 24 * 60
                WorkSegmentEntity(
                    recordId = 0,
                    startTime = at(entry.startMinutes),
                    endTime = at(endMinutes),
                    minutes = endMinutes - entry.startMinutes,
                    deductRest = entry.deductRest,
                    segmentType = entry.segmentType,
                    siteId = entry.siteId,
                    siteLabel = entry.siteLabel,
                    note = entry.note
                )
            }
            val counted = mapped
                .filter { it.segmentType == WorkSegmentEntity.TYPE_WORK }
                .map { WorkSegment(it.startTime, it.endTime, it.deductRest) }

            val old = db.workRecordDao().getByDate(date.toString())
            val minutes = WorkHourCalculator(zone).calculateFinalMinutes(
                WorkCalculationInput(
                    startMillis = old?.startTime,
                    endMillis = old?.endTime,
                    manualSegments = counted,
                    settings = settings
                )
            )
            val base = old ?: WorkRecordEntity(workDate = date.toString(), status = "MANUAL", createdAt = now)
            val updated = base.copy(
                status = if (base.status.isBlank() || base.status == "REST") "MANUAL" else base.status,
                finalMinutes = minutes,
                isManual = true,
                manualFieldsMask = base.manualFieldsMask or ManualField.FINAL_MINUTES.bit or ManualField.NOTE.bit,
                needsReview = false,
                reviewReason = null,
                note = note.ifBlank { base.note },
                updatedAt = now
            )
            val id = db.workRecordDao().upsert(updated)
            val recordId = if (updated.id == 0L) id else updated.id
            db.workSegmentDao().deleteForRecord(recordId)
            db.workSegmentDao().insertAll(mapped.map { it.copy(recordId = recordId) })
            db.manualOverrideDao().insert(
                ManualOverrideEntity(
                    recordId = recordId,
                    oldValue = base.finalMinutes.toString(),
                    newValue = minutes.toString(),
                    reason = note.ifBlank { "补录时段（${mapped.size} 段）" }
                )
            )
            db.appLogDao().insert(
                AppLogEntity(type = "MANUAL", content = "补录 ${mapped.size} 个时段，当天计入 $minutes 分钟")
            )
            refreshDay(date)
            onResult(null)
        }
    }

    // ---------------------------------------------------------------------
    // 计薪 / 采集档位（界面稿「计薪规则」「常规采集间隔」「Burst 上限」）
    // ---------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 计薪规则 v2（工资条口径）
    //
    // ⚠️ 这一段**只写两张新表**（pay_rate_segments / monthly_pay_params），
    //    绝不触碰 monthly_salaries 与 work_records —— 用户已录入的实发工资与工时
    //    是唯一权威来源，推算结果永不落库（用户 2026-09-13 明确要求）。
    // ------------------------------------------------------------------

    /**
     * 新增/覆盖一条计薪参数**分段常量**。
     *
     * [text] 是输入框原文，单位由 [PayRateKey.unit] 决定（元 / 小时 / %）。
     * [effectiveFrom] 形如 `2026-07`；同一（参数, 生效月）会被覆盖。
     */
    fun savePayRateSegment(
        key: PayRateKey,
        text: String,
        effectiveFrom: String,
        note: String? = null
    ) {
        val value = PayrollPresenter.parseValue(key, text) ?: return
        if (!MONTH_PATTERN.matches(effectiveFrom)) return
        viewModelScope.launch {
            db.payrollDao().upsertRateSegment(
                PayRateSegmentEntity(
                    paramKey = key.storageKey,
                    effectiveFrom = effectiveFrom,
                    value = value,
                    note = note
                )
            )
            reloadPayrollConfig()
        }
    }

    fun deletePayRateSegment(id: Long) {
        viewModelScope.launch {
            db.payrollDao().deleteRateSegment(id)
            reloadPayrollConfig()
        }
    }

    /** 某参数在指定计薪月适用的分段（值 + 生效月 + 全部历史）。 */
    fun payRateRow(key: PayRateKey, payrollMonth: String = _month.value.toString()): PayRateRowUi {
        val rows = _payRateSegments.value.filter { it.paramKey == key.storageKey }
        val picked = PayRateResolver.pick(
            payrollMonth,
            rows.map { PayRateResolver.Segment(it.effectiveFrom, it.value) }
        )
        return PayRateRowUi(
            key = key,
            value = picked?.value ?: key.defaultValue,
            effectiveFrom = picked?.effectiveFrom,
            segments = rows.sortedBy { it.effectiveFrom }
        )
    }

    /** 某计薪月的浮动参数（没有则 null）。 */
    fun monthlyPayParams(payrollMonth: String = _month.value.toString()): MonthlyPayParamsEntity? =
        _payParams.value[payrollMonth]

    /**
     * 保存某计薪月的浮动参数。
     *
     * 绩效系数非法（非数字 / 负数 / > 10）直接整单拒绝，避免静默存进半截数据。
     * 全空的草稿会**删掉**该行，不留垃圾记录。
     */
    fun saveMonthlyPayParams(payrollMonth: String, draft: MonthlyPayDraft) {
        val coefficient = draft.perfCoefficient.trim().ifBlank { null }
        if (coefficient != null && PayrollPresenter.parseCoefficient(coefficient) == null) return
        val nights = draft.nightShiftsOverride.trim()
        val nightsValue = if (nights.isBlank()) null else (nights.toIntOrNull() ?: return)
        if (nightsValue != null && nightsValue !in 0..31) return

        viewModelScope.launch {
            val entity = MonthlyPayParamsEntity(
                payrollMonth = payrollMonth,
                perfCoefficient = coefficient,
                perfBaseDeltaCents = PayrollPresenter.parseMoneyOrNull(draft.perfBaseDelta) ?: 0L,
                perfAmountCents = PayrollPresenter.parseMoneyOrNull(draft.perfAmount),
                benefitBonusCents = PayrollPresenter.parseMoneyOrNull(draft.benefitBonus) ?: 0L,
                heatAllowanceCents = PayrollPresenter.parseMoneyOrNull(draft.heatAllowance) ?: 0L,
                sickPayCents = PayrollPresenter.parseMoneyOrNull(draft.sickPay) ?: 0L,
                backPayCents = PayrollPresenter.parseMoneyOrNull(draft.backPay) ?: 0L,
                otherAddCents = PayrollPresenter.parseMoneyOrNull(draft.otherAdd) ?: 0L,
                socialOverrideCents = PayrollPresenter.parseMoneyOrNull(draft.socialOverride),
                housingFundOverrideCents = PayrollPresenter.parseMoneyOrNull(draft.housingFundOverride),
                nightShiftsOverride = nightsValue
            )
            if (entity.isEmpty) {
                db.payrollDao().deletePayParams(payrollMonth)
            } else {
                db.payrollDao().savePayParams(entity)
            }
            reloadPayrollConfig()
        }
    }

    /**
     * 只改某计薪月的**绩效系数**，其余分项原样保留。
     *
     * ⚠️ **不要用 [saveMonthlyPayParams] 代替** —— 那个是整行覆盖。界面既然只剩一个输入框，
     * 直接调它会把「工资条导入」写进来的效益奖金 / 高温 / 病假 / 补发 / 社保公积金 / 夜班天数
     * 全部抹成 0，预估会立刻失真。
     */
    fun savePerfCoefficient(payrollMonth: String, coefficient: String) {
        val value = coefficient.trim().ifBlank { null }
        if (value != null && PayrollPresenter.parseCoefficient(value) == null) return
        viewModelScope.launch {
            val existing = db.payrollDao().payParams(payrollMonth)
            val merged = (existing ?: MonthlyPayParamsEntity(payrollMonth = payrollMonth))
                .copy(perfCoefficient = value)
            if (merged.isEmpty) {
                db.payrollDao().deletePayParams(payrollMonth)
            } else {
                db.payrollDao().savePayParams(merged)
            }
            reloadPayrollConfig()
        }
    }

    /**
     * 当日工资估算（基准月校准法）。[minutes] 为当日计薪分钟。
     *
     * 没有基准月（全新安装 / 尚无任何实发录入）返回 null，界面显示「暂无基准」，
     * 而不是拿一个拍脑袋的单价去猜。
     */
    fun dailyPayCents(minutes: Int): Long? {
        val baseline = _payBaseline.value ?: return null
        return PayrollEngine.dailyEstimateCents(minutes, baseline.netCents, baseline.minutes)
    }

    // ---------------------------------------------------------------------
    // 年度统计（「今日」Tab = XX 年数据统计）
    // ---------------------------------------------------------------------

    /**
     * 年度统计：每月工时 / 每月实发 / 年休息日口径。
     *
     * 纯读、不落库（推算与统计都不回写）。跟着「今日」页的 30 秒心跳一起刷新：
     * 查一年记录 = 一个 BETWEEN，本地库几百行，代价可以忽略；不刷新反而会出现
     * 「刚补录完今天，切到年统计还是旧数」这种自相矛盾。
     */
    private val _yearStats = MutableStateFlow<YearStatsPresenter.YearStats?>(null)
    val yearStats: StateFlow<YearStatsPresenter.YearStats?> = _yearStats

    fun refreshYearStats(year: Int = _workday.value.year) {
        viewModelScope.launch {
            val rows = runCatching { db.workRecordDao().getMonthRecords("$year-01-01", "$year-12-31") }
                .getOrDefault(emptyList())
            val salaries = runCatching { db.monthlySalaryDao().all() }.getOrDefault(emptyList())
            // 休息日口径的兜底日均：没有任何记录时才用设置里的默认工时
            val fallback = _settings.value.defaultWorkMinutes
                ?: YearStatsPresenter.WEEKEND_SCOPE_THRESHOLD_MINUTES
            val today = _workday.value
            _yearStats.value = withContext(Dispatchers.Default) {
                YearStatsPresenter.build(
                    year = year,
                    records = rows,
                    salaries = salaries,
                    today = today,
                    fallbackDailyMinutes = fallback
                )
            }
        }
    }

    /** 重新读入分段常量与月度参数，再重算当月推算 + 日工资基准。 */
    fun reloadPayrollConfig() {
        viewModelScope.launch {
            _payRateSegments.value = db.payrollDao().rateSegments()
            _payParams.value = db.payrollDao().allPayParams().associateBy { it.payrollMonth }
            _payBaseline.value = computePayBaseline()
            recomputeMonthPayroll(_month.value, _records.value)
        }
    }

    /** 基准月 = 最近一个「已录入实发 且 出勤分钟 > 0」的计薪月。 */
    private suspend fun computePayBaseline(): PayBaseline? {
        val rows = db.monthlySalaryDao().all()
            .filter { it.payrollMonth.isNotBlank() && it.netSalaryCents > 0L }
            .sortedByDescending { it.payrollMonth }
        for (row in rows) {
            val minutes = db.payrollDao().minutesInMonth(row.payrollMonth)
            if (minutes > 0) return PayBaseline(row.payrollMonth, row.netSalaryCents, minutes)
        }
        return null
    }

    /** 纯计算，不落库。 */
    private fun recomputeMonthPayroll(month: YearMonth, records: List<UiDayRecord>) {
        val monthKey = month.toString()
        val segments = _payRateSegments.value
            .groupBy { PayRateKey.byStorageKey(it.paramKey) }
            .mapNotNull { (key, rows) ->
                key?.let { it to rows.map { row -> PayRateResolver.Segment(row.effectiveFrom, row.value) } }
            }
            .toMap()
        val rates = PayRateResolver.resolve(monthKey, segments)
        val params = _payParams.value[monthKey]
        val stats = PayrollPresenter.attendanceStats(records)
        _monthPayroll.value = PayrollEngine.estimate(
            rates,
            PayrollInputs(
                attendDays = stats.attendDays,
                nightShiftDays = stats.nightShiftDays,
                perfCoefficient = params?.perfCoefficient?.toBigDecimalOrNull(),
                perfBaseDeltaCents = params?.perfBaseDeltaCents ?: 0L,
                perfAmountCents = params?.perfAmountCents,
                benefitBonusCents = params?.benefitBonusCents ?: 0L,
                heatAllowanceCents = params?.heatAllowanceCents ?: 0L,
                sickPayCents = params?.sickPayCents ?: 0L,
                backPayCents = params?.backPayCents ?: 0L,
                otherAddCents = params?.otherAddCents ?: 0L,
                socialOverrideCents = params?.socialOverrideCents,
                housingFundOverrideCents = params?.housingFundOverrideCents,
                nightShiftsOverride = params?.nightShiftsOverride
            )
        )
        _monthProjection.value = buildProjection(records, params?.nightShiftsOverride)
    }

    /**
     * 整月预估 = **预估工时 × 基准月到手单价**（用户 2026-09-14 选定口径）。
     *
     * 没有基准月（全新安装 / 还没有任何实发录入）返回 null —— 界面不显示，而不是猜一个数。
     * 当月已经走完（没有可补的日子）也返回 null，避免多一行没信息量的数。
     */
    private fun buildProjection(records: List<UiDayRecord>, nightOverride: Int?): MonthProjection? {
        val baseline = _payBaseline.value ?: return null
        val standardMinutes = _settings.value.defaultWorkMinutes ?: DEFAULT_WORK_MINUTES
        val stats = PayrollPresenter.projectionStats(
            records = records,
            today = _workday.value,
            standardMinutes = standardMinutes,
            nightShiftsOverride = nightOverride
        )
        if (!stats.hasProjection) return null
        val hourly = PayrollEngine.baselineHourlyCents(baseline.netCents, baseline.minutes) ?: return null
        val cents = PayrollEngine.dailyEstimateCents(
            stats.projectedMinutes, baseline.netCents, baseline.minutes
        ) ?: return null
        return MonthProjection(
            stats = stats,
            netCents = cents,
            hourlyCents = hourly,
            standardMinutes = standardMinutes,
            baseline = baseline
        )
    }

    private companion object {
        /** 生效月格式 `YYYY-MM` */
        val MONTH_PATTERN = Regex("""\d{4}-\d{2}""")

        /** 没设「每日标准工时」时的兜底（与今日页 fixedMinutes 同口径）。 */
        const val DEFAULT_WORK_MINUTES = 11 * 60
    }

    fun saveSamplingInterval(minutes: Int) {
        viewModelScope.launch {
            saveSettings(_settings.value.copy(samplingIntervalMinutes = minutes.coerceIn(1, 60)))
        }
    }

    fun saveBurstCap(minutes: Int) {
        viewModelScope.launch {
            saveSettings(_settings.value.copy(burstCapMinutes = minutes.coerceIn(1, 60)))
        }
    }

    fun saveAccuracyMode(mode: String) {
        viewModelScope.launch {
            saveSettings(_settings.value.copy(locationAccuracyMode = mode))
        }
    }

    fun saveRestWeekPattern(pattern: String) {
        viewModelScope.launch {
            saveSettings(_settings.value.copy(restWeekPattern = pattern))
            loadMonth(force = true)
        }
    }

    fun saveHolidaySourceMode(mode: String) {
        viewModelScope.launch {
            saveSettings(_settings.value.copy(holidaySourceMode = mode))
            loadMonth(force = true)
        }
    }

    // ---------------------------------------------------------------------
    // 地点（DB v11 的 sites 表）
    // ---------------------------------------------------------------------

    private val _sites = MutableStateFlow<List<SiteEntity>>(emptyList())
    val sites: StateFlow<List<SiteEntity>> = _sites
    private val _siteEvidenceSources = MutableStateFlow<List<SiteEvidenceSourceEntity>>(emptyList())
    val siteEvidenceSources: StateFlow<List<SiteEvidenceSourceEntity>> = _siteEvidenceSources

    /**
     * 每个地点的学习状态（DB v16）；单条渲染信息量较大，所以按 placeId 索引。
     *
     * 空 map = 还没取过 / 没有带坐标的地点。**不许**用「阶段默认值」占位：
     * 那会让列表先显示一排「尚未开始学习」，再跳成真实状态，看起来像状态在乱跳。
     */
    private val _learningStatuses = MutableStateFlow<Map<Long, PlaceLearningStatus>>(emptyMap())
    val learningStatuses: StateFlow<Map<Long, PlaceLearningStatus>> = _learningStatuses

    private val learningReport by lazy { PlaceLearningReport(db) }
    private val learningPreferences by lazy { PlaceLearningPreferenceService(db) }

    /**
     * 四个来源（GPS / Wi-Fi / 蓝牙 / 基站）的可用性。
     *
     * 键恒定存在：没有健康记录时是 [SourceStatus.UNKNOWN]，不会凭空显示「正常」。
     */
    private val _sourceHealth = MutableStateFlow(
        EvidenceSourceKind.entries.associateWith { SourceStatus.UNKNOWN }
    )
    val sourceHealth: StateFlow<Map<EvidenceSourceKind, SourceStatus>> = _sourceHealth

    /** 「立即刷新一次」的进行中/一次性提示状态。 */
    private val _evidenceRefresh = MutableStateFlow(EvidenceRefreshState())
    val evidenceRefresh: StateFlow<EvidenceRefreshState> = _evidenceRefresh

    /** Wi-Fi 选择页的扫描状态（界面稿 11）；候选只在内存，勾选后才由 [replaceWifiSources] 落哈希。 */
    private val _wifiScan = MutableStateFlow<WifiScanUi>(WifiScanUi.Idle)
    val wifiScan: StateFlow<WifiScanUi> = _wifiScan

    /** 重新读一遍地点与证据源。地点页增删改后、以及首次进入时调用。 */
    fun reloadSites() {
        viewModelScope.launch {
            _sites.value = runCatching { db.siteDao().all() }.getOrDefault(emptyList())
            _siteEvidenceSources.value = runCatching { db.siteDao().allSources() }.getOrDefault(emptyList())
        }
    }

    /**
     * 重新读一遍学习状态（地点页打开时、切换开关后调用）。
     *
     * 只读、不改任何算法行为：`PlaceLearningReport` 是把候选行重新投影一遍算出来的，
     * 所以它随时可跑，不需要考虑时序。
     */
    fun reloadLearningStatuses() {
        viewModelScope.launch {
            val statuses = runCatching { learningReport.statuses() }.getOrDefault(emptyList())
            _learningStatuses.value = statuses.associateBy { it.placeId }
        }
    }

    /**
     * 停用 / 重新开启某地点的学习校准（粘性，DB v16）。
     *
     * 三件事的顺序都不能省：
     *  1. 写偏好 + 按规则调整模型/窗口（[PlaceLearningPreferenceService]）；
     *  2. 让定位服务**立刻**丢掉生效地点缓存 —— 否则「停用」最多晚 60 秒
     *     才在判定侧生效，而界面已经说「已停用」，那就是界面在说谎；
     *  3. 重读状态，让页面显示与实际一致。
     *
     * 重新开启时后台会重新走 7 天前向验证 —— 这是刻意的，不是「没生效」的 bug。
     */
    fun setLearningAutoApply(placeId: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { learningPreferences.setAutoApplyEnabled(placeId, enabled) }
            runCatching { ServiceRecovery.invalidateSiteCache(getApplication()) }
            reloadLearningStatuses()
        }
    }

    /** 重新判定四个来源的可用性（进入相关页面时调一次，刷新完成后也调）。 */
    fun reloadSourceHealth() {
        viewModelScope.launch {
            val rows = runCatching { db.environmentEvidenceDao().allHealth() }.getOrDefault(emptyList())
            _sourceHealth.value = SourceHealthJudge.snapshot(rows, System.currentTimeMillis())
        }
    }

    /** 消费掉一次性反馈提示（Snackbar 展示完之后调）。 */
    fun clearEvidenceRefreshMessage() {
        _evidenceRefresh.value = _evidenceRefresh.value.copy(message = null)
    }

    /**
     * 用户手动「立即刷新一次」：让前台服务重取一次定位 + 环境三源（Wi-Fi / 蓝牙 / 基站），
     * 然后重新判定四源状态并给一次性反馈。
     *
     * ⚠️ 这里**只做重取样**：拿到的新定位仍要按原有口径走融合与状态机，
     * 弱证据（单源）依然不得改变工时状态 —— 手动刷新不是「绕过判定」的后门。
     *
     * 注意原实现的坑：只重读 `location_logs` 里最后一行，没有触发任何新采样，
     * 所以在店里点多少次「刷新」都是同一串坐标 —— 用户会认为这个按钮是坏的。
     */
    fun refreshEvidenceNow() {
        if (_evidenceRefresh.value.running) return
        viewModelScope.launch {
            _evidenceRefresh.value = EvidenceRefreshState(
                running = true,
                message = "正在重新取样 GPS / Wi-Fi / 蓝牙 / 基站…"
            )
            val beforeFix = runCatching { db.locationLogDao().latest()?.time }.getOrNull()
            val beforeRows = runCatching { db.environmentEvidenceDao().allHealth() }
                .getOrDefault(emptyList()).associateBy { it.name }

            if (!ServiceRecovery.startRefresh(getApplication())) {
                reloadSourceHealthNow()
                _evidenceRefresh.value = EvidenceRefreshState(
                    running = false,
                    message = "刷新失败：缺少定位权限，或系统定位已关闭",
                    completedAt = System.currentTimeMillis()
                )
                return@launch
            }

            // 服务侧一次性刷新的兜底时限是 8 秒，这里多留一点给环境扫描
            val deadline = System.currentTimeMillis() + REFRESH_WAIT_MILLIS
            var afterFix = beforeFix
            while (System.currentTimeMillis() < deadline) {
                delay(400)
                afterFix = runCatching { db.locationLogDao().latest()?.time }.getOrNull()
                if (afterFix != null && afterFix != beforeFix) break
            }
            val afterRows = runCatching { db.environmentEvidenceDao().allHealth() }
                .getOrDefault(emptyList())
            _sourceHealth.value = SourceHealthJudge.snapshot(afterRows, System.currentTimeMillis())
            refreshLastKnownLocation()

            val updated = afterRows.filter { row ->
                val prev = beforeRows[row.name]
                prev == null || row.lastCallbackAt > prev.lastCallbackAt
            }.mapNotNull { row ->
                EvidenceSourceKind.entries.firstOrNull { it.storageName == row.name }?.label
            }
            val gotFix = afterFix != null && afterFix != beforeFix
            _evidenceRefresh.value = EvidenceRefreshState(
                running = false,
                message = when {
                    gotFix && updated.isNotEmpty() -> "已刷新：新定位已取到，${updated.joinToString("、")}也已更新"
                    gotFix -> "已刷新：新定位已取到"
                    updated.isNotEmpty() -> "已刷新：${updated.joinToString("、")}已更新（定位这次没变化）"
                    else -> "已重新取样，四个来源这次都没有新回调（室内或静止时属正常）"
                },
                completedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun reloadSourceHealthNow() {
        val rows = runCatching { db.environmentEvidenceDao().allHealth() }.getOrDefault(emptyList())
        _sourceHealth.value = SourceHealthJudge.snapshot(rows, System.currentTimeMillis())
    }

    /** 每个地点已选证据源数量（地点列表右侧的「N 个证据源」）。 */
    fun evidenceCountBySite(): Map<Long, Int> =
        _siteEvidenceSources.value.groupingBy { it.siteId }.eachCount()

    /** 某地点已声明的证据源（编辑页 / Wi-Fi 选择页按站点过滤）。 */
    fun sourcesForSite(siteId: Long): List<SiteEvidenceSourceEntity> =
        _siteEvidenceSources.value.filter { it.siteId == siteId }

    /** 列表页行模型：地点 + 证据源计数 + 到最近一次定位的距离。 */
    fun siteRows(lastLatitude: Double?, lastLongitude: Double?): List<SiteRowUi> {
        val counts = evidenceCountBySite()
        return _sites.value.map { site ->
            SiteRowUi(
                site = site,
                sourceCount = counts[site.id] ?: 0,
                distanceMeters = SiteResolver.distanceTo(lastLatitude, lastLongitude, site.toSitePoint())
            )
        }
    }

    /**
     * 取最近一次定位坐标（编辑页「使用当前位置」）。
     * 无定位时回调 null 而不是抛错——界面提示「暂无定位，请先到室外等一次定位」。
     */
    fun loadCurrentLocation(onPoint: (Double?, Double?) -> Unit) {
        viewModelScope.launch {
            val last = runCatching { db.locationLogDao().latest() }.getOrNull()
            onPoint(last?.latitude, last?.longitude)
        }
    }

    /**
     * 保存地点（新增或修改）。
     *
     * 三件事必须原子地一起完成，否则会出现「两个主地点」或「没有主地点」：
     * 1. 声明为主地点 → 先清掉其他主地点；
     * 2. 写入/更新本行；
     * 3. [ensurePrimarySite] 兜底：一个启用的主地点都没有时，顶一个上来。
     */
    fun saveSite(draft: SiteDraft, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val dao = db.siteDao()
            val now = System.currentTimeMillis()
            val existing = draft.id?.let { runCatching { dao.byId(it) }.getOrNull() }
            if (draft.isPrimary) runCatching { dao.clearPrimary() }
            val entity = SiteEntity(
                id = existing?.id ?: 0L,
                name = draft.name.trim(),
                siteType = draft.siteType,
                latitude = draft.latitude,
                longitude = draft.longitude,
                radiusMeters = draft.radiusMeters
                    .coerceIn(SiteEntity.MIN_RADIUS_METERS, SiteEntity.MAX_RADIUS_METERS),
                isPrimary = draft.isPrimary,
                enabled = draft.enabled,
                migrated = existing?.migrated ?: false,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            val rowId = runCatching { dao.upsert(entity) }.getOrDefault(0L)
            ensurePrimarySite()
            reloadSites()
            onSaved(if (rowId > 0L) rowId else entity.id)
        }
    }

    /** 删除地点：连同它的证据源一起删（证据源脱离地点没有意义）。 */
    fun deleteSite(id: Long) {
        viewModelScope.launch {
            runCatching { db.siteDao().deleteSourcesFor(id) }
            runCatching { db.siteDao().delete(id) }
            ensurePrimarySite()
            reloadSites()
        }
    }

    /** 设为主地点：主地点必须启用，否则「优先匹配」名存实亡。 */
    fun setPrimarySite(id: Long) {
        viewModelScope.launch {
            val dao = db.siteDao()
            val site = runCatching { dao.byId(id) }.getOrNull() ?: return@launch
            runCatching { dao.clearPrimary() }
            runCatching {
                dao.upsert(site.copy(isPrimary = true, enabled = true, updatedAt = System.currentTimeMillis()))
            }
            reloadSites()
        }
    }

    /** 启用 / 停用地点。停用主地点后会自动把另一个启用地点顶成主地点。 */
    fun setSiteEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { db.siteDao().setEnabled(id, enabled, System.currentTimeMillis()) }
            ensurePrimarySite()
            reloadSites()
        }
    }

    /**
     * 保存 Wi-Fi 证据源：**整组替换**（先清空该站点的 WIFI 源，再写入勾选结果）。
     *
     * 隐私不变量：只落 64 位加盐哈希与信号强度，`label` 一律留空——
     * SSID 是用户的位置隐私，不进数据库（界面用「已选 N 个」表达选择结果）。
     */
    fun replaceWifiSources(siteId: Long, picked: List<ScannedWifi>) {
        viewModelScope.launch {
            runCatching {
                val dao = db.siteDao()
                dao.deleteSources(siteId, SiteEvidenceSourceEntity.TYPE_WIFI)
                if (picked.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    dao.upsertSources(
                        picked.map { wifi ->
                            SiteEvidenceSourceEntity(
                                siteId = siteId,
                                sourceType = SiteEvidenceSourceEntity.TYPE_WIFI,
                                identifierHash = wifi.identifierHash,
                                label = null,
                                lastSignal = wifi.signal,
                                selectedAt = now
                            )
                        }
                    )
                }
            }
            reloadSites()
        }
    }

    /** 清空某地点某类证据源（编辑页「清空」按钮）。 */
    fun clearSiteSources(siteId: Long, sourceType: String) {
        viewModelScope.launch {
            runCatching { db.siteDao().deleteSources(siteId, sourceType) }
            reloadSites()
        }
    }

    /**
     * 扫描工作地点的 Wi-Fi（界面稿 11）。
     * 扫描在 IO 线程执行；结果只留在内存，用户勾选后由 [replaceWifiSources] 落哈希。
     */
    fun scanWifi() {
        if (_wifiScan.value is WifiScanUi.Scanning) return
        _wifiScan.value = WifiScanUi.Scanning
        viewModelScope.launch {
            val saltStore = EnvironmentSaltStore(getApplication())
            val outcome = withContext(Dispatchers.IO) {
                runCatching { SiteWifiScanner(getApplication(), { saltStore.getOrCreate() }).scan() }
                    .getOrElse { failure ->
                        WifiScanOutcome.Error(
                            WifiScanOutcome.Reason.FAILED, failure.message ?: "扫描失败"
                        )
                    }
            }
            _wifiScan.value = when (outcome) {
                is WifiScanOutcome.Ok -> WifiScanUi.Ready(outcome.items, System.currentTimeMillis())
                is WifiScanOutcome.Error -> WifiScanUi.Failed(outcome.message)
            }
        }
    }

    /**
     * 保证「有且仅有一个启用的主地点」这条不变量。
     *
     * 修的是两个真实缺陷（2026-09-16 真机核对发现）：
     * 1. **两个主地点并存** —— v11 迁移把「公司」和「家」都写成了主地点，
     *    旧实现 `if (all.any { it.isPrimary && it.enabled }) return` 只要**存在**一个就收工，
     *    于是两行都带「主」，[SiteResolver.matching] 的「主地点优先」退化成不确定。
     *    → 多于一个启用主地点时只留一个（优先带证据源的），其余清掉。
     * 2. **空壳地点抢占主地点** —— 刚新建、还没挂 Wi-Fi/GPS 的地点被顶成主地点后，
     *    它在判定里什么都匹配不到。→ 需要补主地点时**优先选带证据源的**。
     *
     * 只有一个主地点时**什么都不做** —— 不覆盖用户自己的选择。
     * ⚠️ 本方法只改 `sites` 这张配置表，绝不碰任何工时记录。
     */
    private suspend fun ensurePrimarySite() {
        runCatching {
            val dao = db.siteDao()
            val all = dao.all()
            val enabled = all.filter { it.enabled }
            if (enabled.isEmpty()) return@runCatching
            val primaries = enabled.filter { it.isPrimary }
            if (primaries.size == 1) return@runCatching

            val evidenceSiteIds = runCatching { dao.allSources().map { it.siteId }.toSet() }
                .getOrDefault(emptySet())
            fun hasEvidence(site: SiteEntity) = site.hasGps || site.id in evidenceSiteIds
            // 排序只为「可复现」：先比有没有证据，再比 id（先建的通常就是公司）
            val ranked = { pool: List<SiteEntity> ->
                pool.sortedWith(
                    compareByDescending<SiteEntity> { hasEvidence(it) }.thenBy { it.id }
                )
            }
            val keeper = if (primaries.isNotEmpty()) ranked(primaries).first() else ranked(enabled).first()

            val now = System.currentTimeMillis()
            val demoted = all.filter { it.isPrimary && it.id != keeper.id }
            demoted.forEach { stale ->
                dao.upsert(stale.copy(isPrimary = false, updatedAt = now))
            }
            val promoted = !keeper.isPrimary
            if (promoted) dao.upsert(keeper.copy(isPrimary = true, updatedAt = now))
            if (demoted.isNotEmpty() || promoted) {
                runCatching {
                    db.appLogDao().insert(AppLogEntity(
                        type = "SITE_PRIMARY_FIX",
                        content = "主地点收敛为 #${keeper.id}「${keeper.name}」；" +
                            "清理多余主地点 ${demoted.size} 个（原启用主地点 ${primaries.size} 个）"
                    ))
                }
            }
        }
    }
}
