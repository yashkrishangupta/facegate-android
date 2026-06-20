package com.facegate.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity


@Database(
    entities = [
        AttendanceEntity::class,
        StudentEntity::class,
        SyncLogEntity::class,
        ConflictEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FaceGateDatabase : RoomDatabase() {
    abstract fun attendanceDao(): AttendanceDao
    abstract fun studentDao()   : StudentDao
    abstract fun syncLogDao()   : SyncLogDao
    abstract fun conflictDao()  : ConflictDao
}