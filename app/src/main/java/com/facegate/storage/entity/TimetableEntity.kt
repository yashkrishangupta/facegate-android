package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timetable")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val dayOfWeek: Int, // 1=Mon 2=Tue 3=Wed 4=Thu 5=Fri
    val periodNumber: Int,
    val subject: String,
    val batch: String,
    val scheduledHour: Int,
    val scheduledMinute: Int,
    val windowMinutes: Int = 10,
    // Kept for display purposes only — NOT the sync source of truth for room.
    val roomNumber: String? = null,
    // The room this period belongs to, per the server (SyncTimetableDto.roomId).
    // This — not roomNumber, and not anything typed in by an admin — is what
    // AttendanceSyncWorker compares against DeviceIdManager.getRoomId() to
    // detect a stale/reassigned room (see mergeTimetable). A device's room is
    // always learned from pairing or from GET /devices/{deviceId}; it is never
    // entered manually anywhere in this app.
    val roomId: String? = null,
    // Added for backend sync. The backend's timetable_id is a UUID, which
    // can't be this entity's @PrimaryKey (existing code depends on `id`
    // being a local Int — see SessionEntity.timetableId, TimetableDao).
    // remoteTimetableId is what attendance-sync payloads actually send as
    // timetable_id; it's null for periods created only on-device that
    // haven't matched a synced server row yet.
    val remoteTimetableId: String? = null,
    val subjectName: String? = null,
    val facultyName: String? = null,

    // ── Column parity with backend `timetable` — previously silently
    // dropped by mergeTimetable even though SyncTimetableDto already
    // carried them. None of these are used for on-device logic yet beyond
    // display, but they're synced down so the app matches what it actually
    // receives instead of throwing data away.

    /** Raw batch_id — batch/subject are stored as display codes above; this is the FK the server uses. */
    val batchId: String? = null,
    val facultyId: String? = null,
    val subjectId: String? = null,
    /** "HH:MM" — the counterpart to scheduledHour/scheduledMinute (which is start_time only). */
    val endHour: Int? = null,
    val endMinute: Int? = null,
    /** "yyyy-MM-dd" — a period outside [effectiveFrom, effectiveTo] is a stale/superseded schedule row. Not yet enforced anywhere on-device; see TodayScheduleViewModel if that's ever needed. */
    val effectiveFrom: String? = null,
    val effectiveTo: String? = null,
    /** Server's timetable.updated_at, ISO-8601 as received — kept for debugging/consistency checks, not currently compared (incremental sync's `since` already handles delta). */
    val serverUpdatedAt: String? = null,
)
