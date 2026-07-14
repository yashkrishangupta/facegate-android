package com.facegate.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.HolidayDao
import com.facegate.storage.dao.OverrideDao
import com.facegate.storage.dao.SessionDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.dao.SyncStateDao
import com.facegate.storage.dao.TimetableDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.HolidayEntity
import com.facegate.storage.entity.OverrideEntity
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity
import com.facegate.storage.entity.SyncStateEntity
import com.facegate.storage.entity.TimetableEntity

@Database(
    entities = [
        AttendanceEntity::class,
        StudentEntity::class,
        SyncLogEntity::class,
        ConflictEntity::class,
        TimetableEntity::class,
        SessionEntity::class,
        OverrideEntity::class,
        HolidayEntity::class,
        SyncStateEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class FaceGateDatabase : RoomDatabase() {
    abstract fun attendanceDao() : AttendanceDao
    abstract fun studentDao()    : StudentDao
    abstract fun syncLogDao()    : SyncLogDao
    abstract fun conflictDao()   : ConflictDao
    abstract fun timetableDao()  : TimetableDao
    abstract fun sessionDao()    : SessionDao
    abstract fun overrideDao()   : OverrideDao
    abstract fun holidayDao()    : HolidayDao
    abstract fun syncStateDao()  : SyncStateDao
}

/**
 * Was previously destructive-only (see AppModule — "no real data yet, safe
 * to wipe"). That stopped being true once devices have enrolled students
 * with captured embeddings on them; wiping the DB on every schema bump would
 * silently delete those. This migration is additive-only (new nullable
 * columns + one new table), matching exactly the fields added to each
 * entity in this change — keep it in sync if those entities change again.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── timetable ──
        db.execSQL("ALTER TABLE timetable ADD COLUMN roomId TEXT")
        db.execSQL("ALTER TABLE timetable ADD COLUMN batchId TEXT")
        db.execSQL("ALTER TABLE timetable ADD COLUMN facultyId TEXT")
        db.execSQL("ALTER TABLE timetable ADD COLUMN subjectId TEXT")
        db.execSQL("ALTER TABLE timetable ADD COLUMN endHour INTEGER")
        db.execSQL("ALTER TABLE timetable ADD COLUMN endMinute INTEGER")
        db.execSQL("ALTER TABLE timetable ADD COLUMN effectiveFrom TEXT")
        db.execSQL("ALTER TABLE timetable ADD COLUMN effectiveTo TEXT")
        db.execSQL("ALTER TABLE timetable ADD COLUMN serverUpdatedAt TEXT")

        // ── conflict_queue ──
        db.execSQL("ALTER TABLE conflict_queue ADD COLUMN remoteConflictId TEXT")
        db.execSQL("ALTER TABLE conflict_queue ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conflict_queue ADD COLUMN source TEXT NOT NULL DEFAULT 'DEVICE'")
        db.execSQL("ALTER TABLE conflict_queue ADD COLUMN conflictType TEXT NOT NULL DEFAULT 'MANUAL_REVIEW'")
        db.execSQL("ALTER TABLE conflict_queue ADD COLUMN severity TEXT NOT NULL DEFAULT 'MEDIUM'")
        db.execSQL("ALTER TABLE conflict_queue ADD COLUMN attendanceId TEXT")

        // ── attendance_records ──
        db.execSQL("ALTER TABLE attendance_records ADD COLUMN attendanceMode TEXT NOT NULL DEFAULT 'FACE_RECOGNITION'")
        db.execSQL("ALTER TABLE attendance_records ADD COLUMN remoteAttendanceId TEXT")
        db.execSQL("ALTER TABLE attendance_records ADD COLUMN serverUpdatedAt INTEGER")
        db.execSQL("ALTER TABLE attendance_records ADD COLUMN verificationStatus TEXT")
        db.execSQL("ALTER TABLE attendance_records ADD COLUMN syncedAt INTEGER")

        // ── students ──
        db.execSQL("ALTER TABLE students ADD COLUMN isLocalOnly INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE students ADD COLUMN registrationNumber TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE students ADD COLUMN email TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN phone TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN gender TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN studentStatus TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("ALTER TABLE students ADD COLUMN batchId TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN serverUpdatedAt TEXT")
        // schema.sql: face_embedding.embedding_version is VARCHAR(20)
        // DEFAULT 'v1.0' — TEXT here, not INTEGER (this migration is new/
        // unreleased in this same pass, so corrected in place rather than
        // adding a follow-up type-change migration).
        db.execSQL("ALTER TABLE students ADD COLUMN embeddingVersion TEXT NOT NULL DEFAULT 'v1.0'")
        db.execSQL("ALTER TABLE students ADD COLUMN embeddingModelName TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN remoteEmbeddingId TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN admissionYear INTEGER")
        db.execSQL("ALTER TABLE students ADD COLUMN dateOfBirth TEXT")
        db.execSQL("ALTER TABLE students ADD COLUMN profilePhotoUrl TEXT")
        // Existing rows synced down from the server before this migration are
        // not local-only — only rows still PENDING (i.e. never enrolled on
        // this device at all) definitely aren't; rows already DONE with an
        // embedding predate isLocalOnly tracking and are assumed server-known
        // (the safer default — it just means a genuinely local-only student
        // enrolled before this update needs one more manual save to trigger
        // its first enroll-upload, rather than risking a duplicate create).
        db.execSQL("UPDATE students SET isLocalOnly = 0 WHERE enrollmentStatus = 'DONE'")

        // ── holidays ──
        db.execSQL("ALTER TABLE holidays ADD COLUMN remoteHolidayId TEXT")
        db.execSQL("ALTER TABLE holidays ADD COLUMN holidayType TEXT")
        db.execSQL("ALTER TABLE holidays ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE holidays ADD COLUMN serverUpdatedAt TEXT")

        // ── timetable_overrides ──
        db.execSQL("ALTER TABLE timetable_overrides ADD COLUMN pushedToChangeLog INTEGER NOT NULL DEFAULT 0")

        // ── new: sync_state ──
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_state (
                category TEXT NOT NULL PRIMARY KEY,
                lastAttemptAt INTEGER NOT NULL,
                lastSuccessAt INTEGER,
                status TEXT NOT NULL,
                message TEXT,
                pendingCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}

/**
 * Weekly-off (a fixed day-of-week the school never has classes) removed
 * entirely per product decision — the timetable itself is the only source
 * of truth for which days have periods now. This is a new migration rather
 * than editing MIGRATION_4_5 in place, since devices have already migrated
 * to v5 and shouldn't have that step change under them.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS weekly_off")
    }
}
