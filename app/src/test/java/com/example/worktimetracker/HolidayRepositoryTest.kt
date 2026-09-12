package com.example.worktimetracker

import com.example.worktimetracker.data.entity.HolidayEntity
import com.example.worktimetracker.data.repository.HolidayRepository
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.HolidayArrangement
import com.example.worktimetracker.domain.engine.HolidaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 节假日仓储的纯逻辑测试（不触碰 Room / 网络）。 */
class HolidayRepositoryTest {

    // ---------- 同步时机判定 ----------

    @Test
    fun `needsSyncWhenCurrentYearHasNoData`() {
        assertTrue(
            "无任何数据时必然需要同步",
            HolidayRepository.needsSync(null, emptySet(), 2026, nowMillis = 1_000_000L)
        )
        assertTrue(
            "缺当前年份必须同步（跨年第一天就会触发）",
            HolidayRepository.needsSync(1_000_000L, setOf(2025), 2026, nowMillis = 1_000_000L + 1000L)
        )
    }

    @Test
    fun `needsSyncWhenCacheIsStale`() {
        val day = 24L * 60 * 60 * 1000
        val last = 1_000_000_000_000L
        // 29 天前：还新鲜
        assertFalse(
            HolidayRepository.needsSync(last, setOf(2026), 2026, nowMillis = last + 29 * day)
        )
        // 31 天前：过期
        assertTrue(
            HolidayRepository.needsSync(last, setOf(2026), 2026, nowMillis = last + 31 * day)
        )
    }

    @Test
    fun `needsSyncWhenNeverSucceededEvenIfDataExists`() {
        assertTrue(
            "有缓存但从未成功同步过（如手动导入），仍应尝试联网",
            HolidayRepository.needsSync(null, setOf(2026), 2026, nowMillis = 5L)
        )
    }

    @Test
    fun `targetYearsCoverPreviousCurrentAndNext`() {
        assertEquals(listOf(2025, 2026, 2027), HolidayRepository.targetYears(2026))
        assertEquals(3, HolidayRepository.targetYears(2026).size)
    }

    // ---------- 落库 / 读库往返 ----------

    @Test
    fun `remoteArrangementSurvivesRoundTrip`() {
        val arrangement = HolidayArrangement(
            year = 2026,
            makeupWorkdays = setOf("2026-09-20", "2026-10-10"),
            restDays = setOf("2026-09-26", "2026-09-27"),
            source = HolidaySource.REMOTE
        )
        val restored = HolidayRepository.rowsToArrangements(
            HolidayRepository.arrangementToRows(arrangement)
        ).single()

        assertEquals(2026, restored.year)
        assertEquals(arrangement.makeupWorkdays, restored.makeupWorkdays)
        assertEquals(arrangement.restDays, restored.restDays)
        assertEquals(HolidaySource.REMOTE, restored.source)
        assertTrue("远端数据不得携带节日名", restored.festivalNames.isEmpty())
    }

    @Test
    fun `importedFestivalNamesSurviveRoundTrip`() {
        val arrangement = HolidayArrangement(
            year = 2030,
            restDays = setOf("2030-09-03"),
            festivalNames = mapOf("2030-09-03" to "抗战胜利纪念日"),
            source = HolidaySource.IMPORTED
        )
        val restored = HolidayRepository.rowsToArrangements(
            HolidayRepository.arrangementToRows(arrangement)
        ).single()
        assertEquals(mapOf("2030-09-03" to "抗战胜利纪念日"), restored.festivalNames)
        assertEquals(HolidaySource.IMPORTED, restored.source)
    }

    @Test
    fun `rowsAreGroupedByYearAndUnknownTypesIgnored`() {
        val rows = listOf(
            HolidayEntity("2026-09-20", "", DayKind.MAKEUP_WORKDAY.name, "remote"),
            HolidayEntity("2026-09-26", "", DayKind.HOLIDAY_REST.name, "remote"),
            HolidayEntity("2027-02-07", "", DayKind.MAKEUP_WORKDAY.name, "remote"),
            HolidayEntity("2026-09-14", "", "SOMETHING_UNKNOWN", "remote"),
            HolidayEntity("2026-09-25", "中秋节", DayKind.FESTIVAL.name, "imported")
        )
        val byYear = HolidayRepository.rowsToArrangements(rows).associateBy { it.year }

        assertEquals(setOf("2026-09-20"), byYear.getValue(2026).makeupWorkdays)
        assertEquals(setOf("2026-09-26"), byYear.getValue(2026).restDays)
        assertEquals(setOf("2027-02-07"), byYear.getValue(2027).makeupWorkdays)
        assertEquals(mapOf("2026-09-25" to "中秋节"), byYear.getValue(2026).festivalNames)
        // 2026 里混进了 imported 行 → 整年标记为导入来源
        assertEquals(HolidaySource.IMPORTED, byYear.getValue(2026).source)
        assertEquals(HolidaySource.REMOTE, byYear.getValue(2027).source)
    }

    @Test
    fun `invalidDatesAreDroppedWhenReadingCache`() {
        val rows = listOf(
            HolidayEntity("bad-date", "", DayKind.HOLIDAY_REST.name, "remote"),
            HolidayEntity("1899-01-01", "", DayKind.HOLIDAY_REST.name, "remote"),
            HolidayEntity("2026-09-26", "", DayKind.HOLIDAY_REST.name, "remote")
        )
        val result = HolidayRepository.rowsToArrangements(rows)
        assertEquals("只有合法年份的行会被保留", 1, result.size)
        assertEquals(2026, result.single().year)
    }

    @Test
    fun `yearExtractionIsDefensive`() {
        assertEquals(2026, HolidayRepository.yearOf("2026-09-26"))
        assertNull(HolidayRepository.yearOf("abc"))
        assertNull(HolidayRepository.yearOf(""))
        assertNull(HolidayRepository.yearOf("1800-01-01"))
    }
}
