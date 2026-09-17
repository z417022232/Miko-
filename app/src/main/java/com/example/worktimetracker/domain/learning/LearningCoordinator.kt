package com.example.worktimetracker.domain.learning

import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.location.AnchorLearner

/**
 * 学习层统一入口（方案 §二）。
 *
 * > 它只负责编排，不直接包含具体算法。
 *
 * 这条纪律是刻意的：算法必须能**单独被单测**（纯函数），
 * 而编排涉及数据库、时间、服务生命周期，只能靠集成验证。
 * 一旦把算法塞进编排器，算法就再也测不动了 —— 这是本项目已有的教训
 * （见 `TodayStatusPresenter`/`CalendarHeatPresenter` 为什么被强制抽成纯逻辑层）。
 *
 * 当前状态（v9.0）：**骨架已就位，算法按阶段分批接入**。
 * 阶段2 接的是地点锚点学习；指纹、班次、行程、工资各自的入口先占位，
 * 未实现的实现类必须是**显式空实现**而不是抛异常 —— 抛异常会让
 * 「还没做到这一步」变成「线上崩溃」。
 */
interface LearningCoordinator {

    /** 一条定位证据（含它的环境支持情况）进入学习管道。 */
    suspend fun onLocationEvidence(input: LocationEvidence)

    /** 用户确认了一条工时记录 → 高权重样本（方案 §五.1「用户确认记录高权重」）。 */
    suspend fun onWorkRecordConfirmed(recordId: Long)

    /** 用户修正了一条工时记录 → 反哺模型（方案 §十三 完成标准 9）。 */
    suspend fun onWorkRecordCorrected(recordId: Long)

    /** 用户确认了一张工资条 → 才允许进入工资学习（原则 2：OCR 未确认不参与学习）。 */
    suspend fun onSalarySlipConfirmed(payrollMonth: String)

    /** 从原始事实数据**全量重建**所有模型（原则 5）。返回每个模型的结果摘要。 */
    suspend fun rebuildAllModels(): List<RebuildResult>

    /** 全量重建单模型的结果：给用户看「重建了什么、用了几条、成没成功」。 */
    data class RebuildResult(
        val type: LearningModelType,
        val modelVersion: Long,
        val sampleCount: Int,
        val success: Boolean,
        val message: String
    )
}

/**
 * 一条定位证据的学习输入。
 *
 * 与 `location.evidence.GnssInput` 的区别：那个在采集层，带 provider 字符串等 Android 侧细节；
 * 这个在 domain，**只保留学习需要的字段**，于是学习算法可以纯 Kotlin 单测。
 */
data class LocationEvidence(
    val eventTime: Long,
    val latitude: Double,
    val longitude: Double,
    /**
     * 精度（米）。粗于 [AnchorLearner.MAX_ACCURACY_METERS] 的样本 [learnable] 直接为 false，
     * 即便漏过这一层，[AnchorLearner] 内部也会再挡一次。
     */
    val accuracyMeters: Float,
    /** 这条证据判定出来的地点（HOME/COMPANY/OTHER…），决定样本归给哪个地点模型 */
    val place: ResolvedPlace,
    /** 是否落在该地点的核心区域（方案 §三.2 的「核心区稳定停留」） */
    val inCore: Boolean,
    /** 核心区连续稳定停留的起点时刻；不在稳定状态时传 null */
    val stableSince: Long? = null,
    /** 支持该地点的环境来源类数（Wi-Fi/蓝牙/基站） */
    val ambientSourceCount: Int = 0,
    /** 推算补全的证据不参与学习（原则：学习输入必须是可靠原始事实） */
    val inferred: Boolean = false,
    /** 人工回放的证据不参与学习 */
    val manualReplay: Boolean = false
) {
    /** 该样本是否可用于锚点学习：必须是原始、可靠、落在核心区的观测。 */
    val learnable: Boolean
        get() = !inferred && !manualReplay && inCore &&
            stableSince != null && accuracyMeters > 0f &&
            accuracyMeters <= AnchorLearner.MAX_ACCURACY_METERS &&
            (place == ResolvedPlace.HOME || place == ResolvedPlace.COMPANY)
}

/**
 * 什么都不做的实现：用于「学习层还没接算法」的阶段，把编排缝先留出来。
 *
 * ⚠️ 不要把它当兜底丢给生产路径 **长期用** —— 它的存在是为了让调用点先编译通过，
 * 每个入口被真正实现后就该被替换掉。`rebuildAllModels` 返回空列表而不是假数据，
 * 免得 UI 上出现「重建成功」的谎报。
 */
class NoopLearningCoordinator : LearningCoordinator {
    override suspend fun onLocationEvidence(input: LocationEvidence) = Unit
    override suspend fun onWorkRecordConfirmed(recordId: Long) = Unit
    override suspend fun onWorkRecordCorrected(recordId: Long) = Unit
    override suspend fun onSalarySlipConfirmed(payrollMonth: String) = Unit
    override suspend fun rebuildAllModels(): List<LearningCoordinator.RebuildResult> = emptyList()
}
