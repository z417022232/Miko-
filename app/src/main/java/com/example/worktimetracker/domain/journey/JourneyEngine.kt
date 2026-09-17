package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace

/**
 * 行程状态机（阶段 3 第 2 步）—— **纯 Reducer**。
 *
 * ```kotlin
 * JourneyEngine.reduce(previous, observation, config) -> JourneyTransition
 * ```
 *
 * 同样的（快照, 观察, 配置）三元组永远产生同样的结果：无时间源、无随机、无 IO、
 * 无 Android 依赖、不读 Room、不调采样策略、不读班次画像。
 *
 * ## 为什么是 Reducer 而不是「一拍判定」
 * 一拍观测做不了**候选累计、迟滞、断流恢复、重启接回** —— 这些都需要上一拍的记忆
 * （规格一轮 P0-1）。把上一状态放进输入不会破坏纯度，反而是纯状态机的标准形式。
 *
 * ## 状态图（§5.2.4）
 * ```
 * AT_HOME ──离家候选──> LEAVING_HOME ──确认──> COMMUTING_TO_WORK ──到岗候选──> ARRIVING_WORK ──确认──> AT_WORK
 *    ^                     │ 反向取消            │ 到家候选(折返)              │ 反向取消
 *    │                     ↓                     ↓                            ↓
 *    └──到家(确认)── ARRIVING_HOME <────── COMMUTING_HOME <──确认下班── LEAVING_WORK ──> TEMP_LEAVE ──> ARRIVING_WORK(回司)
 *                                                                    └─> OTHER_STOP（第三方地点停留）
 *                                                                    └─> AWAY（无活动会话）
 * ```
 *
 * ## 五条硬规则（改代码前先看这里）
 * 1. **`placeDecision` 决定能不能推进**（§5.2.1）：`CONFIRMED` 才可推进；`MAINTAINED` 只维持，
 *    **不创建 / 不续期 / 不确认**候选；`UNKNOWN` 不推进，断流超时进 `STALE`。
 * 2. **确认门槛只看 `accumulatedStableMillis`**（§5.2.5）：拍数随采样档漂移（CRITICAL 三拍 90 秒、
 *    STABLE 三拍 30 分钟），`supportCount` 只进解释与诊断。
 * 3. **空窗不计入稳定时长**：两次支持之间出现过 `UNKNOWN` / `MAINTAINED` / 反向证据时，
 *    那段时间**不算**稳定（否则一次断流就能把候选"泡"到门槛）。这是 [JourneyCandidate.lastUnsupportedAt]
 *    存在的唯一理由 —— 纯 Reducer 只凭八字段**在数学上区分不出**「连续支持」与「中间断过一拍」，
 *    与一轮 P0-1 是同一类问题。
 * 4. **事件正式时刻 = 候选 `firstObservedAt`**（`occurredAt`），`confirmedAt` 只进诊断 ——
 *    用确认时刻会把「到岗 08:40」记成 09:12，误差直接进工资。
 * 5. **正式下班由「路径 + 时长 + 到家证据」确认**，**不用** `hasActiveWorkSession` 单独决定
 *    （§5.2.4 二轮 P1-1：会话正等待 `CompanyDeparture` 才结束，拿它当前置条件是循环依赖）。
 *    会话事实的唯一用途是区分 `OTHER_STOP`（工作期间在外停留）与 `AWAY`（休息日外出）。
 *
 * ## 迟滞怎么来的
 * 确认任何一个事件都需要「累计稳定时长」达标（`arrivalRequiredMillis` / `departureRequiredMillis`），
 * 所以单拍抖动**不可能**确认事件；反向证据取消候选后再次同向，也要重新累计完整时长。
 * 已确认态因此不会在阈值附近来回跳 —— 会动的只有候选态，而候选态不产生事件。
 */
object JourneyEngine {

    /**
     * 推进一步。
     *
     * @param previous 上一拍快照（状态机的全部记忆）
     * @param observation 本拍观测（外部事实）
     * @param config 阈值（引擎内部零魔数）
     */
    fun reduce(
        previous: JourneySnapshot,
        observation: JourneyObservation,
        config: JourneyConfig
    ): JourneyTransition = Reduction(previous, observation, config).run()

    // ---------------------------------------------------------------- 内部：一次归约的可变工作区

