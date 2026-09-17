package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.journey.JourneyConfig

/** 把既有生产常量汇聚成 JourneyEngine 配置的唯一入口；本类不另造门槛。 */
object JourneyRuntimeConfigFactory {
    fun create(settings: UserSettingsEntity): JourneyConfig = JourneyConfig(
        staleAfterSeconds = EvidenceContinuityPolicy.DEFAULT_MAX_GAP_MILLIS / 1_000L,
        // 融合层已确认的绝对定位保持旧机单拍确认语义。
        strongArrivalRequiredMillis = 0L,
        // 环境证据沿用转换期的一分钟取证节奏，至少跨一次连续观察。
        ambientArrivalRequiredMillis = LocationSamplingPolicy.FAST_INTERVAL_MILLIS,
        strongDepartureRequiredMillis = 0L,
        ambientDepartureRequiredMillis = LocationSamplingPolicy.FAST_INTERVAL_MILLIS,
        candidateExpiryMillis = TrajectoryAnchorEngine.CANDIDATE_EXPIRE_MILLIS,
        tempLeaveMaxMillis = settings.leaveCompanyConfirmMinutes.coerceAtLeast(0).toLong() * 60_000L,
        // 运动判定保鲜期与可靠绝对定位TTL同源。
        motionExpirySeconds = EvidenceFusionEngine.GNSS_MAX_AGE_MILLIS / 1_000L
    )
}
