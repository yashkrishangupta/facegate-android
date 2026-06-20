package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.AttendanceEntity

/** Result class for class-wise attendance JOIN query */
data class ClassAttendanceSummary(
    val studentClass : String,
    val presentCount : Int,
)

@Dao
interface AttendanceDao {

    @Insert
    suspend fun insertAttendance(attendance: AttendanceEntity)

    @Query("SELECT * FROM attendance_records WHERE synced = 0")
    suspend fun getUnsyncedRecords(): List<AttendanceEntity>

    @Query("UPDATE attendance_records SET synced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Int)

    @Query("SELECT * FROM attendance_records WHERE timeStamp >= :startOfDay")
    suspend fun getTodayAttendance(startOfDay: Long): List<AttendanceEntity>

    @Query("SELECT * FROM attendance_records")
    suspend fun getAllAttendance(): List<AttendanceEntity>

    @Query("""
        SELECT s.studentClass, COUNT(a.id) AS presentCount
        FROM attendance_records a
        INNER JOIN students s ON a.studentId = s.studentId
        WHERE a.timeStamp >= :startOfDay
        GROUP BY s.studentClass
        ORDER BY s.studentClass ASC
    """)
    suspend fun getClassWiseAttendance(startOfDay: Long): List<ClassAttendanceSummary>

    @Query("""
        SELECT COUNT(*) FROM attendance_records
        WHERE studentId = :studentId AND timeStamp >= :startOfDay
    """)
    suspend fun isStudentMarkedToday(studentId: String, startOfDay: Long): Int

    @Query("""
        DELETE FROM attendance_records
        WHERE studentId = :studentId AND timeStamp >= :startOfDay
    """)
    suspend fun deleteAttendanceToday(studentId: String, startOfDay: Long)

    @Query("UPDATE attendance_records SET studentId = :newId WHERE studentId = :oldId")
    suspend fun renameStudentId(oldId: String, newId: String)
}