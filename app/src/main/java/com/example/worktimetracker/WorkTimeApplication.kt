package com.example.worktimetracker

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.location.recovery.GeofenceRecovery
import com.example.worktimetracker.data.HistoricalRecordRepair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WorkTimeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceRecovery.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            HistoricalRecordRepair.runOnce(this@WorkTimeApplication)
            database.userSettingsDao().getSettings()?.let { GeofenceRecovery.register(this@WorkTimeApplication, it) }
        }
    }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "work_time_tracker.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN onboardingDone INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `monthly_salaries` (`month` TEXT NOT NULL, `netSalaryCents` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`month`))"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_state ADD COLUMN tempLeaveStart INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monthly_salaries ADD COLUMN payrollMonth TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE monthly_salaries ADD COLUMN paymentDate TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE monthly_salaries SET " +
                        "payrollMonth = strftime('%Y-%m', date(month || '-01', '-1 month')), " +
                        "paymentDate = month || '-15'"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_state ADD COLUMN confirmedDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN homeDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN homeArrivalTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN lastGpsFixTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN lastNetworkFixTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN lastCompanyDistanceMeters REAL")
                db.execSQL("ALTER TABLE work_records ADD COLUMN homeDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_records ADD COLUMN homeArrivalTime INTEGER")
                db.execSQL(
                    "DELETE FROM location_logs WHERE id NOT IN (" +
                        "SELECT MIN(id) FROM location_logs GROUP BY (time / 60000), provider, " +
                        "latitude, longitude, accuracyMeters, locationType)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_records ADD COLUMN manualFieldsMask INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE work_state ADD COLUMN sessionId TEXT")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateHomeDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateCompanyArrivalTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateCompanyDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateHomeArrivalTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN companyArrivalConfirmedAt INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN companyDepartureConfirmedAt INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN homeArrivalConfirmedAt INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN stableCompanyCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE work_state ADD COLUMN stableHomeCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE work_state ADD COLUMN movingAwayCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE work_state SET candidateCompanyDepartureTime = tempLeaveStart WHERE tempLeaveStart IS NOT NULL")
                db.execSQL(
                    "UPDATE work_records SET manualFieldsMask = 33" +
                        " + CASE WHEN startTime IS NOT NULL THEN 2 ELSE 0 END" +
                        " + CASE WHEN endTime IS NOT NULL THEN 4 ELSE 0 END" +
                        " + CASE WHEN homeDepartureTime IS NOT NULL THEN 8 ELSE 0 END" +
                        " + CASE WHEN homeArrivalTime IS NOT NULL THEN 16 ELSE 0 END" +
                        " + CASE WHEN note IS NOT NULL THEN 64 ELSE 0 END" +
                        " WHERE isManual = 1"
                )
            }
        }
    }
}

