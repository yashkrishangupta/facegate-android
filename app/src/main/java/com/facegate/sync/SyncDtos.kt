package com.facegate.sync

/**
 * Request for full synchronization.
 */
data class FullSyncRequest(
    val deviceId: String,
    val appVersion: String
)

/**
 * Wrapper for full/incremental synchronization data.
 */
data class SyncDataDto(
    val students: List<SyncStudentDto> = emptyList(),
    val faceEmbeddings: List<SyncFaceEmbeddingDto> = emptyList(),
    val timetable: List<SyncTimetableEntryDto> = emptyList(),
    val attendanceSessions: List<SyncAttendanceSessionDto> = emptyList(),
    val holidays: List<SyncHolidayDto> = emptyList()
)

/**
 * Response for full synchronization.
 */
data class FullSyncResponse(
    val success: Boolean,
    val data: SyncDataDto?
)

/**
 * Response for incremental synchronization.
 */
data class IncrementalSyncResponse(
    val success: Boolean,
    val changes: SyncDataDto?
)

/**
 * Student data for synchronization.
 */
data class SyncStudentDto(
    val id: String,
    val rollNumber: String,
    val name: String,
    val batch: String?
)

/**
 * Face embedding data for synchronization.
 */
data class SyncFaceEmbeddingDto(
    val studentId: String,
    val embedding: List<Float>,
    val modelVersion: String
)

/**
 * Timetable entry for synchronization.
 */
data class SyncTimetableEntryDto(
    val id: String,
    val dayOfWeek: String,
    val subject: String,
    val faculty: String,
    val room: String,
    val startTime: String?,
    val endTime: String?
)

/**
 * Attendance session for synchronization.
 */
data class SyncAttendanceSessionDto(
    val sessionId: String,
    val subject: String,
    val faculty: String,
    val status: String,
    val date: String?,
    val room: String?
)

/**
 * Holiday data for synchronization.
 */
data class SyncHolidayDto(
    val holidayName: String,
    val date: String,
    val type: String
)

/**
 * Request for uploading offline attendance.
 */
data class AttendanceUploadRequest(
    val deviceId: String,
    val attendance: List<OfflineAttendanceDto>
)

/**
 * Single offline attendance record.
 */
data class OfflineAttendanceDto(
    val studentId: String,
    val attendanceSessionId: String,
    val recognitionConfidence: Double,
    val attendanceTime: String
)

/**
 * Result of attendance upload.
 */
data class AttendanceUploadResponse(
    val success: Boolean,
    val uploaded: Int,
    val failed: Int
)

/**
 * Heartbeat request from device.
 */
data class HeartbeatRequest(
    val deviceId: String,
    val batteryLevel: Int,
    val networkStatus: String,
    val storageAvailable: Long
)

/**
 * Generic response for operations that just return success and a message.
 */
data class SyncMessageResponse(
    val success: Boolean,
    val message: String?
)

/**
 * Response for synchronization status check.
 */
data class SyncStatusResponse(
    val success: Boolean,
    val status: String,
    val lastSync: String,
    val pendingUploads: Int
)

/**
 * Response for retry synchronization.
 */
data class RetrySyncResponse(
    val success: Boolean,
    val retried: Int,
    val uploaded: Int
)

/**
 * Specialized response for attendance sessions sync.
 */
data class SyncAttendanceSessionsResponse(
    val success: Boolean,
    val attendanceSessions: List<SyncAttendanceSessionDto>
)

/**
 * Specialized response for students sync.
 */
data class SyncStudentsResponse(
    val success: Boolean,
    val students: List<SyncStudentDto>
)

/**
 * Specialized response for face embeddings sync.
 */
data class SyncFaceEmbeddingsResponse(
    val success: Boolean,
    val embeddings: List<SyncFaceEmbeddingDto>
)

/**
 * Specialized response for timetable sync.
 */
data class SyncTimetableResponse(
    val success: Boolean,
    val timetable: List<SyncTimetableEntryDto>
)

/**
 * Specialized response for holidays sync.
 */
data class SyncHolidaysResponse(
    val success: Boolean,
    val holidays: List<SyncHolidayDto>
)
