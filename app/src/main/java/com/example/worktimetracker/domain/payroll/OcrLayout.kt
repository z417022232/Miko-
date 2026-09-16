package com.example.worktimetracker.domain.payroll

/**
 * OCR 出来的一行文本 + 它的版面位置（像素，y 向下、x 向右）。纯数据，零 Android 依赖，可单测。
 */
data class PositionedLine(
    val text: String,
    val top: Int,
    val bottom: Int,
    val left: Int,
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * 把「按块识别出来的行」还原成**视觉行**。
 *
 * ### 为什么必须有这一层（真机实测踩坑）
 * ML Kit 把工资条的**左列标签**和**右列金额**识别成了两个互不相干的 block，
 * 于是 `flatMap { it.lines }` 拿到的是一堆"只有标签"的行，紧跟一堆"只有金额"的行，
 * 而且块顺序还可能错乱。真实 8 月工资条截图（带平铺水印）实测：
 *
 * ```
 * 标签块: … |基本工资 绩效系数 |绩效工资 工龄工资 0独生费 |岗位津贴 …
 * 金额块: … 3,100.00  600  0.8  810.00  50.00  0.00  1,900.00 …
 * ```
 *
 * 靠"按顺序相邻配对"来还原表格是**不可靠**的（19 个标签对 10 个金额，一错就是错账）。
 * 改用**几何**：所有行按纵向位置聚类成视觉行，行内按 x 从左到右拼接 ——
 * 这与人的阅读方式一致，也不会因为块顺序错乱而错配。
 *
 * ### 不变量
 * - **同一视觉行才合并**；纵向明显错开的两行绝不合并（宁可漏识别，不要错配）。
 * - 输出顺序 = 自上而下，行内 = 自左而右。
 * - 输入顺序无关紧要（内部会先排序）。
 */
object OcrLayout {

    /** 纵向重叠达到较矮那行高度的这个比例，才算"同一视觉行"。 */
    private const val OVERLAP_RATIO = 0.5

    fun merge(lines: List<PositionedLine>): List<String> {
        val rows = mutableListOf<MutableList<PositionedLine>>()
        var bandTop = 0
        var bandBottom = 0

        lines.asSequence()
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .forEach { line ->
                val overlap = minOf(bandBottom, line.bottom) - maxOf(bandTop, line.top)
                val threshold = OVERLAP_RATIO * minOf(line.height, (bandBottom - bandTop).coerceAtLeast(1))
                val sameRow = rows.isNotEmpty() && overlap >= threshold
                if (sameRow) {
                    rows.last() += line
                    bandTop = minOf(bandTop, line.top)
                    bandBottom = maxOf(bandBottom, line.bottom)
                } else {
                    rows += mutableListOf(line)
                    bandTop = line.top
                    bandBottom = line.bottom
                }
            }

        return rows.map { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }
}
