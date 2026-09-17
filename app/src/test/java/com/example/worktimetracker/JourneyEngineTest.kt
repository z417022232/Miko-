package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.journey.JourneyCandidate
import com.example.worktimetracker.domain.journey.JourneyConfig
import com.example.worktimetracker.domain.journey.JourneyEngine
import com.example.worktimetracker.domain.journey.JourneyEvent
import com.example.worktimetracker.domain.journey.JourneyEventOrder
import com.example.worktimetracker.domain.journey.JourneyObservation
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.JourneyReason
import com.example.worktimetracker.domain.journey.JourneySnapshot
import com.example.worktimetracker.domain.journey.JourneyTransition
import com.example.worktimetracker.domain.journey.MotionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3 第 2 步：`JourneyEngine` 纯 Reducer 的行为测试。
 *
 * 这组用例按**规格里最容易写歪的地方**组织，每条都能单独失败：
 *
 * | 关注点 | 用例 |
 * |---|---|
 * | 纯度 | 同三元组同结果、不修改上一快照 |
 * | 推进规则 | `MAINTAINED` 不创建/不续期/不确认；`UNKNOWN` 与 `STALE` 分离 |
 * | 候选 | 创建 / 续期 / 空窗不累计 / 过期重新起算 / 反向取消 |
 * | 事件时刻 | `occurredAt = firstObservedAt`、`confirmedAt = 本拍 now` |
 * | 同拍多事件 | 公司在→家一拍两事件、顺序合法 |
 * | 临时离岗 | 往返闭环、会话不结束；到家/超时/持续远离才转正式下班 |
 * | 保守处理 | 时间回拨丢弃候选、非法输入按保守侧清洗 |
 */
class JourneyEngineTest {

    // ---------------------------------------------------------------- 1. 纯度与初始状态

    @Test
    fun sameTripleAlwaysYieldsTheSameTransition() {
        val previous = snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK)
        val observation = obs(now = T0 + 60_000L, place = ResolvedPlace.OTHER)

        val first = JourneyEngine.reduce(previous, observation, CONFIG)
        val second = JourneyEngine.reduce(previous, observation, CONFIG)

