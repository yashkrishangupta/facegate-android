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

    suspend fun enrollStudent(request: EnrollStudentRequest): Result<EnrollStudentResponse> {
        return safeApiCall { api.enrollStudent(request) }
    }

    suspend fun uploadEmbedding(request: EmbeddingUploadRequest): Result<EmbeddingUploadResponse> {
        return safeApiCall { api.uploadEmbedding(request) }
    }

    suspend fun uploadConflicts(request: ConflictUploadRequest): Result<ConflictUploadResponse> {
        return safeApiCall { api.uploadConflicts(request) }
    }

    suspend fun resolveConflict(conflictId: String, request: ConflictResolveRequest): Result<SyncMessageResponse> {
        return safeApiCall { api.resolveConflict(conflictId, request) }
    }

    suspend fun getReports(since: String?): Result<ReportsSyncResponse> {
        return safeApiCall { api.getReports(since) }
    }

    /**
     * Wraps an HTTP error with the server's actual response body message
     * (e.g. `{"success":false,"message":"No active batch found for
     * batch_code CS-3A"}`) instead of Retrofit's bare "HTTP 400", so
     * callers logging `e.message` (AttendanceSyncWorker's onFailure
     * blocks) show something actionable.
     */
    class SyncHttpException(
        val httpCode: Int,
        rawBody: String?,
        cause: Throwable
    ) : Exception(extractMessage(rawBody, httpCode), cause) {
        private companion object {
            fun extractMessage(rawBody: String?, httpCode: Int): String {
                if (rawBody.isNullOrBlank()) return "HTTP $httpCode"
                return try {
                    com.google.gson.JsonParser.parseString(rawBody)
                        .asJsonObject.get("message")?.asString
                        ?: "HTTP $httpCode: $rawBody"
                } catch (e: Exception) {
                    "HTTP $httpCode: $rawBody"
                }
            }
        }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: IOException) {
            Log.e(TAG, "Network error during sync", e)
            Result.failure(e)
        } catch (e: HttpException) {
            // e.message is just "HTTP 400" — the real reason lives in the
            // response body and was never being read, so every failure
            // logged identically regardless of cause. Read it once here
            // (errorBody() can only be consumed once) and surface it so
            // both Logcat and the caller's Result.failure see the actual
            // server-side message (e.g. "No active batch found for
            // batch_code ...") instead of a bare status code.
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (readError: Exception) {
                null
            }
            Log.e(TAG, "HTTP error during sync: ${e.code()} — ${errorBody ?: "(no body)"}", e)
            Result.failure(SyncHttpException(e.code(), errorBody, e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sync", e)
            Result.failure(e)
        }
    }
}