package com.facegate.sync

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Device identity calls: pairing, heartbeating, and looking up this
 * device's own row. Sync-data pulls/pushes live in SyncApi.
 */
interface DeviceApi {

    /**
     * POST /api/v1/devices/pair — no auth header needed (this is the call
     * that OBTAINS the device_token used by every other request). Requires
     * an admin to have already created the device record on the website
     * and handed over the resulting 6-digit pairing code.
     */
    @POST("api/v1/devices/pair")
    suspend fun pairDevice(@Body request: PairDeviceRequest): PairDeviceResponse

    /**
     * POST /api/v1/devices/heartbeat — x-api-key protected (DeviceAuthInterceptor
     * attaches the token automatically). Lives under /devices, not /sync,
     * despite being sync-adjacent.
     */
    @POST("api/v1/devices/heartbeat")
    suspend fun heartbeat(@Body request: HeartbeatRequest): HeartbeatResponse

    /**
     * GET /api/v1/devices/{deviceId} — documented in two places (API_CONTRACT.md
     * §9, and FaceGate_Feature_API_Documentation.docx §3.2 Step 4) as public/
     * no-auth, specifically for a device to self-check its room assignment.
     * **Confirmed against the actual backend route (devices.ts) that this is
     * false** — the real route has `requireAuth` (website JWT session) on it,
     * so a device's x-api-key always gets 401 here. AttendanceSyncWorker no
     * longer calls this for exactly that reason (see git history — this was
     * wired up, then removed once the 401s were traced to this route rather
     * than a token problem). Left defined, unused, in case a backend fix ever
     * makes the documented behavior real; don't wire it back in without
     * re-confirming against the actual route middleware first.
     */
    @GET("api/v1/devices/{deviceId}")
    suspend fun getDeviceDetails(@Path("deviceId") deviceId: String): DeviceDetailsResponse

    /**
     * POST /api/v1/devices/change-log — device-authed equivalent of the
     * website's ADMIN+/JWT-only GET /change-log (that one's read-only anyway).
     * Not on the backend yet — see API_CONTRACT.md Part 3. Deliberately its
     * own device-scoped endpoint rather than reusing /change-log, per this
     * doc's own rule: "two separate credential systems — don't conflate them".
     */
    @POST("api/v1/devices/change-log")
    suspend fun pushChangeLogEvents(@Body request: ChangeLogEventRequest): SyncMessageResponse
}
