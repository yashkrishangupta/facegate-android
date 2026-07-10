package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId    : String,   // UUID, local-only bookkeeping —
                                              // the backend resolves its own
                                              // canonical session id from
                                              // (remoteTimetableId, sessionDate);
                                              // see SyncRepository.uploadAttendance
                                              // on the backend.
    val timetableId  : Int?      = null,     // null = extra period (not from timetable)
    val subject      : String,
    val batch        : String,
    val startTime    : Long,
    val windowMinutes: Int,
    val endedAt      : Long?     = null,     // null = still active
    // Added for backend sync — required on every session whose attendance
    // will be pushed. Populate both from the matched TimetableEntity
    // (remoteTimetableId) and today's date when a session starts; a session
    // missing either is skipped by AttendanceSyncWorker (logged, not synced)
    // rather than sent as attendance the backend can't attach to a class.
    val remoteTimetableId: String? = null,
    val sessionDate: String? = null,         // "yyyy-MM-dd"
)