package com.example.worktimetracker.location.evidence

enum class ScanDecision { NONE, SNAPSHOT, BURST }

data class ScanPolicyInput(
    val now: Long,
    val lastScanAt: Long,
    val significantMotion: Boolean,
    val gnssStale: Boolean,
    val nearShiftWindow: Boolean,
    val stableKnownPlace: Boolean
)

/**
 * 省电的环境扫描策略（纯 Kotlin，无 Android 依赖）。
 *
 * 规则：
 * - 扫描冷却至少 5 分钟；
 * - 显著运动或 GNSS 超过 20 分钟无回调返回 BURST；
 * - 班次窗口附近返回 SNAPSHOT；
 * - 稳定已知地点且无运动返回 NONE。
 */
class AmbientScanPolicy {

    fun evaluate(input: ScanPolicyInput): ScanDecision {
        if (input.now < 0) return ScanDecision.NONE
        if (withinCooldown(input)) {
            return ScanDecision.NONE
        }
        if (input.significantMotion || input.gnssStale) return ScanDecision.BURST
        if (input.nearShiftWindow) return ScanDecision.SNAPSHOT
        if (input.stableKnownPlace) return ScanDecision.NONE
        return ScanDecision.NONE
    }

    private fun withinCooldown(input: ScanPolicyInput): Boolean =
        input.lastScanAt > 0 && input.now - input.lastScanAt < SCAN_COOLDOWN_MILLIS

    companion object {
        const val SCAN_COOLDOWN_MILLIS = 5 * 60_000L
    }
}
