package com.facegate.sync

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [DeviceApi]: registration, heartbeat, device lookup.
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val api: DeviceApi
) {

    private companion object {
        const val TAG = "DeviceRepository"
    }

    /**
     * Redeems an admin-issued pairing code. Called once, the first time the
     * app is paired — see PairingViewModel. Returns the device's permanent
     * device_id + device_token, which the caller must persist via
     * DeviceIdManager.saveCredentials before making any other API call.
     */
    suspend fun pairDevice(request: PairDeviceRequest): Result<PairDeviceResponse> {
        return safeApiCall { api.pairDevice(request) }
    }

    suspend fun heartbeat(request: HeartbeatRequest): Result<HeartbeatResponse> {
        return safeApiCall { api.heartbeat(request) }
    }

    suspend fun getDeviceDetails(deviceId: String): Result<DeviceDetailsResponse> {
        return safeApiCall { api.getDeviceDetails(deviceId) }
    }

    private suspend fun <T> safeApiCall(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
            Result.failure(e)
        } catch (e: HttpException) {
            Log.e(TAG, "HTTP error: ${e.code()}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            Result.failure(e)
        }
    }
}
