package com.example.worktimetracker.data.repository

import com.example.worktimetracker.data.dao.WorkRecordDao
import com.example.worktimetracker.data.dao.WorkSegmentDao
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.WorkSegmentEntity
import kotlinx.coroutines.flow.Flow

class WorkRecordRepository(
    private val workRecordDao: WorkRecordDao,
    private val workSegmentDao: WorkSegmentDao
) {
    fun observeMonthRecords(startDate: String, endDate: String): Flow<List<WorkRecordEntity>> =
        workRecordDao.observeMonthRecords(startDate, endDate)

    suspend fun getByDate(date: String): WorkRecordEntity? = workRecordDao.getByDate(date)

    suspend fun saveRecord(record: WorkRecordEntity): Long =
        workRecordDao.upsert(record.copy(updatedAt = System.currentTimeMillis()))

    fun observeSegments(recordId: Long): Flow<List<WorkSegmentEntity>> =
        workSegmentDao.observeForRecord(recordId)

    suspend fun replaceSegments(recordId: Long, segments: List<WorkSegmentEntity>) {
        workSegmentDao.deleteForRecord(recordId)
        workSegmentDao.insertAll(segments)
    }
}
