package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.domain.journey.RetryState

/** 新机首次启用时用最近30天既有事实预热影子快照；已有快照时严格幂等。 */
class JourneyHistoryPrelearningService(private val db: AppDatabase) {
    suspend fun runOnce(now: Long = System.currentTimeMillis()) {
        val dao = db.journeyShadowStateDao()
        if (dao.get() != null) return
        val settings = db.userSettingsDao().getSettings() ?: return
        val logs = db.locationLogDao().getLogs(now - LOOKBACK_MILLIS, now)
        val records = db.workRecordDao().getMonthRecords("0000-01-01", "9999-12-31")
        val learned = JourneyHistoryPrelearner.replay(logs, records, settings) ?: return
        dao.upsert(JourneyShadowStateCodec.encode(learned.snapshot, RetryState(), now))
        db.appLogDao().insert(
            AppLogEntity(
                type = "JOURNEY",
                content = "历史预学习完成：回放 ${learned.processedCount} 条定位，初始阶段 ${learned.snapshot.phase.name}；后续影子验证仍只使用未来数据"
            )
        )
    }

    companion object {
        const val LOOKBACK_MILLIS: Long = 30L * 24 * 60 * 60 * 1_000
    }
}
