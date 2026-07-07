package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timetable")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val dayOfWeek: Int, // 1=Mon 2=Tue 3=Wed 4=Thu 5=Fri
    val periodNumber: Int,
    val subject: String,
    val batch: String,
    val scheduledHour: Int,
    val scheduledMinute: Int,
    val windowMinutes: Int = 10,
    // TODO: A migration is required to add roomNumber to the database.
    val roomNumber: String? = null,
)
