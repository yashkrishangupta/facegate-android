package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A locally-cached admin/faculty login account, synced down from
 * `authUsers` (see API_CONTRACT.md §3 and SyncAuthUserDto). Drives two
 * on-device gates in MainActivity/TodayScheduleFragment:
 *  - Admin Mode entry: password must match any row with role
 *    ADMIN/SUPER_ADMIN.
 *  - "Start" a period: password must match the row whose facultyId equals
 *    that period's own faculty_id, OR any ADMIN/SUPER_ADMIN row (override).
 *
 * passwordHash is a bcrypt hash, the same one the website checks at
 * login — never plaintext. See PasswordVerifier for how it's checked.
 *
 * This table is always replaced wholesale on every sync (see
 * AuthUserDao.replaceAll / AttendanceSyncWorker.mergeAuthUsers), not
 * upserted-and-left — AttendanceSyncWorker always calls incrementalSync
 * with since = null, so every sync response already contains the complete,
 * current set of accounts allowed on this device. Doing a full replace
 * (rather than insert-only) is what makes a deactivated/removed account's
 * password stop working here promptly, instead of staying valid forever
 * in a stale local cache.
 */
@Entity(tableName = "auth_users")
data class AuthUserEntity(
    @PrimaryKey val adminId : String,
    val employeeId          : String?,
    val firstName           : String,
    val lastName            : String,
    val role                : String, // "SUPER_ADMIN" | "ADMIN" | "FACULTY"
    val passwordHash        : String,
    val facultyId           : String? = null,
    val serverUpdatedAt     : String? = null,
) {
    val fullName: String get() = "$firstName $lastName"
    val isAdmin: Boolean get() = role == "SUPER_ADMIN" || role == "ADMIN"
}
