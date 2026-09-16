package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.model.LocationType
import java.util.UUID

/**
 * 工时事件状态机：唯一负责事件顺序（在家 → 离家 → 到公司 → 工作中 → 离公司 → 到家）
 * 与候选确认的组件。多源证据融合结果转换为 Fix 后进入本引擎。
 */
class TrajectoryAnchorEngine(
    private val continuity: EvidenceContinuityPolicy = EvidenceContinuityPolicy()
) {
    data class Config(
        val companyRadiusMeters: Int,
        val homeRadiusMeters: Int,
        val companyStableRadiusMeters: Int,
        val homeStableRadiusMeters: Int,
        val leaveConfirmMinutes: Int
    )

    data class Fix(
        val time: Long,
        val type: LocationType,
        val accuracyMeters: Float,
        val provider: String,
        val companyDistanceMeters: Double?,
        val companyAnchorDistanceMeters: Double?,
        val homeDistanceMeters: Double?,
        val homeAnchorDistanceMeters: Double?,
        val speedMetersPerSecond: Float,
        val movingAway: Boolean,
        /**
         * 可靠绝对定位（融合层判定为 CONFIRMED_GNSS）时为 true。
         * 该证据在融合层已经过「质量 >= 0.80 的 GNSS 直接确认」这道门槛，
         * 状态机不再要求连续两次 —— 否则一次定位回调空窗就能把已经成立的到岗时刻拖后。
         */
        val strongEvidence: Boolean = false
    )

    sealed class Event(open val occurredAt: Long, open val confirmedAt: Long) {
        data class HomeDeparture(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
        data class CompanyArrival(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
        data class CompanyDeparture(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
        data class HomeArrival(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
    }

    data class Decision(val nextState: WorkStateEntity, val events: List<Event>)

    fun next(previous: WorkStateEntity, fix: Fix, config: Config): Decision {
        if (fix.accuracyMeters > 100f || fix.type == LocationType.UNKNOWN) {
            return Decision(previous.copy(lastLocationTime = fix.time, updatedAt = fix.time), emptyList())
        }
        val companyStable = fix.companyAnchorDistanceMeters?.let { it <= config.companyStableRadiusMeters } == true
        val homeStable = fix.homeAnchorDistanceMeters?.let { it <= config.homeStableRadiusMeters } == true
        val continuous = continuity.isContinuous(previous.lastLocationTime, fix.time)
        val events = mutableListOf<Event>()
        val next = when (previous.currentState) {
            "REST" -> when {
                homeStable -> previous.copy(sessionStart = null)
                fix.movingAway || fix.type == LocationType.OTHER -> newSession(previous, fix.time).also {
                    events += Event.HomeDeparture(fix.time, fix.time)
                }
                companyStable -> newSession(previous, fix.time).copy(
                    currentState = "NEAR_COMPANY", candidateCompanyArrivalTime = fix.time, stableCompanyCount = 1
                )
                else -> previous
            }
            "LEAVING_HOME", "NEAR_COMPANY" -> if (companyStable) {
                // 连续性中断（超过 20 分钟回调空窗）时旧计数失效，但「已知最早在公司」的候选时刻仍要保留：
                // 断流本身不构成离开证据，丢掉候选等于让这段回调空窗白白推迟到岗时刻。
                // 只有候选已过期（超过 CANDIDATE_EXPIRE_MILLIS）才认为中间真的离开过公司。
                val priorCount = if (continuous) previous.stableCompanyCount else 0
                val candidate = previous.candidateCompanyArrivalTime
                    ?.takeIf { fix.time - it <= CANDIDATE_EXPIRE_MILLIS }
                    ?: fix.time
                val count = priorCount + 1
                // 可靠绝对定位（CONFIRMED_GNSS）单次即确认；环境证据（AMBIENT）仍需连续两次
                val required = if (fix.strongEvidence) 1 else COMPANY_ARRIVAL_CONFIRM_COUNT
                if (count >= required) {
                    events += Event.CompanyArrival(candidate, fix.time)
                    previous.copy(currentState = "WORKING", sessionStart = candidate,
                        candidateCompanyArrivalTime = candidate, companyArrivalConfirmedAt = fix.time,
                        stableCompanyCount = count)
                } else previous.copy(candidateCompanyArrivalTime = candidate, stableCompanyCount = count)
            } else previous.copy(stableCompanyCount = 0)
            "WORKING" -> if (!companyStable && (fix.movingAway || fix.type != LocationType.COMPANY)) {
                previous.copy(currentState = "TEMP_LEAVE",
                    candidateCompanyDepartureTime = previous.candidateCompanyDepartureTime ?: fix.time,
                    movingAwayCount = 1, stableCompanyCount = 0)
            } else previous.copy(stableCompanyCount = if (companyStable) previous.stableCompanyCount + 1 else 0)
            "TEMP_LEAVE" -> updateTemporaryLeave(
                if (continuous) previous else previous.copy(movingAwayCount = 0, stableHomeCount = 0),
                fix, config, companyStable, homeStable, events
            )
            "FINISHED" -> if (homeStable) {
                if (previous.homeArrivalTime == null) {
                    // 晚到家：只补齐同一 sessionId 的到家证据，不创建新会话
                    events += Event.HomeArrival(fix.time, fix.time)
                    previous.copy(currentState = "REST", sessionStart = null,
                        homeArrivalTime = fix.time, homeArrivalConfirmedAt = fix.time)
                } else previous.copy(currentState = "REST", sessionStart = null)
            } else previous
            else -> previous
        }
        return Decision(next.copy(lastLocationTime = fix.time, updatedAt = fix.time), events)
    }

    private fun updateTemporaryLeave(
        previous: WorkStateEntity,
        fix: Fix,
        config: Config,
        companyStable: Boolean,
        homeStable: Boolean,
        events: MutableList<Event>
    ): WorkStateEntity {
        if (companyStable) {
            val count = previous.stableCompanyCount + 1
            return if (count >= 2) previous.copy(currentState = "WORKING",
                candidateCompanyDepartureTime = null, movingAwayCount = 0, stableCompanyCount = count,
                candidateHomeArrivalTime = null, stableHomeCount = 0)
            else previous.copy(stableCompanyCount = count)
        }
        val candidate = previous.candidateCompanyDepartureTime ?: fix.time
        val firstHome = if (homeStable) previous.candidateHomeArrivalTime ?: fix.time else previous.candidateHomeArrivalTime
        // type == HOME 不再作为离开公司的通用移动证据；只有显式移动证据才计数
        val movingCount = previous.movingAwayCount + if (fix.movingAway) 1 else 0
        val homeCount = if (homeStable) previous.stableHomeCount + 1 else 0
        val elapsed = fix.time - candidate
        val farEnough = fix.companyDistanceMeters?.let { it >= config.companyRadiusMeters + 100.0 } == true
        // 强到家证据（到家核心区 + 远离公司）本身足以证明下班到家，立即确认；
        // confirmMinutes 只作为弱证据（移动/远距/多次计数）的兜底等待时间
        val strongHomeArrival = homeStable && farEnough
        val confirmed = strongHomeArrival ||
            (elapsed >= config.leaveConfirmMinutes * 60_000L && (farEnough || movingCount >= 2 || homeCount >= 2))
        if (!confirmed) return previous.copy(candidateCompanyDepartureTime = candidate,
            candidateHomeArrivalTime = firstHome, movingAwayCount = movingCount,
            stableHomeCount = homeCount, stableCompanyCount = 0)
        events += Event.CompanyDeparture(candidate, fix.time)
        // 到家时间必须不早于离岗时间，否则按顺序规则拒绝该到家事件
        val validHome = firstHome?.takeIf { it >= candidate }
        if (validHome != null) events += Event.HomeArrival(validHome, fix.time)
        return previous.copy(currentState = "FINISHED", candidateCompanyDepartureTime = candidate,
            confirmedDepartureTime = candidate, companyDepartureConfirmedAt = fix.time,
            candidateHomeArrivalTime = validHome, homeArrivalTime = validHome,
            homeArrivalConfirmedAt = if (validHome != null) fix.time else null,
            movingAwayCount = movingCount, stableHomeCount = homeCount, stableCompanyCount = 0)
    }

    private fun newSession(previous: WorkStateEntity, now: Long) = previous.copy(
        currentState = "LEAVING_HOME", sessionId = UUID.randomUUID().toString(), sessionStart = null,
        candidateHomeDepartureTime = now, homeDepartureTime = now,
        candidateCompanyArrivalTime = null, candidateCompanyDepartureTime = null,
        candidateHomeArrivalTime = null, companyArrivalConfirmedAt = null,
        companyDepartureConfirmedAt = null, homeArrivalConfirmedAt = null,
        confirmedDepartureTime = null, homeArrivalTime = null, tempLeaveStart = null,
        stableCompanyCount = 0, stableHomeCount = 0, movingAwayCount = 1
    )

    companion object {
        /** 环境证据（AMBIENT）确认到岗所需的连续稳定读数次数。 */
        const val COMPANY_ARRIVAL_CONFIRM_COUNT = 2

        /** 候选到岗时刻的保鲜期：超过它说明断流期间很可能真的离开过公司，候选作废。 */
        const val CANDIDATE_EXPIRE_MILLIS = 2 * 60 * 60_000L
    }
}
