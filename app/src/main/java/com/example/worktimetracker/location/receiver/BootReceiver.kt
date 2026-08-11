package com.example.worktimetracker.location.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.location.recovery.ServiceRecoveryPolicy
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.location.permission.AutostartVerificationStore
import com.example.worktimetracker.location.recovery.GeofenceRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in ServiceRecoveryPolicy.actions) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val app = context.applicationContext as WorkTimeApplication
                    val healthScheduled = ServiceRecovery.schedule(context)
                    val serviceStarted = ServiceRecovery.start(context, ServiceRecoveryPolicy.RecoveryTrigger.BOOT)
                    val settings = app.database.userSettingsDao().getSettings()
                    val geofenceRegistered = settings != null && GeofenceRecovery.register(context, settings)
                    if (ServiceRecoveryPolicy.bootVerified(serviceStarted, healthScheduled, geofenceRegistered)) {
                        AutostartVerificationStore(context).verifyBootRecovery()
                    } else {
                        app.database.appLogDao().insert(
                            AppLogEntity(
                                type = "BOOT_RECOVERY",
                                content = "恢复未完全成功 service=$serviceStarted health=$healthScheduled geofence=$geofenceRegistered"
                            )
                        )
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
