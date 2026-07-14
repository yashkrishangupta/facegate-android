package com.facegate.sync

import com.google.gson.annotations.SerializedName

/**
 * Data transfer objects for the FaceGate sync/device API.
 *
 * Every field name here is verified against the running backend
 * (facegate-backend/api), not against the aspirational API_CONTRACT.md —
 * see the updated contract doc for the authoritative reference. Gson has no
 * naming policy configured (AppModule.provideGson uses GsonBuilder().create()
 * with no FieldNamingPolicy), so every field needs an explicit
 * @SerializedName matching the backend's real JSON key, camelCase or
 * snake_case as it actually is — a bare Kotlin camelCase field name will
 * silently fail to deserialize a snake_case key.
 */

// ── Device pairing ───────────────────────────────────────────────────────────
//
// Pairing is admin-initiated, not self-service. An admin creates the device
// record on the website (choosing a room) and gets back a 6-digit pairing
// code, valid 15 minutes. This screen's only job is to redeem that code.

/**
 * POST /api/v1/devices/pair — no auth header needed (this IS the call that
 * obtains the device_token used by every other request). Body is snake_case.
 */
data class PairDeviceRequest(
    @SerializedName("pairing_code") val pairingCode: String,
    @SerializedName("device_identifier") val deviceIdentifier: String,
    @SerializedName("app_version") val appVersion: String,
    @SerializedName("operating_system") val operatingSystem: String,
)

data class PairDeviceData(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceToken") val deviceToken: String,
    @SerializedName("roomId") val roomId: String,
    // Not sent by the real backend today (confirmed against
    // DeviceController.pairDevice — its response only has deviceId/
    // deviceToken/roomId) — kept optional so this starts working the moment
    // that response is extended, without an Android-side contract change.
    @SerializedName("roomNumber") val roomNumber: String? = null,
)

data class PairDeviceResponse(
    val success: Boolean,
    val message: String?,
    val data: PairDeviceData?,
)

data class DeviceDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("room_id") val roomId: String,
    @SerializedName("device_identifier") val deviceIdentifier: String?,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_type") val deviceType: String?,
    @SerializedName("app_version") val appVersion: String?,
    @SerializedName("operating_system") val operatingSystem: String?,
    @SerializedName("device_status") val deviceStatus: String?,
    @SerializedName("network_status") val networkStatus: String?,
    @SerializedName("battery_percentage") val batteryPercentage: Int?,
    @SerializedName("is_active") val isActive: Boolean?,
)

data class DeviceDetailsResponse(
    val success: Boolean,
    val message: String?,
    val data: DeviceDto?,
)

// ── Heartbeat (lives under /devices/heartbeat, not /sync/heartbeat) ────────

/** POST /api/v1/devices/heartbeat — x-api-key protected. */
data class HeartbeatRequest(
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("network_status") val networkStatus: String,
    @SerializedName("storage_available_mb") val storageAvailableMb: Long,
)

data class HeartbeatResponseData(
    val success: Boolean,
    val device: DeviceDto?,
)

data class HeartbeatResponse(
    val success: Boolean,
    val message: String?,
    val data: HeartbeatResponseData?,
)

// ── Sync payload shapes ─────────────────────────────────────────────────────

data class SyncTimetableDto(
    @SerializedName("timetable_id") val timetableId: String,
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("batch_code") val batchCode: String?,
    @SerializedName("faculty_id") val facultyId: String,
    @SerializedName("faculty_name") val facultyName: String?,
    @SerializedName("subject_id") val subjectId: String,
    @SerializedName("subject_code") val subjectCode: String?,
    @SerializedName("subject_name") val subjectName: String?,
    @SerializedName("room_id") val roomId: String,
    @SerializedName("day_of_week") val dayOfWeek: String,   // "Monday".."Saturday" — a name, not a number
    @SerializedName("lecture_number") val lectureNumber: Int,
    @SerializedName("start_time") val startTime: String,    // "HH:MM:SS"
    @SerializedName("end_time") val endTime: String,
    @SerializedName("attendance_window_minutes") val attendanceWindowMinutes: Int,
    // API_CONTRACT.md's documented timetable[] shape includes these — the
    // DTO was previously missing them entirely, silently discarding
    // whatever the server sent.
    @SerializedName("effective_from") val effectiveFrom: String? = null,
    @SerializedName("effective_to") val effectiveTo: String? = null,
    @SerializedName("updated_at") val updatedAt: String?,
)

