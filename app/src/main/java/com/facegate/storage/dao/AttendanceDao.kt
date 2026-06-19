package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.AttendanceEntity

@Dao
interface AttendanceDao {

    @Insert
    suspend fun insertAttendance(attendance: AttendanceEntity)

    @Query("SELECT * FROM attendance_records WHERE synced = 0")
    suspend fun getUnsyncedRecords(): List<AttendanceEntity>

    @Query("UPDATE attendance_records SET synced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Int)
}