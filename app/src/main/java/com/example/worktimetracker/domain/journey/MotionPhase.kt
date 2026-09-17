package com.example.worktimetracker.domain.journey

/**
 * 运动形态（阶段 3 第一版，**只有三档**，§5.2.1 二轮 P0-4）。
 *
 * ⚠️ 证据边界（硬约束）：采集层（`MotionEvidenceController`）实测只有
 * **SignificantMotion 触发 + 加速度阈值**，只能证明「发生了明显运动」，
 * **分不出步行和车载**。因此枚举**不得**定义 `WALKING` / `VEHICLE` ——
 * 没有分类器之前，枚举不得表达超出证据能力的事实。
 * 将来要区分步/车，需先单独建运动分类器（Activity Recognition / 定位速度 + 稳定时长 /
 * 多源联合），再扩展本枚举并同步 §5.6 的 `modelVersion`。
 *
 * [UNKNOWN] = 尚无运动判定，或判定已过期（`motionObservedAt` 距 `now` 超过
 * `JourneyConfig.motionExpirySeconds`）。
 */
enum class MotionPhase {
    STATIONARY,
    MOVING,
    UNKNOWN;

    companion object {
        /**
         * 从持久化字符串解析；**未知值返回 null（失败），绝不猜测**。
         *
         * 注意 [UNKNOWN] 是**合法的观察态**（没有运动数据），不是"解析失败的安全兜底" ——
         * 两者不可混用：解析失败必须让调用方显式处理（重置或拒绝），
         * 不能把"存了个不认识的字符串"悄悄变成"没有运动数据"。
         */
        fun parseOrNull(raw: String?): MotionPhase? = entries.firstOrNull { it.name == raw }
    }
}
