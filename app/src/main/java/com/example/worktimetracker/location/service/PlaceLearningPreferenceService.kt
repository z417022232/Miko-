package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.data.entity.PlaceLearningPreferenceEntity
import com.example.worktimetracker.domain.location.AnchorCandidateStatus
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.PlaceLearningPreferencePolicy
import java.time.ZoneId

/**
 * 用户对学习校准的**停用 / 重新开启**（方案 §十一 阶段2 的回退入口，DB v16）。
 *
 * ## 只做两件事
 *
 * 1. 写一行偏好（[PlaceLearningPreferencePolicy] 说该写什么，这里只负责落库）；
 * 2. 按 [PlaceLearningPreferencePolicy.Effect] 调整模型/窗口 —— 停用时**什么都不调**。
 *
 * ## 回退不删数据（硬约束）
 *
 * 「停用学习校准」**不是**「删除学习成果」：
 * `place_anchor_candidates`（观测日志）、`learned_place_models`（模型）、
 * `learning_model_meta`（版本）一律不动。所以停用后重新开启，
 * 学习是从原处继续的，用户不会因为按过一次暂停而丢掉几周的观察。
 *
 * 用户要「真的清掉」是另一件事（清数据是显式入口，不混在开关里）。
 *
 * ## 重新开启为什么要动模型
 *
 * 见 [PlaceLearningPreferencePolicy] 的类注释：开启时吊销 `autoApplied` 并重开前向窗口，
 * 否则「开启后立刻生效」，7 天重新验证等于没做。
 * 注意这是**算法规则**（暂停会中断连续观察），不是把用户偏好写进模型 ——
 * 偏好的真相始终只在 `place_learning_preferences` 那一行。
 */
class PlaceLearningPreferenceService(
    private val db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /**
     * 设置某地点的自动校准开关。
     *
     * 幂等：重复设成同一个值只会刷新 `updatedAt`；已经停用时再停用不会重复重开窗口
     * （停用本来也不重开窗口，见 [PlaceLearningPreferencePolicy.disable]）。
     */
    suspend fun setAutoApplyEnabled(
        placeId: Long,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): PlaceLearningPreferencePolicy.Effect {
        val dao = db.learningModelDao()
        val effect = if (enabled) {
            PlaceLearningPreferencePolicy.enable(placeId, now)
        } else {
            PlaceLearningPreferencePolicy.disable(placeId, now)
        }

        runCatching {
            dao.upsertPreference(
                PlaceLearningPreferenceEntity(
                    placeId = placeId,
                    autoApplyEnabled = effect.preference.autoApplyEnabled,
                    updatedAt = now
                )
            )

            // 吊销可生效状态（只在开启时非 null；停用时整段不执行 —— 用户偏好不改算法事实）
            effect.modelAutoAppliedAfter?.let { autoApplied ->
                dao.setAutoApplied(placeId, autoApplied, now)
            }

            if (effect.reopenShadowWindow) {
                reopenWindow(placeId, now)
            }

            db.appLogDao().insert(
                AppLogEntity(
                    type = LOG_TYPE,
                    content = buildString {
                        append("地点 ").append(placeId).append(" 学习校准")
                        if (enabled) append("恢复，从冻结状态继续")
                        else append("暂停并冻结")
                        append("；学习数据一律保留")
                    }
                )
            )
        }

        return effect
    }

    /**
     * 重开前向窗口：把**最新那一行**候选的窗口起点推到此刻。
     *
     * 只改一行 —— 见 `LearningModelDao.reopenShadowWindow` 的说明，
     * 改成「按 placeId 全部改」会把几段不连续的观察并成一个窗口，
     * 那正是影子验证要防的事。
     *
     * 还没有候选行时什么都不做：没有窗口可重开，等下次学习自然形成新窗口
     * （那本来就是全新的窗口，无需干预）。
     */
    private suspend fun reopenWindow(placeId: Long, now: Long) {
        val dao = db.learningModelDao()
        val latest = dao.latestCandidate(placeId) ?: return
        dao.reopenShadowWindow(
            id = latest.id,
            now = now,
            status = AnchorCandidateStatus.SHADOW.name,
            explanation = "用户重新开启学习校准：前向验证从此刻重新计时"
        )
    }

    private companion object {
        const val LOG_TYPE = "LEARNING"
    }
}
