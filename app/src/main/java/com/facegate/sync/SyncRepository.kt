package com.facegate.sync

import com.facegate.storage.FaceGateDatabase
import com.facegate.storage.entity.SyncLogEntity

class SyncRepository(
    private val database: FaceGateDatabase
) {

    suspend fun syncData() {

        val attendanceDao = database.attendanceDao()
        val studentDao = database.studentDao()
        val syncLogDao = database.syncLogDao()

        // TODO:
        // 1. Get offline attendance records
        // 2. Get student data
        // 3. Send data to server/API
        // 4. Mark records as synced

        val log = SyncLogEntity(
            id = 0,
            message = "Sync completed successfully",
            timeStamp = System.currentTimeMillis()
        )

        syncLogDao.insertLog(log)
    }
}