package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.journey.JourneyEvent
import com.example.worktimetracker.domain.journey.JourneyTransition

/**
 * 将新行程引擎的已确认事件投影到既有 work_state 结构。
 * 候选阶段不写正式状态；只有 confirmedEvents 能改变正式会话。
 */
object JourneyAuthorityAdapter {
    fun apply(
        previous: WorkStateEntity,
        transition: JourneyTransition,
        newSessionId: String,
        now: Long
    ): WorkStateEntity {
        var next = previous
        transition.confirmedEvents.forEach { event ->
            next = when (event) {
                is JourneyEvent.HomeDeparture -> next.copy(
                    currentState = "LEAVING_HOME",
                    sessionId = next.sessionId ?: newSessionId,
                    homeDepartureTime = event.occurredAt,
                    candidateHomeDepartureTime = event.occurredAt,
                    // 新一轮通勤开始：上一班的到家痕迹必须清掉。留着会跨班次残留，
                    // 被下一班的「离岗计时确认」当成到家证据（实测 09-20 因此被判成已到家）。
                    homeArrivalTime = null,
                    candidateHomeArrivalTime = null,
                    confirmedDepartureTime = null,
                    tempLeaveStart = null,
                    updatedAt = now
                )
                is JourneyEvent.CompanyArrival -> next.copy(
                    currentState = "WORKING",
                    sessionId = next.sessionId ?: newSessionId,
                    sessionStart = event.occurredAt,
                    candidateCompanyArrivalTime = event.occurredAt,
                    companyArrivalConfirmedAt = event.confirmedAt,
                    candidateCompanyDepartureTime = null,
                    candidateHomeArrivalTime = null,
                    tempLeaveStart = null,
                    updatedAt = now
                )
                is JourneyEvent.TempLeaveStart -> next.copy(
                    currentState = "TEMP_LEAVE",
                    tempLeaveStart = event.occurredAt,
                    candidateCompanyDepartureTime = event.occurredAt,
                    updatedAt = now
                )
                is JourneyEvent.TempLeaveEnd -> next.copy(
                    currentState = "WORKING",
                    tempLeaveStart = null,
                    candidateCompanyDepartureTime = null,
                    updatedAt = now
                )
                is JourneyEvent.CompanyDeparture -> next.copy(
                    currentState = "FINISHED",
                    confirmedDepartureTime = event.occurredAt,
                    candidateCompanyDepartureTime = event.occurredAt,
                    companyDepartureConfirmedAt = event.confirmedAt,
                    updatedAt = now
                )
                is JourneyEvent.HomeArrival -> next.copy(
                    currentState = "REST",
                    homeArrivalTime = event.occurredAt,
                    candidateHomeArrivalTime = event.occurredAt,
                    homeArrivalConfirmedAt = event.confirmedAt,
                    sessionStart = null,
                    tempLeaveStart = null,
                    updatedAt = now
                )
            }
        }
        return next
    }
}
