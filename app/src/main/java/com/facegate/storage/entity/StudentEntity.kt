package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey
    val studentId: String,
    val name: String,
    val studentClass: String = "",
    val embedding: String? = null,
    val enrollmentStatus: String = "PENDING",
    val embeddingSynced: Boolean = false,
    // Added for backend sync (server student payload — see SyncDtos.kt).
    // studentId above now holds the server's student_id (UUID) once synced;
    // rollNumber is kept separately since it's what's shown in the UI and
    // used to key manual/admin lookups.
    val rollNumber: String = "",
    val batchCode: String? = null,
)