package com.example.worktimetracker.domain.evidence

import com.example.worktimetracker.data.entity.LocationHealthEntity

/**
 * 用户能看懂的「四个来源」——与 `location_health` 表里的 `name` 一一对应。
 *
 * 刻意**不含 motion**：运动传感器不产生地点证据（方案一），摆出来只会让用户以为
 * 「有个来源坏了」；它只负责唤醒重新取证。
 */
enum class EvidenceSourceKind(val storageName: String, val label: String) {
    GNSS("gnss", "GPS"),
    WIFI("wifi", "Wi-Fi"),
    BLUETOOTH("bluetooth", "蓝牙"),
    CELL("cell", "基站"),
}

/** 单个来源的可用性。 */
enum class SourceStatus {
    /** 近期有过成功回调，且没有硬性故障 */
    NORMAL,

    /** 权限缺失 / 开关关闭 / 长时间没有任何回调 */
    ABNORMAL,

    /** 后台还没记录过这个来源（刚装、刚解锁、或从未扫描过） */
    UNKNOWN,
}

/**
 * 来源健康判定（**纯函数**，零 IO）。
 *
 * 数据来源是 `location_health` 表 —— 服务每次环境采样后按来源写一行
 * （成功与失败都写，见 `EvidenceCoordinator.collectAmbient`），
 * 所以「最后回调时间」是真实的活动痕迹，不是乐观假设。
 *
 * 判定口径（顺序即优先级）：
 * 1. 没有记录 → [SourceStatus.UNKNOWN]（不谎报成正常，也不吓唬用户）
 * 2. `registered == false` → 异常
 * 3. `lastFailure` ∈ {PERMISSION, SECURITY, DISABLED} → 异常（这是真故障，不是环境为空）
 * 4. 距最后回调超过 [FRESH_WINDOW_MILLIS] → 异常（服务可能已停）
 * 5. 其余 → 正常
 *
 * ⚠️ `EMPTY` / `FAILED` **不算**异常：前者是「这附近没有可用的 AP/信标」，
 * 后者是单次采集抖动 —— 把它们染成红色会让用户在正常环境里看见一片红。
 */
object SourceHealthJudge {

    /**
     * 「多久没回调算不正常」。
     *
     * 环境三源按常规采样档（最长 30 分钟）触发，且运动时还会额外触发；
     * GPS 静止时有 50 米最小位移门槛，室内可能长时间不回调。2 小时留足了余量，
     * 又能在服务真的死掉时及时变红。
     */
    const val FRESH_WINDOW_MILLIS = 2 * 60 * 60_000L

    /** 明确表示「这个来源根本不可用」的失败原因。 */
    private val HARD_FAILURES = setOf("PERMISSION", "SECURITY", "DISABLED")

    /**
     * 允许的时钟偏差。回调时间比当前时间还晚超过这个量，就不是"新鲜"，而是数据异常
     * （系统改过时间、时区跳变、写库时用了错的时钟）。
     *
     * ⚠️ 若不挡，`now - lastCallbackAt` 是负数，永远超不过 [FRESH_WINDOW_MILLIS]，
     * 一个**永远不会过期**的脏时间戳会把来源一直显示成"正常"，看门狗也永远不动作
     * （2026-09-16 复查 P2）。
     */
    const val FUTURE_SKEW_TOLERANCE_MILLIS = 5 * 60_000L

    fun status(health: LocationHealthEntity?, now: Long): SourceStatus = when {
        health == null -> SourceStatus.UNKNOWN
        !health.registered -> SourceStatus.ABNORMAL
        health.lastFailure in HARD_FAILURES -> SourceStatus.ABNORMAL
        health.lastCallbackAt <= 0L -> SourceStatus.UNKNOWN
        // 时间戳落在未来 → 数据异常，不能因为"差值算出来是负的"就当正常
        health.lastCallbackAt > now + FUTURE_SKEW_TOLERANCE_MILLIS -> SourceStatus.ABNORMAL
        now - health.lastCallbackAt > FRESH_WINDOW_MILLIS -> SourceStatus.ABNORMAL
        else -> SourceStatus.NORMAL
    }

    /**
     * 异常时的中文原因（界面上给用户看的一句话）；正常/未知返回 `null`。
     *
     * 需要 [now] 才能区分「过期」与「时间戳异常」两种异常。
     */
    fun reason(health: LocationHealthEntity?, now: Long): String? = when {
        health == null -> null
        !health.registered -> "未注册"
        health.lastFailure == "PERMISSION" -> "缺少权限"
        health.lastFailure == "SECURITY" -> "系统安全策略拦截"
        health.lastFailure == "DISABLED" -> "开关已关闭"
        health.lastCallbackAt <= 0L -> null
        health.lastCallbackAt > now + FUTURE_SKEW_TOLERANCE_MILLIS -> "回调时间异常"
        now - health.lastCallbackAt > FRESH_WINDOW_MILLIS -> "长时间没有回调"
        else -> null
    }

    /** 一次判定四个来源，键恒定存在（缺记录的来源是 [SourceStatus.UNKNOWN]）。 */
    fun snapshot(
        rows: List<LocationHealthEntity>,
        now: Long,
    ): Map<EvidenceSourceKind, SourceStatus> {
        val byName = rows.associateBy { it.name }
        return EvidenceSourceKind.entries.associateWith { kind ->
            status(byName[kind.storageName], now)
        }
    }
}
