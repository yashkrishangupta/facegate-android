package com.facegate.storage.dao

import androidx.room.*
import com.facegate.storage.entity.OverrideEntity

@Dao
interface OverrideDao {
    @Insert
    suspend fun insert(override: OverrideEntity)
    @Query("SELECT * FROM timetable_overrides WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: String): List<OverrideEntity>
    @Query("SELECT * FROM timetable_overrides ORDER BY changedAt DESC")
    suspend fun getAll(): List<OverrideEntity>

    @Query("SELECT * FROM timetable_overrides WHERE pushedToChangeLog = 0 ORDER BY changedAt ASC")
    suspend fun getUnpushed(): List<OverrideEntity>

    @Query("UPDATE timetable_overrides SET pushedToChangeLog = 1 WHERE id = :id")
    suspend fun markPushed(id: Int)
}
