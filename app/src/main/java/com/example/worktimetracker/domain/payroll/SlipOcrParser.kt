package com.example.worktimetracker.domain.payroll

/**
 * 工资条照片的 OCR 文本 → 录入草稿字段（**纯函数，零 IO，可单测**）。
 *
 * 设计取舍：
 * 1. **只认「标签 + 同一行的数字」**。工资条是一张表，标签与金额基本同行；
 *    跨行找数字很容易把下一行的金额错配到上一行 —— 宁可漏识别，也不要错配。
 * 2. **别名按长度降序匹配，命中区间互斥**。「绩效工资」和「绩效扣款」同时出现时，
 *    不会被更短的别名抢先吃掉半行。
 * 3. **识别不到就返回 null，绝不猜**。调用方只覆盖非空字段，
 *    用户自己已经填好的内容不会被一张拍糊的照片抹掉。
 */
object SlipOcrParser {

    /** 解析结果。所有字段为「已识别到的录入文本」，null = 没认出来。 */
    data class Parsed(
        val paymentDate: String? = null,
        val grossText: String? = null,
        val netText: String? = null,
        val attendDays: String? = null,
        val nightShifts: String? = null,
        val items: Map<SlipItemKey, String> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = paymentDate == null && grossText == null && netText == null &&
                attendDays == null && nightShifts == null && items.isEmpty()

        /** 给用户看的一句话回执。 */
        val summary: String
            get() {
                val parts = buildList {
                    if (paymentDate != null) add("发薪日期")
                    if (grossText != null) add("应发")
                    if (netText != null) add("实发")
                    if (attendDays != null) add("出勤天数")
                    if (nightShifts != null) add("夜班数")
                    if (items.isNotEmpty()) add("${items.size} 个分项")
                }
                return if (parts.isEmpty()) "没读到可用字段" else "识别到 " + parts.joinToString("、")
            }
    }

    // 标签与数字之间允许的分隔长度：「（元）」「：」这类最多几个字符；
    // 再长就说明这个标签后面根本没跟金额（是标题或说明文字），不该硬凑。
    private const val GAP_LIMIT = 6

    private val DATE = Regex("""(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""")

    /** 金额：优先千分位写法，避免把 `1,500.00` 读成 `1` 或 `500.00`。 */
    private val NUM = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

    private val GROSS_ALIASES = listOf("应发工资", "应发合计", "应发金额", "应发")
    private val NET_ALIASES = listOf("实发工资", "实发合计", "实发金额", "到手工资", "实发", "到手")
    private val ATTEND_ALIASES = listOf("计薪出勤天数", "应出勤天数", "计薪天数", "出勤天数")
    private val NIGHT_ALIASES = listOf("计薪夜班数", "夜班天数", "夜班个数", "夜班数")

    /**
     * 分项别名表 —— 覆盖工资条上的常见写法。
     * 顺序无关紧要，匹配前会按别名长度降序排。
     */
    private val ITEM_ALIASES: List<Pair<String, SlipItemKey>> = buildList {
        fun add(key: SlipItemKey, vararg aliases: String) {
            aliases.forEach { add(it to key) }
        }
        add(SlipItemKey.BASIC_SALARY, "基本工资", "岗位基本工资", "基础工资")
        add(SlipItemKey.POST_ALLOWANCE, "岗位津贴", "岗位工资", "岗位补贴")
        add(SlipItemKey.PERFORMANCE_PAY, "绩效工资", "绩效奖金")
        add(SlipItemKey.SENIOR_ALLOWANCE, "工龄工资", "工龄补贴", "工龄奖")
        add(SlipItemKey.FULL_ATTENDANCE, "全勤奖", "全勤奖金")
        add(SlipItemKey.OVERTIME_PAY, "加班工资", "加班津贴", "加班费")
        add(SlipItemKey.NIGHT_ALLOWANCE, "夜班津贴", "夜班补贴", "夜班费", "夜班补助")
        add(SlipItemKey.BENEFIT_BONUS, "效益奖金", "效益工资", "效益奖")
        add(SlipItemKey.HEAT_ALLOWANCE, "高温补贴", "高温津贴", "高温费")
        add(SlipItemKey.SICK_PAY, "病假工资", "病假补贴", "病假")
        add(SlipItemKey.BACK_PAY, "补发工资", "补发")
        add(SlipItemKey.OTHER_ADD, "其他加项", "其他收入", "其他补贴")
        add(SlipItemKey.PERFORMANCE_DEDUCT, "绩效扣款", "绩效扣发")
        add(SlipItemKey.PERSONAL_LEAVE_DEDUCT, "事假扣款", "事假扣发", "事假")
        add(SlipItemKey.LATE_DEDUCT, "迟到扣款", "迟到扣发", "迟到")
        add(SlipItemKey.SOCIAL_INSURANCE, "社保个人", "社会保险", "社保扣款", "养老保险", "社保")
        add(SlipItemKey.HOUSING_FUND, "住房公积金个人", "住房公积金", "公积金个人", "公积金")
        add(SlipItemKey.INCOME_TAX, "个人所得税", "个税", "所得税")
        add(SlipItemKey.DORM_DEDUCT, "宿舍代扣", "宿舍费", "住宿费")
        add(SlipItemKey.UNION_FEE, "工会费", "工会经费")
        add(SlipItemKey.OTHER_DEDUCT, "其他扣款", "其他扣项")
    }.sortedByDescending { it.first.length }