data class SyncStudentDto(
    @SerializedName("student_id") val studentId: String,
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("batch_code") val batchCode: String?,
    @SerializedName("registration_number") val registrationNumber: String,
    @SerializedName("roll_number") val rollNumber: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    @SerializedName("email") val email: String?,
    @SerializedName("phone") val phone: String?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("admission_year") val admissionYear: Int? = null,
    @SerializedName("date_of_birth") val dateOfBirth: String? = null,
    @SerializedName("profile_photo_url") val profilePhotoUrl: String? = null,
    @SerializedName("student_status") val studentStatus: String?,
    @SerializedName("updated_at") val updatedAt: String?,
)

data class SyncHolidayDto(
    @SerializedName("holiday_id") val holidayId: String,
    @SerializedName("holiday_date") val holidayDate: String,
    @SerializedName("holiday_name") val holidayName: String,
    @SerializedName("holiday_type") val holidayType: String?,
    @SerializedName("is_recurring") val isRecurring: Boolean?,
    @SerializedName("updated_at") val updatedAt: String?,
)

/**
 * A conflict row relevant to this device's room, mirrored down so a
 * website-side resolution (or a conflict an admin raised manually) shows up
 * here too — not just conflicts this device itself detected.
 * See ConflictDao (source = "WEBSITE" for these).
 */
data class SyncConflictDto(
    @SerializedName("conflict_id") val conflictId: String,
    @SerializedName("attendance_id") val attendanceId: String?,
    @SerializedName("attendance_session_id") val attendanceSessionId: String?,
    @SerializedName("student_id") val studentId: String?,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("conflict_type") val conflictType: String,
    @SerializedName("severity") val severity: String?,
    @SerializedName("conflict_status") val conflictStatus: String, // PENDING/UNDER_REVIEW/RESOLVED/REJECTED
    @SerializedName("description") val description: String?,
    @SerializedName("updated_at") val updatedAt: String?,
)

/**
 * An attendance row as it currently stands server-side — the "attendance-down"
 * half of plan.md §6.2 (not built on the backend yet; this DTO is what that
 * endpoint/payload needs to return once it exists). attendance_id +
 * updated_at are what let AttendanceDao.applyServerUpdateIfNewer() do the
 * "most recent wins" merge instead of blindly overwriting a pending local edit.
 */
data class SyncAttendanceDto(
    @SerializedName("attendance_id") val attendanceId: String,
    @SerializedName("attendance_session_id") val attendanceSessionId: String,
    @SerializedName("timetable_id") val timetableId: String?,
    @SerializedName("session_date") val sessionDate: String?,
    @SerializedName("student_id") val studentId: String,
    @SerializedName("attendance_status") val attendanceStatus: String,
    @SerializedName("attendance_mode") val attendanceMode: String?,
    @SerializedName("updated_at") val updatedAt: String,
)

/**
 * A student's face embedding, synced down so they're recognizable at any
 * room's device, not just the one that originally enrolled them — this is
 * the real, backend-verified counterpart to EmbeddingUploadRequest above.
 * Previously (see StudentEntity.isLocalOnly/embeddingSynced doc history)
 * this app assumed embeddings never synced down at all and every device
 * had to re-capture every student locally; that's no longer true.
 *
 * embeddingData is left as a raw JsonElement rather than a typed structure
 * since its shape is a model concern, not a sync-protocol concern — see
 * AttendanceSyncWorker's json↔CSV conversion helpers for how this app's
 * local storage format (comma-separated floats, same as everywhere else in
 * StudentEntity.embedding) is produced from it.
 */
