package com.facegate.sync

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AuthUserEntity
import com.facegate.storage.entity.HolidayEntity
import com.facegate.storage.entity.OverrideEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncStateEntity
import com.facegate.storage.entity.TimetableEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker responsible for coordinating synchronization with the
 * backend. Ordered in three tiers:
 *
 *  1. Identity/pull — device-check (room reassignment), heartbeat, and the
 *     combined timetable+students+holidays+conflicts+attendance-down pull.
 *     A failure here retries the whole worker (Result.retry()), same as
 *     before, since everything downstream depends on it.
 *
 *  2. Core push — unsynced attendance. Also retries the whole worker on
 *     failure, since this is the one thing that must never silently stop
 *     going up.
 *
 *  3. Best-effort extras — student enrollment/embedding upload, conflict
 *     push, change-log push. Each is wrapped independently: a failure in
 *     one (e.g. because the backend doesn't have that endpoint yet — see
 *     API_CONTRACT.md Part 3) is recorded to SyncStateEntity and logged,
 *     but does not fail the whole sync cycle or block the others.
 *
 * Every step — including tier 1/2 — records its outcome to SyncStateEntity
 * so AdminDashboard can show real per-category status instead of just the
 * single GET /sync/status line.
 */
@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val deviceRepository: DeviceRepository,
    private val templateRepository: TemplateRepository,
    private val deviceIdManager: DeviceIdManager
) : CoroutineWorker(context, workerParams) {

    private companion object {
        const val TAG = "AttendanceSyncWorker"

        // Input Data Keys
        const val KEY_ROOM_ID = "room_id"

        // Local data retention: keep 30 days of already-synced attendance,
        // then let it age out one day at a time as each new day crosses the
        // boundary (rather than deleting a month's worth all at once).
        const val RETENTION_DAYS = 30L
        const val UNIQUE_WORK_NAME = "attendance_sync_periodic"

        // Server day names, matching chk_day_of_week on the backend
        // (Monday..Saturday — Sunday isn't a valid class day there).
        // Local TimetableEntity.dayOfWeek uses 1=Mon .. 6=Sat.
        val DAY_NAME_TO_INT = mapOf(
            "monday" to 1, "tuesday" to 2, "wednesday" to 3,
            "thursday" to 4, "friday" to 5, "saturday" to 6,
        )

        // How much grace beyond a period's own attendance_window_minutes
        // before we consider it "missed" rather than "still could start
        // late". Keeps a session that starts a couple minutes late from
        // being wrongly flagged every single cycle.
        val MISSED_SESSION_GRACE_MINUTES = 5
    }

    object Scheduler {
        fun schedulePeriodicSync(context: Context, roomId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AttendanceSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_ROOM_ID to roomId))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Forces a one-off run right now, regardless of the hourly schedule —
         * e.g. a manual "Sync Now" button. Runs once; the periodic schedule
         * above is untouched. Returns the request's id so a caller that wants
         * to show a spinner/status (rather than pure fire-and-forget) can
         * observe it via WorkManager.getInstance(context).getWorkInfoByIdLiveData(id).
         */
        fun runOnce(context: Context, roomId: String): java.util.UUID {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_ROOM_ID to roomId))
                .build()

            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting synchronization coordination...")

        if (!deviceIdManager.isPaired()) {
            Log.w(TAG, "Device is not paired yet — skipping sync.")
            return Result.failure()
        }

        try {
            // 1. Heartbeat — device identity comes from the x-api-key header
            // (DeviceAuthInterceptor), not the request body.
            recordAttempt(SyncStateEntity.Category.HEARTBEAT)
            deviceRepository.heartbeat(
                HeartbeatRequest(
                    batteryLevel = getBatteryLevel(),
                    networkStatus = "ONLINE", // WorkManager constraints ensure connectivity
                    storageAvailableMb = getAvailableStorageMb(),
                )
            ).getOrRetry().also { recordSuccess(SyncStateEntity.Category.HEARTBEAT) }

            // 2. Pull timetable + students + holidays + conflicts +
            // attendance-down in one call, merge into local Room entities.
            recordAttempt(SyncStateEntity.Category.PULL)
            val syncData = syncRepository.incrementalSync(since = null).getOrRetry()

            syncData.data?.timetable?.forEach { dto -> mergeTimetable(dto) }
            syncData.data?.students?.forEach { dto -> mergeStudent(dto) }
            syncData.data?.holidays?.forEach { dto -> mergeHoliday(dto) }
            syncData.data?.conflicts?.forEach { dto -> templateRepository.upsertServerConflict(dto) }
            syncData.data?.embeddings?.forEach { dto -> mergeEmbeddingDown(dto) }
            syncData.data?.attendanceUpdates?.forEach { dto -> mergeAttendanceDown(dto) }
            syncData.data?.authUsers?.let { mergeAuthUsers(it) }
            recordSuccess(SyncStateEntity.Category.PULL)

            // 3. Push unsynced attendance — core path, unchanged.
            pushUnsyncedAttendance()

            val cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
            templateRepository.purgeOldSyncedAttendance(cutoffMillis)

            // ── Best-effort extras — each independently caught so one gap
            // (most likely: the backend endpoint doesn't exist yet, see
            // API_CONTRACT.md Part 3) never blocks the others or the core
            // sync above.
            runCatchingStep(SyncStateEntity.Category.ENROLLMENT_UP) { pushPendingEnrollments() }
            runCatchingStep(SyncStateEntity.Category.EMBEDDING_UP) { pushPendingEmbeddings() }
            runCatchingStep(SyncStateEntity.Category.CONFLICTS_UP) { pushPendingConflicts() }
            runCatchingStep(SyncStateEntity.Category.CHANGE_LOG_UP) { detectMissedSessions(); pushChangeLogEvents() }

            Log.i(TAG, "AttendanceSyncWorker executed successfully.")
            return Result.success()

        } catch (e: SyncRetryException) {
            Log.w(TAG, "Temporary failure during sync, will retry: ${e.message}")
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unrecoverable error during sync", e)
            return Result.failure()
        }
    }

    // ── 2. Merge helpers (pull) ──────────────────────────────────────────────

    /**
     * Wholesale replace, not per-row upsert — since AttendanceSyncWorker
     * always calls incrementalSync(since = null) (see doWork above), every
     * sync's authUsers list is already the complete, current set for this
     * device. Replacing rather than upserting is what makes a deactivated
     * or reassigned account's password stop working here promptly, instead
     * of an old row lingering forever in a local-only cache with no way to
     * know it should be removed. See AuthUserEntity's doc comment.
     */
    private suspend fun mergeAuthUsers(dtos: List<SyncAuthUserDto>) {
        templateRepository.replaceAuthUsers(
            dtos.map { dto ->
                AuthUserEntity(
                    adminId = dto.adminId,
                    employeeId = dto.employeeId,
                    firstName = dto.firstName,
                    lastName = dto.lastName,
                    role = dto.role,
                    passwordHash = dto.passwordHash,
                    facultyId = dto.facultyId,
                    serverUpdatedAt = dto.updatedAt,
                )
            }
        )
    }

    private suspend fun mergeTimetable(dto: SyncTimetableDto) {
        val dayInt = DAY_NAME_TO_INT[dto.dayOfWeek.lowercase()] ?: return
        val (hour, minute) = parseTime(dto.startTime) ?: return
        val endParsed = parseTime(dto.endTime)

        // Defensive consistency check — the server is supposed to filter
        // sync by this device's own room already, so this should never
        // actually fire. If it does, it's a real signal something's stale
        // (e.g. a reassignment mid-cycle) rather than a silent data mismatch.
        val expectedRoom = deviceIdManager.getRoomId()
        if (expectedRoom != null && dto.roomId != expectedRoom) {
            Log.w(TAG, "Timetable row ${dto.timetableId} has room ${dto.roomId}, expected $expectedRoom — syncing anyway, but this shouldn't happen.")
        }

        templateRepository.upsertSyncedTimetable(
            TimetableEntity(
                dayOfWeek = dayInt,
                periodNumber = dto.lectureNumber,
                subject = dto.subjectCode ?: dto.subjectName ?: dto.subjectId,
                batch = dto.batchCode ?: dto.batchId,
                scheduledHour = hour,
                scheduledMinute = minute,
                windowMinutes = dto.attendanceWindowMinutes,
                remoteTimetableId = dto.timetableId,
                subjectName = dto.subjectName,
                facultyName = dto.facultyName,
                roomId = dto.roomId,
                batchId = dto.batchId,
                facultyId = dto.facultyId,
                subjectId = dto.subjectId,
                endHour = endParsed?.first,
                endMinute = endParsed?.second,
                effectiveFrom = dto.effectiveFrom,
                effectiveTo = dto.effectiveTo,
                serverUpdatedAt = dto.updatedAt,
            )
        )
    }

    private suspend fun mergeStudent(dto: SyncStudentDto) {
        // This server row and a not-yet-synced on-device capture can be the
        // same real person: the device creates a local-only row keyed by a
        // typed placeholder id (e.g. "CSE24A-R11") the moment a face is
        // captured, before it's ever pushed. If the matching roster row
        // shows up in a pull first — created by the website, another
        // device, or this device's own enroll call merging into an
        // existing row (see backend enrollStudent) — insertPendingStudent
        // below would IGNORE on conflict, but that conflict check is by
        // primary key, and the two rows have different keys (placeholder
        // vs. server UUID). Without this, that's a second, embedding-less
        // row for the same student sitting right next to the real one —
        // reassign the local row's id instead so there's only ever one.
        val localMatch = templateRepository.findLocalOnlyMatch(
            rollNumber = dto.rollNumber,
            batchId = dto.batchId,
            batchCode = dto.batchCode,
        )
        if (localMatch != null && localMatch.studentId != dto.studentId) {
            templateRepository.reassignLocalStudentId(localMatch.studentId, dto.studentId)
        }

        // insertPendingStudent() uses OnConflictStrategy.IGNORE, so a student
        // already enrolled locally (with a captured embedding) is never
        // clobbered by a repeat sync — see StudentDao. That means a status
        // change (e.g. ACTIVE -> DROPPED) for an already-enrolled student
        // won't land via this path; syncPendingStudents applies it as a
        // separate explicit update below so recognition filtering (see
        // StudentDao.getAllEnrolledStudents) stays correct regardless.
        templateRepository.syncPendingStudents(
            listOf(
                StudentEntity(
                    studentId = dto.studentId,   // server's real UUID
                    name = "${dto.firstName} ${dto.lastName}".trim(),
                    studentClass = dto.batchCode ?: dto.batchId,
                    embedding = null,
                    enrollmentStatus = "PENDING",
                    rollNumber = dto.rollNumber,
                    batchCode = dto.batchCode,
                    isLocalOnly = false,   // came from the server — never a candidate for the enroll-create endpoint
                    registrationNumber = dto.registrationNumber,
                    email = dto.email,
                    phone = dto.phone,
                    gender = dto.gender,
                    studentStatus = dto.studentStatus ?: "ACTIVE",
                    batchId = dto.batchId,
                    serverUpdatedAt = dto.updatedAt,
                    admissionYear = dto.admissionYear,
                    dateOfBirth = dto.dateOfBirth,
                    profilePhotoUrl = dto.profilePhotoUrl,
                )
            )
        )
        templateRepository.updateStudentStatus(dto.studentId, dto.studentStatus ?: "ACTIVE")
    }

    private suspend fun mergeHoliday(dto: SyncHolidayDto) {
        templateRepository.insertHoliday(
            HolidayEntity(
                date = dto.holidayDate,
                name = dto.holidayName,
                createdAt = System.currentTimeMillis(),
                remoteHolidayId = dto.holidayId,
                holidayType = dto.holidayType,
                isRecurring = dto.isRecurring ?: false,
                serverUpdatedAt = dto.updatedAt,
            )
        )
    }

    /**
     * Embedding-down: a student enrolled at another room's device (or
     * enrolled via some other path entirely) becomes recognizable on THIS
     * device too, without ever being captured locally. Previously
     * StudentEntity.embedding was only ever set by this device's own
     * enrollment pipeline — a student's DONE/embeddingSynced state now also
     * gets set purely from a sync pull, no capture flow involved.
     *
     * Only applies to a student this device already knows about (from the
     * students[] half of the same sync payload, or a prior one) — an
     * embedding for a student not yet locally present is skipped rather
     * than creating a bare placeholder row; it'll be picked up the next
     * cycle once that student's own row has synced down first.
     */
    private suspend fun mergeEmbeddingDown(dto: SyncEmbeddingDto) {
        val student = templateRepository.getStudentById(dto.studentId) ?: return

        // Pull happens before push in this worker's step order (see doWork).
        // If this student has a locally-captured embedding still queued to
        // upload (embeddingSynced = false), it's necessarily newer than
        // whatever this pull just fetched — applying the pulled value here
        // would silently throw away a capture that hasn't gone out yet.
        // It'll get its turn once pushPendingEmbeddings() runs later this
        // same cycle, and the server value will catch up next pull.
        if (!student.embeddingSynced && student.embedding != null) return

        val csv = jsonEmbeddingToCsv(dto.embeddingData) ?: return

        templateRepository.applyServerEmbedding(
            studentId = dto.studentId,
            embeddingCsv = csv,
            embeddingVersion = dto.embeddingVersion ?: "v1.0",
            modelName = dto.modelName,
        )
    }

    /** Attendance-down: applies a website-side correction locally, most-recent-wins. */
    private suspend fun mergeAttendanceDown(dto: SyncAttendanceDto) {
        val serverUpdatedAtMs = parseIsoToMillis(dto.updatedAt) ?: return
        val existing = templateRepository.findAttendanceByRemoteId(dto.attendanceId)
        if (existing != null) {
            templateRepository.applyServerAttendanceUpdate(dto, serverUpdatedAtMs)
        } else {
            templateRepository.reconcileServerAttendanceUpdate(dto, serverUpdatedAtMs)
        }
    }

    // ── 3. Core push (attendance up) ─────────────────────────────────────────

    private suspend fun pushUnsyncedAttendance() {
        recordAttempt(SyncStateEntity.Category.ATTENDANCE_UP)
        // Records whose session is missing remoteTimetableId/sessionDate
        // (e.g. an ad-hoc "extra period" with no matching server timetable
        // row — see SessionEntity) can't be attached to anything on the
        // backend and are skipped, not silently dropped: they stay unsynced
        // and get logged every cycle until that gap is addressed.
        val unsynced = templateRepository.getUnsyncedAttendance()
        if (unsynced.isEmpty()) {
            recordSuccess(SyncStateEntity.Category.ATTENDANCE_UP, pendingCount = 0)
            return
        }

        val uploadable = mutableListOf<Pair<Int, OfflineAttendanceDto>>()
        var skipped = 0

        for (record in unsynced) {
            val session = record.sessionId?.let { templateRepository.getSessionById(it) }
            val timetableId = session?.remoteTimetableId
            val sessionDate = session?.sessionDate

            if (session == null || timetableId.isNullOrBlank() || sessionDate.isNullOrBlank()) {
                skipped++
                continue
            }

            uploadable += record.id to OfflineAttendanceDto(
                sessionId = record.sessionId,
                timetableId = timetableId,
                sessionDate = sessionDate,
                studentId = record.studentId,
                status = record.attendanceStatus,
                attendanceMode = record.attendanceMode,
                // schema.sql: recognition_confidence is DECIMAL(5,2) CHECK
                // (0-100) — a percentage. record.confidence is this app's
                // internal 0.0-1.0 cosine similarity; sending it unconverted
                // silently stores "0.94%" instead of "94%" (it still passes
                // the CHECK, since 0.94 is between 0 and 100, so this was
                // never going to show up as an upload failure — just wrong
                // numbers on the website's Reports/Conflicts pages).
                confidence = record.confidence?.let { (it * 100).coerceIn(0.0, 100.0) },
                // schema.sql: attendance_time is TIMESTAMP — must be
                // ISO-8601. record.timeStamp is epoch millis; sending
                // `.toString()` of that (e.g. "1752247523000") is not a
                // valid timestamp and is the most likely reason attendance
                // uploads were failing outright rather than just having
                // wrong confidence values.
                timestamp = toIso8601(record.timeStamp),
            )
        }

        if (skipped > 0) {
            Log.w(TAG, "$skipped attendance record(s) skipped — session missing server timetable reference.")
        }

        if (uploadable.isNotEmpty()) {
            syncRepository.uploadAttendance(
                AttendanceUploadRequest(records = uploadable.map { it.second })
            ).getOrRetry()

            uploadable.forEach { (id, _) -> templateRepository.markAttendanceSynced(id) }
        }
        recordSuccess(SyncStateEntity.Category.ATTENDANCE_UP, pendingCount = skipped)
    }

    // ── 3a. Enrollment / embedding push (best-effort) ────────────────────────

    private suspend fun pushPendingEnrollments() {
        val pending = templateRepository.getStudentsWithUnsyncedEmbedding().filter { it.isLocalOnly }
        for (student in pending) {
            val embeddingFloats = student.embedding
                ?.split(",")
                ?.mapNotNull { it.trim().toFloatOrNull() }
                ?: continue
            if (embeddingFloats.isEmpty()) continue

            // schema.sql: student.gender and admission_year are NOT NULL
            // with no default — sending a request without them would just
            // fail server-side. EnrollmentFragment's dialog collects both
            // (rgGender / etAdmissionYear) for every new enrollment, so
            // this only matters for a student row that predates that
            // dialog field or was created through some other path; kept as
            // a defensive skip (retried every cycle) rather than sending a
            // request guaranteed to be rejected.
            val gender = student.gender
            val admissionYear = student.admissionYear
            if (gender.isNullOrBlank() || admissionYear == null) {
                Log.w(TAG, "Skipping enrollment upload for ${student.studentId} — gender/admission_year not captured yet (required by student table).")
                continue
            }

            val (first, last) = splitName(student.name)
            // Deterministic, not random: this call can retry (network drop
            // after the backend already committed, worker killed before the
            // response arrives, etc.) without the client ever finding out
            // the first attempt actually succeeded. A fresh random UUID on
            // every retry meant the backend's `ON CONFLICT (student_id) DO
            // UPDATE` upsert never matched the earlier row, so a retry after
            // a lost response tried to INSERT a second row with the same
            // (batch_id, roll_number) and hit uq_student_batch_roll instead
            // of just updating the original — a permanent 400 loop. Hashing
            // the stable local studentId gives the same UUID on every
            // attempt for this student, so retries are true no-ops on the
            // backend once the first one has landed.
            val serverStudentId = UUID.nameUUIDFromBytes(
                student.studentId.toByteArray(Charsets.UTF_8)
            ).toString()
            val modelName = student.embeddingModelName ?: "facegate-mobile"

            val result = syncRepository.enrollStudent(
                EnrollStudentRequest(
                    studentId = serverStudentId,
                    batchCode = student.batchCode ?: student.studentClass,
                    registrationNumber = student.registrationNumber.ifBlank { student.rollNumber.ifBlank { student.studentId } },
                    rollNumber = student.rollNumber.ifBlank { student.studentId },
                    firstName = first,
                    lastName = last,
                    gender = gender,
                    admissionYear = admissionYear,
                    dateOfBirth = student.dateOfBirth,
                    profilePhotoUrl = student.profilePhotoUrl,
                    email = student.email,
                    phone = student.phone,
                    embeddingData = embeddingFloats,
                    embeddingVersion = student.embeddingVersion,
                    modelName = modelName,
                )
            )

            result.onSuccess { response ->
                val returnedId = response.data?.studentId ?: serverStudentId
                templateRepository.completeStudentEnrollmentSync(student.studentId, returnedId)
                templateRepository.updateEmbeddingMetadata(returnedId, response.data?.embeddingId, modelName, student.embeddingVersion)
            }.onFailure { e ->
                Log.w(TAG, "Enrollment upload failed for ${student.studentId}: ${e.message}")
            }
        }
    }

    private suspend fun pushPendingEmbeddings() {
        // Students whose embedding changed (re-enrollment) but who the
        // server already knows about — isLocalOnly = false, so these never
        // overlap with pushPendingEnrollments() above.
        val pending = templateRepository.getStudentsWithUnsyncedEmbedding().filterNot { it.isLocalOnly }
        if (pending.isEmpty()) return

        for (student in pending) {
            val json = csvEmbeddingToJson(student.embedding) ?: continue
            val modelName = student.embeddingModelName ?: "facegate-mobile"

            syncRepository.uploadEmbedding(
                EmbeddingUploadRequest(
                    studentId = student.studentId,
                    embeddingData = json,
                    embeddingVersion = student.embeddingVersion,
                    modelName = modelName,
                )
            ).onSuccess {
                templateRepository.markEmbeddingSyncedOnly(student.studentId)
                templateRepository.updateEmbeddingMetadata(student.studentId, null, modelName, student.embeddingVersion)
            }.onFailure { e ->
                Log.w(TAG, "Embedding upload failed for ${student.studentId}: ${e.message}")
            }
        }
    }

    // ── 3b. Conflict push (best-effort) ──────────────────────────────────────

    private suspend fun pushPendingConflicts() {
        val unsynced = templateRepository.getUnsyncedConflicts()
        if (unsynced.isEmpty()) return

        // Conflicts already carrying a remoteConflictId are resolutions
        // being pushed (this device resolved something it created earlier);
        // everything else is a brand-new conflict this device just detected.
        val (toResolve, toCreate) = unsynced.partition { it.remoteConflictId != null }

        for (conflict in toResolve) {
            val remoteId = conflict.remoteConflictId ?: continue
            val status = if (conflict.resolved) "RESOLVED" else "REJECTED"
            syncRepository.resolveConflict(remoteId, ConflictResolveRequest(conflictStatus = status))
                .onSuccess { templateRepository.markConflictSynced(conflict.id) }
                .onFailure { e -> Log.w(TAG, "Conflict resolve push failed for ${conflict.id}: ${e.message}") }
        }

        if (toCreate.isNotEmpty()) {
            // A conflict recorded outside any tracked session (e.g.
            // AttendancePipeline's "no_session" fallback — sessionId ==
            // "no_session") has no timetableId/sessionDate to resolve.
            // Previously that meant skipping it entirely, since
            // conflict.attendance_session_id was NOT NULL server-side; the
            // backend now accepts a NULL session (device_id alone still
            // attributes it to a room), so these are sent too, just
            // without timetableId/sessionDate.
            val records = toCreate.map { conflict ->
                val session = conflict.sessionId
                    .takeIf { it.isNotBlank() && it != "no_session" }
                    ?.let { templateRepository.getSessionById(it) }

                ConflictUploadDto(
                    sessionId = conflict.sessionId,
                    timetableId = session?.remoteTimetableId,
                    sessionDate = session?.sessionDate,
                    studentId = conflict.topStudentId.takeIf { it.isNotBlank() && it != "unknown" },
                    conflictType = conflict.conflictType,
                    severity = conflict.severity,
                    description = conflict.reason,
                )
            }
            syncRepository.uploadConflicts(
                ConflictUploadRequest(records = records, clientRefs = toCreate.map { it.id })
            ).onSuccess { response ->
                response.data?.created?.forEach { result ->
                    templateRepository.markConflictPushed(result.clientRef, result.conflictId)
                }
            }.onFailure { e ->
                Log.w(TAG, "Conflict upload failed: ${e.message}")
            }
        }
    }

    // ── 3c. Missed-session detection + change-log push (best-effort) ────────

    /**
     * Flags today's scheduled periods whose attendance window has already
     * closed with no session ever started on this device — plan.md's
     * "change log ... if teacher not start class". Writes a local
     * OverrideEntity (this app's existing change-log concept — see
     * ChangesLogViewModel) which pushChangeLogEvents() then ships up.
     */
    private suspend fun detectMissedSessions() {
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_WEEK).let {
            // Calendar.SUNDAY=1..SATURDAY=7 → this app's 1=Mon..6=Sat, 7=Sun
            if (it == Calendar.SUNDAY) 7 else it - 1
        }
        // Weekly-off removed — the timetable itself is the only source of
        // truth for which days have periods now. Sunday (7) still has no
        // valid day_of_week value on the backend (see DAY_NAME_TO_INT), so
        // it's the one day still worth short-circuiting here.
        if (today == 7) return

        val todayString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (templateRepository.isHoliday(todayString)) return

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endOfDay = startOfDay + TimeUnit.DAYS.toMillis(1) - 1

        val periods = templateRepository.getTimetableForDay(today).filter { it.remoteTimetableId != null }
        val sessionsToday = templateRepository.getSessionsForDate(startOfDay, endOfDay)
        val startedTimetableIds = sessionsToday.mapNotNull { it.timetableId }.toSet()

        for (period in periods) {
            if (period.id in startedTimetableIds) continue

            val scheduled = Calendar.getInstance().apply {
                timeInMillis = startOfDay
                set(Calendar.HOUR_OF_DAY, period.scheduledHour)
                set(Calendar.MINUTE, period.scheduledMinute)
            }
            val windowClosedAt = scheduled.timeInMillis +
                TimeUnit.MINUTES.toMillis((period.windowMinutes + MISSED_SESSION_GRACE_MINUTES).toLong())

            if (System.currentTimeMillis() <= windowClosedAt) continue // window hasn't closed yet, not "missed"

            // Avoid re-logging the same missed period every hourly cycle —
            // one override per (day, timetable row) is enough signal.
            val alreadyLogged = templateRepository.getOverridesForSession("missed:${period.id}:$todayString")
            if (alreadyLogged.isNotEmpty()) continue

            templateRepository.insertOverride(
                OverrideEntity(
                    sessionId = "missed:${period.id}:$todayString",
                    fieldChanged = "session not started",
                    oldValue = "scheduled ${period.scheduledHour}:${"%02d".format(period.scheduledMinute)}",
                    newValue = "no session started",
                    changedAt = System.currentTimeMillis(),
                    reason = "${period.subjectName ?: period.subject} (${period.batch}) — attendance window closed with no session started",
                )
            )
        }
    }

    private suspend fun pushChangeLogEvents() {
        val unpushed = templateRepository.getUnpushedOverrides()
        if (unpushed.isEmpty()) return

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        val events = unpushed.map { override ->
            ChangeLogEventDto(
                entityName = if (override.fieldChanged == "session not started") "timetable_session" else "timetable",
                entityId = override.sessionId,
                action = "UPDATE",
                description = "${override.fieldChanged}: ${override.oldValue} → ${override.newValue}" +
                    if (override.reason.isNotBlank()) " (${override.reason})" else "",
                occurredAt = isoFormat.format(Date(override.changedAt)),
            )
        }

        deviceRepository.pushChangeLogEvents(ChangeLogEventRequest(events = events)).onSuccess {
            unpushed.forEach { templateRepository.markOverridePushed(it.id) }
        }.onFailure { e ->
            Log.w(TAG, "Change log push failed: ${e.message}")
        }
    }

    // ── Sync status bookkeeping ──────────────────────────────────────────────

    private suspend fun recordAttempt(category: String) {
        val previous = templateRepository.getSyncStates().find { it.category == category }
        templateRepository.recordSyncState(
            SyncStateEntity(
                category = category,
                lastAttemptAt = System.currentTimeMillis(),
                lastSuccessAt = previous?.lastSuccessAt,
                status = "IN_PROGRESS",
                pendingCount = previous?.pendingCount ?: 0,
            )
        )
    }

    private suspend fun recordSuccess(category: String, message: String? = null, pendingCount: Int = 0) {
        val now = System.currentTimeMillis()
        templateRepository.recordSyncState(
            SyncStateEntity(
                category = category,
                lastAttemptAt = now,
                lastSuccessAt = now,
                status = "OK",
                message = message,
                pendingCount = pendingCount,
            )
        )
    }

    private suspend fun recordFailure(category: String, message: String?) {
        val previous = templateRepository.getSyncStates().find { it.category == category }
        templateRepository.recordSyncState(
            SyncStateEntity(
                category = category,
                lastAttemptAt = System.currentTimeMillis(),
                lastSuccessAt = previous?.lastSuccessAt,
                status = "FAILED",
                message = message,
                pendingCount = previous?.pendingCount ?: 0,
            )
        )
    }

    /** Runs a best-effort sync step: records attempt/success/failure without throwing into the outer catch. */
    private suspend fun runCatchingStep(category: String, block: suspend () -> Unit) {
        recordAttempt(category)
        try {
            block()
            recordSuccess(category)
        } catch (e: Exception) {
            Log.w(TAG, "Sync step '$category' failed (non-fatal): ${e.message}")
            recordFailure(category, e.message)
        }
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private fun parseTime(value: String): Pair<Int, Int>? {
        val parts = value.split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return hour to minute
    }

    /**
     * StudentEntity.embedding is stored as comma-separated floats
     * everywhere else in this app (AttendancePipeline's parseEmbedding
     * does the same encoding) — this just wraps that same string as the
     * JSON array embedding_data actually expects on the wire.
     */
    private fun csvEmbeddingToJson(csv: String?): com.google.gson.JsonElement? {
        if (csv.isNullOrBlank()) return null
        val floats = csv.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (floats.isEmpty()) return null
        val array = com.google.gson.JsonArray()
        floats.forEach { array.add(it) }
        return array
    }

    /** Inverse of csvEmbeddingToJson — tolerates only a JSON array of numbers; anything else is treated as unparseable rather than guessed at. */
    private fun jsonEmbeddingToCsv(json: com.google.gson.JsonElement): String? {
        if (!json.isJsonArray) return null
        val values = json.asJsonArray.mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asFloat else null
        }
        if (values.isEmpty()) return null
        return values.joinToString(",")
    }

    private fun toIso8601(epochMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMillis))
    }

    private fun parseIsoToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(iso.replace("Z", "").substringBefore("."))?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun splitName(fullName: String): Pair<String, String> {
        val parts = fullName.trim().split(" ", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else (parts.getOrElse(0) { "" } to "")
    }

    /**
     * Extension to handle kotlin.Result and throw a custom exception for retries.
     * Uses fully qualified name for Result to avoid conflict with androidx.work.Result.
     */
    private fun <T> kotlin.Result<T>.getOrRetry(): T {
        return getOrElse { throwable ->
            if (throwable is IOException || throwable is HttpException) {
                throw SyncRetryException(throwable.message, throwable)
            }
            throw throwable
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun getAvailableStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
    }

    /**
     * Custom exception to signal that WorkManager should retry the task.
     */
    private class SyncRetryException(message: String?, cause: Throwable?) : Exception(message, cause)
}