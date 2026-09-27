package com.example.worktimetracker.data

import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.domain.learning.LearningModelType

/**
 * 学习模型版本元数据的**一次性修复**（DB v18 历史伤害）。
 *
 * 背景：v18 之前 `learning_model_meta` 没有站点维度，所有站点共用一条 `PLACE_ANCHOR`
 * 版本序列，`retireOtherVersions` 只按 modelType 过滤 ⇒ 后开版本的站点会把先开版本站点的
 * 版本一并 RETIRED。真机实测：`learned_place_models` 里站点 1 指向 v3，而 v3 是 RETIRED
 * （只有最后开版本的站点 2 拿到 ACTIVE 的 v4）。
 *
 * 代码侧的退休范围已在 v18 修好，但**已经写进库里的错不会自己消失**，所以补这一步：
 * 让每个地点认领它当前指向的版本（依据是显式指针 `modelVersion`，不是猜的），
 * 并把它置为 ACTIVE —— 这样「回滚 = 把新版本置 RETIRED、目标版本置 ACTIVE」对每个站点都成立。
 *
 * 幂等（只处理 `placeId IS NULL` 的老行），因此可以安全地挂在启动流程里反复跑。
 */
object LearningModelRepair {

    suspend fun runOnce(db: AppDatabase) {
        val dao = db.learningModelDao()
        val places = runCatching { dao.allPlaces() }.getOrNull() ?: return
        val type = LearningModelType.PLACE_ANCHOR.name
        places.forEach { place ->
            val version = place.modelVersion
            if (version > 0L) {
                runCatching { dao.claimVersion(type, version, place.placeId) }
            }
        }
    }
}
