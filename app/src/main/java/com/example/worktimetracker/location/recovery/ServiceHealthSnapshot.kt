package com.example.worktimetracker.location.recovery

/** 单一证据来源的健康状态：最后回调、最后成功、是否注册、恢复次数和失败原因。 */
data class SourceHealth(
    val lastCallbackAt: Long,
    val lastSuccessAt: Long,
    val registered: Boolean,
    val recoveryCount: Int,
    val lastFailure: String? = null
)

data class ServiceHealthSnapshot(
    val serviceHeartbeat: Long,
    val lastLocationCallback: Long,
    val lastReliableLocation: Long,
    val providerAvailable: Boolean,
    val sourceHealth: Map<String, SourceHealth> = emptyMap()
)

enum class HealthAction {
    HEALTHY,
    REREGISTER_LOCATION,
    REREGISTER_GNSS,
    REREGISTER_MOTION,
    /** 辅助来源（Wi-Fi/蓝牙/基站）失效：只降级环境证据，不算定位服务故障 */
    AUXILIARY_DEGRADED,
    PROVIDER_UNAVAILABLE,
    NOTIFY_TAP_TO_RECOVER
}

object ServiceHealthPolicy {
    const val STALE_MILLIS = 25 * 60_000L

    fun evaluate(snapshot: ServiceHealthSnapshot, now: Long): HealthAction = when {
        now - snapshot.serviceHeartbeat >= STALE_MILLIS -> HealthAction.NOTIFY_TAP_TO_RECOVER
        !snapshot.providerAvailable -> HealthAction.PROVIDER_UNAVAILABLE
        snapshot.sourceHealth["gnss"]?.let { it.registered && now - it.lastCallbackAt >= STALE_MILLIS } == true ->
            HealthAction.REREGISTER_GNSS
        snapshot.sourceHealth["motion"]?.let { !it.registered } == true -> HealthAction.REREGISTER_MOTION
        now - snapshot.lastLocationCallback >= STALE_MILLIS -> HealthAction.REREGISTER_LOCATION
        snapshot.sourceHealth.values.any { it.lastFailure == "PERMISSION" || it.lastFailure == "SECURITY" } ->
            HealthAction.AUXILIARY_DEGRADED
        else -> HealthAction.HEALTHY
    }
}

/** 相同失败在窗口期内只通知一次（默认 60 分钟）。 */
class HealthNotificationGate(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    private val lastNotified = mutableMapOf<String, Long>()

    fun shouldNotify(key: String, now: Long): Boolean {
        val last = lastNotified[key]
        if (last != null && now - last < windowMillis) return false
        lastNotified[key] = now
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60 * 60_000L
    }
}
