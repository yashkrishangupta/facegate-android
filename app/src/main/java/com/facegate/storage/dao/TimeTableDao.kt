package com.facegate.storage.dao

import androidx.room.*
import com.facegate.storage.entity.TimetableEntity

@Dao
interface TimetableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TimetableEntity)
    @Update
    suspend fun update(entry: TimetableEntity)
    @Query("DELETE FROM timetable WHERE id = :id")
    suspend fun delete(id: Int)
    @Query("SELECT * FROM timetable WHERE dayOfWeek = :dayOfWeek ORDER BY periodNumber ASC")
    suspend fun getForDay(dayOfWeek: Int): List<TimetableEntity>
    @Query("SELECT * FROM timetable ORDER BY dayOfWeek ASC, periodNumber ASC")
    suspend fun getAll(): List<TimetableEntity>
    @Query("SELECT DISTINCT batch FROM timetable ORDER BY batch ASC")
    suspend fun getAllBatches(): List<String>
    @Query("SELECT DISTINCT subject FROM timetable ORDER BY subject ASC")
    suspend fun getAllSubjects(): List<String>
    // Added for backend sync — lets upsertSyncedTimetable() find the local
    // row for a given server timetable_id, so repeated syncs update the
    // existing row instead of inserting a new one every cycle.
    @Query("SELECT * FROM timetable WHERE remoteTimetableId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: String): TimetableEntity?
}
