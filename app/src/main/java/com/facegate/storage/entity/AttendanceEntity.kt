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
)
