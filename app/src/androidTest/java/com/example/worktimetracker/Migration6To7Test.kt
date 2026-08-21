package com.example.worktimetracker

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.data.entity.ManualField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration6To7Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationPreservesDataAndLeavesMissingManualEventsUnlocked() {
        helper.createDatabase(DB_NAME, 6).apply {
            execSQL("INSERT INTO work_records(id,workDate,status,shift,startTime,endTime,actualMinutes,finalMinutes,isManual,needsReview,note,createdAt,updatedAt,homeDepartureTime,homeArrivalTime) VALUES(1,'2026-08-19','MANUAL','NIGHT_SHIFT',100,NULL,NULL,660,1,0,'',1,1,90,NULL)")
            execSQL("INSERT INTO monthly_salaries(month,netSalaryCents,updatedAt,payrollMonth,paymentDate) VALUES('2026-08',123456,1,'2026-07','2026-08-15')")
            execSQL("INSERT INTO location_logs(id,time,latitude,longitude,accuracyMeters,locationType,provider) VALUES(1,100,31,121,10,'COMPANY','gps')")
            execSQL("INSERT INTO work_state(id,currentState,updatedAt,tempLeaveStart) VALUES(1,'TEMP_LEAVE',1,900)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 7, true, WorkTimeApplication.MIGRATION_6_7)
        db.query("SELECT manualFieldsMask FROM work_records WHERE id=1").use {
            it.moveToFirst(); val mask = it.getInt(0)
            assertFalse(mask and ManualField.COMPANY_DEPARTURE.bit != 0)
            assertFalse(mask and ManualField.HOME_ARRIVAL.bit != 0)
        }
        db.query("SELECT candidateCompanyDepartureTime FROM work_state WHERE id=1").use {
            it.moveToFirst(); assertEquals(900L, it.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM monthly_salaries").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM location_logs").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.close()
    }

    private companion object { const val DB_NAME = "migration-6-7" }
}