data class SyncEmbeddingDto(
    @SerializedName("student_id") val studentId: String,
    @SerializedName("embedding_data") val embeddingData: com.google.gson.JsonElement,
    @SerializedName("embedding_version") val embeddingVersion: String?,
    @SerializedName("model_name") val modelName: String,
    @SerializedName("confidence_threshold") val confidenceThreshold: Double?,
    @SerializedName("updated_at") val updatedAt: String?,
)

data class FullSyncData(
    val timetable: List<SyncTimetableDto> = emptyList(),
    val students: List<SyncStudentDto> = emptyList(),
    val holidays: List<SyncHolidayDto> = emptyList(),
    // Not on the backend yet — see API_CONTRACT.md Part 3. Defaulted to
    // emptyList() so Gson leaves this alone (and merges no-op) until the
    // backend adds it, rather than every existing full-sync call needing
    // a matching field.
    val conflicts: List<SyncConflictDto> = emptyList(),
    // Real, backend-verified — see SyncEmbeddingDto's doc comment.
    val embeddings: List<SyncEmbeddingDto> = emptyList(),
    @SerializedName("attendanceUpdates") val attendanceUpdates: List<SyncAttendanceDto> = emptyList(),
    @SerializedName("lastSync") val lastSync: String?,
)

data class FullSyncResponse(
    val success: Boolean,
    val message: String?,
    val data: FullSyncData?,
)

data class IncrementalSyncData(
    @SerializedName("updatedTimetable") val updatedTimetable: Int,
    @SerializedName("updatedStudents") val updatedStudents: Int,
    @SerializedName("updatedHolidays") val updatedHolidays: Int,
    @SerializedName("updatedEmbeddings") val updatedEmbeddings: Int = 0,
    val timetable: List<SyncTimetableDto> = emptyList(),
    val students: List<SyncStudentDto> = emptyList(),
    val holidays: List<SyncHolidayDto> = emptyList(),
    val conflicts: List<SyncConflictDto> = emptyList(),
    val embeddings: List<SyncEmbeddingDto> = emptyList(),
    @SerializedName("attendanceUpdates") val attendanceUpdates: List<SyncAttendanceDto> = emptyList(),
    @SerializedName("lastSync") val lastSync: String?,
)

data class IncrementalSyncResponse(
    val success: Boolean,
    val message: String?,
    val data: IncrementalSyncData?,
)

// ── Student enrollment + face-embedding upload (device → server) ───────────
//
// Neither endpoint exists on the backend yet — see API_CONTRACT.md Part 3,
// "New device sync endpoints". Written against what the DATABASE_DESIGN.md
// `student` / `face_embedding` tables need to receive.

/**
 * Creates a brand-new student record AND its embedding in one call, for a
 * student enrolled directly on a device (StudentsFragment/EnrollmentViewModel)
 * rather than imported via the website first. student_id is generated
 * client-side (UUID) so the local row and the server row can share the same
 * id from the start — see TemplateRepository.completeStudentEnrollmentSync.
 */
data class EnrollStudentRequest(
    @SerializedName("student_id") val studentId: String,
    @SerializedName("batch_code") val batchCode: String?,
    @SerializedName("registration_number") val registrationNumber: String,
    @SerializedName("roll_number") val rollNumber: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    // schema.sql: student.gender/admission_year are NOT NULL with no
    // default — omitting these would fail the insert server-side.
    @SerializedName("gender") val gender: String,           // CHECK: Male | Female | Other
    @SerializedName("admission_year") val admissionYear: Int,
    @SerializedName("date_of_birth") val dateOfBirth: String? = null,   // "yyyy-MM-dd", nullable on the backend
    @SerializedName("email") val email: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("embedding_data") val embeddingData: List<Float>,
    // schema.sql: face_embedding.embedding_version is VARCHAR(20) DEFAULT
    // 'v1.0' — a label, not an integer.
    @SerializedName("embedding_version") val embeddingVersion: String = "v1.0",
    @SerializedName("model_name") val modelName: String,
)

