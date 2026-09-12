package com.example.worktimetracker

import com.example.worktimetracker.domain.model.ShiftType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ShiftType 字符串归一化（写入侧 normalize / 读取侧 parse）。
 *
 * 背景：同一字段历史上出现 3 种写法——枚举名、中文显示标签、null。
 * 旧版 CalendarScreen 手动工时对话框把显示标签（"白班"）直接写回 shift，
 * 使记录在 ShiftProfileLearner 与日历标签链路中静默失效（真实数据 id=15/158）。
 */
class ShiftTypeNormalizationTest {
    @Test fun parseAcceptsEnumNamesAndChineseLabels() {
        assertEquals(ShiftType.DAY_SHIFT, ShiftType.parse("DAY_SHIFT"))
        assertEquals(ShiftType.NIGHT_SHIFT, ShiftType.parse("NIGHT_SHIFT"))
        assertEquals(ShiftType.DAY_SHIFT, ShiftType.parse("白班"))
        assertEquals(ShiftType.NIGHT_SHIFT, ShiftType.parse("夜班"))
    }

    @Test fun parseTrimsWhitespaceAndRejectsUnknownOrNull() {
        assertEquals(ShiftType.NIGHT_SHIFT, ShiftType.parse("  夜班 "))
        assertNull(ShiftType.parse(null))
        assertNull(ShiftType.parse(""))
        assertNull(ShiftType.parse("白夜班"))
    }

    @Test fun normalizeReturnsEnumNameAndDefaultsUnknownToDayShift() {
        assertEquals("NIGHT_SHIFT", ShiftType.normalize("夜班"))
        assertEquals("DAY_SHIFT", ShiftType.normalize("白班"))
        assertEquals("DAY_SHIFT", ShiftType.normalize(null))
        assertEquals("DAY_SHIFT", ShiftType.normalize("??? "))
    }

    @Test fun normalizeIsIdempotent() {
        listOf("DAY_SHIFT", "NIGHT_SHIFT", "白班", "夜班", null).forEach { raw ->
            assertEquals(ShiftType.normalize(raw), ShiftType.normalize(ShiftType.normalize(raw)))
        }
    }
}
