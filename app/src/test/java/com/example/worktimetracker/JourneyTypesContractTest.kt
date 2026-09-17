package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.journey.EvidenceHealth
import com.example.worktimetracker.domain.journey.JourneyCandidate
import com.example.worktimetracker.domain.journey.JourneyConfig
import com.example.worktimetracker.domain.journey.JourneyEvent
import com.example.worktimetracker.domain.journey.JourneyEventOrder
import com.example.worktimetracker.domain.journey.JourneyEventViolation
import com.example.worktimetracker.domain.journey.JourneyObservation
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.JourneyRuntimeDecision
import com.example.worktimetracker.domain.journey.JourneySnapshot
import com.example.worktimetracker.domain.journey.JourneyTransition
import com.example.worktimetracker.domain.journey.MotionPhase
import com.example.worktimetracker.domain.journey.RetryState
import com.example.worktimetracker.domain.journey.SamplingDecision
import com.example.worktimetracker.domain.journey.SamplingTier
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * 阶段 3 第 1 步：**纯 domain 类型的契约测试**。
 *
 * 这些用例钉的是**契约本身**（字段有没有、类型对不对、枚举有几档、解析失败怎么处理），
 * 不是状态机行为 —— 状态机是第 2 步 `JourneyEngine` 的事，别混进这个提交。
 *
 * 为什么契约要用编译级反射钉住：阶段 3 的两轮复查里，v1/v2 规格都出现过
 * 「字段漏了（lastConfirmedPhase）」「字段放错层（activeWorkSession）」
 * 「枚举表达了超出证据能力的事实（步行/车载）」这类问题，
 * 而它们**在业务测试里全都看不出来** —— 只有把类型结构本身当成断言对象才抓得住。
 */
class JourneyTypesContractTest {

    // ---------- 契约 1：观察必须带 hasActiveWorkSession 与 motionObservedAt ----------

    @Test
    fun observationCarriesSessionFactAndMotionTimestamp() {
        val observation = JourneyObservation(
            now = NOW,
            place = ResolvedPlace.COMPANY,
            placeDecision = FusedDecision.CONFIRMED,
            confidence = 0.82,
            motion = MotionPhase.STATIONARY,
            motionObservedAt = NOW - 30_000L,
            secondsSinceFix = 12L,
            hasActiveWorkSession = true,
            distanceToHomeMeters = 4200.0,
            distanceToWorkMeters = 7.0
        )

        // 二轮 P0-1：工作会话是外部事实，必须在 Observation 上
        assertTrue(observation.hasActiveWorkSession)
        // 二轮 P0-4：运动判定必须带自己的时刻（且是 epoch 域），否则无法判断它是否过期
        assertEquals(NOW - 30_000L, observation.motionObservedAt)
    }

    @Test
    fun observationHasNoStateFields() {
        // 观察只描述事实：不许出现 phase / candidate / lastTransitionAt 这类状态机记忆
        val names = JourneyObservation::class.java.declaredFields.map { it.name }.toSet()
        listOf("phase", "candidate", "lastTransitionAt", "lastConfirmedPhase").forEach {
            assertFalse("JourneyObservation 不应包含状态字段 $it", names.contains(it))
        }
    }

    // ---------- 契约 2：Snapshot 有 lastConfirmedPhase、没有 activeWorkSession ----------

    @Test
    fun snapshotHasLastConfirmedPhaseAndNoSessionFact() {
        val names = JourneySnapshot::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("缺 lastConfirmedPhase：STALE 恢复与时间回拨接回都要用它", names.contains("lastConfirmedPhase"))
        assertFalse(
            "activeWorkSession 是外部事实，已移入 JourneyObservation（二轮 P0-1）",
            names.contains("activeWorkSession")
        )

        val snapshot = JourneySnapshot(
            phase = JourneyPhase.STALE,
            candidate = null,
            lastConfirmedPhase = JourneyPhase.AT_WORK,
            lastTransitionAt = NOW
        )
        assertEquals(JourneyPhase.AT_WORK, snapshot.lastConfirmedPhase)
    }

    @Test
    fun initialSnapshotStartsUnknownWithoutEvidence() {
        val initial = JourneySnapshot.initial(NOW)
        assertEquals(JourneyPhase.UNKNOWN, initial.phase)
        assertNull(initial.candidate)
        assertNull(initial.lastConfirmedPhase)
        assertEquals(NOW, initial.lastTransitionAt)
    }

    // ---------- 契约 3：confirmedEvents 是列表 ----------

    @Test
    fun transitionCarriesEventsAsList() {
        assertEquals(
            "confirmedEvents 必须是 List（旧机同拍可产多事件，单字段会漏记）",
            List::class.java,
            JourneyTransition::class.java.getDeclaredMethod("getConfirmedEvents").returnType
        )

        val events = listOf(
            JourneyEvent.CompanyDeparture(occurredAt = NOW - 3_600_000L, confirmedAt = NOW),
            JourneyEvent.HomeArrival(occurredAt = NOW - 1_800_000L, confirmedAt = NOW)
        )
        val transition = JourneyTransition(
            snapshot = JourneySnapshot.initial(NOW),
            confirmedEvents = events,
            reasonCodes = emptySet(),
            explanation = "在公司断流后直接在家取得可靠定位，补记离岗与到家"
        )
        assertEquals(2, transition.confirmedEvents.size)
    }

