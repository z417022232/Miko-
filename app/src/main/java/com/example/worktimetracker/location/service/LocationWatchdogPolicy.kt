package com.example.worktimetracker.location.service

/**
 * 定位看门狗的判定（纯函数，无平台依赖）。
 *
 * 判据只有两个，但都很陡：
 *
 * 1. **弱陈旧**（[needsAmbientScan]）：距上次回调超过 [staleAfter]。
 *    静止且设了最小位移时无回调是**正常**的，所以这一步只请求环境快照补证据，不动注册。
 * 2. **强陈旧**（[shouldReRegister]）：距上次回调超过 [hardStaleAfter]，
 *    且距上次**重新注册**也超过 [hardStaleAfter]（冷却）。
 *
 * 冷却是必要的：`SourceRegistrationState.invalidate()` 只清 `registered` 位，
 * **不清** `lastCallbackAt` —— 于是「重注册」本身换不来一次回调，
 * 没有冷却时看门狗会在每个 tick（3 分钟）打一条日志并再注册一次。
 * 实测 09-21 白天每 6 分钟一条、全天 95 条：日志噪音之外还有无谓的功耗。
 */
object LocationWatchdogPolicy {

    /** null / 0 都表示「从没发生过」（回调与重注册同口径）。 */
    private fun elapsedSince(at: Long?, now: Long): Long =
        when {
            at == null || at == 0L -> Long.MAX_VALUE
            else -> now - at
        }

    fun needsAmbientScan(lastCallback: Long?, now: Long, staleAfter: Long): Boolean =
        elapsedSince(lastCallback, now) >= staleAfter

    fun shouldReRegister(
        lastCallback: Long?,
        now: Long,
        hardStaleAfter: Long,
        lastReRegisterAt: Long
    ): Boolean = elapsedSince(lastCallback, now) >= hardStaleAfter &&
        elapsedSince(lastReRegisterAt, now) >= hardStaleAfter
}
