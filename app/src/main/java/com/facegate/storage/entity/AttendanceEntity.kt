package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attendance_records")
data class AttendanceEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val studentId: String,

    val timeStamp: Long,

    val synced: Boolean = false
)