    /**
     * 归约工作区。
     *
     * 用可变字段是为了让「分派 → 改状态 → 收集原因 → 拼文案」读起来是一件事；
     * 它对外部完全不可见（[run] 结束后只产出一个不可变的 [JourneyTransition]），
     * 所以不破坏纯度 —— 同输入同输出由「没有隐藏输入」保证，而不是由写法保证。
     */
    private class Reduction(
        private val previous: JourneySnapshot,
        private val observation: JourneyObservation,
        private val config: JourneyConfig
    ) {
        /** 本拍使用的（已清洗的）观测值，见 [sanitized]。 */
        private lateinit var obs: JourneyObservation

        private val reasons = LinkedHashSet<JourneyReason>()
        private val events = ArrayList<JourneyEvent>()
        private val notes = ArrayList<String>()

        private var phase: JourneyPhase = previous.phase
        private var candidate: JourneyCandidate? = previous.candidate
        private var lastConfirmedPhase: JourneyPhase? = previous.lastConfirmedPhase
        private var lastTransitionAt: Long = previous.lastTransitionAt

        fun run(): JourneyTransition {
            obs = sanitized(observation)
            if (obs !== observation) reasons += JourneyReason.INVALID_INPUT

            when {
                clockRolledBack() -> rollbackAfterClockChange()
                isStale() -> enterStale()
                previous.phase == JourneyPhase.STALE -> recoverFromStale()
                else -> {
                    expireCandidateIfNeeded()
                    noteMotionExpiry()
                    dispatch()
                }
            }
            return build()
        }

        // ---------------------------------------------------------------- 0. 输入清洗与保守处理

        /**
         * 把不可能/越界的观测值拉回可解释范围，**方向一律取保守侧**：
         * 负数秒数按 0（不凭空判断流）、NaN 或越界置信按 0（不当成强证据）。
         *
         * ⚠️ 清洗**不修补语义**：地点与决策等级一个都不改 —— 那是融合层的事，
         * 状态机擅自"帮忙改判"会让影子对比对不上账。
         */
        private fun sanitized(o: JourneyObservation): JourneyObservation {
            val seconds = o.secondsSinceFix.coerceAtLeast(0L)
            val confidence = if (o.confidence.isNaN()) 0.0 else o.confidence.coerceIn(0.0, 1.0)
            val distanceHome = o.distanceToHomeMeters?.takeIf { it >= 0.0 && !it.isNaN() }
            val distanceWork = o.distanceToWorkMeters?.takeIf { it >= 0.0 && !it.isNaN() }
            return if (
                seconds == o.secondsSinceFix &&
                confidence == o.confidence &&
                distanceHome == o.distanceToHomeMeters &&
                distanceWork == o.distanceToWorkMeters
            ) {
                o
            } else {
                o.copy(
                    secondsSinceFix = seconds,
                    confidence = confidence,
                    distanceToHomeMeters = distanceHome,
                    distanceToWorkMeters = distanceWork
                )
            }
        }

        /** 时间回拨 / 非法时刻：拿未来或过去的脏数据继续推，比停下来更危险。 */
        private fun clockRolledBack(): Boolean {
            if (obs.now <= 0L) return true
            if (obs.now < previous.lastTransitionAt) return true
            val lastSupported = previous.candidate?.lastSupportedAt ?: return false
            return lastSupported > obs.now
        }

        /**
         * 时间回拨的落点（§5.6 规则 2）：**丢弃候选、保留 `lastConfirmedPhase`、状态置 UNKNOWN**。
         *
         * 候选的时间轴已经不可信（它的 `lastSupportedAt` 在未来），继续累计就会把
         * 一个虚构的稳定时长记成事件依据。已确认状态没有时间轴问题，所以留着。
         */
        private fun rollbackAfterClockChange() {
            reasons += JourneyReason.CLOCK_ROLLED_BACK
            notes += "观测时刻早于状态机已知的最后时刻（时间回拨）：丢弃候选、保留已确认状态，重新观察"
            phase = JourneyPhase.UNKNOWN
            candidate = null
            lastTransitionAt = obs.now
        }

        // ---------------------------------------------------------------- 1. 断流与恢复

        private fun isStale(): Boolean = obs.secondsSinceFix > config.staleAfterSeconds

        /**
         * 进 `STALE`（§5.2.4：`UNKNOWN` 是"判不出来"，`STALE` 是"压根没证据"，两者不许合并）。
         *
         * ⚠️ **候选保留**：断流本身不是离开证据（v7.0 修的就是"断流清候选 ⇒ 到岗被记晚 25 分钟"），
         * 但支持链要标记为已中断，断流的那段时间**不计入**稳定时长。
         */
        private fun enterStale() {
            reasons += JourneyReason.STALE_NO_FIX
            candidate?.let { candidate = breakChain(it) }
            if (phase == JourneyPhase.STALE) {
                notes += "仍无有效定位（${obs.secondsSinceFix} 秒），维持断流态：状态不许凭空跳变"
            } else {
                notes += "距最近有效定位 ${obs.secondsSinceFix} 秒（超过 ${config.staleAfterSeconds} 秒），" +
                    "进入断流态，保留「${phaseText(lastConfirmedPhase ?: JourneyPhase.UNKNOWN)}」待恢复"
                phase = JourneyPhase.STALE
            }
        }

        /**
         * 断流恢复。
         *
         * - 有**未过期的候选** ⇒ 接回候选态（候选的 `firstObservedAt` 保住，事件正式时刻才不会被推迟）；
         * - 无候选 ⇒ 接回 `lastConfirmedPhase`，且**绝不恢复到候选态**（候选态是"尚未确认"的中间态，
         *   恢复时没有支持它的证据链，凭空回到候选态等于伪造进度）。
         */
        private fun recoverFromStale() {
            val live = candidate
            if (live != null && obs.now - live.lastSupportedAt <= config.candidateExpiryMillis) {
                phase = candidatePhaseOf(live.targetPhase)
                notes += "定位恢复：接回进行中的「${phaseText(phase)}」候选，" +
                    "断流时长不计入稳定时长（已累计 ${durationText(live.accumulatedStableMillis)}）"
                return
            }
            if (live != null) {
                reasons += JourneyReason.CANDIDATE_EXPIRED
                candidate = null
                notes += "断流期间候选超过保鲜期 ${durationText(config.candidateExpiryMillis)}，作废"
            }
            val target = lastConfirmedPhase?.let { confirmedEquivalent(it) } ?: phaseForFirstFix()
            phase = target
            notes += "定位恢复：接回最近一次已确认状态「${phaseText(target)}」"
        }

        /** 首次取到定位时直接落状态，**不发事件**（无起点，补不出"到家/到岗"这种变迁）。 */
        private fun phaseForFirstFix(): JourneyPhase = when (obs.place) {
            ResolvedPlace.HOME -> JourneyPhase.AT_HOME
            ResolvedPlace.COMPANY -> JourneyPhase.AT_WORK
            ResolvedPlace.OTHER, ResolvedPlace.MOVING ->
                if (obs.hasActiveWorkSession) JourneyPhase.OTHER_STOP else JourneyPhase.AWAY
            ResolvedPlace.UNKNOWN -> JourneyPhase.UNKNOWN
        }

        // ---------------------------------------------------------------- 2. 分派

        private fun dispatch() {
            when (obs.placeDecision) {
                FusedDecision.CONFIRMED -> {
                    reasons += JourneyReason.PLACE_CONFIRMED
                    advance()
                }
                FusedDecision.MAINTAINED -> hold(unknown = false)
                FusedDecision.UNKNOWN -> hold(unknown = true)
            }
        }

        /**
         * 维持：**状态不动、候选不创建/不续期/不确认**，只把支持链标记为已中断。
         *
         * `MAINTAINED` 走这里是因为它只够维持上一地点（§5.2.1 推进规则）；
         * `UNKNOWN` 走这里是因为它连维持的地点都没给出来。
         */
        private fun hold(unknown: Boolean) {
            candidate?.let { candidate = breakChain(it) }
            if (unknown) {
                reasons += JourneyReason.PLACE_UNKNOWN
                reasons += JourneyReason.INSUFFICIENT_EVIDENCE
                notes += "地点判不出来（${placeText(obs.place)}）：维持「${phaseText(phase)}」不动，不推进候选"
            } else {
                reasons += JourneyReason.PLACE_MAINTAINED_ONLY
                notes += "证据只够维持（${placeText(obs.place)}）：不推进状态、不续期候选"
            }
        }

        /** `CONFIRMED`：按当前状态推进。 */
        private fun advance() {
            when (phase) {
                JourneyPhase.AT_HOME -> atHome()
                JourneyPhase.LEAVING_HOME -> leavingHome()
                JourneyPhase.COMMUTING_TO_WORK -> commutingToWork()
                JourneyPhase.ARRIVING_WORK -> arrivingWork()
                JourneyPhase.AT_WORK -> atWork()
                JourneyPhase.LEAVING_WORK -> leavingWork()
                JourneyPhase.TEMP_LEAVE -> tempLeave()
                JourneyPhase.OTHER_STOP -> otherStop()
                JourneyPhase.COMMUTING_HOME -> commutingHome()
                JourneyPhase.ARRIVING_HOME -> arrivingHome()
                JourneyPhase.AWAY -> away()
                JourneyPhase.UNKNOWN -> unknownPhase()
                // STALE 已在 recoverFromStale 里处理完，不会走到这；兜底维持，绝不猜。
                JourneyPhase.STALE -> hold(unknown = true)
            }
        }

        // ---------------------------------------------------------------- 3. 各状态的推进规则

        private fun atHome() {
            when (obs.place) {
                ResolvedPlace.HOME -> notes += "在家证据稳定，维持在家"
                ResolvedPlace.COMPANY -> {
                    // 家 → 公司一拍跳变：补记「离家 + 到岗」（与公司在→家的补记同口径）
                    emit(JourneyEvent.HomeDeparture(occurredAt = obs.now, confirmedAt = obs.now))
                    emit(JourneyEvent.CompanyArrival(occurredAt = obs.now, confirmedAt = obs.now))
                    reasons += JourneyReason.ARRIVAL_CONFIRMED
                    enter(JourneyPhase.AT_WORK, occurredAt = obs.now)
                    notes += "一拍内从家直接到公司：补记离家与到岗（无更早证据，正式时刻取本拍）"
                }
                ResolvedPlace.OTHER, ResolvedPlace.MOVING -> openOrSupport(JourneyPhase.LEAVING_HOME)
                ResolvedPlace.UNKNOWN -> noAdvance()
            }
        }

        private fun leavingHome() {
            when (obs.place) {
                ResolvedPlace.HOME -> reverseCancel()
                ResolvedPlace.COMPANY -> {
                    val left = candidate?.firstObservedAt ?: obs.now
                    emit(JourneyEvent.HomeDeparture(occurredAt = left, confirmedAt = obs.now))
                    emit(JourneyEvent.CompanyArrival(occurredAt = obs.now, confirmedAt = obs.now))
                    reasons += JourneyReason.ARRIVAL_CONFIRMED
                    enter(JourneyPhase.AT_WORK, occurredAt = obs.now)
                    notes += "离家候选期内直接确认在公司：补记离家（正式时刻取最早证据）与到岗"
                }
                ResolvedPlace.OTHER, ResolvedPlace.MOVING -> openOrSupport(JourneyPhase.LEAVING_HOME)
                ResolvedPlace.UNKNOWN -> noAdvance()
            }
        }

        private fun commutingToWork() {
            when (obs.place) {
                ResolvedPlace.COMPANY -> openOrSupport(JourneyPhase.ARRIVING_WORK)
                ResolvedPlace.HOME -> openOrSupport(JourneyPhase.ARRIVING_HOME)
                else -> notes += "上班途中（${placeText(obs.place)}），维持"
            }
        }

        private fun arrivingWork() {
            when (obs.place) {
                ResolvedPlace.COMPANY -> openOrSupport(JourneyPhase.ARRIVING_WORK)
                else -> reverseCancel()
            }
        }

        private fun atWork() {
            when (obs.place) {
                ResolvedPlace.COMPANY -> notes += "在岗证据稳定，维持在岗"
                ResolvedPlace.HOME -> {
                    // 在岗 → 一拍在家（典型：定位断流后第一条可靠定位在家）⇒ 离岗 + 到家一起补记
                    confirmDepartureAndHomeArrival(departureStart = obs.now)
                }
                ResolvedPlace.OTHER, ResolvedPlace.MOVING -> openOrSupport(JourneyPhase.LEAVING_WORK)
                ResolvedPlace.UNKNOWN -> noAdvance()
            }
        }

        /**
         * 离岗候选期。三条正式下班判据（§5.2.4）**先于候选累计**判定，任一成立即确认：
         * ① 到家 ② 离开时长超过 `tempLeaveMaxMillis` ③ 持续远离（候选累计达标且仍在移动）。
         */
        private fun leavingWork() {
            val c = candidate
            val start = c?.firstObservedAt ?: lastTransitionAt
            val timedOut = obs.now - start >= config.tempLeaveMaxMillis
            when (obs.place) {
                // 到家永远优先：与临时离岗不竞争（§5.2.4 判定规则 4）
                ResolvedPlace.HOME -> confirmDepartureAndHomeArrival(start)
                ResolvedPlace.UNKNOWN -> noAdvance()
                ResolvedPlace.COMPANY -> if (timedOut) confirmDeparture(start, timedOut = true) else reverseCancel()
                ResolvedPlace.OTHER, ResolvedPlace.MOVING ->
                    if (timedOut) confirmDeparture(start, timedOut = true) else openOrSupport(JourneyPhase.LEAVING_WORK)
            }
        }

        private fun tempLeave() {
            val start = lastTransitionAt
            if (obs.place == ResolvedPlace.HOME) {
                confirmDepartureAndHomeArrival(start)
                return
            }
            if (obs.now - start >= config.tempLeaveMaxMillis) {
                confirmDeparture(start, timedOut = true)
                return
            }
            when (obs.place) {
                ResolvedPlace.COMPANY -> openOrSupport(JourneyPhase.ARRIVING_WORK)
                else -> notes += "仍在临时离岗（${placeText(obs.place)}），已离开 ${durationText(obs.now - start)}"
            }
        }

        private fun otherStop() {
            val start = lastTransitionAt
            if (obs.place == ResolvedPlace.HOME) {
                confirmDepartureAndHomeArrival(start)
                return
            }
            if (obs.now - start >= config.tempLeaveMaxMillis) {
                confirmDeparture(start, timedOut = true)
                return
            }
            when (obs.place) {
                ResolvedPlace.COMPANY -> openOrSupport(JourneyPhase.ARRIVING_WORK)
                else -> notes += "仍在外勤停留（${placeText(obs.place)}），已离开 ${durationText(obs.now - start)}"
            }
        }

        private fun commutingHome() {
            when (obs.place) {
                ResolvedPlace.HOME -> openOrSupport(JourneyPhase.ARRIVING_HOME)
                ResolvedPlace.COMPANY -> openOrSupport(JourneyPhase.ARRIVING_WORK)
                else -> notes += "回家途中（${placeText(obs.place)}），维持"
            }
        }

        private fun arrivingHome() {
            when (obs.place) {
                ResolvedPlace.HOME -> openOrSupport(JourneyPhase.ARRIVING_HOME)
                else -> reverseCancel()
            }
        }

        private fun away() {
            when (obs.place) {
                ResolvedPlace.HOME -> openOrSupport(JourneyPhase.ARRIVING_HOME)
                ResolvedPlace.COMPANY -> openOrSupport(JourneyPhase.ARRIVING_WORK)
                else -> notes += "无活动会话，在外（${placeText(obs.place)}），维持外出"
            }
        }

        /** 起始态：首次确定地点只落状态、**不发事件**（没有起点，补不出变迁）。 */
        private fun unknownPhase() {
            when (obs.place) {
                ResolvedPlace.HOME -> {
                    enter(JourneyPhase.AT_HOME, occurredAt = obs.now)
                    notes += "首次确定在家：只建状态，不补记到家事件（无离家起点）"
                }
                ResolvedPlace.COMPANY -> {
                    enter(JourneyPhase.AT_WORK, occurredAt = obs.now)
                    notes += "首次确定在公司：只建状态，不补记到岗事件（无离家起点）"
                }
                ResolvedPlace.OTHER, ResolvedPlace.MOVING -> {
                    val target = if (obs.hasActiveWorkSession) JourneyPhase.OTHER_STOP else JourneyPhase.AWAY
                    enter(target, occurredAt = obs.now)
                    notes += "首次确定在别处：有活动会话记外勤停留，否则记外出"
                }
                ResolvedPlace.UNKNOWN -> noAdvance()
            }
        }

        // ---------------------------------------------------------------- 4. 候选的创建 / 续期 / 确认 / 取消

        /**
         * 开启或续期候选；达标则确认。
         *
         * 目标状态唯一决定候选身份：同一目标只存在一个候选，
         * 换目标（如 LEAVING_WORK → ARRIVING_WORK）会**另起**一个候选。
         */
        private fun openOrSupport(candidatePhase: JourneyPhase) {
            val target = targetPhaseOf(candidatePhase)
            val current = candidate
            if (current == null || current.targetPhase != target) {
                candidate = newCandidate(target)
                phase = candidatePhase
                reasons += JourneyReason.CANDIDATE_STARTED
                // 地点已经指向目标，但只进候选态、不确认事件 —— 这就是迟滞本身
                reasons += JourneyReason.HYSTERESIS_HELD
                val required = requiredMillis(target)
                notes += "开启「${phaseText(candidatePhase)}」候选（目标 ${phaseText(target)}）：" +
                    "需连续稳定 ${durationText(required)}"
                return
            }
            val supported = support(current)
            candidate = supported
            phase = candidatePhase
            val required = requiredMillis(target)
            if (supported.accumulatedStableMillis >= required) {
                confirm(target, supported)
            } else {
                reasons += JourneyReason.CANDIDATE_SUPPORTED
                // 地点已经指向目标、但累计未达标 ⇒ 只进候选态、不确认事件 —— 这就是迟滞本身
                reasons += JourneyReason.HYSTERESIS_HELD
                notes += "「${phaseText(candidatePhase)}」累计 ${durationText(supported.accumulatedStableMillis)}" +
                    " / 需 ${durationText(required)}（还差 ${durationText(required - supported.accumulatedStableMillis)}，" +
                    "${supported.supportCount} 拍）"
            }
        }

        private fun newCandidate(target: JourneyPhase): JourneyCandidate = JourneyCandidate(
            targetPhase = target,
            firstObservedAt = obs.now,
            lastSupportedAt = obs.now,
            supportCount = 1,
            accumulatedStableMillis = 0L,
            evidenceSources = obs.evidenceSources,
            strongestDecision = obs.placeDecision,
            confidence = obs.confidence,
            lastUnsupportedAt = null
        )

        /**
         * 续期：累计稳定时长 += 距上次支持的间隔，**但只在支持链连续时才加**。
         *
         * ⚠️ 断过链（[JourneyCandidate.lastUnsupportedAt] 非 null）的这一拍只把链接回去，
         * **不加时长** —— 中间那段空窗不是"稳定支持"，加进去就是拿断流当证据。
         */
        private fun support(c: JourneyCandidate): JourneyCandidate {
            val gap = (obs.now - c.lastSupportedAt).coerceAtLeast(0L)
            val continuous = c.lastUnsupportedAt == null
            return c.copy(
                lastSupportedAt = obs.now,
                supportCount = c.supportCount + 1,
                accumulatedStableMillis = c.accumulatedStableMillis + if (continuous) gap else 0L,
                evidenceSources = c.evidenceSources + obs.evidenceSources,
                strongestDecision = strongestOf(c.strongestDecision, obs.placeDecision),
                confidence = maxOf(c.confidence, obs.confidence),
                lastUnsupportedAt = null
            )
        }

        /** 支持链中断：保留最早证据与已累计时长，只把"链断了"记下来。 */
        private fun breakChain(c: JourneyCandidate): JourneyCandidate = c.copy(lastUnsupportedAt = obs.now)

        private fun expireCandidateIfNeeded() {
            val c = candidate ?: return
            if (obs.now - c.lastSupportedAt <= config.candidateExpiryMillis) return
            reasons += JourneyReason.CANDIDATE_EXPIRED
            notes += "候选超过保鲜期 ${durationText(config.candidateExpiryMillis)} 未获支持，作废：" +
                "下次观察将重新起算最早证据时刻"
            candidate = null
            if (isCandidatePhase(phase)) {
                val base = lastConfirmedPhase ?: defaultBaseOf(phase)
                phase = base
                lastTransitionAt = obs.now
                lastConfirmedPhase = base
            }
        }

        private fun confirm(target: JourneyPhase, c: JourneyCandidate) {
            reasons += JourneyReason.CANDIDATE_CONFIRMED
            when (target) {
                JourneyPhase.COMMUTING_TO_WORK -> {
                    emit(JourneyEvent.HomeDeparture(occurredAt = c.firstObservedAt, confirmedAt = obs.now))
                    enter(JourneyPhase.COMMUTING_TO_WORK, occurredAt = c.firstObservedAt)
                    notes += "确认离家：正式时刻取最早证据（比本拍早 ${durationText(obs.now - c.firstObservedAt)}）"
                }
                JourneyPhase.AT_HOME -> {
                    emit(JourneyEvent.HomeArrival(occurredAt = c.firstObservedAt, confirmedAt = obs.now))
                    enter(JourneyPhase.AT_HOME, occurredAt = c.firstObservedAt)
                    notes += "确认到家：正式时刻取最早证据（比本拍早 ${durationText(obs.now - c.firstObservedAt)}）"
                }
                JourneyPhase.AT_WORK -> confirmArrival(c)
                JourneyPhase.TEMP_LEAVE -> confirmLeavingWork(c)
                else -> noAdvance()
            }
        }

        /**
         * 到岗候选确认。
         *
         * 事件取决于**从哪来**：从临时离岗回来是 [JourneyEvent.TempLeaveEnd]（会话继续），
         * 从通勤/家侧首次到达才是 [JourneyEvent.CompanyArrival]；
         * 从外勤/在岗返回则不发事件（压根没确认过离岗，发到岗事件等于凭空多一次出勤）。
         */
        private fun confirmArrival(c: JourneyCandidate) {
            when (lastConfirmedPhase) {
                JourneyPhase.TEMP_LEAVE -> {
                    emit(JourneyEvent.TempLeaveEnd(occurredAt = c.firstObservedAt, confirmedAt = obs.now))
                    reasons += JourneyReason.TEMP_LEAVE_ENDED
                    notes += "回司确认：临时离岗闭环，工作会话继续（未确认离岗，不算新一次到岗）"
                }
                JourneyPhase.OTHER_STOP, JourneyPhase.AT_WORK -> {
                    notes += "回到在岗：此前未确认离岗，不产生到岗事件"
                }
                else -> {
                    emit(JourneyEvent.CompanyArrival(occurredAt = c.firstObservedAt, confirmedAt = obs.now))
                    reasons += JourneyReason.ARRIVAL_CONFIRMED
                    notes += "确认到岗：正式时刻取最早证据（比本拍早 ${durationText(obs.now - c.firstObservedAt)}）"
                }
            }
            enter(JourneyPhase.AT_WORK, occurredAt = c.firstObservedAt)
        }

        /**
         * 离岗候选确认后的三条出路（§5.2.4）：
         * 持续远离 ⇒ 正式下班；否则按「有无会话 / 是否明确在第三方地点」分到
         * `OTHER_STOP`、`TEMP_LEAVE`、`AWAY` —— **都不是**正式下班，工作会话一律不结束。
         */
        private fun confirmLeavingWork(c: JourneyCandidate) {
            when {
                effectiveMotion() == MotionPhase.MOVING -> {
                    emit(JourneyEvent.CompanyDeparture(occurredAt = c.firstObservedAt, confirmedAt = obs.now))
                    reasons += JourneyReason.DEPARTURE_CONFIRMED
                    enter(JourneyPhase.COMMUTING_HOME, occurredAt = c.firstObservedAt)
                    notes += "持续远离公司：确认正式下班（路径 + 时长，不靠会话是否结束）"
                }
                !obs.hasActiveWorkSession -> {
                    enter(JourneyPhase.AWAY, occurredAt = c.firstObservedAt)
                    notes += "已离开公司但无活动会话：记为外出，不确认下班"
                }
                obs.place == ResolvedPlace.OTHER -> {
                    enter(JourneyPhase.OTHER_STOP, occurredAt = c.firstObservedAt)
                    notes += "活动会话期间在第三方地点停留：记外勤停留，工作会话继续"
                }
                else -> {
                    emit(JourneyEvent.TempLeaveStart(occurredAt = c.firstObservedAt, confirmedAt = obs.now))
                    reasons += JourneyReason.TEMP_LEAVE_STARTED
                    enter(JourneyPhase.TEMP_LEAVE, occurredAt = c.firstObservedAt)
                    notes += "确认临时离岗：工作会话不结束（回司走闭环，到家或超时才转正式下班）"
                }
            }
        }

        private fun confirmDepartureAndHomeArrival(departureStart: Long) {
            emit(JourneyEvent.CompanyDeparture(occurredAt = departureStart, confirmedAt = obs.now))
            // 到家正式时刻：本拍（在这条路径上它是第一条到家证据）
            emit(JourneyEvent.HomeArrival(occurredAt = obs.now, confirmedAt = obs.now))
            reasons += JourneyReason.DEPARTURE_CONFIRMED
            enter(JourneyPhase.AT_HOME, occurredAt = obs.now)
            notes += "到家确认正式下班：同拍补记离岗与到家（到家永远优先，与临时离岗不竞争）"
        }

        private fun confirmDeparture(departureStart: Long, timedOut: Boolean) {
            emit(JourneyEvent.CompanyDeparture(occurredAt = departureStart, confirmedAt = obs.now))
            reasons += JourneyReason.DEPARTURE_CONFIRMED
            if (timedOut) {
                reasons += JourneyReason.TEMP_LEAVE_TIMEOUT
                notes += "离开超过 ${durationText(config.tempLeaveMaxMillis)} 仍未归：按正式下班确认"
            } else {
                notes += "确认正式下班"
            }
            enter(JourneyPhase.COMMUTING_HOME, occurredAt = departureStart)
        }

        /** 反向证据：候选作废，回到上一个已确认状态（无事件）。 */
        private fun reverseCancel() {
            val base = lastConfirmedPhase ?: defaultBaseOf(phase)
            if (candidate != null) {
                reasons += JourneyReason.CANDIDATE_REVERSED
                notes += "出现反向证据（${placeText(obs.place)}）：取消「${phaseText(phase)}」候选，回到「${phaseText(base)}」"
            } else {
                notes += "出现反向证据（${placeText(obs.place)}）：回到「${phaseText(base)}」"
            }
            candidate = null
            if (phase != base) {
                phase = base
                lastTransitionAt = obs.now
                lastConfirmedPhase = base
            }
        }

        /** 地点给不出推进方向（CONFIRMED 却 place=UNKNOWN 这类矛盾输入）：不推进，也不猜。 */
        private fun noAdvance() {
            candidate?.let { candidate = breakChain(it) }
            reasons += JourneyReason.INSUFFICIENT_EVIDENCE
            notes += "地点为 ${placeText(obs.place)}，无法推进：维持「${phaseText(phase)}」"
        }

        // ---------------------------------------------------------------- 5. 状态写入与产物

        /**
         * 进入一个**已确认**状态。
         *
         * `lastTransitionAt` 写的是**语义时刻**（事件正式时刻）而不是本拍时刻 ——
         * 它是 `TEMP_LEAVE` / `OTHER_STOP` 超时判正式下班时的离岗起点，
         * 写成确认时刻会把下班时间推晚（与"事件正式时刻取最早证据"同一条纪律）。
         */
        private fun enter(newPhase: JourneyPhase, occurredAt: Long) {
            phase = newPhase
            lastConfirmedPhase = confirmedEquivalent(newPhase) ?: lastConfirmedPhase
            lastTransitionAt = occurredAt
            candidate = null
        }

        private fun emit(event: JourneyEvent) {
            events += event
        }

        /** 确认门槛：到岗/到家用 [JourneyConfig.arrivalRequiredMillis]，离家/离岗用 departure 那条。 */
        private fun requiredMillis(target: JourneyPhase): Long = when (target) {
            JourneyPhase.AT_WORK, JourneyPhase.AT_HOME -> config.arrivalRequiredMillis
            JourneyPhase.COMMUTING_TO_WORK, JourneyPhase.TEMP_LEAVE -> config.departureRequiredMillis
            else -> config.arrivalRequiredMillis
        }

        private fun noteMotionExpiry() {
            val observedAt = obs.motionObservedAt ?: return
            if (obs.motion == MotionPhase.UNKNOWN) return
            if (obs.now - observedAt <= config.motionExpirySeconds * 1_000L) return
            reasons += JourneyReason.MOTION_EXPIRED
            notes += "运动判定已过期（${durationText(obs.now - observedAt)}）：按 UNKNOWN 处理，不据此判持续远离"
        }

        private fun effectiveMotion(): MotionPhase {
            val observedAt = obs.motionObservedAt ?: return MotionPhase.UNKNOWN
            if (obs.now - observedAt > config.motionExpirySeconds * 1_000L) return MotionPhase.UNKNOWN
            return obs.motion
        }

        private fun build(): JourneyTransition {
            val snapshot = JourneySnapshot(
                phase = phase,
                candidate = candidate,
                lastConfirmedPhase = lastConfirmedPhase,
                lastTransitionAt = lastTransitionAt
            )
            if (snapshot == previous && events.isEmpty()) {
                reasons += JourneyReason.NO_CHANGE
            }
            return JourneyTransition(
                snapshot = snapshot,
                confirmedEvents = events.toList(),
                reasonCodes = reasons.toSet(),
                explanation = notes.joinToString("；").ifBlank { "状态与候选均无变化" }
            )
        }
    }

