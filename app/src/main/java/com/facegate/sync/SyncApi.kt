package com.facegate.sync

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Sync-data pull/push calls. All device-authenticated (x-api-key via
 * DeviceAuthInterceptor).
 *
 * The backend does NOT have separate /sync/students, /sync/timetable,
 * /sync/holidays, /sync/face-embeddings, or /sync/attendance-sessions
 * endpoints — timetable + students + holidays all come back together in one
 * call. Face-embedding sync isn't implemented on the backend yet at all
 * (see the updated API_CONTRACT.md, "Known gaps").
 */
interface SyncApi {

    /** POST /api/v1/sync/full — first sync after pairing (no `since`). */
    @POST("api/v1/sync/full")
    suspend fun fullSync(): FullSyncResponse

    /** GET /api/v1/sync/incremental?since=... — every sync after the first. */
    @GET("api/v1/sync/incremental")
    suspend fun incrementalSync(@Query("since") since: String? = null): IncrementalSyncResponse

    /**
     * POST /api/v1/sync/attendance — batched, idempotent. Safe to retry;
     * the backend upserts on (session, student), and resolves/creates the
     * actual attendance_session server-side from timetableId + sessionDate
     * on each record (see OfflineAttendanceDto).
     */
    @POST("api/v1/sync/attendance")
    suspend fun uploadAttendance(@Body request: AttendanceUploadRequest): AttendanceUploadResponse

    /** GET /api/v1/sync/status */
    @GET("api/v1/sync/status")
    suspend fun getSyncStatus(): SyncStatusResponse

    /** POST /api/v1/sync/retry — re-runs an incremental sync server-side. */
    @POST("api/v1/sync/retry")
    suspend fun retrySync(): RetrySyncResponse
}
