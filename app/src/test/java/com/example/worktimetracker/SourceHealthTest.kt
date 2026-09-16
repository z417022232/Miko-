package com.example.worktimetracker

import com.example.worktimetracker.data.entity.LocationHealthEntity
import com.example.worktimetracker.domain.evidence.EvidenceSourceKind
import com.example.worktimetracker.domain.evidence.SourceHealthJudge
import com.example.worktimetracker.domain.evidence.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 四源可用性判定的语义护栏。
 *
 * 这块判定直接决定当天记录上四个图标是蓝还是红，而数据来自 `location_health` ——
 * 一旦口径写歪，用户看到的就是「一片红」或者「全蓝但什么都没采到」。
 *
 * 守三件事：
 *  1. **没数据 ≠ 正常**（显示「暂无数据」而不是蓝色，不谎报）；
 *  2. **权限/开关故障才算红**，`EMPTY`（附近没有 AP）不算故障；
 *  3. **过期才算红**：静止/室内长时间不回调是正常的，不能秒判异常。
 */
class SourceHealthTest {

    private val now = 1_800_000_000_000L

    private fun row(
        name: String,
        lastCallbackAt: Long = now - 60_000L,
        lastSuccessAt: Long = now - 60_000L,
        registered: Boolean = true,
        lastFailure: String? = null,
    ) = LocationHealthEntity(name, lastCallbackAt, lastSuccessAt, registered, 0, lastFailure)

    @Test
    fun `没有健康记录时是暂无数据而不是正常`() {
        assertEquals(SourceStatus.UNKNOWN, SourceHealthJudge.status(null, now))
    }

    @Test
    fun `刚回调过且无故障是正常`() {
        assertEquals(SourceStatus.NORMAL, SourceHealthJudge.status(row("gnss"), now))
    }

    @Test
    fun `未注册是异常`() {
        assertEquals(
            SourceStatus.ABNORMAL,
            SourceHealthJudge.status(row("wifi", registered = false), now)
        )
    }

    @Test
    fun `权限缺失即使刚刚回调过也算异常`() {
        val health = row("wifi", lastCallbackAt = now - 1_000L, lastFailure = "PERMISSION")
        assertEquals(SourceStatus.ABNORMAL, SourceHealthJudge.status(health, now))
        assertEquals("缺少权限", SourceHealthJudge.reason(health))
    }

    @Test
    fun `开关关闭算异常并给出原因`() {
        assertEquals(
            SourceStatus.ABNORMAL,
            SourceHealthJudge.status(row("bluetooth", lastFailure = "DISABLED"), now)
        )
        assertEquals("开关已关闭", SourceHealthJudge.reason(row("bluetooth", lastFailure = "DISABLED")))
    }

    @Test
    fun `附近没有设备 EMPTY 不算异常`() {
        // EMPTY 是「这附近扫不到 AP/信标」，不是来源坏了 —— 判成红色会让用户在正常环境里看见一片红
        assertEquals(
            SourceStatus.NORMAL,
            SourceHealthJudge.status(row("wifi", lastFailure = "EMPTY"), now)
        )
    }

    @Test
    fun `超过新鲜窗口没有回调算异常`() {
        val stale = row("cell", lastCallbackAt = now - SourceHealthJudge.FRESH_WINDOW_MILLIS - 1)
        assertEquals(SourceStatus.ABNORMAL, SourceHealthJudge.status(stale, now))
    }

    @Test
    fun `恰好在新鲜窗口边界内仍是正常`() {
        val edge = row("cell", lastCallbackAt = now - SourceHealthJudge.FRESH_WINDOW_MILLIS + 1)
        assertEquals(SourceStatus.NORMAL, SourceHealthJudge.status(edge, now))
    }

    @Test
    fun `回调时间为零表示还没真正采过 是暂无数据`() {
        val health = row("gnss", lastCallbackAt = 0L, lastSuccessAt = 0L)
        assertEquals(SourceStatus.UNKNOWN, SourceHealthJudge.status(health, now))
    }

    @Test
    fun `快照恒定给出四个来源 缺记录的是暂无数据`() {
        val map = SourceHealthJudge.snapshot(listOf(row("gnss"), row("wifi")), now)

        assertEquals(4, map.size)
        assertEquals(SourceStatus.NORMAL, map[EvidenceSourceKind.GNSS])
        assertEquals(SourceStatus.NORMAL, map[EvidenceSourceKind.WIFI])
        assertEquals(SourceStatus.UNKNOWN, map[EvidenceSourceKind.BLUETOOTH])
        assertEquals(SourceStatus.UNKNOWN, map[EvidenceSourceKind.CELL])
    }

    @Test
    fun `快照里的来源名与后台写入名一致`() {
        // 后台 EvidenceCoordinator 写的是 wifi / bluetooth / cell / gnss 四个字面量，
        // 这里对不上就会全部显示「暂无数据」
        val rows = EvidenceSourceKind.entries.map { row(it.storageName) }
        val map = SourceHealthJudge.snapshot(rows, now)
        assertEquals(
            listOf(SourceStatus.NORMAL, SourceStatus.NORMAL, SourceStatus.NORMAL, SourceStatus.NORMAL),
            EvidenceSourceKind.entries.map { map[it] }
        )
    }
}
