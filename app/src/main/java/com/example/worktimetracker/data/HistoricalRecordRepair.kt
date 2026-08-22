package com.example.worktimetracker.data

import android.content.Context
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.domain.engine.SessionTimelineReconstructor
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.LocationType
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.location.service.ConfirmedSession
import com.example.worktimetracker.data.entity.WorkRecordEntity
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import androidx.room.withTransaction
import com.example.worktimetracker.location.permission.LocationCalibrationStore
import com.example.worktimetracker.location.service.CalibrationSessionReplay
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer

object HistoricalRecordRepair {
    private const val PREFS = "historical_repair"
    private const val KEY = "reliable_sessions_v6"
    private const val CROSS_MIDNIGHT_KEY = "cross_midnight_sessions_v7"
    private const val AUGUST_19_KEY = "august_19_incomplete_v8"

    fun shouldRepair(record: WorkRecordEntity): Boolean =
        !record.isManual && !record.needsReview && record.startTime != null && record.endTime != null

    suspend fun runOnce(app: WorkTimeApplication) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val db = app.database
        val settings = db.userSettingsDao().getSettings() ?: return
        val domain = WorkSettings(
            settings.workStartMinutes, settings.workEndMinutes, settings.hasDefaultHours,
            settings.defaultWorkMinutes, settings.restDeductionMinutes, settings.outsideThresholdMinutes,
            settings.leaveCompanyConfirmMinutes, settings.earlyLeaveToleranceMinutes
        )
        val engine = WorkSessionEngine(ZoneId.systemDefault())
        if (!prefs.getBoolean(KEY, false)) {
            val reconstructor = SessionTimelineReconstructor()
            for (record in db.workRecordDao().recentAutomaticRecordsForRepair()) {
                if (!shouldRepair(record)) continue
                val start = record.startTime ?: continue
                val logs = db.locationLogDao().getLogs(start - 6 * 60 * 60_000L, start + 18 * 60 * 60_000L)
                val points = logs.mapNotNull { log ->
                    val type = runCatching { LocationType.valueOf(log.locationType) }.getOrNull() ?: return@mapNotNull null
                    SessionTimelineReconstructor.Point(log.time, type)
                }
                val rebuilt = reconstructor.reconstruct(start, points, 18 * 60) ?: continue
                val session = engine.buildSession(start, rebuilt.companyDeparture, domain)
                val merged = ConfirmedSession.merge(existing = record, shift = session.shiftType.name, companyArrival = start, companyDeparture = rebuilt.companyDeparture, homeDeparture = rebuilt.homeDeparture, homeArrival = rebuilt.homeArrival, actualMinutes = session.actualMinutes, calculatedMinutes = session.finalMinutes, needsReview = session.needsReview)
                db.workRecordDao().upsert(merged)
            }
            prefs.edit().putBoolean(KEY, true).apply()
        }
        repairCrossMidnightSessions(app, domain, engine, prefs)
        if (!prefs.getBoolean(AUGUST_19_KEY, false)) {
            db.workRecordDao().getByDate("2026-08-19")?.let { db.workRecordDao().update(markAugustNineteenthIncomplete(it)) }
            prefs.edit().putBoolean(AUGUST_19_KEY, true).apply()
        }
        db.workRecordDao().getByDate("2026-08-19")?.let { record ->
            if (record.isManual && db.manualOverrideDao().countForRecord(record.id) == 0) {
                db.manualOverrideDao().insert(
                    com.example.worktimetracker.data.entity.ManualOverrideEntity(
                        recordId = record.id,
                        oldValue = record.finalMinutes.toString(),
                        newValue = record.finalMinutes.toString(),
                        reason = "恢复人工修改审计",
                        modifiedAt = record.updatedAt
                    )
                )
            }
        }
        repairCalibrationSplitSession(app, settings, domain, engine)
    }

    private suspend fun repairCalibrationSplitSession(
        app: WorkTimeApplication,
        settings: com.example.worktimetracker.data.entity.UserSettingsEntity,
        domain: WorkSettings,
        engine: WorkSessionEngine
    ) {
        val store = LocationCalibrationStore(app)
        val calibratedAt = store.companyCalibratedAt()
        val companyLat = settings.companyLat ?: return
        val companyLng = settings.companyLng ?: return
        if (calibratedAt <= 0L) return
        val db = app.database
        db.withTransaction {
            val state = db.workStateDao().getState() ?: return@withTransaction
            val homeDeparture = state.candidateHomeDepartureTime ?: state.homeDepartureTime ?: return@withTransaction
            val logs = db.locationLogDao().getLogs(homeDeparture, calibratedAt)
            val analyzer = LocationStatusAnalyzer()
            val arrival = CalibrationSessionReplay.findArrival(homeDeparture, calibratedAt,
                store.companyStableRadius(), logs.map {
                    CalibrationSessionReplay.Sample(it.time,
                        analyzer.distanceMeters(it.latitude, it.longitude, companyLat, companyLng),
                        it.accuracyMeters ?: 999f)
                }) ?: return@withTransaction
            val session = engine.buildSession(arrival, null, domain)
            val departure = state.candidateCompanyDepartureTime?.takeIf { it >= arrival }
            val homeArrival = state.candidateHomeArrivalTime?.takeIf { departure != null && it >= departure }
            val completed = departure?.let { engine.buildSession(arrival, it, domain) }
            val target = db.workRecordDao().getByDate(session.assignedDate)
            val repaired = (target ?: WorkRecordEntity(workDate = session.assignedDate, status = "WORK")).copy(
                status = "WORK", shift = session.shiftType.name, startTime = arrival,
                endTime = departure, homeDepartureTime = homeDeparture, homeArrivalTime = homeArrival,
                actualMinutes = completed?.actualMinutes,
                finalMinutes = completed?.finalMinutes ?: 0,
                needsReview = departure != null,
                note = null, updatedAt = System.currentTimeMillis())
            if (target == null) db.workRecordDao().upsert(repaired) else db.workRecordDao().update(repaired)
            val nextDate = LocalDate.parse(session.assignedDate).plusDays(1).toString()
            db.workRecordDao().getByDate(nextDate)?.takeIf {
                isCalibrationSplitDuplicate(it, homeDeparture, calibratedAt)
            }?.let { db.workRecordDao().delete(it) }
            state.sessionStart?.let { oldStart ->
                val wrongDate = Instant.ofEpochMilli(oldStart).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                if (wrongDate != session.assignedDate) {
                    db.workRecordDao().getByDate(wrongDate)?.takeIf { !it.isManual && it.endTime == null }?.let { db.workRecordDao().delete(it) }
                }
            }
            db.workStateDao().save(state.copy(
                currentState = if (state.currentState == "LEAVING_HOME") "WORKING" else state.currentState,
                sessionStart = arrival, candidateCompanyArrivalTime = arrival,
                companyArrivalConfirmedAt = calibratedAt, updatedAt = System.currentTimeMillis()))
            db.appLogDao().insert(com.example.worktimetracker.data.entity.AppLogEntity(
                type = "CALIBRATION_REPLAY", content = "校准后回放当前会话并归入${session.assignedDate}"))
        }
    }

    fun isCalibrationSplitDuplicate(record: WorkRecordEntity, homeDeparture: Long, calibratedAt: Long): Boolean =
        !record.isManual && record.homeDepartureTime == homeDeparture &&
            record.startTime?.let { it in calibratedAt..(calibratedAt + 2 * 60 * 60_000L) } == true

    fun markAugustNineteenthIncomplete(record: WorkRecordEntity): WorkRecordEntity {
        if (record.workDate != "2026-08-19" || record.endTime != null) return record
        val evidence = "离岗候选约08:59，持续远离证据约09:14；09:46后定位中断，到家时间需人工确认"
        return record.copy(needsReview = true, homeArrivalTime = null,
            note = record.note?.takeIf { it.isNotBlank() }?.let { "$it；$evidence" } ?: evidence,
            updatedAt = System.currentTimeMillis())
    }

    private suspend fun repairCrossMidnightSessions(
        app: WorkTimeApplication,
        settings: WorkSettings,
        engine: WorkSessionEngine,
        prefs: android.content.SharedPreferences
    ) {
        if (prefs.getBoolean(CROSS_MIDNIGHT_KEY, false)) return
        val db = app.database
        val logs = db.locationLogDao().getAllLogs()
        var lastHome: com.example.worktimetracker.data.entity.LocationLogEntity? = null
        var leftHome: com.example.worktimetracker.data.entity.LocationLogEntity? = null
        var companyArrival: com.example.worktimetracker.data.entity.LocationLogEntity? = null
        for (log in logs) {
            when (log.locationType) {
                "HOME" -> {
                    if (companyArrival != null && log.time - companyArrival.time <= 18 * 60 * 60_000L) {
                        val session = engine.buildSession(companyArrival.time, null, settings)
                        val existing = db.workRecordDao().getByDate(session.assignedDate)
                        if (existing == null) {
                            db.workRecordDao().upsert(
                                WorkRecordEntity(
                                    workDate = session.assignedDate,
                                    status = "WORK",
                                    shift = session.shiftType.name,
                                    startTime = companyArrival.time,
                                    homeDepartureTime = leftHome?.time ?: lastHome?.time,
                                    homeArrivalTime = log.time,
                                    finalMinutes = session.finalMinutes,
                                    needsReview = true,
                                    note = "已根据家→公司→家定位回填；离开公司时间待确认"
                                )
                            )
                        }
                        companyArrival = null
                        leftHome = null
                    }
                    lastHome = log
                }
                "OTHER" -> if (companyArrival == null && lastHome != null) leftHome = log
                "COMPANY" -> if (companyArrival == null && leftHome != null) companyArrival = log
            }
        }
        prefs.edit().putBoolean(CROSS_MIDNIGHT_KEY, true).apply()
    }
}
