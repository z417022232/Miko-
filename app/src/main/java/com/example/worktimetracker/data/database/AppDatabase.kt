package com.example.worktimetracker.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.worktimetracker.data.dao.AppLogDao
import com.example.worktimetracker.data.dao.EnvironmentEvidenceDao
import com.example.worktimetracker.data.dao.HolidayDao
import com.example.worktimetracker.data.dao.LocationLogDao
import com.example.worktimetracker.data.dao.ManualOverrideDao
import com.example.worktimetracker.data.dao.MonthlySalaryDao
import com.example.worktimetracker.data.dao.UserSettingsDao
import com.example.worktimetracker.data.dao.WorkRecordDao
import com.example.worktimetracker.data.dao.WorkSegmentDao
import com.example.worktimetracker.data.dao.WorkStateDao
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.EvidenceObservationEntity
import com.example.worktimetracker.data.entity.HolidayEntity
import com.example.worktimetracker.data.entity.LocationHealthEntity
import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.data.entity.ManualOverrideEntity
import com.example.worktimetracker.data.entity.MonthlySalaryEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.WorkSegmentEntity
import com.example.worktimetracker.data.entity.WorkStateEntity

@Database(
    entities = [
        UserSettingsEntity::class,
        WorkRecordEntity::class,
        WorkSegmentEntity::class,
        LocationLogEntity::class,
        HolidayEntity::class,
        ManualOverrideEntity::class,
        WorkStateEntity::class,
        AppLogEntity::class,
        MonthlySalaryEntity::class,
        EnvironmentFingerprintEntity::class,
        EvidenceObservationEntity::class,
        LocationHealthEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun workRecordDao(): WorkRecordDao
    abstract fun workSegmentDao(): WorkSegmentDao
    abstract fun locationLogDao(): LocationLogDao
    abstract fun holidayDao(): HolidayDao
    abstract fun manualOverrideDao(): ManualOverrideDao
    abstract fun workStateDao(): WorkStateDao
    abstract fun appLogDao(): AppLogDao
    abstract fun monthlySalaryDao(): MonthlySalaryDao
    abstract fun environmentEvidenceDao(): EnvironmentEvidenceDao
}
