package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per sync category (see [Category]), updated every time
 * AttendanceSyncWorker attempts that step. This is what backs the "sync
 * status" surface in AdminDashboard — previously the app only ever showed
 * the single GET /sync/status response, which says nothing about
 * enrollment/embedding/conflict/change-log pushes since those aren't
 * covered by that endpoint at all.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val category: String,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long? = null,
    val status: String,       // "OK" | "FAILED" | "SKIPPED"
    val message: String? = null,
    val pendingCount: Int = 0,
) {
    object Category {
        const val HEARTBEAT = "heartbeat"
        const val DEVICE_CHECK = "device_check"
        const val PULL = "pull"                    // timetable + students + holidays + conflicts + attendance-down
        const val ATTENDANCE_UP = "attendance_up"
        const val ENROLLMENT_UP = "enrollment_up"
        const val EMBEDDING_UP = "embedding_up"
        const val CONFLICTS_UP = "conflicts_up"
        const val CHANGE_LOG_UP = "change_log_up"
    }
}
