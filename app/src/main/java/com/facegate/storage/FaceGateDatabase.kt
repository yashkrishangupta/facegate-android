package com.facegate.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity

@Database(
    entities = [
        AttendanceEntity::class,
        StudentEntity::class,
        SyncLogEntity::class,
        ConflictEntity::class,      // ← added in version 2
    ],
    version = 2,
    exportSchema = false,           // keeps it consistent with the v1 setting
)
abstract class FaceGateDatabase : RoomDatabase() {

    abstract fun attendanceDao(): AttendanceDao
    abstract fun studentDao(): StudentDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun conflictDao(): ConflictDao

    companion object {
        /**
         * Manual migration v1 → v2.
         *
         * AutoMigration was not possible because exportSchema was false in v1
         * (no 1.json snapshot existed for Room to diff against).
         * This SQL is equivalent to what AutoMigration would have generated:
         * it creates the conflict_queue table with every column in ConflictEntity.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conflict_queue` (
                        `id`                INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `topStudentId`      TEXT    NOT NULL,
                        `topStudentName`    TEXT    NOT NULL,
                        `topScore`          REAL    NOT NULL,
                        `secondStudentId`   TEXT    NOT NULL,
                        `secondStudentName` TEXT    NOT NULL,
                        `secondScore`       REAL    NOT NULL,
                        `reason`            TEXT    NOT NULL,
                        `sessionId`         TEXT    NOT NULL,
                        `timestamp`         INTEGER NOT NULL,
                        `resolved`          INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
    }
}