    // ---------------------------------------------------------------- 内部：纯函数查表

    /** 候选态 → 它确认后要进入的目标状态。 */
    private fun targetPhaseOf(candidatePhase: JourneyPhase): JourneyPhase = when (candidatePhase) {
        JourneyPhase.LEAVING_HOME -> JourneyPhase.COMMUTING_TO_WORK
        JourneyPhase.ARRIVING_WORK -> JourneyPhase.AT_WORK
        JourneyPhase.LEAVING_WORK -> JourneyPhase.TEMP_LEAVE
        JourneyPhase.ARRIVING_HOME -> JourneyPhase.AT_HOME
        else -> candidatePhase
    }

    /** 目标状态 → 它对应的候选态（恢复时用）。 */
    private fun candidatePhaseOf(target: JourneyPhase): JourneyPhase = when (target) {
        JourneyPhase.COMMUTING_TO_WORK -> JourneyPhase.LEAVING_HOME
        JourneyPhase.AT_WORK -> JourneyPhase.ARRIVING_WORK
        JourneyPhase.TEMP_LEAVE -> JourneyPhase.LEAVING_WORK
        JourneyPhase.AT_HOME -> JourneyPhase.ARRIVING_HOME
        else -> target
    }

    private fun isCandidatePhase(phase: JourneyPhase): Boolean = when (phase) {
        JourneyPhase.LEAVING_HOME, JourneyPhase.ARRIVING_WORK,
        JourneyPhase.LEAVING_WORK, JourneyPhase.ARRIVING_HOME -> true
        else -> false
    }

