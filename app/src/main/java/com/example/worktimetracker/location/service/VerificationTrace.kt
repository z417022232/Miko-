package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkStateEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 真机轨迹验证 TRACE（第四阶段测试工具，不改业务逻辑）：
 * 状态每次变化时输出一行结构化日志，把融合判断、WorkState 前后、采样档位与 Burst 阶段
 * 汇聚到 app_logs（type=TRACE），供 PC 端导出工具生成因果链时间线。
 */
object VerificationTrace {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun burstPhase(until: Long, medium: Boolean, now: Long): String = when {
        until <= 0L || until <= now -> "-"
        medium -> "MOVING_TRACK"
        else -> "FAST_BURST"
    }

    fun stateLine(
        before: WorkStateEntity,
        after: WorkStateEntity,
        resolvedPlace: String,
        samplingIntervalMillis: Long,
        burstUntil: Long,
        burstMedium: Boolean,
        now: Long
    ): String {
        val burst = burstPhase(burstUntil, burstMedium, now)
        return buildString {
            append("TRACE")
            append(" | gps=").append(resolvedPlace)
            append(" | state=").append(before.currentState).append("→").append(after.currentState)
            append(" | session=").append(fmt(before.sessionStart)).append("→").append(fmt(after.sessionStart))
            append(" | 离家=").append(fmt(after.homeDepartureTime ?: before.homeDepartureTime))
            append(" | 到公司=").append(fmt(after.companyArrivalConfirmedAt ?: before.companyArrivalConfirmedAt))
            append(" | 离公司=").append(fmt(after.confirmedDepartureTime ?: before.confirmedDepartureTime))
            append(" | 到家=").append(fmt(after.homeArrivalTime ?: before.homeArrivalTime))
            append(" | 采样=").append(samplingIntervalMillis / 60_000).append("分钟档")
            append(" | burst=").append(burst)
        }
    }

    private fun fmt(ms: Long?): String =
        if (ms == null || ms <= 0L) "-" else TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