        assertEquals(first, second)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals(first.confirmedEvents, second.confirmedEvents)
        assertEquals(first.reasonCodes, second.reasonCodes)
        assertEquals(first.explanation, second.explanation)
    }

    @Test
    fun reduceDoesNotMutateThePreviousSnapshot() {
        val previous = snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME)
        JourneyEngine.reduce(previous, obs(now = T0 + 60_000L, place = ResolvedPlace.MOVING), CONFIG)
        assertEquals("纯函数不得改动输入快照", snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME), previous)
    }

    @Test
    fun firstFixOnlyEstablishesStateWithoutEvents() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.UNKNOWN),
            obs(now = T0, place = ResolvedPlace.HOME),
            CONFIG
        )

        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.phase)
        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.lastConfirmedPhase)
        assertTrue("没有起点就补不出变迁，不许凭空发事件", transition.confirmedEvents.isEmpty())
    }

    @Test
    fun confirmedHomeKeepsAtHomeAndReportsNoChange() {
        val previous = snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME)
        val transition = JourneyEngine.reduce(previous, obs(now = T0 + 60_000L, place = ResolvedPlace.HOME), CONFIG)

        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.phase)
        assertNull(transition.snapshot.candidate)
        assertTrue(transition.reasonCodes.contains(JourneyReason.NO_CHANGE))
        assertTrue(transition.explanation.isNotBlank())
    }

    @Test
    fun confirmedOtherPlaceOpensLeavingHomeCandidate() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME),
            obs(now = T0 + 60_000L, place = ResolvedPlace.MOVING),
            CONFIG
        )

        assertEquals(JourneyPhase.LEAVING_HOME, transition.snapshot.phase)
        val candidate = requireNotNull(transition.snapshot.candidate)
        assertEquals(JourneyPhase.COMMUTING_TO_WORK, candidate.targetPhase)
        assertEquals(T0 + 60_000L, candidate.firstObservedAt)
        assertEquals(0L, candidate.accumulatedStableMillis)
        assertTrue(transition.reasonCodes.contains(JourneyReason.CANDIDATE_STARTED))
        assertTrue(transition.reasonCodes.contains(JourneyReason.HYSTERESIS_HELD))
        assertTrue("候选期只更新状态，还没到确认那一刻", transition.confirmedEvents.isEmpty())
    }

    // ---------------------------------------------------------------- 2. MAINTAINED 只维持

    @Test
    fun maintainedDoesNotStartCandidate() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME),
            obs(now = T0 + 60_000L, place = ResolvedPlace.OTHER, decision = FusedDecision.MAINTAINED),
            CONFIG
        )

        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.phase)
        assertNull("MAINTAINED 不许开候选", transition.snapshot.candidate)
        assertTrue(transition.reasonCodes.contains(JourneyReason.PLACE_MAINTAINED_ONLY))
        assertFalse(
            "MAINTAINED 不是确认级证据",
            transition.reasonCodes.contains(JourneyReason.CANDIDATE_STARTED)
        )
    }

    @Test
    fun maintainedDoesNotRenewOrConfirmCandidate() {
        val candidate = candidate(
            target = JourneyPhase.COMMUTING_TO_WORK,
            firstObservedAt = T0,
            lastSupportedAt = T0 + 4 * 60_000L,
            supportCount = 3,
            accumulated = 4 * 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.LEAVING_HOME,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_HOME,
            lastTransitionAt = T0
        )

        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.OTHER, decision = FusedDecision.MAINTAINED),
            CONFIG
        )

        val after = requireNotNull(transition.snapshot.candidate)
        assertEquals("不续期：最近支持时刻不动", T0 + 4 * 60_000L, after.lastSupportedAt)
        assertEquals("不续期：累计稳定时长不加", 4 * 60_000L, after.accumulatedStableMillis)
        assertEquals("不续期：拍数不涨", 3, after.supportCount)
        assertNotNull("支持链被标记为中断", after.lastUnsupportedAt)
        assertEquals(JourneyPhase.LEAVING_HOME, transition.snapshot.phase)
        assertTrue("不许确认事件", transition.confirmedEvents.isEmpty())
    }

    @Test
    fun maintainedDoesNotConfirmEvenWhenThresholdIsAlreadyMet() {
        // 累计已远超门槛（10 分钟 > 5 分钟），但这一拍只有 MAINTAINED —— 仍然不许确认
        val candidate = candidate(
            target = JourneyPhase.COMMUTING_TO_WORK,
            firstObservedAt = T0,
            lastSupportedAt = T0 + 9 * 60_000L,
            supportCount = 4,
            accumulated = 10 * 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.LEAVING_HOME,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_HOME,
            lastTransitionAt = T0
        )

        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 10 * 60_000L, place = ResolvedPlace.MOVING, decision = FusedDecision.MAINTAINED),
            CONFIG
        )

        assertTrue("MAINTAINED 即使累计达标也不确认", transition.confirmedEvents.isEmpty())
        assertEquals(JourneyPhase.LEAVING_HOME, transition.snapshot.phase)
    }

    // ---------------------------------------------------------------- 3. UNKNOWN 与 STALE 分离

    @Test
    fun unknownDecisionHoldsWithoutAdvancing() {
        val previous = snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK)
        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 60_000L, place = ResolvedPlace.UNKNOWN, decision = FusedDecision.UNKNOWN),
            CONFIG
        )

        assertEquals(JourneyPhase.AT_WORK, transition.snapshot.phase)
        assertTrue(transition.reasonCodes.contains(JourneyReason.PLACE_UNKNOWN))
        assertTrue(transition.reasonCodes.contains(JourneyReason.INSUFFICIENT_EVIDENCE))
    }

    @Test
    fun noFixBeyondThresholdEntersStaleNotUnknown() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0 + 60_000L, place = ResolvedPlace.COMPANY, secondsSinceFix = 21 * 60L),
            CONFIG
        )

        assertEquals(JourneyPhase.STALE, transition.snapshot.phase)
        assertTrue(transition.reasonCodes.contains(JourneyReason.STALE_NO_FIX))
        assertEquals(
            "断流要记住从哪断的，恢复才有得接",
            JourneyPhase.AT_WORK,
            transition.snapshot.lastConfirmedPhase
        )
    }

    @Test
    fun unknownAndStaleAreDistinctPhases() {
        val stale = JourneyEngine.reduce(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0 + 60_000L, place = ResolvedPlace.COMPANY, secondsSinceFix = 21 * 60L),
            CONFIG
        )
        val unknown = JourneyEngine.reduce(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0 + 60_000L, place = ResolvedPlace.UNKNOWN, decision = FusedDecision.UNKNOWN),
            CONFIG
        )

        assertEquals(JourneyPhase.STALE, stale.snapshot.phase)
        assertEquals(JourneyPhase.AT_WORK, unknown.snapshot.phase)
        assertTrue(
            "两者原因码必须分开，否则诊断页分不清是数据缺失还是算法失灵",
            stale.reasonCodes.contains(JourneyReason.STALE_NO_FIX) &&
                unknown.reasonCodes.contains(JourneyReason.INSUFFICIENT_EVIDENCE)
        )
    }

    @Test
    fun staleKeepsCandidateBecauseOutageIsNotDepartureEvidence() {
        val candidate = candidate(
            target = JourneyPhase.TEMP_LEAVE,
            firstObservedAt = T0,
            lastSupportedAt = T0 + 60_000L,
            accumulated = 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.LEAVING_WORK,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_WORK,
            lastTransitionAt = T0
        )

        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.OTHER, secondsSinceFix = 25 * 60L),
            CONFIG
        )

        assertEquals(JourneyPhase.STALE, transition.snapshot.phase)
        assertNotNull("断流只清支持链、不清候选", transition.snapshot.candidate)
        assertNotNull(transition.snapshot.candidate?.lastUnsupportedAt)
    }

    // ---------------------------------------------------------------- 4. STALE 恢复

    @Test
    fun staleRecoveryReturnsToLastConfirmedPhaseAndNeverToCandidatePhase() {
        // 刻意塞一个"脏"的 lastConfirmedPhase（候选态）进来：恢复必须归一到已确认态
        val previous = JourneySnapshot(
            phase = JourneyPhase.STALE,
            candidate = null,
            lastConfirmedPhase = JourneyPhase.LEAVING_WORK,
            lastTransitionAt = T0
        )

        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 25 * 60_000L, place = ResolvedPlace.COMPANY, secondsSinceFix = 5L),
            CONFIG
        )

        assertEquals(JourneyPhase.AT_WORK, transition.snapshot.phase)
    }

    @Test
    fun staleRecoveryKeepsLiveCandidateSoDepartureTimeIsNotPushedLater() {
        val candidate = candidate(
            target = JourneyPhase.TEMP_LEAVE,
            firstObservedAt = T0,
            lastSupportedAt = T0 + 60_000L,
            accumulated = 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.STALE,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_WORK,
            lastTransitionAt = T0
        )

        val recovered = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 25 * 60_000L, place = ResolvedPlace.COMPANY, secondsSinceFix = 5L),
            CONFIG
        )
        assertEquals(JourneyPhase.LEAVING_WORK, recovered.snapshot.phase)
        assertEquals("候选的 earliest 证据必须活过断流", T0, recovered.snapshot.candidate?.firstObservedAt)

        val home = JourneyEngine.reduce(
            recovered.snapshot,
            obs(now = T0 + 26 * 60_000L, place = ResolvedPlace.HOME),
            CONFIG
        )
        val departure = home.confirmedEvents.filterIsInstance<JourneyEvent.CompanyDeparture>().singleOrNull()
        assertNotNull("到家必须确认正式下班", departure)
        assertEquals(
            "断流不该把离岗时刻推晚（v7.0 修的就是这一条）",
            T0,
            departure?.occurredAt
        )
    }

    @Test
    fun staleRecoveryWithoutHistoryAdoptsPlaceWithoutEvents() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.STALE),
            obs(now = T0 + 25 * 60_000L, place = ResolvedPlace.HOME, secondsSinceFix = 5L),
            CONFIG
        )

        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.phase)
        assertTrue(transition.confirmedEvents.isEmpty())
    }

    // ---------------------------------------------------------------- 5. 候选：创建 / 续期 / 空窗 / 过期 / 反向取消

    @Test
    fun candidateAccumulatesOnlyContinuouslySupportedTime() {
        val transitions = drive(
            snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME),
            obs(now = T0, place = ResolvedPlace.OTHER),
            obs(now = T0 + 60_000L, place = ResolvedPlace.OTHER),
            obs(now = T0 + 120_000L, place = ResolvedPlace.MOVING)
        )

        val last = transitions.last().snapshot.candidate
        assertNotNull(last)
        assertEquals("两拍间隔合计 2 分钟", 120_000L, last?.accumulatedStableMillis)
        assertEquals(3, last?.supportCount)
        assertEquals(T0, last?.firstObservedAt)
        assertEquals(T0 + 120_000L, last?.lastSupportedAt)
    }

    @Test
    fun gapBetweenSupportsIsNotCountedAsStableTime() {
        val transitions = drive(
            snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME),
            obs(now = T0, place = ResolvedPlace.OTHER),                                              // 开候选
            obs(now = T0 + 60_000L, place = ResolvedPlace.OTHER),                                    // 累计 60s
            obs(now = T0 + 120_000L, place = ResolvedPlace.UNKNOWN, decision = FusedDecision.UNKNOWN), // 空窗
            obs(now = T0 + 180_000L, place = ResolvedPlace.OTHER),                                   // 重新支持：不加空窗
            obs(now = T0 + 240_000L, place = ResolvedPlace.OTHER)                                    // 再支持 60s
        )

        val afterGap = transitions[3].snapshot.candidate!!
        assertEquals("空窗那一拍只把支持链接回去，不加时长", 60_000L, afterGap.accumulatedStableMillis)
        val last = transitions[4].snapshot.candidate!!
        assertEquals("空窗的 60 秒不许混进稳定时长（否则断流能泡到门槛）", 120_000L, last.accumulatedStableMillis)
        assertEquals(T0, last.firstObservedAt)
    }

    @Test
    fun candidateExpiresAndNextObservationStartsFreshFirstObservedAt() {
        val candidate = candidate(
            target = JourneyPhase.COMMUTING_TO_WORK,
            firstObservedAt = T0,
            lastSupportedAt = T0,
            accumulated = 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.LEAVING_HOME,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_HOME,
            lastTransitionAt = T0
        )

        val expired = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 2 * 60 * 60_000L + 60_000L, place = ResolvedPlace.OTHER),
            CONFIG
        )
        assertTrue(expired.reasonCodes.contains(JourneyReason.CANDIDATE_EXPIRED))
        // 过期回到基准态「在家」后，同一拍的 OTHER 证据又开了新候选 —— 所以终态是候选态
        assertEquals(JourneyPhase.LEAVING_HOME, expired.snapshot.phase)
        assertEquals(JourneyPhase.COMMUTING_TO_WORK, expired.snapshot.candidate?.targetPhase)
        assertEquals(
            "过期的候选作废后，新的观察必须重新起算最早证据时刻",
            T0 + 2 * 60 * 60_000L + 60_000L,
            expired.snapshot.candidate?.firstObservedAt
        )
    }

    @Test
    fun reverseEvidenceCancelsCandidateAndReturnsToBase() {
        val candidate = candidate(
            target = JourneyPhase.TEMP_LEAVE,
            firstObservedAt = T0,
            lastSupportedAt = T0 + 60_000L,
            accumulated = 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.LEAVING_WORK,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_WORK,
            lastTransitionAt = T0
        )

        val transition = JourneyEngine.reduce(previous, obs(now = T0 + 120_000L, place = ResolvedPlace.COMPANY), CONFIG)

        assertEquals(JourneyPhase.AT_WORK, transition.snapshot.phase)
        assertNull(transition.snapshot.candidate)
        assertTrue(transition.reasonCodes.contains(JourneyReason.CANDIDATE_REVERSED))
        assertTrue("反向取消不产生事件", transition.confirmedEvents.isEmpty())
    }

    // ---------------------------------------------------------------- 6. 事件时刻

    @Test
    fun homeToWorkChainUsesFirstObservedAtAsOccurredAtAndNowAsConfirmedAt() {
        val transitions = drive(
            snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME),
            obs(now = T0, place = ResolvedPlace.MOVING),                 // 开离家候选
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.MOVING),   // 达标 → HomeDeparture
            obs(now = T0 + 6 * 60_000L, place = ResolvedPlace.COMPANY),  // 开到岗候选
            obs(now = T0 + 9 * 60_000L, place = ResolvedPlace.COMPANY)   // 达标 → CompanyArrival
        )

        val departure = transitions[1].confirmedEvents.filterIsInstance<JourneyEvent.HomeDeparture>().single()
        assertEquals("事件正式时刻 = 候选最早证据", T0, departure.occurredAt)
        assertEquals("confirmedAt = 命中门槛那一拍", T0 + 5 * 60_000L, departure.confirmedAt)
        assertEquals(JourneyPhase.COMMUTING_TO_WORK, transitions[1].snapshot.phase)

        val arrival = transitions[3].confirmedEvents.filterIsInstance<JourneyEvent.CompanyArrival>().single()
        assertEquals("到岗正式时刻取到岗候选的 firstObservedAt，不是确认时刻", T0 + 6 * 60_000L, arrival.occurredAt)
        assertEquals(T0 + 9 * 60_000L, arrival.confirmedAt)
        assertEquals(JourneyPhase.AT_WORK, transitions[3].snapshot.phase)
    }

    @Test
    fun arrivingHomeRequiresSustainedEvidence() {
        val transitions = drive(
            snap(JourneyPhase.COMMUTING_HOME, lastConfirmed = JourneyPhase.COMMUTING_HOME),
            obs(now = T0, place = ResolvedPlace.HOME),
            obs(now = T0 + 60_000L, place = ResolvedPlace.HOME),
            obs(now = T0 + 3 * 60_000L, place = ResolvedPlace.HOME)
        )

        assertTrue("第一拍只开候选", transitions[0].confirmedEvents.isEmpty())
        assertEquals(JourneyPhase.ARRIVING_HOME, transitions[0].snapshot.phase)
        val arrival = transitions[2].confirmedEvents.filterIsInstance<JourneyEvent.HomeArrival>().single()
        assertEquals(T0, arrival.occurredAt)
        assertEquals(T0 + 3 * 60_000L, arrival.confirmedAt)
        assertEquals(JourneyPhase.AT_HOME, transitions[2].snapshot.phase)
    }

    // ---------------------------------------------------------------- 7. 同拍多事件

    @Test
    fun companyStraightToHomeEmitsDepartureThenArrivalInOrder() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0 + 60_000L, place = ResolvedPlace.HOME),
            CONFIG
        )

        val events = transition.confirmedEvents
        assertEquals("公司在 → 下一拍在家：一拍要补两个事件", 2, events.size)
        assertTrue(events[0] is JourneyEvent.CompanyDeparture)
        assertTrue(events[1] is JourneyEvent.HomeArrival)
        assertTrue("事件列表必须通过排序校验", JourneyEventOrder.isValid(events))
        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.phase)
    }

    @Test
    fun aFullWorkingDayProducesAnOrderedEventSequence() {
        val transitions = drive(
            snap(JourneyPhase.UNKNOWN),
            obs(now = T0, place = ResolvedPlace.HOME),                                                   // AT_HOME
            obs(now = T0 + 60_000L, place = ResolvedPlace.MOVING),                                       // 离家候选
            obs(now = T0 + 6 * 60_000L, place = ResolvedPlace.MOVING),                                   // HomeDeparture
            obs(now = T0 + 7 * 60_000L, place = ResolvedPlace.COMPANY),                                  // 到岗候选
            obs(now = T0 + 10 * 60_000L, place = ResolvedPlace.COMPANY),                                 // CompanyArrival
            obs(now = T0 + 11 * 60_000L, place = ResolvedPlace.MOVING, motion = MotionPhase.MOVING),      // 离岗候选
            obs(now = T0 + 16 * 60_000L, place = ResolvedPlace.MOVING, motion = MotionPhase.MOVING),      // CompanyDeparture
            obs(now = T0 + 17 * 60_000L, place = ResolvedPlace.HOME),                                    // 归宅候选
            obs(now = T0 + 20 * 60_000L, place = ResolvedPlace.HOME)                                     // HomeArrival
        )

        val events = transitions.flatMap { it.confirmedEvents }
        assertEquals(
            listOf(
                "HomeDeparture",
                "CompanyArrival",
                "CompanyDeparture",
                "HomeArrival"
            ),
            events.map { it::class.simpleName }
        )
        assertEquals(JourneyPhase.AT_HOME, transitions.last().snapshot.phase)
        assertTrue("整串事件时刻必须升序", JourneyEventOrder.isValid(events))
        transitions.forEach {
            assertTrue("每一拍的事件列表都要过排序校验：${it.explanation}", JourneyEventOrder.isValid(it.confirmedEvents))
        }
    }

    // ---------------------------------------------------------------- 8. 临时离岗 vs 正式下班

    @Test
    fun temporaryLeaveRoundTripEmitsStartAndEndAndKeepsSession() {
        val transitions = drive(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0, place = ResolvedPlace.MOVING, session = true),                     // 离岗候选
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.MOVING, session = true),       // TempLeaveStart
            obs(now = T0 + 6 * 60_000L, place = ResolvedPlace.COMPANY, session = true),      // 回司候选
            obs(now = T0 + 9 * 60_000L, place = ResolvedPlace.COMPANY, session = true)       // TempLeaveEnd
        )

        assertEquals(JourneyPhase.TEMP_LEAVE, transitions[1].snapshot.phase)
        val start = transitions[1].confirmedEvents.filterIsInstance<JourneyEvent.TempLeaveStart>().single()
        assertEquals(T0, start.occurredAt)

        val end = transitions[3].confirmedEvents.filterIsInstance<JourneyEvent.TempLeaveEnd>().single()
        assertEquals("回司候选的 firstObservedAt", T0 + 6 * 60_000L, end.occurredAt)
        assertEquals(JourneyPhase.AT_WORK, transitions[3].snapshot.phase)

        val all = transitions.flatMap { it.confirmedEvents }
        assertTrue(
            "临时离岗回司不得结束工作会话（不许出现 CompanyDeparture）",
            all.none { it is JourneyEvent.CompanyDeparture }
        )
        assertTrue(JourneyEventOrder.isValid(all))
    }

    @Test
    fun tempLeaveTimeoutConfirmsFormalDeparture() {
        val previous = JourneySnapshot(
            phase = JourneyPhase.TEMP_LEAVE,
            candidate = null,
            lastConfirmedPhase = JourneyPhase.TEMP_LEAVE,
            lastTransitionAt = T0
        )
        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 46 * 60_000L, place = ResolvedPlace.MOVING, session = true),
            CONFIG
        )

        val departure = transition.confirmedEvents.filterIsInstance<JourneyEvent.CompanyDeparture>().single()
        assertEquals("离开超过上限仍未归：正式下班", T0, departure.occurredAt)
        assertEquals(JourneyPhase.COMMUTING_HOME, transition.snapshot.phase)
        assertTrue(transition.reasonCodes.contains(JourneyReason.TEMP_LEAVE_TIMEOUT))
    }

    @Test
    fun continuousMovingAwayConfirmsDepartureInsteadOfTempLeave() {
        val transitions = drive(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0, place = ResolvedPlace.MOVING, motion = MotionPhase.MOVING, session = true),
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.MOVING, motion = MotionPhase.MOVING, session = true)
        )

        val departure = transitions.last().confirmedEvents.filterIsInstance<JourneyEvent.CompanyDeparture>().single()
        assertEquals(T0, departure.occurredAt)
        assertEquals(JourneyPhase.COMMUTING_HOME, transitions.last().snapshot.phase)
        assertTrue(
            "持续远离：确认正式下班而不是临时离岗",
            transitions.flatMap { it.confirmedEvents }.none { it is JourneyEvent.TempLeaveStart }
        )
    }

    @Test
    fun expiredMotionJudgementCannotProveMovingAway() {
        // 融合层说在移动，但运动判定已经过期 —— 不许据此判"持续远离"
        val transitions = drive(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0, place = ResolvedPlace.MOVING, motion = MotionPhase.MOVING, session = true),
            obs(
                now = T0 + 5 * 60_000L,
                place = ResolvedPlace.MOVING,
                motion = MotionPhase.MOVING,
                motionObservedAt = T0 - 10 * 60_000L,
                session = true
            )
        )

        assertTrue(transitions.last().reasonCodes.contains(JourneyReason.MOTION_EXPIRED))
        assertTrue(
            "运动判定过期后改判临时离岗，不许确认正式下班",
            transitions.flatMap { it.confirmedEvents }.none { it is JourneyEvent.CompanyDeparture }
        )
        assertEquals(JourneyPhase.TEMP_LEAVE, transitions.last().snapshot.phase)
    }

    @Test
    fun departureIsConfirmedByPathAndEvidenceNotBySessionState() {
        // 会话已经结束（hasActiveWorkSession=false）：到家证据 + 路径仍然要确认正式下班，
        // 反过来，会话是否存在**不单独**决定下班（§5.2.4 二轮 P1-1）
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0 + 60_000L, place = ResolvedPlace.HOME, session = false),
            CONFIG
        )

        assertTrue(
            "正式下班靠路径+到家证据，不靠会话是否结束",
            transition.confirmedEvents.any { it is JourneyEvent.CompanyDeparture }
        )
    }

    @Test
    fun activeSessionDecidesOtherStopVersusAway() {
        val withSession = drive(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0, place = ResolvedPlace.OTHER, session = true),
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.OTHER, session = true)
        ).last()
        assertEquals("工作期间在第三方地点停留 = 外勤停留", JourneyPhase.OTHER_STOP, withSession.snapshot.phase)

        val withoutSession = drive(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            obs(now = T0, place = ResolvedPlace.OTHER, session = false),
            obs(now = T0 + 5 * 60_000L, place = ResolvedPlace.OTHER, session = false)
        ).last()
        assertEquals("无活动会话时在别处 = 外出", JourneyPhase.AWAY, withoutSession.snapshot.phase)
    }

    @Test
    fun returningFromOtherStopDoesNotCreateASecondArrival() {
        val previous = JourneySnapshot(
            phase = JourneyPhase.OTHER_STOP,
            candidate = null,
            lastConfirmedPhase = JourneyPhase.OTHER_STOP,
            lastTransitionAt = T0
        )
        val transitions = drive(
            previous,
            obs(now = T0 + 60_000L, place = ResolvedPlace.COMPANY, session = true),
            obs(now = T0 + 4 * 60_000L, place = ResolvedPlace.COMPANY, session = true)
        )

        assertEquals(JourneyPhase.AT_WORK, transitions.last().snapshot.phase)
        assertTrue(
            "回司不算新一次到岗（压根没确认过离岗）",
            transitions.flatMap { it.confirmedEvents }.none { it is JourneyEvent.CompanyArrival }
        )
    }

    @Test
    fun arrivingHomeAlwaysWinsOverTempLeave() {
        val previous = JourneySnapshot(
            phase = JourneyPhase.TEMP_LEAVE,
            candidate = null,
            lastConfirmedPhase = JourneyPhase.TEMP_LEAVE,
            lastTransitionAt = T0
        )
        val transition = JourneyEngine.reduce(
            previous,
            obs(now = T0 + 10 * 60_000L, place = ResolvedPlace.HOME, session = true),
            CONFIG
        )

        assertEquals(JourneyPhase.AT_HOME, transition.snapshot.phase)
        assertEquals(2, transition.confirmedEvents.size)
        assertTrue(transition.confirmedEvents[0] is JourneyEvent.CompanyDeparture)
        assertTrue(transition.confirmedEvents[1] is JourneyEvent.HomeArrival)
    }

    // ---------------------------------------------------------------- 9. 迟滞：阈值附近不抖动

    @Test
    fun noJitterAroundTheConfirmationThreshold() {
        val transitions = drive(
            snap(JourneyPhase.AT_WORK, lastConfirmed = JourneyPhase.AT_WORK),
            *(0 until 10).map { i ->
                val place = if (i % 2 == 0) ResolvedPlace.OTHER else ResolvedPlace.COMPANY
                obs(now = T0 + i * 60_000L, place = place, session = true)
            }.toTypedArray()
        )

        assertTrue(
            "抖动输入一拍事件都不许确认",
            transitions.flatMap { it.confirmedEvents }.isEmpty()
        )
        assertTrue(
            "已确认态不许来回跳（只许在在岗与离岗候选期之间）",
            transitions.map { it.snapshot.phase }.all {
                it == JourneyPhase.AT_WORK || it == JourneyPhase.LEAVING_WORK
            }
        )
    }

    // ---------------------------------------------------------------- 10. 时间回拨与非法输入

    @Test
    fun clockRollbackDropsCandidateAndFallsBackToSafeState() {
        val candidate = candidate(
            target = JourneyPhase.TEMP_LEAVE,
            firstObservedAt = T0,
            lastSupportedAt = T0 + 5 * 60_000L,
            accumulated = 5 * 60_000L
        )
        val previous = JourneySnapshot(
            phase = JourneyPhase.LEAVING_WORK,
            candidate = candidate,
            lastConfirmedPhase = JourneyPhase.AT_WORK,
            lastTransitionAt = T0
        )

        // 现在比候选的"最近支持时刻"还早 ⇒ 时间回拨
        val transition = JourneyEngine.reduce(previous, obs(now = T0 + 60_000L, place = ResolvedPlace.OTHER), CONFIG)

        assertEquals(JourneyPhase.UNKNOWN, transition.snapshot.phase)
        assertNull("候选的时间轴不可信，必须丢弃", transition.snapshot.candidate)
        assertEquals(
            "已确认状态没有时间轴问题，要保留（§5.6 规则 2）",
            JourneyPhase.AT_WORK,
            transition.snapshot.lastConfirmedPhase
        )
        assertTrue(transition.reasonCodes.contains(JourneyReason.CLOCK_ROLLED_BACK))
        assertTrue(transition.confirmedEvents.isEmpty())
    }

    @Test
    fun invalidInputsAreClampedToTheConservativeSide() {
        val transition = JourneyEngine.reduce(
            snap(JourneyPhase.AT_HOME, lastConfirmed = JourneyPhase.AT_HOME),
            obs(
                now = T0,
                place = ResolvedPlace.OTHER,
                confidence = Double.NaN,
                secondsSinceFix = -5L,
                distanceHome = -1.0
            ),
            CONFIG
        )

        assertTrue(transition.reasonCodes.contains(JourneyReason.INVALID_INPUT))
        val candidate = requireNotNull(transition.snapshot.candidate)
        assertEquals("NaN 置信按最保守的 0 处理，绝不猜一个数", 0.0, candidate.confidence, 0.0)
        assertEquals(JourneyPhase.LEAVING_HOME, transition.snapshot.phase)
    }

    @Test
    fun everyTransitionExplainsItself() {
        val transitions = drive(
            snap(JourneyPhase.UNKNOWN),
            obs(now = T0, place = ResolvedPlace.HOME),
            obs(now = T0 + 60_000L, place = ResolvedPlace.MOVING),
            obs(now = T0 + 6 * 60_000L, place = ResolvedPlace.MOVING),
            obs(now = T0 + 7 * 60_000L, place = ResolvedPlace.COMPANY),
            obs(now = T0 + 10 * 60_000L, place = ResolvedPlace.COMPANY)
        )

        transitions.forEach {
            assertTrue("每一拍都必须能说出依据：${it.reasonCodes}", it.explanation.isNotBlank())
            assertTrue("原因码不许为空", it.reasonCodes.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun drive(start: JourneySnapshot, vararg observations: JourneyObservation): List<JourneyTransition> {
        val result = ArrayList<JourneyTransition>()
        var current = start
        for (observation in observations) {
            val transition = JourneyEngine.reduce(current, observation, CONFIG)
            result += transition
            current = transition.snapshot
        }
        return result
    }

    private fun snap(
        phase: JourneyPhase,
        candidate: JourneyCandidate? = null,
        lastConfirmed: JourneyPhase? = null,
        lastTransitionAt: Long = T0
    ) = JourneySnapshot(
        phase = phase,
        candidate = candidate,
        lastConfirmedPhase = lastConfirmed,
        lastTransitionAt = lastTransitionAt
    )

    private fun candidate(
        target: JourneyPhase,
        firstObservedAt: Long,
        lastSupportedAt: Long = firstObservedAt,
        supportCount: Int = 1,
        accumulated: Long = 0L,
        lastUnsupportedAt: Long? = null
    ) = JourneyCandidate(
        targetPhase = target,
        firstObservedAt = firstObservedAt,
        lastSupportedAt = lastSupportedAt,
        supportCount = supportCount,
        accumulatedStableMillis = accumulated,
        evidenceSources = setOf(EvidenceSource.GNSS),
        strongestDecision = FusedDecision.CONFIRMED,
        confidence = 0.9,
        lastUnsupportedAt = lastUnsupportedAt
    )

    @Suppress("LongParameterList")
    private fun obs(
        now: Long,
        place: ResolvedPlace,
        decision: FusedDecision = FusedDecision.CONFIRMED,
        confidence: Double = 0.9,
        motion: MotionPhase = MotionPhase.STATIONARY,
        motionObservedAt: Long? = now,
        secondsSinceFix: Long = 10L,
        session: Boolean = false,
        distanceHome: Double? = null,
        distanceWork: Double? = null
    ) = JourneyObservation(
        now = now,
        place = place,
        placeDecision = decision,
        confidence = confidence,
        evidenceSources = setOf(EvidenceSource.GNSS, EvidenceSource.WIFI),
        motion = motion,
        motionObservedAt = motionObservedAt,
        secondsSinceFix = secondsSinceFix,
        hasActiveWorkSession = session,
        distanceToHomeMeters = distanceHome,
        distanceToWorkMeters = distanceWork
    )

    private companion object {
        /** 2026-09-17 08:00 左右的一个固定 epoch 基准（只用相对时长，绝对值无意义）。 */
        const val T0: Long = 1_784_000_000_000L

        val CONFIG = JourneyConfig(
            staleAfterSeconds = 20 * 60L,
            arrivalRequiredMillis = 3 * 60_000L,
            departureRequiredMillis = 5 * 60_000L,
            candidateExpiryMillis = 2 * 60 * 60_000L,
            tempLeaveMaxMillis = 45 * 60_000L,
            motionExpirySeconds = 120L
        )
    }
}
