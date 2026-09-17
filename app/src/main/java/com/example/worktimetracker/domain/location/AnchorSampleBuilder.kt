package com.example.worktimetracker.domain.location

import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import java.time.Instant
import java.time.ZoneId

/**
 * 从原始定位轨迹里切出「可靠学习样本」（方案 §三.2 的输入准备）。
 *
 * 这一段刻意做成**纯函数**：它决定了「什么算一次有效停留」，
 * 是整条锚点学习链路里最容易写歪的地方。一旦它掺进 Room / Android Location，
 * 就只能靠真机轨迹回归，而这恰恰是本项目历史上最难复现的一类 bug。
 *
 * 三条判定口径：
 *  1. **入核**：点必须在站点半径内（用配置半径，不用学习半径 —— 否则会自我强化）；
 *  2. **连续**：相邻点间隔 > [MAX_GAP_MILLIS] 就断开成两段停留，取最长的那段；
 *  3. **跨天**：按设备时区取自然日，`yyyy-MM-dd` 作为「跨了几天」的口径。
 */
object AnchorSampleBuilder {

    /** 相邻两点间隔超过 15 分钟即视为「离开过」，停留段在此断开。 */
    const val MAX_GAP_MILLIS = 15 * 60_000L

    /** 一条原始定位点（只保留采样需要的字段，不带 provider 等 Android 细节）。 */
    data class Fix(
        val time: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float
    )

    /** 切出来的结果：样本 + 最长连续停留时长。 */
    data class Result(
        val samples: List<AnchorLearner.Sample>,
        val stableMillis: Long
    )

    private val analyzer = LocationStatusAnalyzer()

    /**
     * 切样本。
     *
     * @param fixes    原始定位点，**必须按时间升序**
     * @param center   该站点的锚点（用配置锚点，不用学习锚点）
     * @param radiusMeters 站点半径，决定「在不在核心区」
     * @param zone     设备时区，决定自然日切分
     */
    fun build(
        fixes: List<Fix>,
        center: GeoPoint,
        radiusMeters: Int,
        zone: ZoneId
    ): Result {
        if (fixes.isEmpty()) return Result(emptyList(), 0L)

        val radius = radiusMeters.toDouble()
        val inCoreFlags = fixes.map { fix ->
            analyzer.distanceMeters(center.latitude, center.longitude, fix.latitude, fix.longitude) <= radius
        }

        // 最长连续入核停留段：只在相邻两点的「间隔」上断开，不看中间是否有点缺失
        var longest = 0L
        var runStart = -1
        for (i in fixes.indices) {
            if (!inCoreFlags[i]) {
                runStart = -1
                continue
            }
            if (runStart < 0) {
                runStart = i
            } else if (fixes[i].time - fixes[i - 1].time > MAX_GAP_MILLIS) {
                runStart = i
            }
            val span = fixes[i].time - fixes[runStart].time
            if (span > longest) longest = span
        }

        val samples = fixes.mapIndexed { index, fix ->
            AnchorLearner.Sample(
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMeters = fix.accuracyMeters,
                time = fix.time,
                localDay = Instant.ofEpochMilli(fix.time).atZone(zone).toLocalDate().toString(),
                inCore = inCoreFlags[index]
            )
        }
        return Result(samples = samples, stableMillis = longest)
    }
}