data class EnrollStudentData(
    @SerializedName("student_id") val studentId: String,
    @SerializedName("embedding_id") val embeddingId: String?,
)

data class EnrollStudentResponse(
    val success: Boolean,
    val message: String?,
    val data: EnrollStudentData?,
)

/** Re-enrollment / embedding update for a student the server already has. */
/**
 * POST /api/v1/sync/embeddings — x-api-key protected. Upserts on
 * student_id server-side, so a re-enrollment naturally replaces the old
 * vector rather than erroring. One embedding per call — the backend has no
 * batch variant (contract confirmed against the running backend, not just
 * API_CONTRACT.md — see SyncEmbeddingDto's doc comment for the matching
 * down-sync half).
 */
data class EmbeddingUploadRequest(
    @SerializedName("student_id") val studentId: String,
    @SerializedName("embedding_data") val embeddingData: com.google.gson.JsonElement,
    @SerializedName("embedding_version") val embeddingVersion: String = "v1.0",
    @SerializedName("model_name") val modelName: String,
    @SerializedName("confidence_threshold") val confidenceThreshold: Double? = null,
)

data class EmbeddingUploadResponse(
    val success: Boolean,
    val message: String?,
)

// ── Conflicts (device → server) ──────────────────────────────────────────

/** A conflict this device's decision engine detected, being pushed up for the first time. */
data class ConflictUploadDto(
    @SerializedName("session_id") val sessionId: String,      // local session id; server resolves via timetable_id+session_date like attendance
    @SerializedName("timetable_id") val timetableId: String?,
    @SerializedName("session_date") val sessionDate: String?,
    @SerializedName("student_id") val studentId: String?,      // top candidate, or null if truly unknown
    @SerializedName("conflict_type") val conflictType: String,
    @SerializedName("severity") val severity: String = "MEDIUM",
    @SerializedName("description") val description: String,
)

data class ConflictUploadRequest(
    val records: List<ConflictUploadDto>,
    // Lets the server correlate created rows back to local ids in the response.
    @SerializedName("client_refs") val clientRefs: List<Int>,
)

data class ConflictUploadResultDto(
    @SerializedName("client_ref") val clientRef: Int,
    @SerializedName("conflict_id") val conflictId: String,
)

data class ConflictUploadData(
    val created: List<ConflictUploadResultDto> = emptyList(),
)

data class ConflictUploadResponse(
    val success: Boolean,
    val message: String?,
    val data: ConflictUploadData?,
)

data class ConflictResolveRequest(
    @SerializedName("conflict_status") val conflictStatus: String, // RESOLVED | REJECTED
)

// ── Change log (device → server) ─────────────────────────────────────────
//
// change_log.action is CHECK-constrained to CREATE/UPDATE/DELETE/LOGIN/
// LOGOUT/SYNC/RESOLVE/EXPORT (see DATABASE_DESIGN.md) — none of which really
// says "a class's window closed with no session started". Rather than widen
// that CHECK, events like that go through as action = "UPDATE" on the
// timetable/session entity, with the human-readable explanation carried in
// `description` (this DTO's field, not old_values/new_values).

data class ChangeLogEventDto(
    @SerializedName("entity_name") val entityName: String,   // "timetable_session" | "timetable"
    @SerializedName("entity_id") val entityId: String?,       // remoteTimetableId when known
    @SerializedName("action") val action: String,             // one of the CHECK values above
    @SerializedName("description") val description: String,
    @SerializedName("occurred_at") val occurredAt: String,    // ISO-8601
)

data class ChangeLogEventRequest(
    val events: List<ChangeLogEventDto>,
)

// ── Reports (server → device, read-only) ─────────────────────────────────

