package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ShiftWindowFallback
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.ZoneId

class CompanyPresenceFallback(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    sealed interface Action {
        data object None : Action
        data class Draft(val record: WorkRecordEntity) : Action
        data class UpsertReview(val record: WorkRecordEntity) : Action
    }

    private val windows = ShiftWindowFallback(zoneId)
    private val sessions = WorkSessionEngine(zoneId)

    fun evaluate(
        companyFixAt: Long,
        now: Long,
        existing: WorkRecordEntity?,
        settings: WorkSettings
    ): Action {
        if (existing?.isManual == true) return Action.None
        if (existing?.startTime != null && existing.endTime != null) return Action.None
        val completion = windows.complete(now, existing?.startTime, existing?.endTime, settings)
        val base = existing ?: WorkRecordEntity(
            workDate = completion.assignedDate,
            status = "WORK",
            finalMinutes = 0,
            createdAt = companyFixAt
        )
        val start = completion.startMillis
        val end = completion.endMillis
        val calculated = if (start != null && end != null) sessions.buildSession(start, end, settings) else null
        val record = base.copy(
            status = "WORK",
            shift = completion.shift.name,
            startTime = start,
            endTime = end,
            actualMinutes = calculated?.actualMinutes ?: base.actualMinutes,
            finalMinutes = calculated?.finalMinutes ?: base.finalMinutes,
            needsReview = true,
            note = FALLBACK_NOTE,
            updatedAt = now
        )
        return if (end == null) Action.Draft(record) else Action.UpsertReview(record)
    }

    companion object {
        const val FALLBACK_NOTE = "按设置班次窗口补全，待人工确认"
    }
}
