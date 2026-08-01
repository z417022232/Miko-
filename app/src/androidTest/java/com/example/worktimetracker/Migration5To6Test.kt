package com.example.worktimetracker

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.worktimetracker.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationPreservesRecordsAndSalaryAndDeduplicatesReplayNoise() {
        helper.createDatabase(DB_NAME, 5).apply {
            execSQL("INSERT INTO work_records VALUES(1,'2026-07-31','MANUAL','NIGHT_SHIFT',1000,2000,16,660,1,0,'keep',1,1)")
            execSQL("INSERT INTO monthly_salaries VALUES('2026-08',123456,'2026-07','2026-08-15',1)")
            execSQL("INSERT INTO location_logs VALUES(1,60001,31.0,121.0,10.0,'COMPANY','gps')")
            execSQL("INSERT INTO location_logs VALUES(2,60002,31.0,121.0,10.0,'COMPANY','gps')")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 6, true, WorkTimeApplication.MIGRATION_5_6)
        db.query("SELECT finalMinutes,isManual FROM work_records WHERE id=1").use {
            it.moveToFirst(); assertEquals(660, it.getInt(0)); assertEquals(1, it.getInt(1))
        }
        db.query("SELECT netSalaryCents FROM monthly_salaries").use {
            it.moveToFirst(); assertEquals(123456L, it.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM location_logs").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    private companion object { const val DB_NAME = "migration-5-6" }
}