data class ReportSummaryDto(
    @SerializedName("timetable_id") val timetableId: String,
    @SerializedName("session_date") val sessionDate: String,
    @SerializedName("subject_name") val subjectName: String?,
    @SerializedName("batch_code") val batchCode: String?,
    @SerializedName("total_students") val totalStudents: Int,
    @SerializedName("present_students") val presentStudents: Int,
    @SerializedName("absent_students") val absentStudents: Int,
    @SerializedName("updated_at") val updatedAt: String?,
)

data class ReportsSyncResponse(
    val success: Boolean,
    val message: String?,
    val data: List<ReportSummaryDto>?,
)

// ── Attendance upload ────────────────────────────────────────────────────────

/**
 * One offline attendance record. timetableId + sessionDate are required —
 * the backend has no separate "create session" endpoint; it resolves (and
 * creates on first use) the one real attendance_session row for
 * (timetableId, sessionDate) itself. sessionId is only used locally to group
 * records before upload; the backend does NOT use it to identify the
 * session (see SyncRepository.uploadAttendance on the backend — the schema
 * enforces one session per timetable per day, so a device-generated UUID
 * can't be trusted as the canonical id).
 */
data class OfflineAttendanceDto(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("timetable_id") val timetableId: String,
    @SerializedName("session_date") val sessionDate: String, // "yyyy-MM-dd"
    @SerializedName("student_id") val studentId: String,
    @SerializedName("status") val status: String,            // "PRESENT" | "ABSENT"
    @SerializedName("attendance_mode") val attendanceMode: String = "FACE_RECOGNITION",
    // Backend column is `recognition_confidence DECIMAL(5,2) CHECK (0-100)`
    // — a percentage, NOT the 0.0-1.0 cosine similarity this app computes
    // internally. Convert at the call site (AttendanceSyncWorker) — never
    // pass a raw AttendanceEntity.confidence value straight through, or
    // it silently stores "0.94%" instead of "94%".
    @SerializedName("confidence") val confidence: Double?,
    // Backend column is `attendance_time TIMESTAMP` — must be ISO-8601, not
    // a raw epoch-millis string (see AttendanceSyncWorker.toIso8601).
    @SerializedName("timestamp") val timestamp: String,
)

data class AttendanceUploadRequest(
    val records: List<OfflineAttendanceDto>,
)

data class AttendanceUploadData(
    @SerializedName("uploadedRecords") val uploadedRecords: Int,
    @SerializedName("failedRecords") val failedRecords: Int,
    val status: String,
)

data class AttendanceUploadResponse(
    val success: Boolean,
    val message: String?,
    val data: AttendanceUploadData?,
)

// ── Status / retry ───────────────────────────────────────────────────────────

data class SyncStatusData(
    @SerializedName("deviceStatus") val deviceStatus: String,
    @SerializedName("networkStatus") val networkStatus: String,
    @SerializedName("syncStatus") val syncStatus: String,
    @SerializedName("lastSync") val lastSync: String?,
    @SerializedName("lastError") val lastError: String?,
)

data class SyncStatusResponse(
    val success: Boolean,
    val data: SyncStatusData?,
)

data class RetrySyncData(
    val retried: Boolean,
    val status: String,
    @SerializedName("updatedTimetable") val updatedTimetable: Int?,
    @SerializedName("updatedStudents") val updatedStudents: Int?,
    @SerializedName("updatedHolidays") val updatedHolidays: Int?,
    val timetable: List<SyncTimetableDto> = emptyList(),
    val students: List<SyncStudentDto> = emptyList(),
    val holidays: List<SyncHolidayDto> = emptyList(),
    @SerializedName("lastSync") val lastSync: String?,
)

data class RetrySyncResponse(
    val success: Boolean,
    val message: String?,
    val data: RetrySyncData?,
)

/** Generic response for operations that just return success and a message. */
data class SyncMessageResponse(
    val success: Boolean,
    val message: String?,
)