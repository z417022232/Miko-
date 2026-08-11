package com.example.worktimetracker.location.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.location.recovery.ServiceRecoveryPolicy
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class LocationTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        val googleTransition = event != null && !event.hasError() && event.geofenceTransition in setOf(
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_EXIT
        )
        val platformTransition = intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)
        if (!googleTransition && !platformTransition) return
        ServiceRecovery.start(context, ServiceRecoveryPolicy.RecoveryTrigger.GEOFENCE)
    }
}
