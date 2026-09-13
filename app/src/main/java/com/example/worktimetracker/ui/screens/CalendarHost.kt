package com.example.worktimetracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.worktimetracker.ui.app.WorkTimeViewModel

private enum class CalendarPage { HOME, MONTHLY }

/**
 * 「日历」一级页宿主。
 *
 * 界面稿 v4 把「统计」从一级入口降级：月度统计不再单占一个 Tab，
 * 而是从日历（或「本月」卡）下钻进来。功能一个不少，只是不再与
 * 「今天算不算在上班」争夺一级入口。
 */
@Composable
fun CalendarHost(vm: WorkTimeViewModel) {
    var page by remember { mutableStateOf(CalendarPage.HOME) }
    BackHandler(page != CalendarPage.HOME) { page = CalendarPage.HOME }
    when (page) {
        CalendarPage.HOME -> CalendarScreen(vm, onOpenMonthly = { page = CalendarPage.MONTHLY })
        CalendarPage.MONTHLY -> StatisticsScreen(vm, onBack = { page = CalendarPage.HOME })
    }
}
