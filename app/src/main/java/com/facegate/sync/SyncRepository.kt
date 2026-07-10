package com.facegate.sync

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository responsible for network synchronization operations.
 * Thin wrapper around [SyncApi] with error handling.
 *
 * Collapsed from 11 wrapper methods down to the 5 endpoints that actually
 * exist on the backend — timetable/students/holidays are not separate
 * calls, they all come back together from fullSync/incrementalSync.
 * Face-embedding sync has no backend support yet (tracked in the updated
 * API_CONTRACT.md under "Known gaps") so there's no wrapper for it here;
 * add one once that endpoint exists.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val api: SyncApi
) {

    private companion object {
        const val TAG = "SyncRepository"
    }

    /** First sync after pairing — no `since` cursor yet. */
    suspend fun fullSync(): Result<FullSyncResponse> {
        return safeApiCall { api.fullSync() }
    }

    /** Every sync after the first, using the timestamp from the previous one. */
    suspend fun incrementalSync(since: String?): Result<IncrementalSyncResponse> {
        return safeApiCall { api.incrementalSync(since) }
    }

    suspend fun uploadAttendance(request: AttendanceUploadRequest): Result<AttendanceUploadResponse> {
        return safeApiCall { api.uploadAttendance(request) }
    }

    suspend fun getSyncStatus(): Result<SyncStatusResponse> {
        return safeApiCall { api.getSyncStatus() }
    }

    suspend fun retrySync(): Result<RetrySyncResponse> {
        return safeApiCall { api.retrySync() }
    }

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
