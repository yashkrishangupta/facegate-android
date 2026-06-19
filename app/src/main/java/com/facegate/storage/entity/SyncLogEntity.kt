package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_logs")
data class SyncLogEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val message: String,

    val timeStamp: Long
)