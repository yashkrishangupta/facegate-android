package com.facegate.storage

import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.ClassAttendanceSummary
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity
import com.facegate.storage.dao.TimetableDao
import com.facegate.storage.dao.SessionDao
import com.facegate.storage.dao.OverrideDao
import com.facegate.storage.dao.HolidayDao
import com.facegate.storage.entity.TimetableEntity
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.OverrideEntity
import com.facegate.storage.entity.HolidayEntity

class TemplateRepository(
    private val studentDao    : StudentDao,
    private val attendanceDao : AttendanceDao,
    private val syncLogDao    : SyncLogDao,
    private val conflictDao   : ConflictDao,
    private val timetableDao  : TimetableDao,
    private val sessionDao    : SessionDao,
    private val overrideDao   : OverrideDao,
    private val holidayDao    : HolidayDao,
) {

    // ── Student ───────────────────────────────────────────────────────────────

    suspend fun addStudent(student: StudentEntity) =
        studentDao.insertStudent(student)

    suspend fun getStudents(): List<StudentEntity> =
        studentDao.getAllStudents()

    suspend fun getStudentsByClass(studentClass: String): List<StudentEntity> =
        studentDao.getStudentsByClass(studentClass)

    suspend fun getAllClasses(): List<String> =
        studentDao.getAllClasses()

    suspend fun getStudentCount(): Int =
        studentDao.getStudentCount()

    /**
     * Delete a student and cascade:
     *   - attendance_records for that student (avoids phantom present counts)
     *   - conflict_queue rows where they appear as top or second candidate
     */
    suspend fun deleteStudent(studentId: String) {
        studentDao.deleteStudent(studentId)
        attendanceDao.deleteAllAttendanceForStudent(studentId)
        conflictDao.deleteAllConflictsForStudent(studentId)
    }

    /**
     * Update name and class only — embedding is preserved so face recognition is unaffected.
     */
    suspend fun updateStudentInfo(studentId: String, name: String, studentClass: String) =
        studentDao.updateStudentInfo(studentId, name, studentClass)

    suspend fun getStudentById(studentId: String): StudentEntity? =
        studentDao.getStudentById(studentId)


    suspend fun updateStudentRollNo(
        oldId: String,
        newId: String,
        name: String,
        studentClass: String,
    ) {
        studentDao.updateStudentIdAndInfo(oldId, newId, name, studentClass)
        attendanceDao.renameStudentId(oldId, newId)
        conflictDao.renameTopStudentId(oldId, newId)
        conflictDao.renameSecondStudentId(oldId, newId)
    }

    // ── Attendance ────────────────────────────────────────────────────────────

    suspend fun addAttendance(record: AttendanceEntity) =
        attendanceDao.insertAttendance(record)

    suspend fun getUnsyncedAttendance(): List<AttendanceEntity> =
        attendanceDao.getUnsyncedRecords()

    suspend fun markAttendanceSynced(id: Int) =
        attendanceDao.markAsSynced(id)

    suspend fun getAttendanceForRange(startOfDay: Long, endOfDay: Long): List<AttendanceEntity> =
        attendanceDao.getAttendanceForRange(startOfDay, endOfDay)

    suspend fun getAllAttendance(): List<AttendanceEntity> =
        attendanceDao.getAllAttendance()

    suspend fun getClassWiseAttendance(startOfDay: Long, endOfDay: Long): List<ClassAttendanceSummary> =
        attendanceDao.getClassWiseAttendance(startOfDay, endOfDay)

    suspend fun isStudentMarkedOnDate(studentId: String, startOfDay: Long, endOfDay: Long): Boolean =
        attendanceDao.isStudentMarkedOnDate(studentId, startOfDay, endOfDay) > 0

    /** Remove a day's attendance for a student (mark absent / undo) — bounded to that single day. */
    suspend fun removeAttendanceOnDate(studentId: String, startOfDay: Long, endOfDay: Long) =
        attendanceDao.deleteAttendanceOnDate(studentId, startOfDay, endOfDay)

    suspend fun getDailyCountsInRange(startMs: Long, endMs: Long) =
        attendanceDao.getDailyCountsInRange(startMs, endMs)

    // ── Session-specific attendance (for manual attendance editing per session) ──
    suspend fun getAttendanceForSession(sessionId: String) =
        attendanceDao.getAttendanceForSession(sessionId)
    suspend fun isStudentMarkedForSession(studentId: String, sessionId: String): Boolean =
        attendanceDao.isStudentMarkedForSession(studentId, sessionId) > 0
    suspend fun deleteAttendanceForSession(studentId: String, sessionId: String) =
        attendanceDao.deleteAttendanceForSession(studentId, sessionId)

    // ── Sync Logs ─────────────────────────────────────────────────────────────

    suspend fun addSyncLog(log: SyncLogEntity) =
        syncLogDao.insertLog(log)

    suspend fun getSyncLogs(): List<SyncLogEntity> =
        syncLogDao.getAllLogs()

    // ── Conflict Queue ────────────────────────────────────────────────────────

    suspend fun addConflict(conflict: ConflictEntity) =
        conflictDao.insertConflict(conflict)

    suspend fun getUnresolvedConflicts(): List<ConflictEntity> =
        conflictDao.getUnresolvedConflicts()

    suspend fun getAllConflicts(): List<ConflictEntity> =
        conflictDao.getAllConflicts()

    suspend fun resolveConflict(id: Int) =
        conflictDao.markResolved(id)

    suspend fun getUnresolvedConflictCount(): Int =
        conflictDao.getUnresolvedCount()

    suspend fun findOpenConflict(sessionId: String, topStudentId: String): ConflictEntity? =
        conflictDao.findOpenConflict(sessionId, topStudentId)

    suspend fun findOpenConflictForPair(sessionId: String, idA: String, idB: String): ConflictEntity? =
        conflictDao.findOpenConflictForPair(sessionId, idA, idB)

    suspend fun updateConflict(
        id: Int,
        topStudentId: String,
        topStudentName: String,
        topScore: Float,
        secondStudentId: String,
        secondStudentName: String,
        secondScore: Float,
        reason: String,
        timestamp: Long,
    ) = conflictDao.updateConflict(
        id, topStudentId, topStudentName, topScore,
        secondStudentId, secondStudentName, secondScore,
        reason, timestamp
    )

    suspend fun resolveAllConflictsForStudent(studentId: String) =
        conflictDao.resolveAllConflictsForStudent(studentId)
    
    // ── Timetable ─────────────────────────────────────────────────────────
    suspend fun insertTimetable(entry: TimetableEntity) = timetableDao.insert(entry)
    suspend fun updateTimetable(entry: TimetableEntity) = timetableDao.update(entry)
    suspend fun deleteTimetable(id: Int) = timetableDao.delete(id)
    suspend fun getTimetableForDay(dayOfWeek: Int) = timetableDao.getForDay(dayOfWeek)
    suspend fun getAllTimetable() = timetableDao.getAll()
    suspend fun getAllBatches() = timetableDao.getAllBatches()
    suspend fun getAllSubjects() = timetableDao.getAllSubjects()

    // ── Sessions ───────────────────────────────────────────────────────────
    suspend fun insertSession(session: SessionEntity) = sessionDao.insert(session)
    suspend fun endSession(sessionId: String, endedAt: Long) = sessionDao.endSession(sessionId, endedAt)
    suspend fun getActiveSession() = sessionDao.getActiveSession()
    suspend fun getSessionsForDate(startOfDay: Long, endOfDay: Long) = sessionDao.getSessionsForDate(startOfDay, endOfDay)
    suspend fun getSessionById(sessionId: String) = sessionDao.getById(sessionId)
    suspend fun findSessionForTimetableOnDate(timetableId: Int?, startOfDay: Long, endOfDay: Long) =
        sessionDao.findSessionForTimetableOnDate(timetableId, startOfDay, endOfDay)

    // ── Overrides ──────────────────────────────────────────────────────────
    suspend fun insertOverride(override: OverrideEntity) = overrideDao.insert(override)
    suspend fun getOverridesForSession(sessionId: String) = overrideDao.getForSession(sessionId)
    suspend fun getAllOverrides() = overrideDao.getAll()

    // ── Holidays ───────────────────────────────────────────────────────────
    suspend fun insertHoliday(holiday: HolidayEntity) = holidayDao.insert(holiday)
    suspend fun deleteHoliday(date: String) = holidayDao.delete(date)
    suspend fun isHoliday(date: String): Boolean = holidayDao.isHoliday(date) > 0
    suspend fun getAllHolidays() = holidayDao.getAll()
    suspend fun getUpcomingHolidays() = holidayDao.getUpcoming(getTodayString())

    private fun getTodayString() = java.text.SimpleDateFormat(
        "yyyy-MM-dd", java.util.Locale.getDefault()
    ).format(java.util.Date())
}