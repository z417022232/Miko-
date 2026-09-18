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

/**
 * 系统定位开关的一次「关闭 -> 恢复」作为一个事件周期。
 *
 * 前台服务、闹钟和 WorkManager 都会观测同一开关；这个纯函数把去重键收敛到
 * [disabledEpisodeStartedAt]，避免各调用方使用不同的通知 key 而连续打扰用户。
 */
data class SystemLocationAlertState(
    val disabledEpisodeStartedAt: Long? = null,
    val notifiedEpisodeStartedAt: Long? = null,
    val lastRecoveredAt: Long? = null
)

data class SystemLocationAlertDecision(
    val next: SystemLocationAlertState,
    val notifyUser: Boolean
)

object SystemLocationAlertPolicy {
    fun onObserved(state: SystemLocationAlertState, enabled: Boolean, now: Long): SystemLocationAlertDecision {
        if (enabled) {
            if (state.disabledEpisodeStartedAt == null) return SystemLocationAlertDecision(state, false)
            return SystemLocationAlertDecision(
                SystemLocationAlertState(lastRecoveredAt = now),
                notifyUser = false
            )
        }

        val episode = state.disabledEpisodeStartedAt ?: now
        val notify = state.notifiedEpisodeStartedAt != episode
        return SystemLocationAlertDecision(
            state.copy(
                disabledEpisodeStartedAt = episode,
                notifiedEpisodeStartedAt = if (notify) episode else state.notifiedEpisodeStartedAt
            ),
            notifyUser = notify
        )
    }
}

/** 设置页状态的纯 Kotlin 表达，便于把 Android 开关读取与 Compose 展示分开验证。 */
object SystemLocationStatusPresenter {
    fun showRepairBanner(enabled: Boolean): Boolean = !enabled
}

object SystemLocationLogPolicy {
    fun shouldLogDisabled(transition: SystemLocationTransition): Boolean =
        transition == SystemLocationTransition.DISABLED
}
