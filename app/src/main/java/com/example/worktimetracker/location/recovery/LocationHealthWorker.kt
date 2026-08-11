package com.example.worktimetracker.location.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LocationHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!ServiceRecovery.isHealthy(applicationContext)) ServiceRecovery.start(applicationContext)
        return Result.success()
    }
}
