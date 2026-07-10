package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.AttendanceEntity

/** Result class for class-wise attendance JOIN query */
data class ClassAttendanceSummary(
    val studentClass : String,
    val presentCount : Int,
)

/** Result class for per-day attendance count (used in monthly report). */
data class DailyAttendanceCount(
    val dateStr      : String,   // "yyyy-MM-dd"
    val presentCount : Int,
)

@Dao
interface AttendanceDao {

    @Insert
    suspend fun insertAttendance(attendance: AttendanceEntity)

    @Query("SELECT * FROM attendance_records WHERE synced = 0")
    suspend fun getUnsyncedRecords(): List<AttendanceEntity>

    @Query("UPDATE attendance_records SET synced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Int)

    // NOTE: timeStamp >= :startOfDay alone (no upper bound) used to mean "today
    // AND everything after it forever", so passing a *previous* day's
    // startOfDay (for previous-day manual attendance) would incorrectly match
    // today's and every future record too. All of the queries below are now
    // bounded with an explicit end timestamp so they only ever match the
    // requested day/range.
    @Query("SELECT * FROM attendance_records WHERE timeStamp >= :startOfDay AND timeStamp <= :endOfDay")
    suspend fun getAttendanceForRange(startOfDay: Long, endOfDay: Long): List<AttendanceEntity>

    @Query("SELECT * FROM attendance_records")
    suspend fun getAllAttendance(): List<AttendanceEntity>

    @Query("""
        SELECT s.studentClass, COUNT(a.id) AS presentCount
        FROM attendance_records a
        INNER JOIN students s ON a.studentId = s.studentId
        WHERE a.timeStamp >= :startOfDay AND a.timeStamp <= :endOfDay
        GROUP BY s.studentClass
        ORDER BY s.studentClass ASC
    """)
    suspend fun getClassWiseAttendance(startOfDay: Long, endOfDay: Long): List<ClassAttendanceSummary>

    @Query("""
        SELECT COUNT(*) FROM attendance_records
        WHERE studentId = :studentId AND timeStamp >= :startOfDay AND timeStamp <= :endOfDay
    """)
    suspend fun isStudentMarkedOnDate(studentId: String, startOfDay: Long, endOfDay: Long): Int

    @Query("""
        DELETE FROM attendance_records
        WHERE studentId = :studentId AND timeStamp >= :startOfDay AND timeStamp <= :endOfDay
    """)
    suspend fun deleteAttendanceOnDate(studentId: String, startOfDay: Long, endOfDay: Long)

    @Query("DELETE FROM attendance_records WHERE studentId = :studentId")
    suspend fun deleteAllAttendanceForStudent(studentId: String)

    /**
     * Retention cleanup: deletes attendance records older than [cutoffMillis]
     * that have already been uploaded (synced = 1). Unsynced records are never
     * touched here regardless of age — losing data that hasn't reached the
     * backend yet would be silent data loss, not cleanup.
     */
    @Query("DELETE FROM attendance_records WHERE timeStamp < :cutoffMillis AND synced = 1")
    suspend fun deleteSyncedRecordsOlderThan(cutoffMillis: Long)

    @Query("UPDATE attendance_records SET studentId = :newId WHERE studentId = :oldId")
    suspend fun renameStudentId(oldId: String, newId: String)

    /** All records for a specific session (for session-level manual attendance editing). */
    @Query("SELECT * FROM attendance_records WHERE sessionId = :sessionId")
    suspend fun getAttendanceForSession(sessionId: String): List<AttendanceEntity>

    /** Check if a student is marked for a specific session. */
    @Query("SELECT COUNT(*) FROM attendance_records WHERE studentId = :studentId AND sessionId = :sessionId")
    suspend fun isStudentMarkedForSession(studentId: String, sessionId: String): Int

    /** Remove attendance for a specific student + session (manual toggle-off). */
    @Query("DELETE FROM attendance_records WHERE studentId = :studentId AND sessionId = :sessionId")
    suspend fun deleteAttendanceForSession(studentId: String, sessionId: String)

    /**
     * Returns the count of distinct students present on each calendar day within
     * [startMs, endMs]. Used to build the monthly report calendar heatmap.
     * The date string is yyyy-MM-dd in device-local time (derived from timeStamp).
     */
    @Query("""
        SELECT CAST(strftime('%Y-%m-%d', timeStamp / 1000, 'unixepoch', 'localtime') AS TEXT) AS dateStr,
               COUNT(DISTINCT studentId) AS presentCount
        FROM attendance_records
        WHERE timeStamp >= :startMs AND timeStamp <= :endMs
        GROUP BY dateStr
        ORDER BY dateStr ASC
    """)
    suspend fun getDailyCountsInRange(startMs: Long, endMs: Long): List<DailyAttendanceCount>
}