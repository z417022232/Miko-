package com.example.worktimetracker.data.repository

import com.example.worktimetracker.data.dao.UserSettingsDao
import com.example.worktimetracker.data.entity.UserSettingsEntity
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dao: UserSettingsDao) {
    fun observeSettings(): Flow<UserSettingsEntity?> = dao.observeSettings()
    suspend fun getSettings(): UserSettingsEntity = dao.getSettings() ?: UserSettingsEntity()
    suspend fun save(settings: UserSettingsEntity) = dao.save(settings.copy(updatedAt = System.currentTimeMillis()))
}
