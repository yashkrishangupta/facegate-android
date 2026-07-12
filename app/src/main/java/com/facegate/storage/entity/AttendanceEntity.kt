package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attendance_records")
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val studentId : String,
    val sessionId : String? = null,
    val timeStamp : Long,
    val synced    : Boolean = false,
    // Added for backend sync — previously the sync worker always sent a
    // hardcoded PRESENT/0.0 because nothing here recorded the real values.
    val attendanceStatus: String = "PRESENT",
    val confidence: Double? = null,
    val deviceId: String? = null,
    // Backend attendance_mode — "FACE_RECOGNITION" (AttendancePipeline) or
    // "MANUAL" (ManualAttendanceViewModel). Previously every uploaded record
    // was hardcoded FACE_RECOGNITION regardless of source — see
    // OfflineAttendanceDto / AttendanceSyncWorker.
    val attendanceMode: String = "FACE_RECOGNITION",
    // Server's attendance_id, once known (set after a successful upload or
    // after this row was created by merging an attendance-down sync). Needed
    // to correlate a later edit/removal with the right server row instead of
    // relying on (session, student) alone once website-side edits are in play.
    val remoteAttendanceId: String? = null,
    // Server's attendance.updated_at (epoch ms), used for the "most recent
    // wins" merge described in plan.md §6.2. Null for rows that only exist
    // locally so far.
    val serverUpdatedAt: Long? = null,
    // ── Column parity with backend `attendance` ──
    /** verification_status — nullable free-form on the backend; not written to by this app, only mirrored down. */
    val verificationStatus: String? = null,
    /** attendance.synced_at — when the server actually 200'd this row, distinct from the local `synced` flag which just means "don't re-push". Null until a successful upload. */
    val syncedAt: Long? = null,
)
