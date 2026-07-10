package com.facegate.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages this device's identity as assigned by the FaceGate backend.
 */
@Singleton
class DeviceIdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun isPaired(): Boolean = !getDeviceId().isNullOrBlank() && !getDeviceToken().isNullOrBlank()

    /**
     * This device's assigned room, looked up from the backend (GET
     * /api/v1/devices/{deviceId}) right after pairing succeeds — see
     * PairingViewModel. Not entered manually.
     */
    fun getRoomId(): String? = prefs.getString(KEY_ROOM_ID, null)

    fun saveRoomId(roomId: String) {
        prefs.edit().putString(KEY_ROOM_ID, roomId).apply()
    }

    fun saveCredentials(deviceId: String, deviceToken: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "device_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_ROOM_ID = "room_id"
    }
}