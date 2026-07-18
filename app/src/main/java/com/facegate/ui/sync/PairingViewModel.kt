package com.facegate.ui.sync

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.facegate.sync.AttendanceSyncWorker
import com.facegate.sync.DeviceIdManager
import com.facegate.sync.PairDeviceRequest
import com.facegate.sync.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

sealed class PairingState {
    object Idle    : PairingState()
    object Loading : PairingState()
    /**
     * Credentials are saved and the pairing call itself succeeded — now
     * waiting on one real sync so the very next screen (role selector,
     * gated Admin Mode included) has synced auth_users/timetable/etc. to
     * work with, instead of an empty local DB that would fail every login
     * with "not synced yet". See AuthGate.NotSyncedYet.
     */
    object Syncing : PairingState()
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

        // How long to wait for the post-pairing sync before giving up on
        // waiting and letting the person through anyway. The periodic sync
        // job (already scheduled by this point) will keep retrying — this
        // timeout just protects against a slow/flaky network turning
        // pairing into an indefinite spinner.
        const val INITIAL_SYNC_TIMEOUT_MS = 30_000L
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
            deviceIdManager.saveRoomNumber(device.roomNumber)

            // Recurring hourly sync from here on.
            AttendanceSyncWorker.Scheduler.schedulePeriodicSync(appContext, device.roomId)

            // ...but also force + wait for one sync right now, rather than
            // trusting the periodic job's own timing. Without this, a
            // freshly-paired device would drop the person on the role
            // selector with zero synced admin/faculty accounts, and Admin
            // Mode's login gate would fail every attempt until the next
            // periodic run happened to fire.
            _pairingState.value = PairingState.Syncing
            val syncRequestId = AttendanceSyncWorker.Scheduler.runOnce(appContext, device.roomId)
            withTimeoutOrNull(INITIAL_SYNC_TIMEOUT_MS) {
                WorkManager.getInstance(appContext)
                    .getWorkInfoByIdFlow(syncRequestId)
                    .firstOrNull { info -> info != null && info.state.isFinished }
            }
            // Proceed regardless of how that resolved (finished, timed out,
            // even failed) — pairing itself already succeeded, and the
            // periodic job will keep retrying the sync in the background.

            _pairingState.value = PairingState.Success
        }
    }

    fun resetState() {
        _pairingState.value = PairingState.Idle
    }
}