    /**
     * @param rawLines OCR 出来的文本行（顺序即版面自上而下）
     */
    fun parse(rawLines: List<String>): Parsed {
        var date: String? = null
        var gross: String? = null
        var net: String? = null
        var attend: String? = null
        var night: String? = null
        val items = LinkedHashMap<SlipItemKey, String>()

        rawLines.asSequence()
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .forEach { line ->
                if (date == null) {
                    DATE.find(line)?.let { m ->
                        val y = m.groupValues[1].toIntOrNull()
                        val mo = m.groupValues[2].toIntOrNull()
                        val d = m.groupValues[3].toIntOrNull()
                        if (y != null && mo != null && d != null && mo in 1..12 && d in 1..31) {
                            date = "%04d-%02d-%02d".format(y, mo, d)
                        }
                    }
                }
                if (gross == null) gross = valueAfter(line, GROSS_ALIASES)
                if (net == null) net = valueAfter(line, NET_ALIASES)
                if (attend == null) attend = valueAfter(line, ATTEND_ALIASES)
                if (night == null) night = valueAfter(line, NIGHT_ALIASES)
                itemPairs(line).forEach { (key, value) -> if (key !in items) items[key] = value }
            }

        return Parsed(
            paymentDate = date,
            grossText = gross,
            netText = net,
            attendDays = attend,
            nightShifts = night,
            items = items,
        )
    }

    /** 表头类字段：命中别名后，取它右侧**紧跟**的第一个数字。 */
    private fun valueAfter(line: String, aliases: List<String>): String? {
        for (alias in aliases) {
            val at = line.indexOf(alias)
            if (at < 0) continue
            val seg = line.substring(at + alias.length)
            val hit = NUM.find(seg) ?: continue
            if (hit.range.first > GAP_LIMIT) continue
            return hit.value.replace(",", "")
        }
        return null
    }

    private data class Hit(val start: Int, val end: Int, val key: SlipItemKey)

    /**
     * 一行里可能并排两个键值对（`基本工资 3000.00  岗位津贴 1500.00`），
     * 所以先定位所有标签、再各自取到「下一个标签之前」的数字。
     */
    private fun itemPairs(line: String): List<Pair<SlipItemKey, String>> {
        val hits = mutableListOf<Hit>()
        for ((alias, key) in ITEM_ALIASES) {
            var from = 0
            while (true) {
                val at = line.indexOf(alias, from)
                if (at < 0) break
                val end = at + alias.length
                // 与已命中区间重叠的短别名直接丢弃 —— 这就是「长别名优先」的落地方式
                if (hits.none { at < it.end && end > it.start }) hits += Hit(at, end, key)
                from = at + 1
            }
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortBy { it.start }

        val out = mutableListOf<Pair<SlipItemKey, String>>()
        hits.forEachIndexed { index, hit ->
            val limit = hits.getOrNull(index + 1)?.start ?: line.length
            if (limit <= hit.end) return@forEachIndexed
            val seg = line.substring(hit.end, limit)
            val num = NUM.find(seg) ?: return@forEachIndexed
            if (num.range.first > GAP_LIMIT) return@forEachIndexed
            out += hit.key to num.value.replace(",", "")
        }
        return out
    }

    /** 全角 → 半角：手机上扫出来的工资条常带全角数字与全角标点。 */
    private fun normalize(line: String): String = buildString(line.length) {
        line.forEach { c ->
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '．' -> '.'
                    '，' -> ','
                    '：' -> ':'
                    '（' -> '('
                    '）' -> ')'
                    '　' -> ' '
                    else -> c
                }
            )
        }
    }
}
