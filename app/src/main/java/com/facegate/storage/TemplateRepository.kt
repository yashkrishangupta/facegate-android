package com.facegate.storage

import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity

class TemplateRepository(
    private val studentDao: StudentDao,
    private val attendanceDao: AttendanceDao,
    private val syncLogDao: SyncLogDao
) {

    // Student
    suspend fun addStudent(student: StudentEntity) {
        studentDao.insertStudent(student)
    }

    suspend fun getStudents(): List<StudentEntity> {
        return studentDao.getAllStudents()
    }


    // Attendance
    suspend fun addAttendance(record: AttendanceEntity) {
        attendanceDao.insertAttendance(record)
    }

    suspend fun getUnsyncedAttendance(): List<AttendanceEntity> {
        return attendanceDao.getUnsyncedRecords()
    }

    suspend fun markAttendanceSynced(id: Int) {
        attendanceDao.markAsSynced(id)
    }


    // Sync Logs
    suspend fun addSyncLog(log: SyncLogEntity) {
        syncLogDao.insertLog(log)
    }

    suspend fun getSyncLogs(): List<SyncLogEntity> {
        return syncLogDao.getAllLogs()
    }
}