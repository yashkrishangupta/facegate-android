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
     * This device's assigned room — only ever learned from the pairing
     * response (PairDeviceData.roomId, see PairingViewModel) or re-pairing.
     * There is currently no backend route a device can call to look this up
     * afterward (GET /devices/{deviceId} requires the website's JWT login,
     * not this device's x-api-key — confirmed against the real backend).
     * Not entered manually anywhere in this app.
     */
    fun getRoomId(): String? = prefs.getString(KEY_ROOM_ID, null)

    fun saveRoomId(roomId: String) {
        prefs.edit().putString(KEY_ROOM_ID, roomId).apply()
    }

    /**
     * A human-readable room label (e.g. "Room-101"), as opposed to
     * getRoomId()'s UUID. NOT currently sent by any endpoint this device
     * calls — pairDevice's response only includes room_id (see
     * DeviceController.pairDevice on the backend). Stored for forward
     * compatibility in case that response is extended to include it later;
     * until then this is always null and the device info screen falls back
     * to showing the room ID.
     */
    fun getRoomNumber(): String? = prefs.getString(KEY_ROOM_NUMBER, null)

    fun saveRoomNumber(roomNumber: String?) {
        prefs.edit().putString(KEY_ROOM_NUMBER, roomNumber).apply()
    }

    /**
     * A device paired under an app version from before KEY_PAIRED_AT was
     * introduced has isPaired() == true but never had this key written —
     * unlike Room, SharedPreferences has no migration mechanism to backfill
     * it, so it reads 0 (-> "Unknown" in the UI) forever. Backfilling to
     * "now" the first time it's read post-upgrade is the only way to stop
     * showing "Unknown" indefinitely; the exact original pairing date is
     * unrecoverable since it was simply never recorded, not a data-loss bug.
     */
    fun getPairedAt(): Long {
        val stored = prefs.getLong(KEY_PAIRED_AT, 0L)
        if (stored > 0L) return stored
        if (isPaired()) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_PAIRED_AT, now).apply()
            return now
        }
        return 0L
    }

    fun saveCredentials(deviceId: String, deviceToken: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
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
        private const val KEY_ROOM_NUMBER = "room_number"
        private const val KEY_PAIRED_AT = "paired_at"
    }
}