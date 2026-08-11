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

    fun shouldRepair(record: WorkRecordEntity): Boolean =
        !record.isManual && !record.needsReview && record.startTime != null && record.endTime != null

    suspend fun runOnce(app: WorkTimeApplication) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        val db = app.database
        val settings = db.userSettingsDao().getSettings() ?: return
        val domain = WorkSettings(
            settings.workStartMinutes, settings.workEndMinutes, settings.hasDefaultHours,
            settings.defaultWorkMinutes, settings.restDeductionMinutes, settings.outsideThresholdMinutes,
            settings.leaveCompanyConfirmMinutes, settings.earlyLeaveToleranceMinutes
        )
        val engine = WorkSessionEngine(ZoneId.systemDefault())
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
            val merged = ConfirmedSession.merge(
                existing = record,
                shift = session.shiftType.name,
                companyArrival = start,
                companyDeparture = rebuilt.companyDeparture,
                homeDeparture = rebuilt.homeDeparture,
                homeArrival = rebuilt.homeArrival,
                actualMinutes = session.actualMinutes,
                calculatedMinutes = session.finalMinutes,
                needsReview = session.needsReview
            )
            db.workRecordDao().upsert(merged)
        }
        prefs.edit().putBoolean(KEY, true).apply()
    }
}