    // ---------- 契约 4：候选八字段齐全 ----------

    @Test
    fun candidateCarriesAllEightFields() {
        // 只数实例字段：companion object / const 会带出静态字段，别把它们算进"候选有几个字段"
        val fields = JourneyCandidate::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        listOf(
            "targetPhase",
            "firstObservedAt",
            "lastSupportedAt",
            "supportCount",
            "accumulatedStableMillis",
            "evidenceSources",
            "strongestDecision",
            "confidence"
        ).forEach { assertTrue("候选缺字段 $it", fields.contains(it)) }
        assertEquals("候选字段必须正好八个", 8, fields.size)
    }

    // ---------- 契约 5：MotionPhase 只有三档 ----------

    @Test
    fun motionPhaseOnlyHasThreeValues() {
        assertEquals(
            "第一版只有 STATIONARY/MOVING/UNKNOWN：采集层分不出步行和车载",
            setOf("STATIONARY", "MOVING", "UNKNOWN"),
            MotionPhase.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun journeyPhaseHasThirteenStates() {
        assertEquals(
            "13 个状态（含 TEMP_LEAVE / OTHER_STOP）",
            setOf(
                "AT_HOME", "LEAVING_HOME", "ARRIVING_HOME",
                "COMMUTING_TO_WORK", "COMMUTING_HOME",
                "ARRIVING_WORK", "AT_WORK", "LEAVING_WORK",
                "TEMP_LEAVE", "OTHER_STOP",
                "AWAY", "UNKNOWN", "STALE"
            ),
            JourneyPhase.entries.map { it.name }.toSet()
        )
        assertEquals(13, JourneyPhase.entries.size)
    }

    // ---------- 契约 6：事件同时保存两个时刻 ----------

    @Test
    fun eventsKeepBothOccurredAtAndConfirmedAt() {
        val departure = JourneyEvent.CompanyDeparture(occurredAt = NOW - 600_000L, confirmedAt = NOW)
        // occurredAt = 最早支持证据的时刻（事件正式时刻）；confirmedAt = 命中门槛那一拍（只进诊断）
        assertEquals(NOW - 600_000L, departure.occurredAt)
        assertEquals(NOW, departure.confirmedAt)

        val arrival = JourneyEvent.HomeArrival(occurredAt = NOW - 300_000L, confirmedAt = NOW)
        assertEquals(NOW - 300_000L, arrival.occurredAt)
        assertEquals(NOW, arrival.confirmedAt)
    }

    // ---------- 契约 7：事件排序验证 ----------

    @Test
    fun eventOrderRejectsDescendingOccurredAt() {
        // 刻意用「离家 + 到公司」这一对：它不触发"到家早于离岗"规则，
        // 才能单独断言倒序违规（否则两条违规混在一起，断言就失去定位能力）
        val events = listOf(
            JourneyEvent.HomeDeparture(occurredAt = NOW, confirmedAt = NOW),
            JourneyEvent.CompanyArrival(occurredAt = NOW - 60_000L, confirmedAt = NOW)
        )
        assertEquals(
            setOf(JourneyEventViolation.NOT_ASCENDING),
            JourneyEventOrder.violations(events)
        )
        assertFalse(JourneyEventOrder.isValid(events))
    }

    @Test
    fun eventOrderRejectsDuplicateEventTypes() {
        val events = listOf(
            JourneyEvent.HomeArrival(occurredAt = NOW, confirmedAt = NOW),
            JourneyEvent.HomeArrival(occurredAt = NOW + 60_000L, confirmedAt = NOW + 60_000L)
        )
        assertEquals(
            setOf(JourneyEventViolation.DUPLICATE_TYPE),
            JourneyEventOrder.violations(events)
        )
    }

    @Test
    fun eventOrderRejectsHomeArrivalBeforeDeparture() {
        // 列表本身是升序的（-3600s 在前、-1800s 在后），但"到家早于离岗"仍然违规 ——
        // 这两条规则必须独立：排序合法不等于顺序语义合法
        val events = listOf(
            JourneyEvent.HomeArrival(occurredAt = NOW - 3_600_000L, confirmedAt = NOW),
            JourneyEvent.CompanyDeparture(occurredAt = NOW - 1_800_000L, confirmedAt = NOW)
        )
        assertEquals(
            setOf(JourneyEventViolation.HOME_ARRIVAL_BEFORE_DEPARTURE),
            JourneyEventOrder.violations(events)
        )
        assertFalse(JourneyEventOrder.isValid(events))
    }

    @Test
    fun eventOrderAcceptsSameTickDepartureThenArrival() {
        // 典型真机场景：公司在 → 断流 → 下一拍在家，一拍补两个事件
        val events = listOf(
            JourneyEvent.CompanyDeparture(occurredAt = NOW - 1_800_000L, confirmedAt = NOW),
            JourneyEvent.HomeArrival(occurredAt = NOW - 900_000L, confirmedAt = NOW)
        )
        assertTrue(JourneyEventOrder.isValid(events))
        assertTrue(JourneyEventOrder.violations(events).isEmpty())
    }

    // ---------- 契约 8：采样决策含限时、冷却、重试、兜底 ----------

    @Test
    fun samplingDecisionCarriesLimitCooldownRetryAndFallback() {
        val decision = SamplingDecision(
            tier = SamplingTier.CRITICAL,
            urgency = 0.9,
            reasonCodes = setOf(com.example.worktimetracker.domain.journey.SamplingReason.STALE_WINDOW),
            expiresAt = NOW + 600_000L,
            cooldownUntil = NOW + 1_200_000L,
            retryAttempt = 2,
            fallbackApplied = false
        )
        assertEquals(NOW + 600_000L, decision.expiresAt)
        assertEquals(NOW + 1_200_000L, decision.cooldownUntil)
        assertEquals(2, decision.retryAttempt)
        assertFalse(decision.fallbackApplied)

        val fields = SamplingDecision::class.java.declaredFields.map { it.name }.toSet()
        listOf("tier", "urgency", "reasonCodes", "expiresAt", "cooldownUntil", "retryAttempt", "fallbackApplied")
            .forEach { assertTrue("采样决策缺字段 $it", fields.contains(it)) }
    }

    // ---------- 契约 9：无 Android / Room / Context 依赖 ----------

    @Test
    fun journeyTypesHaveNoAndroidDependency() {
        listOf(
            JourneyObservation::class,
            JourneySnapshot::class,
            JourneyConfig::class,
            JourneyCandidate::class,
            JourneyEvent::class,
            JourneyTransition::class,
            JourneyPhase::class,
            MotionPhase::class,
            SamplingTier::class,
            SamplingDecision::class,
            EvidenceHealth::class,
            RetryState::class,
            JourneyRuntimeDecision::class
        ).forEach { assertNoPlatformDependency(it) }
    }

    // ---------- 契约 10：枚举解析遇未知值必须失败，不猜测 ----------

    @Test
    fun enumParsingFailsLoudlyOnUnknownValue() {
        assertNull(JourneyPhase.parseOrNull("AT_THE_MOON"))
        assertNull(JourneyPhase.parseOrNull(null))
        assertNull(JourneyPhase.parseOrNull(""))
        assertEquals(JourneyPhase.TEMP_LEAVE, JourneyPhase.parseOrNull("TEMP_LEAVE"))

        assertNull(MotionPhase.parseOrNull("WALKING"))
        assertNull(MotionPhase.parseOrNull("VEHICLE"))
        assertEquals(MotionPhase.MOVING, MotionPhase.parseOrNull("MOVING"))

        assertNull(SamplingTier.parseOrNull("TURBO"))
        assertEquals(SamplingTier.CRITICAL, SamplingTier.parseOrNull("CRITICAL"))
    }

    @Test
    fun candidateSourceCodecIsStableAndFailsOnUnknownToken() {
        val sources = setOf(EvidenceSource.CELL, EvidenceSource.GNSS, EvidenceSource.WIFI)
        // 顺序稳定：按 EvidenceSource 的 ordinal 排（GNSS<NETWORK_LOCATION<CELL<WIFI<…），
        // 不随 Set 迭代顺序漂移 —— 所以"稳定"指的是确定性，不是字母序
        assertEquals("GNSS,CELL,WIFI", JourneyCandidate.encodeSources(sources))
        assertEquals(
            "GNSS,CELL,WIFI",
            JourneyCandidate.encodeSources(setOf(EvidenceSource.WIFI, EvidenceSource.CELL, EvidenceSource.GNSS))
        )

        assertEquals(sources, JourneyCandidate.decodeSources("GNSS,CELL,WIFI"))
        assertEquals(emptySet<EvidenceSource>(), JourneyCandidate.decodeSources(""))
        assertNull("未知来源必须失败，不能静默丢弃", JourneyCandidate.decodeSources("GNSS,MAGIC"))
        assertNull(JourneyCandidate.decodeSources(null))
    }

    // ---------- 辅助 ----------

    /**
     * 反射扫描：类自身的字段/方法/构造签名中不得出现 `android.*` / `androidx.*`。
     * 这是"纯 Kotlin domain"的最低门槛 —— 一旦有人在类型里加 Context 参数，这条会立刻红。
     */
    private fun assertNoPlatformDependency(type: KClass<*>) {
        val java = type.java
        val names = mutableListOf(java.name)
        java.declaredFields.forEach { names += it.type.name }
        java.declaredMethods.forEach {
            names += it.returnType.name
            it.parameterTypes.forEach { p -> names += p.name }
        }
        java.declaredConstructors.forEach {
            it.parameterTypes.forEach { p -> names += p.name }
        }
        java.declaredClasses.forEach { names += it.name }

        names.forEach { name ->
            assertFalse(
                "${type.simpleName} 出现了平台依赖：$name",
                name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("kotlinx.android")
            )
        }
    }

    private companion object {
        val NOW: Long = LocalDate.of(2026, 9, 17)
            .atTime(9, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
