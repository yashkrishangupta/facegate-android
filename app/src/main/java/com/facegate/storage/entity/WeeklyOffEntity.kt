package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weekly_off")
data class WeeklyOffEntity(
    @PrimaryKey val dayOfWeek : Int,   // 1=Mon 2=Tue 3=Wed 4=Thu 5=Fri 6=Sat 7=Sun
    val createdAt             : Long,
)