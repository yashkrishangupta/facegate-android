package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timetable_overrides")
data class OverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId    : String,
    val fieldChanged : String,   // "subject"|"batch"|"windowMinutes"|"extra period"
    val oldValue     : String,
    val newValue     : String,
    val changedAt    : Long,
    val reason       : String = "",
)