package com.facegate.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the unique identifier for this device.
 *
 * This manager generates a UUID on the first launch and persists it locally.
 * The same ID is returned on every future launch unless explicitly cleared.
 */
@Singleton
class DeviceIdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the unique device ID.
     * Generates and persists a new UUID if one does not already exist.
     */
    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }
    }

    /**
     * Clears the stored device ID.
     * A new ID will be generated upon the next call to [getDeviceId].
     */
    fun clearDeviceId() {
        prefs.edit().remove(KEY_DEVICE_ID).apply()
    }

    companion object {
        private const val PREFS_NAME = "device_prefs"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
