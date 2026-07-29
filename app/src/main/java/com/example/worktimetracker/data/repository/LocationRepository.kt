package com.example.worktimetracker.data.repository

import com.example.worktimetracker.data.dao.AppLogDao
import com.example.worktimetracker.data.dao.LocationLogDao
import com.example.worktimetracker.data.dao.WorkStateDao
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.data.entity.WorkStateEntity

class LocationRepository(
    private val locationLogDao: LocationLogDao,
    private val workStateDao: WorkStateDao,
    private val appLogDao: AppLogDao
) {
    suspend fun saveLocation(log: LocationLogEntity): Long = locationLogDao.insert(log)
    suspend fun latestLocation(): LocationLogEntity? = locationLogDao.latest()
    suspend fun saveState(state: WorkStateEntity) = workStateDao.save(state.copy(updatedAt = System.currentTimeMillis()))
    suspend fun getState(): WorkStateEntity = workStateDao.getState() ?: WorkStateEntity()
    suspend fun log(type: String, content: String) = appLogDao.insert(AppLogEntity(type = type, content = content))
}
