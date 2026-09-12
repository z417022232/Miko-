package com.example.worktimetracker.data.repository

import com.example.worktimetracker.data.dao.HolidayDao
import com.example.worktimetracker.data.entity.HolidayEntity
import com.example.worktimetracker.data.remote.HolidayRemoteSource
import com.example.worktimetracker.data.remote.HolidaySyncStore
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.HolidayArrangement
import com.example.worktimetracker.domain.engine.HolidaySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Year

/** 一次同步的结果，供设置页展示。 */
data class HolidaySyncOutcome(
    val succeededYears: Set<Int>,
    val failures: Map<Int, String>,
    val host: String?,
    val atMillis: Long
) {
    val ok: Boolean get() = succeededYears.isNotEmpty()
}

/**
 * 节假日仓储：**远端拉取 → 落库缓存 → 供运行时合并层读取**。
 *
 * 降级策略（这是"以后节假日怎么获取"的最终答案）：
 * ```
 *  联网成功  → 写入 holidays 表 → HolidayCalendar 覆盖内嵌表
 *  联网失败  → 保留上次成功缓存；若无缓存则回落内嵌表 + 算法推导的法定节日
 *  跨年未更新 → 设置页显式告警"20XX 年节假日待更新"，但法定节日标记仍然正确
 * ```
 */
class HolidayRepository(
    private val dao: HolidayDao,
    private val store: HolidaySyncStore,
    private val source: HolidayRemoteSource = HolidayRemoteSource()
) {

    /** App 启动时把缓存灌进运行时合并层；无缓存时 [HolidayCalendarReset] 由调用方决定。 */
    suspend fun restoreCache(): List<HolidayArrangement> = withContext(Dispatchers.IO) {
        rowsToArrangements(dao.getAll())
    }

    /**
     * 同步指定年份。逐年独立成败，某年失败不影响其他年份。
     * @return 同步结果；调用方应随后 [restoreCache] 并刷新 [com.example.worktimetracker.domain.engine.HolidayCalendar]
     */
    suspend fun sync(years: Collection<Int>): HolidaySyncOutcome = withContext(Dispatchers.IO) {
        val succeeded = linkedSetOf<Int>()
        val failures = linkedMapOf<Int, String>()
        var host: String? = null

        years.distinct().sorted().forEach { year ->
            source.fetch(year).fold(
                onSuccess = { result ->
                    val rows = arrangementToRows(result.arrangement)
                    val start = "$year-01-01"
                    val end = "$year-12-31"
                    dao.deleteRange(start, end)
                    dao.upsertAll(rows)
                    succeeded += year
                    host = result.host
                },
                onFailure = { error ->
                    failures[year] = error.message ?: error.javaClass.simpleName
                }
            )
        }

        val now = System.currentTimeMillis()
        if (succeeded.isNotEmpty()) {
            store.recordSuccess(now, host ?: "", coveredYearsWithCache(succeeded))
        }
        if (failures.isNotEmpty()) {
            store.recordFailure(now, failures.entries.joinToString("；") { "${it.key} 年：${it.value}" })
        }
        HolidaySyncOutcome(succeeded, failures, host, now)
    }

    /** 已缓存数据的年份（用于判断"当前年份是否已有公告数据"）。 */
    suspend fun cachedYears(): Set<Int> = withContext(Dispatchers.IO) {
        dao.getAll().mapNotNull { yearOf(it.date) }.toSet()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        store.clear()
    }

    private suspend fun coveredYearsWithCache(fresh: Set<Int>): Set<Int> =
        cachedYears() + fresh

    companion object {
        /** 自动同步的过期阈值：30 天。 */
        const val SYNC_MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * 是否需要自动同步：缓存过期，或当前年份还没拿到公告数据。
         * 纯函数，便于单测。
         */
        fun needsSync(
            lastSuccessAt: Long?,
            cachedYears: Set<Int>,
            currentYear: Int,
            nowMillis: Long,
            maxAgeMillis: Long = SYNC_MAX_AGE_MILLIS
        ): Boolean {
            if (currentYear !in cachedYears) return true
            val last = lastSuccessAt ?: return true
            return nowMillis - last > maxAgeMillis
        }

        /** 需要同步的年份范围：去年（跨年查询）～明年（提前排班）。 */
        fun targetYears(referenceYear: Int = Year.now().value): List<Int> =
            listOf(referenceYear - 1, referenceYear, referenceYear + 1)

        fun yearOf(date: String): Int? =
            date.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }

        /** 数据库行 → 运行时安排对象。 */
        fun rowsToArrangements(rows: List<HolidayEntity>): List<HolidayArrangement> =
            rows.groupBy { yearOf(it.date) }
                .filterKeys { it != null }
                .map { (year, yearRows) ->
                    val makeup = linkedSetOf<String>()
                    val rest = linkedSetOf<String>()
                    val festivals = linkedMapOf<String, String>()
                    var source = HolidaySource.REMOTE
                    yearRows.forEach { row ->
                        val kind = DayKind.entries.firstOrNull { it.name.equals(row.type.trim(), true) }
                        when (kind) {
                            DayKind.MAKEUP_WORKDAY -> makeup += row.date
                            DayKind.HOLIDAY_REST -> rest += row.date
                            // 仅导入数据允许自带节日名；远端数据的 name 一律忽略（会把整段假期都标成节日）
                            DayKind.FESTIVAL -> if (row.name.isNotBlank()) festivals[row.date] = row.name
                            else -> Unit
                        }
                        if (row.source.equals(HolidaySource.IMPORTED.name, true)) {
                            source = HolidaySource.IMPORTED
                        }
                    }
                    HolidayArrangement(
                        year = year!!,
                        makeupWorkdays = makeup,
                        restDays = rest,
                        festivalNames = festivals,
                        source = source
                    )
                }

        /** 运行时安排对象 → 数据库行。 */
        fun arrangementToRows(arrangement: HolidayArrangement): List<HolidayEntity> {
            val rows = mutableListOf<HolidayEntity>()
            arrangement.makeupWorkdays.forEach {
                rows += HolidayEntity(it, "", DayKind.MAKEUP_WORKDAY.name, arrangement.source.name.lowercase())
            }
            arrangement.restDays.forEach {
                rows += HolidayEntity(it, "", DayKind.HOLIDAY_REST.name, arrangement.source.name.lowercase())
            }
            arrangement.festivalNames.forEach { (date, name) ->
                rows += HolidayEntity(date, name, DayKind.FESTIVAL.name, arrangement.source.name.lowercase())
            }
            return rows.sortedBy { it.date }
        }
    }
}
