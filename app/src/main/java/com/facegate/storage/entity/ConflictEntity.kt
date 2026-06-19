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
)