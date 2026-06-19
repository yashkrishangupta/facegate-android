package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.ConflictEntity

@Dao
interface ConflictDao {

    @Insert
    suspend fun insertConflict(conflict: ConflictEntity)

    @Query("SELECT * FROM conflict_queue WHERE resolved = 0 ORDER BY timestamp DESC")
    suspend fun getUnresolvedConflicts(): List<ConflictEntity>

    @Query("SELECT * FROM conflict_queue ORDER BY timestamp DESC")
    suspend fun getAllConflicts(): List<ConflictEntity>

    @Query("UPDATE conflict_queue SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Int)

    @Query("SELECT COUNT(*) FROM conflict_queue WHERE resolved = 0")
    suspend fun getUnresolvedCount(): Int
}