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
 * 4. **参数类短句必须占位屏蔽**（见 [IGNORE_ALIASES]）：工资条上常印着
 *    「绩效工资基数 600」「个税起征点 5000」「夜班津贴单价 45」这类**计薪参数**，
 *    它们本身不是分项、却含着一个分项别名。不屏蔽就会把参数金额当分项金额记账
 *    —— 真实案例（2026-08 条）：`绩效工资基数 600` 把 `绩效工资 810.00` 顶掉。
 * 5. **发薪日期不能取「第一个日期」**：条上第一个日期往往是入职日期
 *    （2026-08 条：`入职日期 2024/02/26`）。见 [pickPaymentDate]。
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

    /**
     * 日期行如果带着这些词，说明它**不是**发薪日期。
     * `入职` 是必须挡住的 —— 2026-08 条第一个日期就是 `入职日期 2024/02/26`。
     */
    private val DATE_NEGATIVE = listOf("入职", "入社", "参加工作", "离职", "出生", "生日", "毕业", "合同")

    /** 带这些词的日期行才是发薪日期。 */
    private val DATE_POSITIVE =
        listOf("发薪", "发放日期", "工资发放", "到账", "支付日期", "工资日期", "结算日期")

    /** 金额：优先千分位写法，避免把 `1,500.00` 读成 `1` 或 `500.00`。 */
    private val NUM = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

    private val GROSS_ALIASES = listOf("应发工资", "应发合计", "应发金额", "应发")
    private val NET_ALIASES =
        listOf("银行实发", "实发工资", "实发合计", "实发金额", "到手工资", "实发", "到手")
    private val ATTEND_ALIASES = listOf("计薪出勤天数", "应出勤天数", "计薪天数", "出勤天数")
    private val NIGHT_ALIASES = listOf("计薪夜班数", "夜班天数", "夜班个数", "夜班数")

    /**
     * **近形字**：真机实测 ML Kit 把标签认错成这些字
     * （`岗位津贴`→`岗位律贴`、`全勤`→`全勒`、`事假`→`事價`、`绩效扣款`→`绩效扣歉`）。
     *
     * 处理方式是**在生成别名时展开变体**，而不是在 [normalize] 里改字 ——
     * 改字会污染整行（`律` 在别的词里是正常字），登记变体只影响这一个标签。
     * 实测每一条都会让整项漏识别（标签行被判成噪声滤掉，金额变成无主的孤儿行）。
     *
     * 后续真机再发现新的近形字，往这里加一条即可。
     */
    private val CONFUSABLE = mapOf(
        '津' to '律',
        '勤' to '勒',
        '假' to '價',
        '款' to '歉',
    )

    /** 别名 → 别名 + "每个可混淆字各错一次"的变体。 */
    private fun variants(alias: String): List<String> = buildList {
        add(alias)
        alias.forEachIndexed { index, c ->
            CONFUSABLE[c]?.let { wrong ->
                add(alias.substring(0, index) + wrong + alias.substring(index + 1))
            }
        }
    }

    /**
     * **参数类短句**（不是分项）—— 命中后只**占住区间**、不产生任何值。
     *
     * 两个作用：
     * - 让更长的参数名把里面的短别名挡掉（`绩效工资基数` 遮住 `绩效工资`）；
     * - 让参数行的金额不被当成分项金额（否则 `个税起征点 5000` 会让个税变成 5000）。
     */
    private val IGNORE_ALIASES: List<String> = listOf(
        // 绩效：基数是计薪参数（PayRateKey.PERF_BASE），系数是倍率
        "绩效工资基数", "绩效系数", "绩效扣款比例",
        // 各类"基数 / 单价 / 起征点 / 税率"都是参数表里的东西
        "基本工资基数", "岗位津贴基数", "加班工资基数", "夜班津贴单价",
        "社保基数", "公积金基数", "公积金缴存基数", "缴费基数",
        "个税起征点", "起征点", "税率",
        // 出勤相关参数
        "计薪出勤天数系数", "全勤天数", "全勤系数", "出勤折算系数",
    ).flatMap { variants(it) }

    /**
     * 分项别名表 —— 覆盖工资条上的常见写法。
     * 顺序无关紧要，匹配前会按别名长度降序排。
     */
    private val ITEM_ALIASES: List<Pair<String, SlipItemKey>> = buildList {
        fun add(key: SlipItemKey, vararg aliases: String) {
            // 每个别名连同近形字变体一起登记
            aliases.forEach { alias -> variants(alias).forEach { add(it to key) } }
        }
        add(SlipItemKey.BASIC_SALARY, "基本工资", "岗位基本工资", "基础工资")
        add(SlipItemKey.POST_ALLOWANCE, "岗位津贴", "岗位工资", "岗位补贴")
        add(SlipItemKey.PERFORMANCE_PAY, "绩效工资", "绩效奖金")
        add(SlipItemKey.SENIOR_ALLOWANCE, "工龄工资", "工龄补贴", "工龄奖")
        // 条上印的是「全勤」两个字，不带「奖」—— 漏掉它会让校验①差 100 元不平
        add(SlipItemKey.FULL_ATTENDANCE, "全勤奖", "全勤奖金", "全勤")
        add(SlipItemKey.OVERTIME_PAY, "加班工资", "加班津贴", "加班费")
        // 「假期加班工资」与「加班工资」是条上的两栏（2026-08：962.07 / 0），不能互相冒充
        add(
            SlipItemKey.HOLIDAY_OVERTIME_PAY,
            "法定节假日加班工资", "节假日加班工资", "假期加班工资"
        )
        add(SlipItemKey.NIGHT_ALLOWANCE, "夜班津贴", "夜班补贴", "夜班费", "夜班补助")
        add(SlipItemKey.BENEFIT_BONUS, "效益奖金", "效益工资", "效益奖")
        add(SlipItemKey.HEAT_ALLOWANCE, "高温补贴", "高温津贴", "高温费")
        add(SlipItemKey.SICK_PAY, "病假工资", "病假补贴", "病假")
        add(SlipItemKey.BACK_PAY, "补发工资", "补发")
        add(
            SlipItemKey.OTHER_ADD,
            "其他加项", "其他收入", "其他补贴", "独生费", "独生子女费", "话费补贴", "话费津贴"
        )
        add(SlipItemKey.PERFORMANCE_DEDUCT, "绩效扣款", "绩效扣发")
        add(SlipItemKey.PERSONAL_LEAVE_DEDUCT, "事假扣款", "事假扣发", "事假")
        add(SlipItemKey.LATE_DEDUCT, "迟到扣款", "迟到扣发", "迟到")
        add(SlipItemKey.SOCIAL_INSURANCE, "社保个人", "社会保险", "社保扣款", "养老保险", "社保")
        add(SlipItemKey.HOUSING_FUND, "住房公积金个人", "住房公积金", "公积金个人", "公积金")
        add(SlipItemKey.INCOME_TAX, "个人所得税", "个税", "所得税")
        add(SlipItemKey.DORM_DEDUCT, "宿舍代扣", "宿舍费", "住宿费")
        add(SlipItemKey.UNION_FEE, "工会费", "工会经费")
        add(SlipItemKey.OTHER_DEDUCT, "其他扣款", "其他扣项", "其他应扣款", "其他应扣")
    }.sortedByDescending { it.first.length }

    /**
     * 分项与参数混排后的完整别名表，**统一按长度降序**。
     * key 为 null = 参数类，只占位不取值。
     */
    private val ALL_ALIASES: List<Pair<String, SlipItemKey?>> =
        (ITEM_ALIASES.map { it.first to it.second } + IGNORE_ALIASES.map { it to null })
            .sortedByDescending { it.first.length }

    /**
     * **完整链路**：逐图去噪 + 还原视觉行，再解析。
     *
     * ⚠️ **必须逐图还原版面**：不同截图的坐标原点相同，跨图一起聚类会把两张图
     * 相同的 y 值当成同一行，错配成灾。
     *
     * @param images 每张图各自的识别结果（含坐标）
     */
    fun parseRecognized(images: List<List<PositionedLine>>): Parsed = parse(rowsForDisplay(images))

    /**
     * 解析器**实际看到**的文本行（已去噪、已还原版面）。
     * 界面用它给用户看"到底读出了什么"，是识别不准时的唯一现场证据。
     */
    fun rowsForDisplay(images: List<List<PositionedLine>>): List<String> =
        images.flatMap { image ->
            OcrLayout.merge(image.filter { !isNoise(normalize(it.text)) })
        }

    /**
     * @param rawLines OCR 出来的文本行（顺序即版面自上而下）
     */
    fun parse(rawLines: List<String>): Parsed {
        var gross: String? = null
        var net: String? = null
        var attend: String? = null
        var night: String? = null
        val items = LinkedHashMap<SlipItemKey, String>()

        val lines = rawLines.asSequence()
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toList()

        lines.forEach { line ->
            if (gross == null) gross = valueAfter(line, GROSS_ALIASES)
            if (net == null) net = valueAfter(line, NET_ALIASES)
            if (attend == null) attend = valueAfter(line, ATTEND_ALIASES)
            if (night == null) night = valueAfter(line, NIGHT_ALIASES)
            itemPairs(line).forEach { (key, value) -> if (key !in items) items[key] = value }
        }

        return Parsed(
            paymentDate = pickPaymentDate(lines),
            grossText = gross,
            netText = net,
            attendDays = attend,
            nightShifts = night,
            items = items,
        )
    }

    /**
     * 挑「发薪日期」—— **绝不取第一个日期**。
     *
     * 工资条上的日期不止一个（入职日期、发薪日期、打印日期），而 2026-08 条里
     * 排在最前的是 `入职日期 2024/02/26`。两趟挑：
     * 1. 上下文（本行 + 上一行）必须带「发薪 / 发放 / 到账」等**正词**；
     * 2. 且**不得**带「入职 / 离职 / 出生」等负词；
     * 3. 两个条件都满足才采纳，否则留空（调用方不覆盖已有值）。
     *
     * ⚠️ 曾经写成"没有正词时退回第一条不含负词的日期"，真机上直接踩坑：
     * ML Kit 把 `入职日期 2024/02/26` 拆成了别的行序，负词没落在上下文里，
     * 于是 2024-02-26 被写进了发薪日期。**日期字段宁缺勿错** —— 空着用户会自己填，
     * 填错他会直接保存进库。
     *
     * 上下文取「上一行 + 本行」：OCR 常把标签与日期拆成两行。
     */
    private fun pickPaymentDate(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val date = extractDate(line) ?: return@forEachIndexed
            val context = (lines.getOrNull(index - 1) ?: "") + line
            // 必须带正词，且不带负词 —— 两个条件都满足才采纳
            if (DATE_POSITIVE.any { context.contains(it) } &&
                DATE_NEGATIVE.none { context.contains(it) }
            ) return date
        }
        return null
    }

    private fun extractDate(line: String): String? {
        val m = DATE.find(line) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        return "%04d-%02d-%02d".format(y, mo, d)
    }

    /** 整行就是一个金额（可带千分位）。 */
    private val AMOUNT_ONLY =
        Regex("""\s*\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?\s*|\s*\d+(?:\.\d{1,2})?\s*""")

    /**
     * 这一行是不是"与工资条无关的噪声"。
     *
     * 真机实测：钉钉工资条截图带**满屏平铺水印**（`刘亚东3702` 重复 30 多次），
     * 加上页眉说明、状态栏、图标，103 行里大半是噪声。噪声本身不匹配任何别名，
     * 但它们会**挤进同一个视觉行**，把标签与金额之间的字距撑爆，让 GAP_LIMIT 把真数据拦掉。
     * 所以做白名单：只留「金额行 / 日期行 / 含已知标签的行」。
     */
    private fun isNoise(line: String): Boolean {
        if (line.isBlank()) return true
        if (AMOUNT_ONLY.matches(line)) return false
        if (DATE.containsMatchIn(line)) return false
        return !(ALL_ALIASES.any { line.contains(it.first) } ||
            GROSS_ALIASES.any { line.contains(it) } ||
            NET_ALIASES.any { line.contains(it) } ||
            ATTEND_ALIASES.any { line.contains(it) } ||
            NIGHT_ALIASES.any { line.contains(it) })
    }

    /**
     * 表头类字段命中别名后，若紧跟的是这些**参数后缀**，说明这一栏是计薪参数而不是金额。
     *
     * 与 [IGNORE_ALIASES] 是同一件事的两面：那边靠"占位"挡分项，
     * 这边靠"后缀"挡表头字段 —— 参数表里印着
     * `夜班津贴单价 45`（会让夜班津贴变成 45）、`个税起征点 5000`（会让个税变成 5000）、
     * `计薪出勤天数系数 1.15`。**这两处必须一起改，别只修一边。**
     */
    private val PARAM_SUFFIXES =
        listOf("系数", "基数", "单价", "比例", "起征点", "税率", "折算", "标准", "上限", "下限")

    /** 表头类字段：命中别名后，取它右侧**紧跟**的第一个数字。 */
    private fun valueAfter(line: String, aliases: List<String>): String? {
        for (alias in aliases) {
            val at = line.indexOf(alias)
            if (at < 0) continue
            val seg = line.substring(at + alias.length)
            if (seg.trimStart().startsWithAny(PARAM_SUFFIXES)) continue
            val hit = NUM.find(seg) ?: continue
            if (hit.range.first > GAP_LIMIT) continue
            return hit.value.replace(",", "")
        }
        return null
    }

    private fun String.startsWithAny(prefixes: List<String>): Boolean =
        prefixes.any { startsWith(it) }

    private data class Hit(val start: Int, val end: Int, val key: SlipItemKey?)

    /**
     * 一行里可能并排两个键值对（`基本工资 3000.00  岗位津贴 1500.00`），
     * 所以先定位所有标签、再各自取到「下一个标签之前」的数字。
     *
     * 参数类别名（key == null）**同样参与占位**，只是不产出值 ——
     * 这正是 `绩效工资基数 600` 挡掉 `绩效工资` 的机制。
     */
    private fun itemPairs(line: String): List<Pair<SlipItemKey, String>> {
        val hits = mutableListOf<Hit>()
        for ((alias, key) in ALL_ALIASES) {
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
            val key = hit.key ?: return@forEachIndexed   // 参数类只占位
            val limit = hits.getOrNull(index + 1)?.start ?: line.length
            if (limit <= hit.end) return@forEachIndexed
            val seg = line.substring(hit.end, limit)
            val num = NUM.find(seg) ?: return@forEachIndexed
            if (num.range.first > GAP_LIMIT) return@forEachIndexed
            out += key to num.value.replace(",", "")
        }
        return out
    }

    /**
     * OCR 常见的**简繁混排**归正 —— 真机实测同一张条子上出现了
     * `高溫补贴`、`所得稅`、`夜班补貼`，不归一会整项漏识别。
     */
    private val CHAR_FIX = mapOf(
        '溫' to '温', '稅' to '税', '補' to '补', '貼' to '贴',
        '個' to '个', '積' to '积', '資' to '资', '險' to '险', '費' to '费',
        '節' to '节', '據' to '据', '標' to '标', '數' to '数', '應' to '应',
        '發' to '发', '獎' to '奖', '單' to '单', '總' to '总',
    )

    /** 全角 → 半角 + 简繁归正：手机上扫出来的工资条常带全角数字与全角标点。 */
    private fun normalize(line: String): String {
        val half = buildString(line.length) {
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
                        else -> CHAR_FIX[c] ?: c
                    }
                )
            }
        }
        return fixLetterDigits(half)
    }

    /**
     * OCR 把数字认成字母（`948.1l`、`1O0O`）——**逐 token 判定，且必须本来是金额形状**。
     *
     * 判据是"替换后整个 token 正好是一个金额 **且** 原 token 至少含一个数字"：
     * - `948.1l` → `948.11` ✓
     * - `ol`（页面元素）→ 没有数字，不动 ✓
     * - `5G` → `G` 不在映射里，不动 ✓
     *
     * 这个判据比"整行是金额"宽，因为真机给出的行是 `社保个人 948.1l` 这种
     * **标签与金额已经粘在一起**的形式。
     */
    private fun fixLetterDigits(line: String): String =
        line.split(' ').joinToString(" ") { fixToken(it) }

    private fun fixToken(token: String): String {
        if (token.none { it.isDigit() }) return token
        if (token.none { it == 'l' || it == 'I' || it == 'O' || it == 'o' }) return token
        val fixed = token.map {
            when (it) {
                'l', 'I' -> '1'
                'O', 'o' -> '0'
                else -> it
            }
        }.joinToString("")
        return if (AMOUNT_ONLY.matches(fixed)) fixed else token
    }
}