    /** 候选态在失去候选后的默认落点（没有 `lastConfirmedPhase` 时用）。 */
    private fun defaultBaseOf(candidatePhase: JourneyPhase): JourneyPhase = when (candidatePhase) {
        JourneyPhase.LEAVING_HOME -> JourneyPhase.AT_HOME
        JourneyPhase.ARRIVING_HOME -> JourneyPhase.COMMUTING_HOME
        JourneyPhase.LEAVING_WORK -> JourneyPhase.AT_WORK
        JourneyPhase.ARRIVING_WORK -> JourneyPhase.COMMUTING_TO_WORK
        else -> candidatePhase
    }

    /**
     * 归一到**已确认**状态；候选态返回它对应的已确认落点。
     *
     * `lastConfirmedPhase` 按定义只会存已确认态，但持久化行可能被别处写脏，
     * 所以这里再做一层归一 —— 恢复时**绝不**把状态接回候选态。
     */
    private fun confirmedEquivalent(phase: JourneyPhase): JourneyPhase? = when (phase) {
        JourneyPhase.LEAVING_HOME -> JourneyPhase.AT_HOME
        JourneyPhase.ARRIVING_HOME -> JourneyPhase.COMMUTING_HOME
        JourneyPhase.LEAVING_WORK, JourneyPhase.ARRIVING_WORK -> JourneyPhase.AT_WORK
        JourneyPhase.UNKNOWN, JourneyPhase.STALE -> null
        else -> phase
    }

