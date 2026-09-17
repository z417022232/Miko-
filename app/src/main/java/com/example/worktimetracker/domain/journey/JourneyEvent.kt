package com.example.worktimetracker.domain.journey

/**
 * 行程事件（阶段 3 产物之一）。
 *
 * **两个时刻的分工是硬约束**（§5.2.3）：
 * - [occurredAt]：候选的 `firstObservedAt`，即**最早**支持该事件的证据时刻 —— **事件正式时刻取它**；
 * - [confirmedAt]：支持链命中门槛的那一拍 `now` —— **只进诊断/延迟统计，绝不进工资**。
 *
 * 为什么不用确认时刻当事件时刻：候选会因断流、重启而推迟确认，
 * 用确认时刻会让"到岗 08:40"记成"到岗 09:12"，**误差直接进工资计算**。
 *
 * [TempLeaveStart] / [TempLeaveEnd] 是「临时离岗与正式下班可区分」这一完成目标的
 * 直接载体（§5.2.4）：离开又回司 → 这两个事件闭环、工作会话继续；
 * 离开到家 → [CompanyDeparture] + [HomeArrival]、会话结束。
 */
sealed class JourneyEvent(
    open val occurredAt: Long,
    open val confirmedAt: Long
) {
    data class HomeDeparture(
        override val occurredAt: Long,
        override val confirmedAt: Long
    ) : JourneyEvent(occurredAt, confirmedAt)

    data class CompanyArrival(
        override val occurredAt: Long,
        override val confirmedAt: Long
    ) : JourneyEvent(occurredAt, confirmedAt)

    data class CompanyDeparture(
        override val occurredAt: Long,
        override val confirmedAt: Long
    ) : JourneyEvent(occurredAt, confirmedAt)

    data class HomeArrival(
        override val occurredAt: Long,
        override val confirmedAt: Long
    ) : JourneyEvent(occurredAt, confirmedAt)

    data class TempLeaveStart(
        override val occurredAt: Long,
        override val confirmedAt: Long
    ) : JourneyEvent(occurredAt, confirmedAt)

    data class TempLeaveEnd(
        override val occurredAt: Long,
        override val confirmedAt: Long
    ) : JourneyEvent(occurredAt, confirmedAt)
}

/** 事件列表的排序违规类型（§5.2.3 三条硬约束）。 */
enum class JourneyEventViolation {
    /** `occurredAt` 倒序：后一个事件早于前一个。 */
    NOT_ASCENDING,

    /** 同一拍出现两个同类型事件。 */
    DUPLICATE_TYPE,

    /** 到家时刻早于离岗时刻（与旧机「到家不得早于离岗」同口径）。 */
    HOME_ARRIVAL_BEFORE_DEPARTURE
}

/**
 * 事件序列校验（纯函数）。
 *
 * 为什么必须支持同拍多事件（二轮 P0-3）：旧机 `TrajectoryAnchorEngine` 同一拍可产多个事件 ——
 * 确认下班的那一拍同时发 `CompanyDeparture` + `HomeArrival`（在公司后定位断流、
 * 下一条可靠定位直接在家时，一拍要把「离岗」和「到家」一起补记）。
 * 单事件结构只能留一个 = **事件漏记**，是对旧机的回归。
 */
object JourneyEventOrder {
    /**
     * 返回全部违规项；空集 = 合法。
     *
     * 校验顺序无关（一次性收集全部违规，便于诊断一次看全）。
     */
    fun violations(events: List<JourneyEvent>): Set<JourneyEventViolation> {
        val found = LinkedHashSet<JourneyEventViolation>()

        for (i in 1 until events.size) {
            if (events[i].occurredAt < events[i - 1].occurredAt) {
                found += JourneyEventViolation.NOT_ASCENDING
            }
        }

        if (events.map { it::class }.distinct().size != events.size) {
            found += JourneyEventViolation.DUPLICATE_TYPE
        }

        val departure = events.filterIsInstance<JourneyEvent.CompanyDeparture>()
            .minByOrNull { it.occurredAt }
        val arrival = events.filterIsInstance<JourneyEvent.HomeArrival>()
            .minByOrNull { it.occurredAt }
        if (departure != null && arrival != null && arrival.occurredAt < departure.occurredAt) {
            found += JourneyEventViolation.HOME_ARRIVAL_BEFORE_DEPARTURE
        }

        return found
    }

    /** 合法（无任何违规）。 */
    fun isValid(events: List<JourneyEvent>): Boolean = violations(events).isEmpty()
}
