package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId    : String,   // UUID
    val timetableId  : Int?      = null,     // null = extra period (not from timetable)
    val subject      : String,
    val batch        : String,
    val startTime    : Long,
    val windowMinutes: Int,
    val endedAt      : Long?     = null,     // null = still active
)