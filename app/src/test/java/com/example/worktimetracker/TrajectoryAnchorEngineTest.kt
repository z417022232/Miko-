package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.model.LocationType
import com.example.worktimetracker.location.service.TrajectoryAnchorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryAnchorEngineTest {
    private val engine = TrajectoryAnchorEngine()
    private val config = TrajectoryAnchorEngine.Config(250, 300, 100, 100, 20)

    @Test fun candidateRadiusDoesNotConfirmCompanyArrival() {
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s")
        val decision = engine.next(state, fix(100, LocationType.COMPANY, company=220.0, companyAnchor=180.0), config)
        assertTrue(decision.events.isEmpty())
        assertEquals("LEAVING_HOME", decision.nextState.currentState)
    }

    @Test fun twoStablePointsConfirmFirstAnchorTime() {
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s")
        val first = engine.next(state, fix(100, LocationType.COMPANY, company=80.0, companyAnchor=70.0), config)
        val second = engine.next(first.nextState, fix(160, LocationType.COMPANY, company=70.0, companyAnchor=60.0), config)
        val event = second.events.single() as TrajectoryAnchorEngine.Event.CompanyArrival
        assertEquals(100L, event.occurredAt)
        assertEquals(160L, event.confirmedAt)
    }

    @Test fun edgeReturnDoesNotCancelDepartureButDeepReturnDoes() {
        val working = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 1L)
        val first = engine.next(working, fix(100, LocationType.OTHER, company=281.0, companyAnchor=181.0, moving=true), config)
        val edge = engine.next(first.nextState, fix(160, LocationType.COMPANY, company=211.0, companyAnchor=111.0), config)
        assertEquals(100L, edge.nextState.candidateCompanyDepartureTime)
        val deep1 = engine.next(edge.nextState, fix(220, LocationType.COMPANY, company=70.0, companyAnchor=60.0), config)
        val deep2 = engine.next(deep1.nextState, fix(280, LocationType.COMPANY, company=60.0, companyAnchor=50.0), config)
        assertNull(deep2.nextState.candidateCompanyDepartureTime)
        assertEquals("WORKING", deep2.nextState.currentState)
    }

    @Test fun strongHomeEvidenceConfirmsDepartureImmediately() {
        // 强到家证据（到家核心区 + 远离公司）不等 confirmMinutes，立即确认离岗到家
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 1L,
            candidateCompanyDepartureTime = 100L, movingAwayCount = 1)
        val decision = engine.next(state,
            fix(200, LocationType.HOME, company = 2000.0, companyAnchor = 1900.0, homeAnchor = 40.0), config)
        assertEquals("FINISHED", decision.nextState.currentState)
        assertEquals(100L, decision.nextState.confirmedDepartureTime)
        val home = decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().single()
        assertEquals(200L, home.occurredAt)
        assertEquals(200L, home.confirmedAt)
    }

    @Test fun homeNearCompanyStillWaitsForConfirmWindow() {
        // 家在公司附近（farEnough 不成立）时，单次到家证据不足，仍需计时兜底
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 1L,
            candidateCompanyDepartureTime = 1_000_000L)
        val decision = engine.next(state,
            fix(1_060_000L, LocationType.HOME, company = 100.0, companyAnchor = 150.0, homeAnchor = 20.0), config)
        assertEquals("TEMP_LEAVE", decision.nextState.currentState)
        assertEquals(1_060_000L, decision.nextState.candidateHomeArrivalTime)
        assertTrue(decision.events.isEmpty())
    }

    @Test fun newSessionClearsPreviousSessionFields() {
        val old = WorkStateEntity(currentState = "REST", sessionId = "old", confirmedDepartureTime = 50L,
            homeArrivalTime = 60L, candidateHomeArrivalTime = 60L)
        val next = engine.next(old, fix(500, LocationType.OTHER, homeAnchor=180.0, moving=true), config)
        assertNotEquals("old", next.nextState.sessionId)
        assertNull(next.nextState.confirmedDepartureTime)
        assertNull(next.nextState.homeArrivalTime)
        assertNull(next.nextState.candidateHomeArrivalTime)
    }

    @Test fun homeWhileRestDoesNotStartCommute() {
        val decision = engine.next(
            WorkStateEntity(currentState = "REST"),
            fix(1_000L, LocationType.HOME, homeAnchor = 20.0), config
        )
        assertEquals("REST", decision.nextState.currentState)
        assertTrue(decision.events.isEmpty())
    }

    @Test fun nearCompanyCandidateExpiresAcrossFifteenHourGap() {
        val state = WorkStateEntity(currentState = "NEAR_COMPANY", sessionId = "s",
            candidateCompanyArrivalTime = 100L, stableCompanyCount = 1, lastLocationTime = 100L)
        val decision = engine.next(state,
            fix(54_000_100L, LocationType.COMPANY, company = 70.0, companyAnchor = 60.0), config)
        assertTrue(decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.CompanyArrival>().isEmpty())
        assertEquals(54_000_100L, decision.nextState.candidateCompanyArrivalTime)
        assertEquals(1, decision.nextState.stableCompanyCount)
    }

    @Test fun lateHomeAfterFinishedCompletesExistingSession() {
        val state = WorkStateEntity(currentState = "FINISHED", sessionId = "s", sessionStart = 1_000L,
            confirmedDepartureTime = 2_000L, homeArrivalTime = null)
        val decision = engine.next(state,
            fix(3_000L, LocationType.HOME, company = 2_000.0, companyAnchor = 1_900.0, homeAnchor = 20.0), config)
        assertEquals("REST", decision.nextState.currentState)
        assertEquals(3_000L, decision.nextState.homeArrivalTime)
        assertEquals(3_000L,
            decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().single().occurredAt)
    }

    @Test fun fiveMinuteUserSettingIsUsedExactly() {
        val fiveMinutes = config.copy(leaveConfirmMinutes = 5)
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 100L,
            candidateCompanyDepartureTime = 1_000L, movingAwayCount = 2)
        val before = engine.next(state,
            fix(300_999L, LocationType.OTHER, company = 500.0, companyAnchor = 500.0, moving = true), fiveMinutes)
        val due = engine.next(before.nextState,
            fix(301_000L, LocationType.OTHER, company = 500.0, companyAnchor = 500.0, moving = true), fiveMinutes)
        assertEquals("TEMP_LEAVE", before.nextState.currentState)
        assertEquals(1_000L,
            due.events.filterIsInstance<TrajectoryAnchorEngine.Event.CompanyDeparture>().single().occurredAt)
    }

    @Test fun homeBeforeCompanyDepartureIsRejected() {
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 100L,
            candidateCompanyDepartureTime = 2_000L, candidateHomeArrivalTime = 1_000L, movingAwayCount = 2)
        val decision = engine.next(state,
            fix(1_300_000L, LocationType.HOME, company = 2_000.0, companyAnchor = 1_900.0,
                homeAnchor = 20.0, moving = true), config)
        assertTrue(decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().isEmpty())
    }

    @Test fun environmentDisappearanceAloneDoesNotLeaveCompany() {
        val state = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 100L)
        val decision = engine.next(state, fix(1_000L, LocationType.UNKNOWN), config)
        assertEquals("WORKING", decision.nextState.currentState)
        assertTrue(decision.events.isEmpty())
    }

    @Test fun confirmedWorkingSessionSurvivesContinuityBreak() {
        val state = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 100L,
            lastLocationTime = 100L)
        val decision = engine.next(state,
            fix(54_000_000L, LocationType.COMPANY, company = 70.0, companyAnchor = 60.0), config)
        assertEquals("WORKING", decision.nextState.currentState)
        assertEquals(100L, decision.nextState.sessionStart)
        assertEquals("s", decision.nextState.sessionId)
        assertTrue(decision.events.isEmpty())
    }

    // ---------- 2026-09-16：到岗时刻不再被「连续两次」门槛与回调空窗联手推迟 ----------

    @Test fun strongGnssEvidenceConfirmsArrivalOnFirstFix() {
        // 融合层已判为可靠绝对定位（质量 >= 0.80 的 GNSS）时，单次读数即确认到岗。
        // 原实现一律要求连续两次，9/15 夜班因此把 20:51 的到岗拖到了 21:16。
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s")
        val decision = engine.next(state,
            fix(100L, LocationType.COMPANY, company = 80.0, companyAnchor = 70.0, strong = true), config)
        val event = decision.events.single() as TrajectoryAnchorEngine.Event.CompanyArrival
        assertEquals(100L, event.occurredAt)
        assertEquals(100L, event.confirmedAt)
        assertEquals("WORKING", decision.nextState.currentState)
        assertEquals(100L, decision.nextState.sessionStart)
    }

    @Test fun ambientEvidenceStillNeedsTwoFixes() {
        // 强证据通道不得放宽环境证据（CONFIRMED_AMBIENT）的门槛：单次仍不足以确认到岗
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s")
        val first = engine.next(state,
            fix(100L, LocationType.COMPANY, company = 30.0, companyAnchor = 20.0), config)
        assertTrue(first.events.isEmpty())
        assertEquals("LEAVING_HOME", first.nextState.currentState)
        assertEquals(1, first.nextState.stableCompanyCount)
    }

    @Test fun arrivalTimeSurvivesTwentyFiveMinuteCallbackGap() {
        // 9/15 实测：20:51 首次判为在公司后定位回调中断 25 分钟，恢复时连续性窗口（20 分钟）已断。
        // 断流本身不构成离开证据，候选到岗时刻必须保留，恢复后只需再补一次稳定读数。
        val firstSeen = 100L
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s",
            candidateCompanyArrivalTime = firstSeen, stableCompanyCount = 1, lastLocationTime = firstSeen)
        val resumed = engine.next(state,
            fix(firstSeen + 25 * 60_000L, LocationType.COMPANY, company = 30.0, companyAnchor = 20.0), config)
        assertTrue(resumed.events.isEmpty())
        assertEquals(firstSeen, resumed.nextState.candidateCompanyArrivalTime)
        val confirmed = engine.next(resumed.nextState,
            fix(firstSeen + 26 * 60_000L, LocationType.COMPANY, company = 30.0, companyAnchor = 20.0), config)
        assertEquals(firstSeen,
            (confirmed.events.single() as TrajectoryAnchorEngine.Event.CompanyArrival).occurredAt)
    }

    @Test fun strongEvidenceStillRejectsExpiredCandidate() {
        // 强证据也不能把一个早已过期的候选时刻翻出来当到岗时间
        val state = WorkStateEntity(currentState = "NEAR_COMPANY", sessionId = "s",
            candidateCompanyArrivalTime = 100L, stableCompanyCount = 1, lastLocationTime = 100L)
        val decision = engine.next(state,
            fix(54_000_100L, LocationType.COMPANY, company = 30.0, companyAnchor = 20.0, strong = true), config)
        assertEquals(54_000_100L,
            (decision.events.single() as TrajectoryAnchorEngine.Event.CompanyArrival).occurredAt)
    }

    private fun fix(time: Long, type: LocationType, company: Double? = null, companyAnchor: Double? = null,
        homeAnchor: Double? = null, moving: Boolean = false, strong: Boolean = false) = TrajectoryAnchorEngine.Fix(
        time, type, 10f, "gps", company, companyAnchor, null, homeAnchor, 0f, moving, strong
    )
}
