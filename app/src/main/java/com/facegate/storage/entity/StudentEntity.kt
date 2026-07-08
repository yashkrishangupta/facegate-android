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
)
