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

data class FullSyncData(
    val timetable: List<SyncTimetableDto> = emptyList(),
    val students: List<SyncStudentDto> = emptyList(),
    val holidays: List<SyncHolidayDto> = emptyList(),
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
    val timetable: List<SyncTimetableDto> = emptyList(),
    val students: List<SyncStudentDto> = emptyList(),
    val holidays: List<SyncHolidayDto> = emptyList(),
    @SerializedName("lastSync") val lastSync: String?,
)

data class IncrementalSyncResponse(
    val success: Boolean,
    val message: String?,
    val data: IncrementalSyncData?,
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
    @SerializedName("confidence") val confidence: Double?,
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