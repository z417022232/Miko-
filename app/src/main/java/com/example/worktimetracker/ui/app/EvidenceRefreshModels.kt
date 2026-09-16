package com.example.worktimetracker.ui.app

/**
 * 「立即刷新一次」的界面状态。
 *
 * 语义刻意区分两件事：
 * - [running] = 正在等服务回一次新的取样（按钮转圈、禁止重复点击）
 * - [message] = **一次性**反馈提示，展示后由 UI 调 `clearEvidenceRefreshMessage()` 消费掉
 *
 * 之所以用「一次性消息」而不是常驻文案：刷新的结果多半是「没取到新定位（室内正常）」，
 * 常驻在那儿会被当成故障提示；短暂提示才符合「我点了一下，它确实动过」的预期。
 */
data class EvidenceRefreshState(
    val running: Boolean = false,
    val message: String? = null,
    /** 本会话最近一次刷新完成的时间；0 = 还没刷过 */
    val completedAt: Long = 0L,
)
