package com.facegate.sync

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/**
 * WorkManager worker responsible for coordinating synchronization with the backend.
 * Follows a specific sequence of operations: Heartbeat, Sync Data, Upload Attendance.
 */
@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: SyncRepository,
    private val deviceIdManager: DeviceIdManager
) : CoroutineWorker(context, workerParams) {

    private companion object {
        const val TAG = "AttendanceSyncWorker"
        
        // Input Data Keys
        const val KEY_ROOM_ID = "room_id"
        const val KEY_LAST_SYNC = "last_sync"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting synchronization coordination...")

        val deviceId = deviceIdManager.getDeviceId()
        val roomId = inputData.getString(KEY_ROOM_ID) ?: ""
        val lastSync = inputData.getString(KEY_LAST_SYNC) ?: ""

        try {
            // 1. Heartbeat
            repository.heartbeat(createHeartbeatRequest(deviceId)).getOrRetry()

            // 2. Incremental Sync
            repository.incrementalSync(lastSync).getOrRetry()

            // 3. Sync Students
            repository.syncStudents().getOrRetry()

            // 4. Sync Face Embeddings
            repository.syncFaceEmbeddings().getOrRetry()

            // 5. Sync Timetable
            repository.syncTimetable().getOrRetry()

            // 6. Sync Holidays
            repository.syncHolidays().getOrRetry()

            // 7. Sync Attendance Sessions
            repository.syncAttendanceSessions(roomId, lastSync).getOrRetry()

            // 8. Upload Offline Attendance
            repository.uploadAttendance(AttendanceUploadRequest(deviceId, emptyList())).getOrRetry()

            // 9. Retry Failed Sync
            repository.retrySync().getOrRetry()

            Log.i(TAG, "AttendanceSyncWorker executed successfully.")
            return Result.success()

        } catch (e: SyncRetryException) {
            Log.w(TAG, "Temporary failure during sync, will retry: ${e.message}")
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unrecoverable error during sync", e)
            return Result.failure()
        }
    }

    /**
     * Extension to handle kotlin.Result and throw a custom exception for retries.
     * Uses fully qualified name for Result to avoid conflict with androidx.work.Result.
     */
    private fun <T> kotlin.Result<T>.getOrRetry(): T {
        return getOrElse { throwable ->
            if (throwable is IOException || throwable is HttpException) {
                throw SyncRetryException(throwable.message, throwable)
            }
            throw throwable
        }
    }

    private fun createHeartbeatRequest(deviceId: String): HeartbeatRequest {
        return HeartbeatRequest(
            deviceId = deviceId,
            batteryLevel = getBatteryLevel(),
            networkStatus = "CONNECTED", // WorkManager constraints ensure connectivity
            storageAvailable = getAvailableStorage()
        )
    }

    private fun getBatteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun getAvailableStorage(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Custom exception to signal that WorkManager should retry the task.
     */
    private class SyncRetryException(message: String?, cause: Throwable?) : Exception(message, cause)
}
