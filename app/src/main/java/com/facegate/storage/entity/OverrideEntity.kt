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
    // Overrides are this app's local "change log" — plan.md §"change log
    // should go to website" means pushing these up via
    // POST /api/v1/devices/change-log. False until that push succeeds.
    val pushedToChangeLog: Boolean = false,
)