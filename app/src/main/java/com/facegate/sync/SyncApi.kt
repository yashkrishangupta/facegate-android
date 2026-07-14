package com.facegate.sync

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Sync-data pull/push calls. All device-authenticated (x-api-key via
 * DeviceAuthInterceptor).
 *
 * The backend does NOT have separate /sync/students, /sync/timetable, or
 * /sync/holidays endpoints — timetable + students + holidays (and now
 * embeddings + attendanceUpdates, see FullSyncData/IncrementalSyncData)
 * all come back together in one call. Face-embedding sync now exists both
 * ways: down via the fields above, up via uploadEmbedding below.
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

    // ── Not on the backend yet — see API_CONTRACT.md Part 3 ─────────────────

    /** POST /api/v1/sync/students/enroll — device-initiated new student + embedding. */
    @POST("api/v1/sync/students/enroll")
    suspend fun enrollStudent(@Body request: EnrollStudentRequest): EnrollStudentResponse

    /** POST /api/v1/sync/embeddings — real, backend-verified endpoint (see SyncEmbeddingDto's doc comment). One embedding per call, upserts on student_id. */
    @POST("api/v1/sync/embeddings")
    suspend fun uploadEmbedding(@Body request: EmbeddingUploadRequest): EmbeddingUploadResponse

    /** POST /api/v1/sync/conflicts — push device-detected conflicts up. */
    @POST("api/v1/sync/conflicts")
    suspend fun uploadConflicts(@Body request: ConflictUploadRequest): ConflictUploadResponse

    /** PUT /api/v1/sync/conflicts/{conflictId}/resolve — mirrors the website's /conflicts/:id/resolve, device-authed. */
    @PUT("api/v1/sync/conflicts/{conflictId}/resolve")
    suspend fun resolveConflict(
        @Path("conflictId") conflictId: String,
        @Body request: ConflictResolveRequest,
    ): SyncMessageResponse

    /** GET /api/v1/sync/reports?since=... — read-only, room-scoped report summaries. */
    @GET("api/v1/sync/reports")
    suspend fun getReports(@Query("since") since: String? = null): ReportsSyncResponse
}
