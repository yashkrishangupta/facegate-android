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
     * GET /api/v1/devices/{deviceId} — open, no auth required on the
     * backend (intentional: there's no admin login in this build at all,
     * see API_CONTRACT.md §9). Useful to refresh this device's own room
     * assignment after an admin reassigns it (Section 2 of the architecture
     * doc — reassignment doesn't require re-pairing). Never returns
     * device_token, so leaving it open doesn't expose credentials.
     */
    @GET("api/v1/devices/{deviceId}")
    suspend fun getDeviceDetails(@Path("deviceId") deviceId: String): DeviceDetailsResponse
}
