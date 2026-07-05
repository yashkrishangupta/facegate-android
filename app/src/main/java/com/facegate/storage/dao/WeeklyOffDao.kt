package com.facegate.storage.dao

import androidx.room.*
import com.facegate.storage.entity.WeeklyOffEntity

@Dao
interface WeeklyOffDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WeeklyOffEntity)
    @Query("DELETE FROM weekly_off WHERE dayOfWeek = :dayOfWeek")
    suspend fun delete(dayOfWeek: Int)
    @Query("SELECT COUNT(*) FROM weekly_off WHERE dayOfWeek = :dayOfWeek")
    suspend fun isOff(dayOfWeek: Int): Int
    @Query("SELECT * FROM weekly_off ORDER BY dayOfWeek ASC")
    suspend fun getAll(): List<WeeklyOffEntity>
}