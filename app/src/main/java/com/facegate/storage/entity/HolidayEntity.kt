package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "holidays")
data class HolidayEntity(
    @PrimaryKey val date : String,   // "yyyy-MM-dd"
    val name             : String,
    val createdAt        : Long,
    // ── Column parity with backend `holiday` — SyncHolidayDto already
    // carries these; mergeHoliday previously dropped them on the floor.
    // date stays the PK (one holiday per day is the only thing this app's
    // UI/queries actually need — see isHoliday/getUpcoming), but the
    // remote id is now kept too, e.g. for a future "holiday removed"
    // reconciliation pass.
    val remoteHolidayId  : String? = null,
    val holidayType      : String? = null,
    val isRecurring       : Boolean = false,
    val serverUpdatedAt   : String? = null,
)