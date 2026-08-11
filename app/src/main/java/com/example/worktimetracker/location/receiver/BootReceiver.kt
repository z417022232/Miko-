package com.example.worktimetracker.location.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.location.recovery.ServiceRecoveryPolicy

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in ServiceRecoveryPolicy.actions) {
            ServiceRecovery.schedule(context)
            ServiceRecovery.start(context, ServiceRecoveryPolicy.RecoveryTrigger.BOOT)
        }
    }
}
