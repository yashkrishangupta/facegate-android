package com.facegate.sync

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit interface for synchronization APIs based on API_CONTRACT.md Section 13.
 */
interface SyncApi {

    /**
     * 13.1 Full Synchronization - Downloads all required data for a newly registered device.
     */
    @POST("api/v1/sync/full")
    suspend fun fullSync(
        @Body request: FullSyncRequest
    ): FullSyncResponse

    /**
     * 13.2 Incremental Synchronization - Returns only records modified after the previous synchronization.
     */
    @GET("api/v1/sync/incremental")
    suspend fun incrementalSync(
        @Query("lastSync") lastSync: String
    ): IncrementalSyncResponse

    /**
     * 13.3 Upload Offline Attendance - Uploads attendance records collected while offline.
     */
    @POST("api/v1/sync/attendance")
    suspend fun uploadAttendance(
        @Body request: AttendanceUploadRequest
    ): AttendanceUploadResponse

    /**
     * 13.4 Synchronize Attendance Sessions - Downloads active attendance sessions assigned to the device.
     */
    @GET("api/v1/sync/attendance-sessions")
    suspend fun syncAttendanceSessions(
        @Query("roomId") roomId: String,
        @Query("lastSync") lastSync: String? = null
    ): SyncAttendanceSessionsResponse

    /**
     * 13.5 Synchronize Students - Downloads students assigned to the device's batches.
     */
    @GET("api/v1/sync/students")
    suspend fun syncStudents(): SyncStudentsResponse

    /**
     * 13.6 Synchronize Face Embeddings - Downloads updated face embeddings.
     */
    @GET("api/v1/sync/face-embeddings")
    suspend fun syncFaceEmbeddings(): SyncFaceEmbeddingsResponse

    /**
     * 13.7 Synchronize Timetable - Downloads timetable updates.
     */
    @GET("api/v1/sync/timetable")
    suspend fun syncTimetable(): SyncTimetableResponse

    /**
     * 13.8 Synchronize Holidays - Downloads updated holidays.
     */
    @GET("api/v1/sync/holidays")
    suspend fun syncHolidays(): SyncHolidaysResponse

    /**
     * 13.9 Heartbeat - Android periodically reports its health to the backend.
     */
    @POST("api/v1/sync/heartbeat")
    suspend fun heartbeat(
        @Body request: HeartbeatRequest
    ): SyncMessageResponse

    /**
     * 13.10 Synchronization Status - Returns synchronization status of the requesting device.
     */
    @GET("api/v1/sync/status")
    suspend fun getSyncStatus(): SyncStatusResponse

    /**
     * 13.11 Retry Failed Synchronization - Retries uploading failed attendance records.
     */
    @POST("api/v1/sync/retry")
    suspend fun retrySync(): RetrySyncResponse
}
