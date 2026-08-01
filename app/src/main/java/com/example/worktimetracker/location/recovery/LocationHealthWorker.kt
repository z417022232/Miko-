package com.example.worktimetracker.location.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.location.service.CompanyPresenceFallback
import java.time.ZoneId

class LocationHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!ServiceRecovery.isHealthy(applicationContext)) ServiceRecovery.start(applicationContext)
        val app = applicationContext as WorkTimeApplication
        val settings = app.database.userSettingsDao().getSettings()
        if (settings != null) {
            val domain = WorkSettings(
                settings.workStartMinutes, settings.workEndMinutes, settings.hasDefaultHours,
                settings.defaultWorkMinutes, settings.restDeductionMinutes, settings.outsideThresholdMinutes,
                settings.leaveCompanyConfirmMinutes, settings.earlyLeaveToleranceMinutes
            )
            val policy = CompanyPresenceFallback(ZoneId.systemDefault())
            val now = System.currentTimeMillis()
            app.database.workRecordDao().incompleteFallbackRecords(CompanyPresenceFallback.FALLBACK_NOTE).forEach { record ->
                when (val action = policy.evaluate(record.createdAt, now, record, domain)) {
                    is CompanyPresenceFallback.Action.UpsertReview -> app.database.workRecordDao().upsert(action.record)
                    is CompanyPresenceFallback.Action.Draft, CompanyPresenceFallback.Action.None -> Unit
                }
            }
        }
        return Result.success()
    }
}
