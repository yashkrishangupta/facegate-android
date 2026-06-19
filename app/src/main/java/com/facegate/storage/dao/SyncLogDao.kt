package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.SyncLogEntity

@Dao
interface SyncLogDao {

    @Insert
    suspend fun insertLog(log: SyncLogEntity)

    @Query("SELECT * FROM sync_logs")
    suspend fun getAllLogs(): List<SyncLogEntity>
}