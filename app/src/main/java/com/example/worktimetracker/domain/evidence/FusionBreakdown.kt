package com.example.worktimetracker.domain.evidence

/**
 * 融合明细字符串的结构化解析（界面稿 04「融合决策」）。
 *
 * 协调器发布的 `sourceBreakdown` 形如：
 * ```
 * GNSS=COMPANY q0.88 30s前(gps 8m) | WIFI=COMPANY q0.95 12s前 | CELL=UNKNOWN q0.10 41s前
 * ```
 * 界面要按来源逐行展示（名称 / 地点 / 质量 / 状态），所以在 domain 层做一次解析，
 * 而不是在 Composable 里切字符串——纯函数才好单测，也才不会再出现格式微调就静默错行。
 *
 * 容错优先：任何解析不了的片段**跳过**，绝不抛异常、也不编造 0 值行；
 * 上游格式演进时界面最多少显示一行，而不是崩。
 */
data class FusionSourceLine(
    /** 原始来源名（GNSS / WIFI / BLUETOOTH / CELL / MOTION / NETWORK_LOCATION / SHIFT_WINDOW）。 */
    val source: String,
    /** 该来源给出的地点判断（HOME / COMPANY / OTHER / MOVING / UNKNOWN）。 */
    val placeHint: String,
    /** 证据质量 0..1。 */
    val quality: Double,
    /** 距最近一次观测的秒数；无法解析时为 null。 */
    val ageSeconds: Int?,
    /** 绝对定位来源（gps / network / passive），环境证据为 null。 */
    val provider: String?,
    /** 原始精度（米），环境证据为 null。 */
    val accuracyMeters: Double?
)

object FusionBreakdown {

    private val LINE = Regex(
        """^([A-Z_]+)=([A-Z_]+)\s+q([0-9]*\.?[0-9]+)\s+(\d+)s前(?:\(([a-z_]+)\s+([0-9]*\.?[0-9]+)m\))?"""
    )

    fun parse(text: String?): List<FusionSourceLine> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split("|").mapNotNull { raw ->
            val match = LINE.find(raw.trim()) ?: return@mapNotNull null
            val (source, place, quality, age, provider, accuracy) = match.destructured
            FusionSourceLine(
                source = source,
                placeHint = place,
                quality = quality.toDoubleOrNull() ?: return@mapNotNull null,
                ageSeconds = age.toIntOrNull(),
                provider = provider.ifBlank { null },
                accuracyMeters = accuracy.toDoubleOrNull()
            )
        }
    }

    /** 来源名 → 界面标签。未知来源原样回显，保证新增来源不会显示成空白。 */
    fun sourceLabel(source: String): String = when (source) {
        "GNSS" -> "GPS"
        "NETWORK_LOCATION" -> "网络定位"
        "WIFI" -> "Wi-Fi"
        "BLUETOOTH" -> "蓝牙"
        "CELL" -> "基站"
        "MOTION" -> "Motion"
        "SHIFT_WINDOW" -> "班次窗口"
        else -> source
    }

    /**
     * 质量分档文案。
     *
     * 刻意**不**把低质量叫「冲突」——冲突是协调器的判定（见 `UNKNOWN_CONFLICT`），
     * 单看某一来源的低分只能说"弱"。混用会让用户以为两条证据在打架。
     */
    fun strengthLabel(quality: Double): String = when {
        quality >= 0.85 -> "强"
        quality >= 0.60 -> "稳定"
        quality >= 0.40 -> "弱"
        else -> "很弱"
    }

    /** 质量 0..1 → "88%"。四舍五入而不是截断：0.885 该显示 89%，截断会永远少一个百分点。 */
    fun percentLabel(quality: Double): String =
        "${Math.round(quality * 100).toInt().coerceIn(0, 100)}%"

    /** 地点提示 → 中文。 */
    fun placeLabel(placeHint: String): String = when (placeHint) {
        "HOME" -> "家"
        "COMPANY" -> "公司"
        "OTHER" -> "其他地点"
        "MOVING" -> "移动中"
        "UNKNOWN" -> "未指认"
        else -> placeHint
    }

    /** 明细行的副标题：观测时间与精度（有才拼）。 */
    fun detailLabel(line: FusionSourceLine): String {
        val parts = mutableListOf<String>()
        parts.add(placeLabel(line.placeHint))
        line.provider?.let { provider ->
            val accuracy = line.accuracyMeters
            parts.add(if (accuracy != null) "$provider ${accuracy.toInt()}m" else provider)
        }
        line.ageSeconds?.let { parts.add("${it}s 前") }
        return parts.joinToString(" · ")
    }
}
