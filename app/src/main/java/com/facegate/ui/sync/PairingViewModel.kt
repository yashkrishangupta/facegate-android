package com.facegate.ui.sync

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.sync.AttendanceSyncWorker
import com.facegate.sync.DeviceIdManager
import com.facegate.sync.PairDeviceRequest
import com.facegate.sync.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class PairingState {
    object Idle    : PairingState()
    object Loading : PairingState()
    object Success : PairingState()
    data class Failed(val reason: String) : PairingState()
}

/**
 * Pairing is admin-initiated (restores the flow the architecture doc
 * originally specified in Section 2). An admin creates the device record
 * on the website — choosing the room, assigning a device name — and hands
 * the installer a 6-digit pairing code, valid 15 minutes. This screen's
 * only job is to redeem that code; the app has no say over which room it
 * ends up in.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val deviceIdManager: DeviceIdManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private companion object {
        // TODO: switch to BuildConfig.VERSION_NAME once buildFeatures.buildConfig = true
        // is enabled in build.gradle.kts.
        const val APP_VERSION = "1.0.0"
    }

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState

    val currentDeviceId: String?
        get() = deviceIdManager.getDeviceId()

    fun pair(pairingCode: String) {
        val code = pairingCode.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            _pairingState.value = PairingState.Failed("Enter the 6-digit pairing code from the admin dashboard")
            return
        }

        _pairingState.value = PairingState.Loading

        viewModelScope.launch {

            val pairResult = deviceRepository.pairDevice(
                PairDeviceRequest(
                    pairingCode = code,
                    // A stable-ish local identifier for this physical device,
                    // for audit purposes on the backend — not used for auth
                    // (device_token, returned below, is what's used for that).
                    deviceIdentifier = "${Build.MANUFACTURER}-${Build.MODEL}-${UUID.randomUUID()}",
                    appVersion = APP_VERSION,
                    operatingSystem = "Android ${Build.VERSION.RELEASE}",
                )
            )

            val device = pairResult.getOrNull()?.data

            if (pairResult.isFailure || device == null) {
                _pairingState.value = PairingState.Failed(
                    pairResult.exceptionOrNull()?.message
                        ?: "Pairing failed — check the code and try again (it expires after 15 minutes)"
                )
                return@launch
            }

            deviceIdManager.saveCredentials(device.deviceId, device.deviceToken)
            deviceIdManager.saveRoomId(device.roomId)

            // First sync happens right away so the device has its timetable/
            // students/holidays before anyone tries to take attendance.
            AttendanceSyncWorker.Scheduler.schedulePeriodicSync(appContext, device.roomId)

            _pairingState.value = PairingState.Success
        }
    }

    fun resetState() {
        _pairingState.value = PairingState.Idle
    }
}
