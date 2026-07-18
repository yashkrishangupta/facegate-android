package com.facegate.storage

import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.AuthUserDao
import com.facegate.storage.dao.ClassAttendanceSummary
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.dao.SyncStateDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.AuthUserEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity
import com.facegate.storage.entity.SyncStateEntity
import com.facegate.storage.dao.TimetableDao
import com.facegate.storage.dao.SessionDao
import com.facegate.storage.dao.OverrideDao
import com.facegate.storage.dao.HolidayDao
import com.facegate.storage.entity.TimetableEntity
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.OverrideEntity
import com.facegate.storage.entity.HolidayEntity
import com.facegate.sync.SyncAttendanceDto
import kotlinx.coroutines.flow.Flow

class TemplateRepository(
    private val studentDao    : StudentDao,
    private val attendanceDao : AttendanceDao,
    private val syncLogDao    : SyncLogDao,
    private val conflictDao   : ConflictDao,
    private val timetableDao  : TimetableDao,
    private val sessionDao    : SessionDao,
    private val overrideDao   : OverrideDao,
    private val holidayDao    : HolidayDao,
    private val syncStateDao  : SyncStateDao,
    private val authUserDao   : AuthUserDao,
) {

    // ── Auth users (Admin Mode / start-my-period login gates) ───────────────

    suspend fun replaceAuthUsers(users: List<AuthUserEntity>) = authUserDao.replaceAll(users)
    suspend fun getAdminAuthUsers() = authUserDao.getAdmins()
    suspend fun getAuthUserByFacultyId(facultyId: String) = authUserDao.getByFacultyId(facultyId)
    suspend fun hasSyncedAuthUsers() = authUserDao.count() > 0

    // ── Student ───────────────────────────────────────────────────────────────

    suspend fun addStudent(student: StudentEntity) =
        studentDao.insertStudent(student)

    /**
     * Same as addStudent, but for (re-)enrollment specifically: preserves
     * isLocalOnly/rollNumber/batchCode from any existing row with this id
     * instead of resetting them to StudentEntity's defaults. addStudent
     * uses REPLACE, so a naive re-enrollment of a student who already came
     * down from a server sync (isLocalOnly = false) would silently flip
     * isLocalOnly back to true — making AttendanceSyncWorker wrongly treat
     * an existing server student as brand new and try to create a
     * duplicate. Embedding sync state (embeddingSynced) is intentionally
     * NOT preserved — a re-captured face is exactly the case that should
     * re-trigger an embedding upload.
     */
    suspend fun upsertEnrollment(student: StudentEntity) {
        val existing = studentDao.getStudentById(student.studentId)
        val merged = if (existing != null) {
            student.copy(
                isLocalOnly = existing.isLocalOnly,
                rollNumber = student.rollNumber.ifBlank { existing.rollNumber },
                batchCode = student.batchCode ?: existing.batchCode,
                registrationNumber = student.registrationNumber.ifBlank { existing.registrationNumber },
                email = student.email ?: existing.email,
                phone = student.phone ?: existing.phone,
                gender = student.gender ?: existing.gender,
                studentStatus = existing.studentStatus,
                batchId = student.batchId ?: existing.batchId,
                serverUpdatedAt = existing.serverUpdatedAt,
                remoteEmbeddingId = existing.remoteEmbeddingId,
            )
        } else student
        studentDao.insertStudent(merged)
    }

    suspend fun syncPendingStudents(students: List<StudentEntity>) {
        students.forEach { studentDao.insertPendingStudent(it) }
    }

    suspend fun findLocalOnlyMatch(rollNumber: String, batchId: String?, batchCode: String?): StudentEntity? =
        studentDao.findLocalOnlyByBatchAndRoll(rollNumber, batchId, batchCode)

    /**
     * Same id-rename cascade as completeStudentEnrollmentSync (attendance +
     * conflict rows follow), but triggered from the pull path (mergeStudent)
     * when a server student turns out to be a local capture that hasn't
     * synced yet — covers the case where the roster row was created by the
     * website or a different device rather than by this device's own
     * enroll push.
     */
    suspend fun reassignLocalStudentId(oldId: String, newId: String) {
        if (oldId == newId) return
        attendanceDao.renameStudentId(oldId, newId)
        conflictDao.renameTopStudentId(oldId, newId)
        conflictDao.renameSecondStudentId(oldId, newId)
        studentDao.reassignLocalId(oldId, newId)
    }

    suspend fun getStudentsWithUnsyncedEmbedding(): List<StudentEntity> =
        studentDao.getStudentsWithUnsyncedEmbedding()

    suspend fun markEmbeddingSynced(studentId: String) =
        studentDao.markEmbeddingSynced(studentId)

    suspend fun getStudents(): List<StudentEntity> =
        studentDao.getAllStudents()

    suspend fun getEnrolledStudents(): List<StudentEntity> =
        studentDao.getAllEnrolledStudents()

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
     * Updates the editable student-record fields (name, class, roll number,
     * registration number, gender, contact info). Embedding and studentId
     * are untouched — studentId is a sync identifier, not something edited
     * from this UI (see StudentsViewModel.updateStudentInfo doc comment).
     */
    suspend fun updateStudentInfo(
        studentId: String,
        name: String,
        studentClass: String,
        rollNumber: String,
        registrationNumber: String,
        gender: String,
        email: String?,
        phone: String?,
        dateOfBirth: String? = null,
        profilePhotoUrl: String? = null,
    ) = studentDao.updateStudentInfo(
        studentId = studentId,
        name = name,
        studentClass = studentClass,
        rollNumber = rollNumber,
        registrationNumber = registrationNumber,
        gender = gender,
        email = email,
        phone = phone,
        dateOfBirth = dateOfBirth,
        profilePhotoUrl = profilePhotoUrl,
    )

    suspend fun getStudentById(studentId: String): StudentEntity? =
        studentDao.getStudentById(studentId)

    /**
     * Finishes a device-initiated enrollment upload: renames the local
     * roll-number-as-id row to the server's real student_id, cascading the
     * same way a manual roll-number edit does (attendance + conflict rows
     * follow), then flips isLocalOnly/embeddingSynced. Split into two steps
     * because the id-rename touches three tables but only StudentDao knows
     * about isLocalOnly/embeddingSynced.
     */
    suspend fun completeStudentEnrollmentSync(localId: String, serverStudentId: String) {
        if (localId == serverStudentId) {
            studentDao.completeEnrollmentSync(localId, serverStudentId)
            return
        }
        attendanceDao.renameStudentId(localId, serverStudentId)
        conflictDao.renameTopStudentId(localId, serverStudentId)
        conflictDao.renameSecondStudentId(localId, serverStudentId)
        studentDao.completeEnrollmentSync(localId, serverStudentId)
    }

    suspend fun markEmbeddingSyncedOnly(studentId: String) =
        studentDao.markEmbeddingSynced(studentId)

    // ── Attendance ────────────────────────────────────────────────────────────

    suspend fun addAttendance(record: AttendanceEntity) =
        attendanceDao.insertAttendance(record)

    suspend fun getUnsyncedAttendance(): List<AttendanceEntity> =
        attendanceDao.getUnsyncedRecords()

    suspend fun markAttendanceSynced(id: Int) =
        attendanceDao.markAsSynced(id)

    /**
     * Rolling 30-day retention: drops already-synced attendance older than
     * [cutoffMillis]. Called once per sync cycle from AttendanceSyncWorker so
     * local storage never grows unbounded — old data is cleared out day by day
     * as new days age past the 1-month mark, rather than all at once.
     */
    suspend fun purgeOldSyncedAttendance(cutoffMillis: Long) =
        attendanceDao.deleteSyncedRecordsOlderThan(cutoffMillis)

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

    // ── Attendance: backend sync helpers ─────────────────────────────────────

    suspend fun findAttendance(studentId: String, sessionId: String): AttendanceEntity? =
        attendanceDao.findByStudentAndSession(studentId, sessionId)

    suspend fun findAttendanceByRemoteId(remoteId: String): AttendanceEntity? =
        attendanceDao.findByRemoteId(remoteId)

    suspend fun markAttendanceSyncedWithRemoteId(id: Int, remoteId: String) =
        attendanceDao.markSyncedWithRemoteId(id, remoteId)

    /** See AttendanceDao.markCorrectedAbsent — turns a synced PRESENT into a re-pushable ABSENT correction. */
    suspend fun correctSyncedAttendanceToAbsent(studentId: String, sessionId: String) =
        attendanceDao.markCorrectedAbsent(studentId, sessionId)

    /** Applies one attendance-down row from the server (most-recent-wins). See plan.md §6.2. */
    suspend fun applyServerAttendanceUpdate(dto: SyncAttendanceDto, serverUpdatedAtMs: Long) {
        attendanceDao.applyServerUpdateIfNewer(
            remoteId = dto.attendanceId,
            status = dto.attendanceStatus,
            mode = dto.attendanceMode ?: "MANUAL",
            serverUpdatedAt = serverUpdatedAtMs,
        )
    }

    /**
     * Fallback for a local row that predates ever learning the server's
     * attendance_id: locate it by (student, session) and backfill
     * remoteAttendanceId while applying the merge. No-ops if no local row
     * exists yet at that (timetable, date) — the row will simply be created
     * fresh next time this device pushes its own mark, or picked up by the
     * remoteAttendanceId fast path once that happens.
     */
    suspend fun reconcileServerAttendanceUpdate(dto: SyncAttendanceDto, serverUpdatedAtMs: Long) {
        val remoteTimetableId = dto.timetableId ?: return
        val sessionDate = dto.sessionDate ?: return
        val session = sessionDao.findByRemoteTimetableAndDate(remoteTimetableId, sessionDate) ?: return
        val local = attendanceDao.findByStudentAndSession(dto.studentId, session.sessionId) ?: return
        if (local.remoteAttendanceId != null) return // fast path already covers this one
        attendanceDao.backfillAndApplyServerUpdate(
            id = local.id,
            remoteId = dto.attendanceId,
            status = dto.attendanceStatus,
            mode = dto.attendanceMode ?: "MANUAL",
            serverUpdatedAt = serverUpdatedAtMs,
        )
    }

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
        conflictDao.markResolvedAndRequeue(id)

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

    // ── Conflicts: backend sync helpers ──────────────────────────────────────

    suspend fun getUnsyncedConflicts(): List<ConflictEntity> =
        conflictDao.getUnsyncedConflicts()

    suspend fun getConflictById(id: Int): ConflictEntity? =
        conflictDao.getById(id)

    suspend fun markConflictPushed(id: Int, remoteId: String) =
        conflictDao.markConflictPushed(id, remoteId)

    suspend fun markConflictSynced(id: Int) =
        conflictDao.markConflictSynced(id)

    /** Mirrors a conflicts[] row from a sync response into the local queue. */
    suspend fun upsertServerConflict(dto: com.facegate.sync.SyncConflictDto) {
        val existing = conflictDao.findByRemoteId(dto.conflictId)
        val resolved = dto.conflictStatus == "RESOLVED" || dto.conflictStatus == "REJECTED"
        if (existing != null) {
            if (resolved && !existing.resolved) {
                conflictDao.resolveByRemoteId(dto.conflictId)
            }
            return
        }
        // A conflict this device never raised — e.g. an admin flagged one
        // manually on the website. Store enough to display it; there's no
        // top/second-candidate scoring for a WEBSITE-sourced row.
        conflictDao.insertConflict(
            ConflictEntity(
                topStudentId = dto.studentId ?: "unknown",
                topStudentName = dto.studentId ?: "Unknown",
                topScore = 0f,
                secondStudentId = "",
                secondStudentName = "",
                secondScore = 0f,
                reason = dto.description ?: dto.conflictType,
                sessionId = dto.attendanceSessionId ?: "unknown",
                timestamp = System.currentTimeMillis(),
                resolved = resolved,
                remoteConflictId = dto.conflictId,
                synced = true,
                source = "WEBSITE",
                conflictType = dto.conflictType,
                severity = dto.severity ?: "MEDIUM",
                attendanceId = dto.attendanceId,
            )
        )
    }

    /** Applies a student_status change from a sync payload — see StudentDao.getAllEnrolledStudents, which face recognition matching relies on to exclude non-ACTIVE students. */
    suspend fun updateStudentStatus(studentId: String, status: String) =
        studentDao.updateStudentStatus(studentId, status)

    suspend fun updateEmbeddingMetadata(studentId: String, embeddingId: String?, modelName: String, version: String) =
        studentDao.updateEmbeddingMetadata(studentId, embeddingId, modelName, version)

    /** Applies an embedding synced down from the server — see AttendanceSyncWorker.mergeEmbeddingDown. */
    suspend fun applyServerEmbedding(studentId: String, embeddingCsv: String, embeddingVersion: String, modelName: String) =
        studentDao.applyServerEmbedding(studentId, embeddingCsv, embeddingVersion, modelName)
    
    // ── Timetable ─────────────────────────────────────────────────────────
    suspend fun insertTimetable(entry: TimetableEntity) = timetableDao.insert(entry)
    suspend fun updateTimetable(entry: TimetableEntity) = timetableDao.update(entry)
    suspend fun deleteTimetable(id: Int) = timetableDao.delete(id)
    suspend fun getTimetableForDay(dayOfWeek: Int) = timetableDao.getForDay(dayOfWeek)
    suspend fun getAllTimetable() = timetableDao.getAll()
    suspend fun getAllBatches() = timetableDao.getAllBatches()
    suspend fun getAllSubjects() = timetableDao.getAllSubjects()

    /**
     * Insert-or-update a timetable row coming from the backend, matched by
     * remoteTimetableId rather than the local autoincrement `id` (which the
     * server has no concept of). Called once per row on every sync cycle —
     * without this, each sync would insert a fresh duplicate row instead of
     * updating the one that already exists locally.
     */
    suspend fun upsertSyncedTimetable(entry: TimetableEntity) {
        val remoteId = entry.remoteTimetableId
        val existing = remoteId?.let { timetableDao.findByRemoteId(it) }
        if (existing != null) {
            timetableDao.update(entry.copy(id = existing.id))
        } else {
            timetableDao.insert(entry)
        }
    }

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

    // ── Change log (overrides pushed to the backend's change_log) ────────────
    suspend fun getUnpushedOverrides() = overrideDao.getUnpushed()
    suspend fun markOverridePushed(id: Int) = overrideDao.markPushed(id)

    // ── Holidays ───────────────────────────────────────────────────────────
    suspend fun insertHoliday(holiday: HolidayEntity) = holidayDao.insert(holiday)
    suspend fun deleteHoliday(date: String) = holidayDao.delete(date)
    suspend fun isHoliday(date: String): Boolean = holidayDao.isHoliday(date) > 0
    suspend fun getAllHolidays() = holidayDao.getAll()
    suspend fun getUpcomingHolidays() = holidayDao.getUpcoming(getTodayString())

    // ── Sync status (per-category, backs the sync status UI) ─────────────────
    suspend fun recordSyncState(state: SyncStateEntity) = syncStateDao.upsert(state)
    suspend fun getSyncStates(): List<SyncStateEntity> = syncStateDao.getAll()
    fun observeSyncStates(): Flow<List<SyncStateEntity>> = syncStateDao.observeAll()

    private fun getTodayString() = java.text.SimpleDateFormat(
        "yyyy-MM-dd", java.util.Locale.getDefault()
    ).format(java.util.Date())
}