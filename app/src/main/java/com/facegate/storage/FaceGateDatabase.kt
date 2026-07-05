package com.facegate.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.facegate.storage.dao.AttendanceDao
import com.facegate.storage.dao.ConflictDao
import com.facegate.storage.dao.HolidayDao
import com.facegate.storage.dao.OverrideDao
import com.facegate.storage.dao.SessionDao
import com.facegate.storage.dao.StudentDao
import com.facegate.storage.dao.SyncLogDao
import com.facegate.storage.dao.TimetableDao
import com.facegate.storage.dao.WeeklyOffDao
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.HolidayEntity
import com.facegate.storage.entity.OverrideEntity
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.SyncLogEntity
import com.facegate.storage.entity.TimetableEntity
import com.facegate.storage.entity.WeeklyOffEntity

@Database(
    entities = [
        AttendanceEntity::class,
        StudentEntity::class,
        SyncLogEntity::class,
        ConflictEntity::class,
        TimetableEntity::class,
        SessionEntity::class,
        OverrideEntity::class,
        HolidayEntity::class,
        WeeklyOffEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FaceGateDatabase : RoomDatabase() {
    abstract fun attendanceDao() : AttendanceDao
    abstract fun studentDao()    : StudentDao
    abstract fun syncLogDao()    : SyncLogDao
    abstract fun conflictDao()   : ConflictDao
    abstract fun timetableDao()  : TimetableDao
    abstract fun sessionDao()    : SessionDao
    abstract fun overrideDao()   : OverrideDao
    abstract fun holidayDao()    : HolidayDao
    abstract fun weeklyOffDao()  : WeeklyOffDao
}