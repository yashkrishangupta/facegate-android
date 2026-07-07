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
    // TODO: A migration is required to add enrollmentStatus to the database.
    val enrollmentStatus: String = "PENDING",
)
