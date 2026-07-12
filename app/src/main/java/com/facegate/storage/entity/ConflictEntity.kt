package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists an ambiguous attendance decision for admin review.
 *
 * Written by AttendancePipeline.handleDecision() whenever
 * AttendanceDecisionEngine returns AttendanceDecision.Ambiguous —
 * i.e. the top-1 and top-2 similarity scores are too close to call
 * safely (twin / lookalike risk).
 *
 * Fields mirror the data available in AttendanceDecision.Ambiguous
 * so nothing is lost between the pipeline and the UI.
 */
@Entity(tableName = "conflict_queue")
data class ConflictEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Top-ranked candidate */
    val topStudentId   : String,
    val topStudentName : String,
    val topScore       : Float,

    /** Second-ranked candidate (the one that was too close) */
    val secondStudentId   : String,
    val secondStudentName : String,
    val secondScore       : Float,

    /** Human-readable reason from the decision engine */
    val reason: String,

    /** Session during which the conflict occurred */
    val sessionId: String,

    /** Unix epoch ms when the conflict was detected */
    val timestamp: Long,

    /** False until an admin resolves it in ConflictQueueFragment */
    val resolved: Boolean = false,

    // ── Backend sync (conflicts now flow both ways — see AttendanceSyncWorker) ──

    /** The server's conflict_id once this row has been pushed up, else null. */
    val remoteConflictId: String? = null,

    /** True once this row's current state (creation or resolution) matches the server. */
    val synced: Boolean = false,

    /**
     * "DEVICE" (detected by this app's own decision engine) or "WEBSITE"
     * (came down from a server sync — e.g. an admin manually flagged one).
     * Only DEVICE-sourced rows are ever pushed up; WEBSITE-sourced rows are
     * mirrored down and their resolution is display-only here.
     */
    val source: String = "DEVICE",

    /**
     * Backend conflict_type — CHECK: LOW_CONFIDENCE, DUPLICATE_ATTENDANCE,
     * SYNC_FAILURE, MANUAL_REVIEW, DEVICE_ERROR, UNKNOWN_FACE. Set explicitly
     * at each ConflictEntity(...) call site in AttendancePipeline; the default
     * here is just a safe fallback.
     */
    val conflictType: String = "MANUAL_REVIEW",

    // ── Column parity with backend `conflict` ──
    /** CHECK-style severity (e.g. LOW/MEDIUM/HIGH) — was hardcoded "MEDIUM" only at push time; now a real field so it round-trips both ways. */
    val severity: String = "MEDIUM",
    /** conflict.attendance_id, when this conflict is tied to a specific attendance row rather than just a session. */
    val attendanceId: String? = null,
)