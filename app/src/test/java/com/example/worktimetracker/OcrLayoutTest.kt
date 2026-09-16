package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.OcrLayout
import com.example.worktimetracker.domain.payroll.PositionedLine
import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipOcrParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **版面还原**护栏 —— 针对真机实测的失败形态：ML Kit 把表格左右两列拆成两个 block。
 *
 * 夹具的行高统一取 40（真机截图上表格行高约这个量级），
 * 关键是**同一视觉行的标签与金额必须合并、不同行的绝不能合并**。
 */
class OcrLayoutTest {

    private fun line(text: String, y: Int, x: Int = 0) =
        PositionedLine(text = text, top = y, bottom = y + 40, left = x)

    @Test
    fun labelAndAmountFromSeparateBlocksMergeIntoOneVisualRow() {
        // 真机就是这么给的：一堆标签（左侧），紧跟一堆金额（右侧）
        val recognized = listOf(
            line("基本工资", 300, 100),
            line("岗位津贴", 380, 100),
            line("3,100.00", 300, 900),
            line("1,900.00", 380, 900),
        )
        assertEquals(
            listOf("基本工资 3,100.00", "岗位津贴 1,900.00"),
            OcrLayout.merge(recognized)
        )
    }

    @Test
    fun verticallySeparatedLinesNeverMerge() {
        val recognized = listOf(
            line("基本工资", 300, 100),
            line("3,100.00", 900, 900),   // 差得远，绝不能凑成一行
        )
        assertEquals(listOf("基本工资", "3,100.00"), OcrLayout.merge(recognized))
    }

    @Test
    fun rowElementsAreOrderedLeftToRight() {
        val recognized = listOf(
            line("3,100.00", 300, 900),
            line("基本工资", 300, 100),
        )
        assertEquals(listOf("基本工资 3,100.00"), OcrLayout.merge(recognized))
    }

    @Test
    fun rowsAreOrderedTopToBottom() {
        val recognized = listOf(
            line("工龄工资 50.00", 700),
            line("基本工资 3,100.00", 300),
        )
        assertEquals(listOf("基本工资 3,100.00", "工龄工资 50.00"), OcrLayout.merge(recognized))
    }

    @Test
    fun slightVerticalOffsetStillCountsAsTheSameRow() {
        // 表格里标签与金额的基线常有几像素差，仍应算同一行
        val recognized = listOf(
            line("效益奖金", 860, 100),
            PositionedLine("3046.55", 866, 906, 900),
        )
        assertEquals(listOf("效益奖金 3046.55"), OcrLayout.merge(recognized))
    }

    // ---------------------------------------------------------------- 端到端

    /** 模拟真机：带水印碎片的**双列**版面 → 应当完整解析出分项。 */
    @Test
    fun separatedColumnsWithWatermarkNoiseStillParse() {
        val labels = listOf(
            "基本工资" to 300, "绩效工资基数" to 380, "绩效系数" to 460, "绩效工资" to 540,
            "工龄工资" to 620, "独生费" to 700, "岗位津贴" to 780, "效益奖金" to 860,
            "加班工资" to 940,
        )
        val values = listOf(
            "3,100.00" to 300, "600" to 380, "0.8" to 460, "810.00" to 540,
            "50.00" to 620, "0.00" to 700, "1,900.00" to 780, "3046.55" to 860,
            "962.07" to 940,
        )
        // 平铺水印碎片与页面元素：都跟表格行同一 y，必须被滤掉且不参与合并
        val noise = listOf(
            line("刘亚东3702", 300, 500), line("0亚3702", 540, 500),
            line("S702", 780, 500), line("工资条详细", 100, 400),
            line("10:10", 60, 120), line("工资明细", 240, 100),
            line("金属车间", 200, 700), line("刘亚东", 220, 700),
        )
        val recognized = (labels + values).map { (t, y) -> line(t, y, if (t in labels.map { it.first }) 100 else 900) } + noise

        val p = SlipOcrParser.parseRecognized(listOf(recognized))

        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("810.00", p.items[SlipItemKey.PERFORMANCE_PAY])   // 不许被 600 抢走
        assertEquals("50.00", p.items[SlipItemKey.SENIOR_ALLOWANCE])
        assertEquals("1900.00", p.items[SlipItemKey.POST_ALLOWANCE])
        assertEquals("3046.55", p.items[SlipItemKey.BENEFIT_BONUS])
        assertEquals("962.07", p.items[SlipItemKey.OVERTIME_PAY])
        assertEquals(null, p.items[SlipItemKey.HOLIDAY_OVERTIME_PAY])
    }

    /** 多张图必须**逐图**还原版面，不能跨图按 y 聚类。 */
    @Test
    fun eachImageIsLaidOutSeparately() {
        val top = listOf(line("基本工资", 300, 100), line("3,100.00", 300, 900))
        val bottom = listOf(line("加班工资", 300, 100), line("962.07", 300, 900))
        val p = SlipOcrParser.parseRecognized(listOf(top, bottom))
        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("962.07", p.items[SlipItemKey.OVERTIME_PAY])
    }

    /** OCR 把数字认成字母：整行就是一个金额时才纠正。 */
    @Test
    fun letterDigitsInsideAnAmountAreFixed() {
        val recognized = listOf(line("社保个人 948.1l", 300, 100))
        assertEquals("948.11", SlipOcrParser.parseRecognized(listOf(recognized)).items[SlipItemKey.SOCIAL_INSURANCE])
    }

    /** 简繁混排归正：`高溫补贴` / `所得稅` 必须认成同一项。 */
    @Test
    fun traditionalVariantsAreNormalised() {
        val recognized = listOf(
            line("高溫补贴 300.00", 300, 100),
            line("所得稅 134.99", 380, 100),
            line("夜班补貼 630", 460, 100),
        )
        val p = SlipOcrParser.parseRecognized(listOf(recognized))
        assertEquals("300.00", p.items[SlipItemKey.HEAT_ALLOWANCE])
        assertEquals("134.99", p.items[SlipItemKey.INCOME_TAX])
        assertEquals("630", p.items[SlipItemKey.NIGHT_ALLOWANCE])
    }
}