    private fun strongestOf(a: FusedDecision, b: FusedDecision): FusedDecision =
        if (a.ordinal <= b.ordinal) a else b

    private fun phaseText(phase: JourneyPhase): String = when (phase) {
        JourneyPhase.AT_HOME -> "在家"
        JourneyPhase.LEAVING_HOME -> "离家候选期"
        JourneyPhase.ARRIVING_HOME -> "归宅候选期"
        JourneyPhase.COMMUTING_TO_WORK -> "上班途中"
        JourneyPhase.COMMUTING_HOME -> "回家途中"
        JourneyPhase.ARRIVING_WORK -> "到岗候选期"
        JourneyPhase.AT_WORK -> "在岗"
        JourneyPhase.LEAVING_WORK -> "离岗候选期"
        JourneyPhase.TEMP_LEAVE -> "临时离岗"
        JourneyPhase.OTHER_STOP -> "外勤停留"
        JourneyPhase.AWAY -> "外出"
        JourneyPhase.UNKNOWN -> "位置未定"
        JourneyPhase.STALE -> "定位断流"
    }

    private fun placeText(place: ResolvedPlace): String = when (place) {
        ResolvedPlace.HOME -> "家"
        ResolvedPlace.COMPANY -> "公司"
        ResolvedPlace.OTHER -> "其他地点"
        ResolvedPlace.MOVING -> "移动中"
        ResolvedPlace.UNKNOWN -> "未知"
    }

    private fun durationText(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours} 小时 ${minutes} 分"
            minutes > 0L -> "${minutes} 分 ${seconds} 秒"
            else -> "${seconds} 秒"
        }
    }
}
