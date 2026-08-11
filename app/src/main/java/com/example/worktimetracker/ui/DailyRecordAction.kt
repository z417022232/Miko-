package com.example.worktimetracker.ui

enum class EditorMode { CONFIRM_REVIEW, EDIT_CONFIRMED }

object DailyRecordAction {
    fun forRecord(needsReview: Boolean): EditorMode =
        if (needsReview) EditorMode.CONFIRM_REVIEW else EditorMode.EDIT_CONFIRMED
}
