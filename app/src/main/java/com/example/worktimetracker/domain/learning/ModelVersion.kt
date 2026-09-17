package com.example.worktimetracker.domain.learning

/**
 * 学习模型的类型（方案 §九「通用模型元数据」）。
 *
 * 每种类型各自维护独立的版本序列，互不影响：地点模型升级不该让班次画像失效。
 */
enum class LearningModelType(val label: String) {
    /** 地点锚点（配置锚点 / 学习锚点 / 半径） */
    PLACE_ANCHOR("地点锚点"),

    /** 环境指纹画像（出现概率、信号分布、时段分布） */
    FINGERPRINT("环境指纹"),

    /** 班次画像（到离岗分位数、时长、通勤） */
    SHIFT_PROFILE("班次画像"),

    /** 行程与候选事件 */
    JOURNEY("行程状态"),

    /** 工资参数分段与预测 */
    PAYROLL("工资参数");

    companion object {
        fun parse(raw: String?): LearningModelType? =
            raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
    }
}

/**
 * 模型版本状态。
 *
 * 「回滚」= 把新版本置 [RETIRED]、把目标版本置 [ACTIVE]，**从不删行** ——
 * 模型错误时必须能从原始数据全量重建（方案 §一 原则 5），而重建的前提是
 * 还看得见每一版是基于什么训出来的。
 */
enum class ModelStatus {
    /** 当前生效 */
    ACTIVE,

    /** 影子运行：已产出但**不参与判定**（方案 §三.2 的「影子验证」） */
    SHADOW,

    /** 已被更新版本取代，保留可追溯 */
    RETIRED,

    /** 判定为无效（数据被污染、锚点被用户否决等） */
    INVALID;

    companion object {
        fun parse(raw: String?): ModelStatus? =
            raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
    }
}

/**
 * 模型版本标识：类型 + 版本号。
 *
 * 版本号在**同类型内单调递增**，只表达「比谁新」，不承载语义
 * （不要从数字里推断训练时间，那要看 `learning_model_meta.trainedThrough`）。
 */
data class ModelVersion(val type: LearningModelType, val version: Long) {
    val next: ModelVersion get() = copy(version = version + 1)
}
