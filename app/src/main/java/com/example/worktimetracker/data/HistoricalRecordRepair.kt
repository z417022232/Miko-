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
            db.workRecordDao().getByDate("2026-08-19")?.let { db.workRecordDao().upsert(markAugustNineteenthIncomplete(it)) }
            prefs.edit().putBoolean(AUGUST_19_KEY, true).apply()
        }
    }

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
