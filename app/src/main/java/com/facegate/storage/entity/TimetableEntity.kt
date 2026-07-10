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
    // Added for backend sync. The backend's timetable_id is a UUID, which
    // can't be this entity's @PrimaryKey (existing code depends on `id`
    // being a local Int — see SessionEntity.timetableId, TimetableDao).
    // remoteTimetableId is what attendance-sync payloads actually send as
    // timetable_id; it's null for periods created only on-device that
    // haven't matched a synced server row yet.
    val remoteTimetableId: String? = null,
    val subjectName: String? = null,
    val facultyName: String? = null,
)
