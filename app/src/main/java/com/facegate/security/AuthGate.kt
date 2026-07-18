package com.facegate.security

import com.facegate.storage.TemplateRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data class Success(val name: String, val role: String) : AuthResult()
    object WrongPassword : AuthResult()
    /** auth_users has never synced (fresh pairing, no connectivity yet). */
    object NotSyncedYet : AuthResult()
}

/**
 * The two on-device login checks described in the Android auth task:
 *  - Admin Mode entry (MainActivity's "Admin Mode" button): password must
 *    match any locally-synced ADMIN/SUPER_ADMIN account.
 *  - Starting a period (TodayScheduleFragment's "Start" button): password
 *    must match the specific faculty assigned to *that* period, or any
 *    ADMIN/SUPER_ADMIN account as an override — an admin should be able to
 *    start any period, not just faculty in that seat.
 *
 * Both checks run entirely offline against AuthUserEntity rows synced down
 * by AttendanceSyncWorker.mergeAuthUsers — see that function's doc comment
 * for why the table is always a full, current replace rather than an
 * accumulating cache.
 */
@Singleton
class AuthGate @Inject constructor(
    private val templateRepository: TemplateRepository,
    private val passwordVerifier: PasswordVerifier,
) {

    suspend fun verifyAdminLogin(password: String): AuthResult {
        val admins = templateRepository.getAdminAuthUsers()
        if (admins.isEmpty()) return AuthResult.NotSyncedYet

        val match = admins.firstOrNull { passwordVerifier.matches(password, it.passwordHash) }
            ?: return AuthResult.WrongPassword

        return AuthResult.Success(match.fullName, match.role)
    }

    suspend fun verifyPeriodStart(facultyId: String, password: String): AuthResult {
        val facultyAccount = templateRepository.getAuthUserByFacultyId(facultyId)
        val admins = templateRepository.getAdminAuthUsers()

        if (facultyAccount == null && admins.isEmpty()) return AuthResult.NotSyncedYet

        // The period's own faculty takes priority; any synced admin is a
        // valid override on top of that.
        if (facultyAccount != null && passwordVerifier.matches(password, facultyAccount.passwordHash)) {
            return AuthResult.Success(facultyAccount.fullName, facultyAccount.role)
        }
        val adminMatch = admins.firstOrNull { passwordVerifier.matches(password, it.passwordHash) }
        if (adminMatch != null) return AuthResult.Success(adminMatch.fullName, adminMatch.role)

        return AuthResult.WrongPassword
    }
}
