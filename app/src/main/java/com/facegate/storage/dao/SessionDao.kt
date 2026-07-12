package com.facegate.storage.dao

import androidx.room.*
import com.facegate.storage.entity.SessionEntity

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)
    @Query("UPDATE sessions SET endedAt = :endedAt WHERE sessionId = :sessionId")
    suspend fun endSession(sessionId: String, endedAt: Long)
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?
    @Query("SELECT * FROM sessions WHERE startTime >= :startOfDay AND startTime <= :endOfDay ORDER BY startTime ASC")
    suspend fun getSessionsForDate(startOfDay: Long, endOfDay: Long): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("""
        SELECT * FROM sessions
        WHERE timetableId = :timetableId AND startTime >= :startOfDay AND startTime <= :endOfDay
        ORDER BY startTime DESC LIMIT 1
    """)
    suspend fun findSessionForTimetableOnDate(timetableId: Int?, startOfDay: Long, endOfDay: Long): SessionEntity?

    /**
     * Matches a session by the server's timetable_id + session_date, the same
     * pair the backend itself uses to resolve attendance_session (see
     * OfflineAttendanceDto). Used to backfill remoteAttendanceId on a local
     * row during attendance-down sync, when that row predates ever knowing
     * the server's attendance_id (see AttendanceSyncWorker.mergeAttendanceDown).
     */
    @Query("SELECT * FROM sessions WHERE remoteTimetableId = :remoteTimetableId AND sessionDate = :sessionDate LIMIT 1")
    suspend fun findByRemoteTimetableAndDate(remoteTimetableId: String, sessionDate: String): SessionEntity?
}