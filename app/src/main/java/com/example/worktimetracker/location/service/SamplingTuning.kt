package com.example.worktimetracker.location.service

/**
 * 用户档位 → 引擎毫秒值的**唯一换算点**（纯 Kotlin，可单测）。
 *
 * 把换算抽出来而不是散在服务里，是因为这里承载两条必须成立的不变量：
 *
 * 1. **常规采集间隔夹在 [1, 10] 分钟**——用户档位不能比"快速档"（转换/贴边/移动）更激进，
 *    也不该比"默认档"更省电，否则省电优化会被一个滑杆文案悄悄推翻；
 * 2. **Burst 上限夹在 [1, 10] 分钟**——用户只能**调低**（更省电，代价是可能漏掉短时进出），
 *    永远不能突破 10 分钟硬顶。那是防后台被系统限制的硬约束，不是可配置项。
 *
 * 默认值刻意等于历史常量（常规 5 分钟、Burst 10 分钟），所以既有单测与线上行为都不变。
 */
object SamplingTuning {

    /** Burst 上限的硬顶（分钟）：用户只能调低，不能突破。 */
    const val HARD_BURST_CAP_MINUTES = 10
    const val MIN_BURST_CAP_MINUTES = 1

    const val DEFAULT_SAMPLING_INTERVAL_MINUTES = 5
    const val MIN_SAMPLING_INTERVAL_MINUTES = 1
    const val MAX_SAMPLING_INTERVAL_MINUTES = 10

    /** 「常规采集间隔」→ 毫秒。null / 越界都回落到合法区间。 */
    fun normalIntervalMillis(samplingIntervalMinutes: Int?): Long =
        (samplingIntervalMinutes ?: DEFAULT_SAMPLING_INTERVAL_MINUTES)
            .coerceIn(MIN_SAMPLING_INTERVAL_MINUTES, MAX_SAMPLING_INTERVAL_MINUTES) * 60_000L

    /** 「Burst 上限」→ 毫秒。null 用硬顶；超出硬顶的值被压回硬顶。 */
    fun burstCapMillis(burstCapMinutes: Int?): Long =
        (burstCapMinutes ?: HARD_BURST_CAP_MINUTES)
            .coerceIn(MIN_BURST_CAP_MINUTES, HARD_BURST_CAP_MINUTES) * 60_000L

    /**
     * Burst 硬顶后"仍在移动"时继续跟踪的最低保持档。
     *
     * 长途移动不能一直按 1 分钟档烧电，所以设一个下限；下限本身不低于历史 5 分钟，
     * 但用户把常规间隔调到 10 分钟时跟随到 10 分钟（更省电）。
     */
    fun movingTrackFloorMillis(samplingIntervalMinutes: Int?): Long =
        maxOf(5 * 60_000L, normalIntervalMillis(samplingIntervalMinutes))
}
