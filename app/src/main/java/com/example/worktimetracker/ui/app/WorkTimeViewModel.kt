package com.example.worktimetracker.ui.app

import android.app.Application
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.data.entity.ManualOverrideEntity
import com.example.worktimetracker.data.entity.MonthlySalaryEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.importer.LegacyAttendanceCsvImporter
import com.example.worktimetracker.domain.engine.ChinaHolidayProvider
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.engine.PayrollPeriodRules
import com.example.worktimetracker.domain.engine.ManualRecordEditor
import com.example.worktimetracker.domain.engine.ReviewRecordEditor
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.export.ExportManager
import com.example.worktimetracker.ui.UiDayRecord
import com.example.worktimetracker.ui.MonthlyRecordIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

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

    init {
        viewModelScope.launch {
            val saved = db.userSettingsDao().getSettings() ?: UserSettingsEntity().also { db.userSettingsDao().save(it) }
            _settings.value = saved
            _onboardingDone.value = saved.onboardingDone
            loadMonth()
            refreshLastKnownLocation()
            refreshLogsOnce()
            refreshLastManualHours()
        }
    }

    fun finishOnboarding() {
        _onboardingDone.value = true
        viewModelScope.launch { saveSettings(_settings.value.copy(onboardingDone = true)) }
    }
    fun previousMonth() { moveToMonth(_month.value.minusMonths(1)) }
    fun nextMonth() { moveToMonth(_month.value.plusMonths(1)) }
    fun today() { _month.value = YearMonth.now(); _selectedDate.value = LocalDate.now(); loadMonth() }
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

    fun loadMonth() {
        val requestedMonth = _month.value
        if (monthJob?.isActive == true && observedMonth == requestedMonth) return
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
                _records.value = MonthlyRecordIndex.build(m, rows, LocalDate.now(), zone)
                _reviewRecords.value = _records.value.filter { it.needsReview }
            }
        }
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
            db.holidayDao().deleteAll()
            db.appLogDao().deleteAll()
            db.monthlySalaryDao().deleteAll()
            _lastKnownLocationText.value = "暂无定位"
            _recentLogs.value = emptyList()
            loadMonth()
        }
    }

    private suspend fun saveSettings(settings: UserSettingsEntity) {
        val updated = settings.copy(updatedAt = System.currentTimeMillis())
        db.userSettingsDao().save(updated)
        _settings.value = updated
    }

    private fun WorkRecordEntity.toUi(date: LocalDate): UiDayRecord = UiDayRecord(
        date = date,
        status = when (status) { "WORK" -> if (shift == "NIGHT_SHIFT") "夜班" else "白班"; "REST" -> "休息"; "OUTSIDE" -> "外出"; "EARLY_LEAVE" -> "下早班"; "ARRIVAL_EXCEPTION" -> "到岗异常"; "MANUAL" -> "手动"; "LEAVE" -> "请假"; else -> status },
        shift = when (shift) { "DAY_SHIFT" -> "白班"; "NIGHT_SHIFT" -> "夜班"; else -> null },
        startText = startTime?.timeText(),
        endText = endTime?.timeText(startTime),
        actualMinutes = actualMinutes,
        finalMinutes = finalMinutes,
        needsReview = needsReview,
        note = note,
        holidayName = ChinaHolidayProvider.name(date),
        companyArrivalText = startTime?.timeText(),
        companyDepartureText = endTime?.timeText(startTime),
        homeDepartureText = homeDepartureTime?.timeText(),
        homeArrivalText = homeArrivalTime?.timeText(startTime)
    )

    private fun UserSettingsEntity.toDomain(): WorkSettings = WorkSettings(workStartMinutes, workEndMinutes, hasDefaultHours, defaultWorkMinutes, restDeductionMinutes, outsideThresholdMinutes, leaveCompanyConfirmMinutes, earlyLeaveToleranceMinutes)
    private fun LocalDateTime.ms(): Long = atZone(zone).toInstant().toEpochMilli()
    private fun Long.timeText(start: Long? = null): String { val t = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime(); val prefix = if (start != null && Instant.ofEpochMilli(start).atZone(zone).toLocalDate() != t.toLocalDate()) "次日" else ""; return prefix + "%02d:%02d".format(t.hour, t.minute) }
    private fun parseRangeMinutes(start: String, end: String): Int { val s = start.split(":").mapNotNull { it.toIntOrNull() }; val e = end.split(":").mapNotNull { it.toIntOrNull() }; if (s.size != 2 || e.size != 2) return 0; val sm = s[0] * 60 + s[1]; var em = e[0] * 60 + e[1]; if (em < sm) em += 24 * 60; return (em - sm).coerceAtLeast(0) }
}

