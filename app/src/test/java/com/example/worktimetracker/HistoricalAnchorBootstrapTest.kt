package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.HistoricalAnchorBootstrap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalAnchorBootstrapTest {
    @Test fun `seven historical days can bootstrap without waiting another seven days`() {
        assertTrue(HistoricalAnchorBootstrap.canApply(everApplied = false, distinctDays = 7, offsetMeters = 12.0))
    }

    @Test fun `insufficient history or large offset stays conservative`() {
        assertFalse(HistoricalAnchorBootstrap.canApply(everApplied = false, distinctDays = 6, offsetMeters = 12.0))
        assertFalse(HistoricalAnchorBootstrap.canApply(everApplied = false, distinctDays = 30, offsetMeters = 31.0))
    }

    // ---------- 2026-09-22：真机体检问题 1（SHADOW ↔ AUTO_APPLIED 每日振荡） ----------

    @Test fun `once applied the site must never bootstrap again`() {
        // 被影子验证否决后 autoApplied 落回 false，但「曾经生效过」的痕迹还在。
        // 此时再看 30 天历史样本直接放行 = 影子验证被永久绕过（实测两个站点每天都在振荡）。
        assertFalse(HistoricalAnchorBootstrap.canApply(everApplied = true, distinctDays = 30, offsetMeters = 6.0))
    }

    @Test fun `everApplied gate fails structurally regardless of how good the data is`() {
        // 门槛必须结构上可失败：样本再好、偏移再小也不能翻案，
        // 否则这条线就只是装饰（MEMORY 里 75m 离散度下限的教训）。
        val days = (AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS * 4).toInt()
        assertFalse(HistoricalAnchorBootstrap.canApply(everApplied = true, distinctDays = days, offsetMeters = 0.0))
        assertTrue(HistoricalAnchorBootstrap.dataSufficient(distinctDays = days, offsetMeters = 0.0))
    }
}
