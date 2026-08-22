package com.example.worktimetracker.location.recovery

data class ServiceHealthSnapshot(
    val serviceHeartbeat: Long,
    val lastLocationCallback: Long,
    val lastReliableLocation: Long,
    val providerAvailable: Boolean
)

enum class HealthAction { HEALTHY, REREGISTER_LOCATION, PROVIDER_UNAVAILABLE, NOTIFY_TAP_TO_RECOVER }

object ServiceHealthPolicy {
    fun evaluate(snapshot: ServiceHealthSnapshot, now: Long): HealthAction = when {
        now - snapshot.serviceHeartbeat >= 25 * 60_000L -> HealthAction.NOTIFY_TAP_TO_RECOVER
        !snapshot.providerAvailable -> HealthAction.PROVIDER_UNAVAILABLE
        now - snapshot.lastLocationCallback >= 25 * 60_000L -> HealthAction.REREGISTER_LOCATION
        else -> HealthAction.HEALTHY
    }
}
