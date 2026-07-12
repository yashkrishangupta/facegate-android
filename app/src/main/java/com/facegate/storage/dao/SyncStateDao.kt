package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facegate.storage.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state ORDER BY category ASC")
    suspend fun getAll(): List<SyncStateEntity>

    @Query("SELECT * FROM sync_state ORDER BY category ASC")
    fun observeAll(): Flow<List<SyncStateEntity>>
}
