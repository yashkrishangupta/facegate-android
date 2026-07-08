package com.facegate.sync

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository responsible for network synchronization operations.
 * This acts as a thin wrapper around [SyncApi] with error handling.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val api: SyncApi
) {

    private companion object {
        const val TAG = "SyncRepository"
    }

    /**
     * 13.1 Full Synchronization - Downloads all required data for a newly registered device.
     */
    suspend fun fullSync(request: FullSyncRequest): Result<FullSyncResponse> {
        return safeApiCall { api.fullSync(request) }
    }

    /**
     * 13.2 Incremental Synchronization - Returns only records modified after the previous synchronization.
     */
    suspend fun incrementalSync(lastSync: String): Result<IncrementalSyncResponse> {
        return safeApiCall { api.incrementalSync(lastSync) }
    }

    /**
     * 13.3 Upload Offline Attendance - Uploads attendance records collected while offline.
     */
    suspend fun uploadAttendance(request: AttendanceUploadRequest): Result<AttendanceUploadResponse> {
        return safeApiCall { api.uploadAttendance(request) }
    }

    /**
     * 13.4 Synchronize Attendance Sessions - Downloads active attendance sessions assigned to the device.
     */
    suspend fun syncAttendanceSessions(roomId: String, lastSync: String? = null): Result<SyncAttendanceSessionsResponse> {
        return safeApiCall { api.syncAttendanceSessions(roomId, lastSync) }
    }

    /**
     * 13.5 Synchronize Students - Downloads students assigned to the device's batches.
     */
    suspend fun syncStudents(): Result<SyncStudentsResponse> {
        return safeApiCall { api.syncStudents() }
    }

    /**
     * 13.6 Synchronize Face Embeddings - Downloads updated face embeddings.
     */
    suspend fun syncFaceEmbeddings(): Result<SyncFaceEmbeddingsResponse> {
        return safeApiCall { api.syncFaceEmbeddings() }
    }

    /**
     * 13.7 Synchronize Timetable - Downloads timetable updates.
     */
    suspend fun syncTimetable(): Result<SyncTimetableResponse> {
        return safeApiCall { api.syncTimetable() }
    }

    /**
     * 13.8 Synchronize Holidays - Downloads updated holidays.
     */
    suspend fun syncHolidays(): Result<SyncHolidaysResponse> {
        return safeApiCall { api.syncHolidays() }
    }

    /**
     * 13.9 Heartbeat - Android periodically reports its health to the backend.
     */
    suspend fun heartbeat(request: HeartbeatRequest): Result<SyncMessageResponse> {
        return safeApiCall { api.heartbeat(request) }
    }

    /**
     * 13.10 Synchronization Status - Returns synchronization status of the requesting device.
     */
    suspend fun getSyncStatus(): Result<SyncStatusResponse> {
        return safeApiCall { api.getSyncStatus() }
    }

    /**
     * 13.11 Retry Failed Synchronization - Retries uploading failed attendance records.
     */
    suspend fun retrySync(): Result<RetrySyncResponse> {
        return safeApiCall { api.retrySync() }
    }

    /**
     * Helper function to execute API calls safely and wrap them in [Result].
     */
    private suspend fun <T> safeApiCall(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: IOException) {
            Log.e(TAG, "Network error during sync", e)
            Result.failure(e)
        } catch (e: HttpException) {
            Log.e(TAG, "HTTP error during sync: ${e.code()}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sync", e)
            Result.failure(e)
        }
    }
}