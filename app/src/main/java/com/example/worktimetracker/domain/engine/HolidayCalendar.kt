package com.example.worktimetracker.domain.engine

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * 运行时节假日合并层：**远端/导入的放假安排 > 内嵌公告表 > 算法推导的法定节日 + 周末**。
 *
 * 分三层是为了"断网也不空窗"：
 * - 联网成功 → 用远端公告数据（可覆盖任意年份）
 * - 联网失败/未同步 → 回落到内嵌表（2026）+ 算法推导（法定节日对任意年份都成立）
 * - 都没有 → 至少还能正确标出周末与 13 个法定节日当天
 *
 * 线程安全：UI 线程读、同步协程写，用 [AtomicReference] 整体替换，不做原地修改。
 */
object HolidayCalendar {

    private val overrides = AtomicReference<Map<Int, HolidayArrangement>>(emptyMap())

    /** 用远端/导入数据覆盖（整体替换，不做增量合并，避免脏数据残留）。 */
    fun apply(arrangements: Collection<HolidayArrangement>) {
        overrides.set(arrangements.associateBy { it.year })
    }

    fun reset() {
        overrides.set(emptyMap())
    }

    /** 该年的生效安排：远端优先，其次内嵌；都没有则 null（退化为「法定节日 + 周末」）。 */
    fun arrangementFor(date: LocalDate): HolidayArrangement? =
        overrides.get()[date.year] ?: ChinaHolidayProvider.embeddedArrangement(date.year)

    /** 该年数据实际来自哪里，用于设置页展示。 */
    fun sourceFor(date: LocalDate): HolidaySource =
        overrides.get()[date.year]?.source
            ?: ChinaHolidayProvider.embeddedArrangement(date.year)?.source
            ?: HolidaySource.EMBEDDED

    fun info(date: LocalDate): DayInfo = ChinaHolidayProvider.info(date, arrangementFor(date))

    fun name(date: LocalDate): String? = info(date).festivalName

    fun badge(date: LocalDate): String? = info(date).let { it.festivalName ?: it.kind.shortLabel }

    /** 已加载远端数据的年份。 */
    fun coveredYears(): Set<Int> = overrides.get().keys.toSet()

    fun hasOverride(): Boolean = overrides.get().isNotEmpty()
}
