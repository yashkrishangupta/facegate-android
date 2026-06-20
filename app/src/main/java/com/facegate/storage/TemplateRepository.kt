package com.facegate.storage

import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity

class TemplateRepository(
    private val studentDao    : StudentDao,
    private val attendanceDao : AttendanceDao,
    private val syncLogDao    : SyncLogDao,
    private val conflictDao   : ConflictDao,
) {

    // ── Student ───────────────────────────────────────────────────────────────

    suspend fun addStudent(student: StudentEntity) =
        studentDao.insertStudent(student)

    suspend fun getStudents(): List<StudentEntity> =
        studentDao.getAllStudents()


    // ── Attendance ────────────────────────────────────────────────────────────

    suspend fun addAttendance(record: AttendanceEntity) =
        attendanceDao.insertAttendance(record)

    suspend fun getUnsyncedAttendance(): List<AttendanceEntity> =
        attendanceDao.getUnsyncedRecords()

    suspend fun markAttendanceSynced(id: Int) =
        attendanceDao.markAsSynced(id)

    /** Records from today only — used by dashboard + reports */
    suspend fun getTodayAttendance(startOfDay: Long): List<AttendanceEntity> =
        attendanceDao.getTodayAttendance(startOfDay)

    /** All records ever — used by reports for historical total */
    suspend fun getAllAttendance(): List<AttendanceEntity> =
        attendanceDao.getAllAttendance()


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
}