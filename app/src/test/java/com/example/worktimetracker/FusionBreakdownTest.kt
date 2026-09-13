package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.FusionBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 融合明细解析护栏。
 *
 * 这个字符串由 [com.example.worktimetracker.location.evidence.EvidenceCoordinator] 拼装，
 * 是「融合决策」页唯一的证据来源。它一旦格式微调，界面要么静默少显示一行、要么整页空白——
 * 所以容错行为（跳过坏的、保留好的）必须被测试钉住。
 */
class FusionBreakdownTest {

    @Test
    fun `解析带精度与环境来源的完整行`() {
        val lines = FusionBreakdown.parse("GNSS=COMPANY q0.88 30s前(gps 8m)")
        assertEquals(1, lines.size)
        val line = lines.first()
        assertEquals("GNSS", line.source)
        assertEquals("COMPANY", line.placeHint)
        assertEquals(0.88, line.quality, 1e-9)
        assertEquals(30, line.ageSeconds)
        assertEquals("gps", line.provider)
        assertEquals(8.0, line.accuracyMeters!!, 1e-9)
    }

    @Test
    fun `解析不带精度的环境来源行`() {
        val lines = FusionBreakdown.parse("WIFI=HOME q0.95 12s前")
        assertEquals(1, lines.size)
        assertNull(lines.first().provider)
        assertNull(lines.first().accuracyMeters)
        assertEquals(12, lines.first().ageSeconds)
    }

    @Test
    fun `按竖线切分多来源`() {
        val text = "GNSS=COMPANY q0.88 30s前(gps 8m) | WIFI=COMPANY q0.95 12s前 | " +
            "BLUETOOTH=COMPANY q0.91 45s前 | CELL=UNKNOWN q0.10 41s前 | MOTION=COMPANY q0.70 60s前"
        val lines = FusionBreakdown.parse(text)
        assertEquals(5, lines.size)
        assertEquals(listOf("GNSS", "WIFI", "BLUETOOTH", "CELL", "MOTION"), lines.map { it.source })
    }

    @Test
    fun `坏片段被跳过而不是整段失效`() {
        val lines = FusionBreakdown.parse("GNSS=COMPANY q0.88 30s前 | 这不是证据 | WIFI=HOME q0.90 5s前")
        assertEquals(2, lines.size)
        assertEquals(listOf("GNSS", "WIFI"), lines.map { it.source })
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(FusionBreakdown.parse(null).isEmpty())
        assertTrue(FusionBreakdown.parse("").isEmpty())
        assertTrue(FusionBreakdown.parse("   ").isEmpty())
    }

    @Test
    fun `来源标签映射与未知来源回显`() {
        assertEquals("GPS", FusionBreakdown.sourceLabel("GNSS"))
        assertEquals("Wi-Fi", FusionBreakdown.sourceLabel("WIFI"))
        assertEquals("蓝牙", FusionBreakdown.sourceLabel("BLUETOOTH"))
        assertEquals("Motion", FusionBreakdown.sourceLabel("MOTION"))
        assertEquals("NEW_SOURCE", FusionBreakdown.sourceLabel("NEW_SOURCE"))
    }

    @Test
    fun `质量分档边界`() {
        assertEquals("强", FusionBreakdown.strengthLabel(0.85))
        assertEquals("强", FusionBreakdown.strengthLabel(1.0))
        assertEquals("稳定", FusionBreakdown.strengthLabel(0.84))
        assertEquals("稳定", FusionBreakdown.strengthLabel(0.60))
        assertEquals("弱", FusionBreakdown.strengthLabel(0.59))
        assertEquals("弱", FusionBreakdown.strengthLabel(0.40))
        assertEquals("很弱", FusionBreakdown.strengthLabel(0.39))
        assertEquals("很弱", FusionBreakdown.strengthLabel(0.0))
    }

    @Test
    fun `百分比文案四舍五入且落在 0 到 100`() {
        assertEquals("88%", FusionBreakdown.percentLabel(0.8849))
        assertEquals("89%", FusionBreakdown.percentLabel(0.8851))
        assertEquals("100%", FusionBreakdown.percentLabel(1.4))
        assertEquals("0%", FusionBreakdown.percentLabel(-0.5))
    }

    @Test
    fun `副标题只拼存在的部分`() {
        val withProvider = FusionBreakdown.parse("GNSS=COMPANY q0.88 30s前(gps 8m)").first()
        assertEquals("公司 · gps 8m · 30s 前", FusionBreakdown.detailLabel(withProvider))

        val withoutProvider = FusionBreakdown.parse("CELL=UNKNOWN q0.10 41s前").first()
        assertEquals("未指认 · 41s 前", FusionBreakdown.detailLabel(withoutProvider))
    }
}
