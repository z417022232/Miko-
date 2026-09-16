package com.example.worktimetracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.worktimetracker.ui.app.WorkTimeViewModel

private enum class CalendarPage { HOME, MONTHLY, SLIP }

/**
 * 「日历」一级页宿主。
 *
 * 界面稿 v4 把「统计」从一级入口降级：月度统计不再单占一个 Tab，
 * 而是从日历（或「本月」卡）下钻进来。功能一个不少，只是不再与
 * 「今天算不算在上班」争夺一级入口。
 *
 * v6 第一步再加一层下钻：月卡上的「工资条录入与核对」。进去时把**当前日历所在月**
 * 当锚点传下去 —— 用户站在哪个月的日历上，这笔工资默认就记到那个计薪月；
 * 只有当「该月没有条子且上一个计薪月也空着」时才先问用户（见 `SlipMonthResolver`）。
 */
@Composable
fun CalendarHost(vm: WorkTimeViewModel, onOpenToday: () -> Unit = {}) {
    var page by remember { mutableStateOf(CalendarPage.HOME) }
    var slipAnchorMonth by remember { mutableStateOf<String?>(null) }
    BackHandler(page != CalendarPage.HOME) { page = CalendarPage.HOME }
    when (page) {
        CalendarPage.HOME -> CalendarScreen(
            vm,
            onOpenMonthly = { page = CalendarPage.MONTHLY },
            onOpenToday = onOpenToday,
            onOpenSlip = { month ->
                slipAnchorMonth = month.toString()
                page = CalendarPage.SLIP
            }
        )
        CalendarPage.MONTHLY -> StatisticsScreen(vm, onBack = { page = CalendarPage.HOME })
        CalendarPage.SLIP -> SlipEntryPage(
            onBack = { page = CalendarPage.HOME },
            initialMonth = slipAnchorMonth,
            // 把主 VM 传下去，录入页才能把「条上实发」一键存成月度实发（计薪基准）
            mainVm = vm
        )
    }
}
