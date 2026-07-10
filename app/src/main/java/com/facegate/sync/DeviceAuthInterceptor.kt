package com.facegate.sync

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches this device's Device Token to every outgoing request.
 *
 * The backend's deviceAuth middleware (api/src/middleware/deviceAuth.ts)
 * checks the "x-api-key" header against device.device_token in Postgres —
 * NOT "Authorization: Bearer", which is what API_CONTRACT.md originally
 * (incorrectly) implied. Confirmed against the running backend directly.
 */

class DeviceAuthInterceptor @Inject constructor(
    private val deviceIdManager: DeviceIdManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = deviceIdManager.getDeviceToken()

        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .addHeader("x-api-key", token)
                .build()
        } else {
            original
        }

        return chain.proceed(request)
    }
}