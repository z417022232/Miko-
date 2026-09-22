package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationWatchdogPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位看门狗的护栏（真机体检问题 9）。
 *
 * 09-21 白天 `LOCATION_WATCHDOG` 每 6 分钟一条、全天 95 条：
 * `invalidate()` 只清 registered 位、不清 lastCallbackAt，于是「重注册」换不来回调，
 * 看门狗在每个 tick 都会再打一条日志 + 再注册一次。冷却就是为这个加的。
 */
class LocationWatchdogPolicyTest {

    private val minute = 60_000L
    private val stale = 10 * minute
    private val hardStale = 20 * minute

    @Test fun `刚收到回调时既不要环境快照也不要重注册`() {
        assertFalse(LocationWatchdogPolicy.needsAmbientScan(lastCallback = 1_000L, now = 2_000L, stale))
        assertFalse(
            LocationWatchdogPolicy.shouldReRegister(
                lastCallback = 1_000L, now = 2_000L, hardStale, lastReRegisterAt = 0L)
        )
    }

    @Test fun `弱陈旧只要环境快照不强起重注册`() {
        val now = stale + 1_000L
        assertTrue(LocationWatchdogPolicy.needsAmbientScan(lastCallback = 1_000L, now, stale))
        assertFalse(
            LocationWatchdogPolicy.shouldReRegister(
                lastCallback = 1_000L, now, hardStale, lastReRegisterAt = 0L)
        )
    }

    @Test fun `从没收到过回调时两级都触发`() {
        assertTrue(LocationWatchdogPolicy.needsAmbientScan(lastCallback = null, now = 1_000L, stale))
        assertTrue(
            LocationWatchdogPolicy.shouldReRegister(
                lastCallback = 0L, now = 1_000L, hardStale, lastReRegisterAt = 0L)
        )
    }

    @Test fun `重注册后未到冷却不得再次重注册`() {
        // 重注册不产生回调：没有冷却的话每个 tick（3 分钟）都会再打一条日志
        val reRegisteredAt = 1_000_000L
        val nextTick = reRegisteredAt + 3 * minute
        assertFalse(
            LocationWatchdogPolicy.shouldReRegister(
                lastCallback = 0L, now = nextTick, hardStale, lastReRegisterAt = reRegisteredAt)
        )
        assertTrue(
            LocationWatchdogPolicy.shouldReRegister(
                lastCallback = 0L, now = reRegisteredAt + hardStale, hardStale,
                lastReRegisterAt = reRegisteredAt)
        )
    }

    @Test fun `收到回调把冷却清零后可以立刻重注册`() {
        // 服务侧在 recordCallback 时把 lastLocationReRegisterAt 归零
        assertTrue(
            LocationWatchdogPolicy.shouldReRegister(
                lastCallback = 0L, now = 5_000_000L, hardStale, lastReRegisterAt = 0L)
        )
    }
}
