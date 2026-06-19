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
    private val conflictDao   : ConflictDao,    // ← injected in version 2
) {

    // ── Student ───────────────────────────────────────────────────────────────

    suspend fun addStudent(student: StudentEntity) {
        studentDao.insertStudent(student)
    }

    suspend fun getStudents(): List<StudentEntity> {
        return studentDao.getAllStudents()
    }


    // ── Attendance ────────────────────────────────────────────────────────────

    suspend fun addAttendance(record: AttendanceEntity) {
        attendanceDao.insertAttendance(record)
    }

    suspend fun getUnsyncedAttendance(): List<AttendanceEntity> {
        return attendanceDao.getUnsyncedRecords()
    }

    suspend fun markAttendanceSynced(id: Int) {
        attendanceDao.markAsSynced(id)
    }


    // ── Sync Logs ─────────────────────────────────────────────────────────────

    suspend fun addSyncLog(log: SyncLogEntity) {
        syncLogDao.insertLog(log)
    }

    suspend fun getSyncLogs(): List<SyncLogEntity> {
        return syncLogDao.getAllLogs()
    }


    // ── Conflict Queue ────────────────────────────────────────────────────────

    /**
     * Persist an ambiguous match as a conflict for admin review.
     * Called by AttendancePipeline.handleDecision() on Ambiguous decisions.
     */
    suspend fun addConflict(conflict: ConflictEntity) {
        conflictDao.insertConflict(conflict)
    }

    /**
     * Returns all unresolved conflicts, newest first.
     * Used by ConflictQueueFragment / its ViewModel to populate the list.
     */
    suspend fun getUnresolvedConflicts(): List<ConflictEntity> {
        return conflictDao.getUnresolvedConflicts()
    }

    suspend fun getAllConflicts(): List<ConflictEntity> {
        return conflictDao.getAllConflicts()
    }

    /**
     * Mark a conflict as resolved after admin decision.
     */
    suspend fun resolveConflict(id: Int) {
        conflictDao.markResolved(id)
    }

    /**
     * Badge count for the admin dashboard conflict indicator.
     */
    suspend fun getUnresolvedConflictCount(): Int {
        return conflictDao.getUnresolvedCount()
    }
}