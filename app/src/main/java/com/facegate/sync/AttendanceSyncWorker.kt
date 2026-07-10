package com.facegate.sync

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.HolidayEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.TimetableEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker responsible for coordinating synchronization with the
 * backend: heartbeat, pull (students/timetable/holidays together, one
 * call), push unsynced attendance.
 *
 * Previously this called 9 separate steps against endpoints that mostly
 * don't exist on the backend (see the updated API_CONTRACT.md, "Known
 * gaps" — there is no /sync/students, /sync/timetable, /sync/holidays,
 * /sync/face-embeddings, /sync/attendance-sessions, or /sync/heartbeat).
 * Collapsed to what's actually there: heartbeat, one combined sync call,
 * one attendance push, retry.
 */
@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val deviceRepository: DeviceRepository,
    private val templateRepository: TemplateRepository,
    private val deviceIdManager: DeviceIdManager
) : CoroutineWorker(context, workerParams) {

    private companion object {
        const val TAG = "AttendanceSyncWorker"

        // Input Data Keys
        const val KEY_ROOM_ID = "room_id"

        // Local data retention: keep 30 days of already-synced attendance,
        // then let it age out one day at a time as each new day crosses the
        // boundary (rather than deleting a month's worth all at once).
        const val RETENTION_DAYS = 30L
        const val UNIQUE_WORK_NAME = "attendance_sync_periodic"

        // Server day names, matching chk_day_of_week on the backend
        // (Monday..Saturday — Sunday isn't a valid class day there).
        // Local TimetableEntity.dayOfWeek uses 1=Mon .. 6=Sat.
        val DAY_NAME_TO_INT = mapOf(
            "monday" to 1, "tuesday" to 2, "wednesday" to 3,
            "thursday" to 4, "friday" to 5, "saturday" to 6,
        )
    }

    object Scheduler {
        fun schedulePeriodicSync(context: Context, roomId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AttendanceSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_ROOM_ID to roomId))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Forces a one-off run right now, regardless of the hourly schedule —
         * e.g. a manual "Sync Now" button. Runs once; the periodic schedule
         * above is untouched.
         */
        fun runOnce(context: Context, roomId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_ROOM_ID to roomId))
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting synchronization coordination...")

        if (!deviceIdManager.isPaired()) {
            Log.w(TAG, "Device is not paired yet — skipping sync.")
            return Result.failure()
        }

        try {
            // 1. Heartbeat — device identity comes from the x-api-key header
            // (DeviceAuthInterceptor), not the request body.
            deviceRepository.heartbeat(
                HeartbeatRequest(
                    batteryLevel = getBatteryLevel(),
                    networkStatus = "ONLINE", // WorkManager constraints ensure connectivity
                    storageAvailableMb = getAvailableStorageMb(),
                )
            ).getOrRetry()

            // 2. Pull timetable + students + holidays in one call, merge into
            // local Room entities.
            val syncData = syncRepository.incrementalSync(since = null).getOrRetry()

            syncData.data?.timetable?.forEach { dto -> mergeTimetable(dto) }
            syncData.data?.students?.forEach { dto -> mergeStudent(dto) }
            syncData.data?.holidays?.forEach { dto -> mergeHoliday(dto) }

            // 3. Push unsynced attendance. Records whose session is missing
            // remoteTimetableId/sessionDate (e.g. an ad-hoc "extra period"
            // with no matching server timetable row — see SessionEntity)
            // can't be attached to anything on the backend and are skipped,
            // not silently dropped: they stay unsynced and get logged every
            // cycle until that gap is addressed.
            val unsynced = templateRepository.getUnsyncedAttendance()
            if (unsynced.isNotEmpty()) {

                val uploadable = mutableListOf<Pair<Int, OfflineAttendanceDto>>()
                var skipped = 0

                for (record in unsynced) {
                    val session = record.sessionId?.let { templateRepository.getSessionById(it) }
                    val timetableId = session?.remoteTimetableId
                    val sessionDate = session?.sessionDate

                    if (session == null || timetableId.isNullOrBlank() || sessionDate.isNullOrBlank()) {
                        skipped++
                        continue
                    }

                    uploadable += record.id to OfflineAttendanceDto(
                        sessionId = record.sessionId,
                        timetableId = timetableId,
                        sessionDate = sessionDate,
                        studentId = record.studentId,
                        status = record.attendanceStatus,
                        confidence = record.confidence,
                        timestamp = record.timeStamp.toString(),
                    )
                }

                if (skipped > 0) {
                    Log.w(TAG, "$skipped attendance record(s) skipped — session missing server timetable reference.")
                }

                if (uploadable.isNotEmpty()) {
                    syncRepository.uploadAttendance(
                        AttendanceUploadRequest(records = uploadable.map { it.second })
                    ).getOrRetry()

                    uploadable.forEach { (id, _) -> templateRepository.markAttendanceSynced(id) }
                }
            }

            val cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
            templateRepository.purgeOldSyncedAttendance(cutoffMillis)

            Log.i(TAG, "AttendanceSyncWorker executed successfully.")
            return Result.success()

        } catch (e: SyncRetryException) {
            Log.w(TAG, "Temporary failure during sync, will retry: ${e.message}")
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unrecoverable error during sync", e)
            return Result.failure()
        }
    }

    private suspend fun mergeTimetable(dto: SyncTimetableDto) {
        val dayInt = DAY_NAME_TO_INT[dto.dayOfWeek.lowercase()] ?: return
        val (hour, minute) = parseTime(dto.startTime) ?: return

        templateRepository.upsertSyncedTimetable(
            TimetableEntity(
                dayOfWeek = dayInt,
                periodNumber = dto.lectureNumber,
                subject = dto.subjectCode ?: dto.subjectName ?: dto.subjectId,
                batch = dto.batchCode ?: dto.batchId,
                scheduledHour = hour,
                scheduledMinute = minute,
                windowMinutes = dto.attendanceWindowMinutes,
                remoteTimetableId = dto.timetableId,
                subjectName = dto.subjectName,
                facultyName = dto.facultyName,
            )
        )
    }

    private suspend fun mergeStudent(dto: SyncStudentDto) {
        // insertPendingStudent() uses OnConflictStrategy.IGNORE, so a student
        // already enrolled locally (with a captured embedding) is never
        // clobbered by a repeat sync — see StudentDao.
        templateRepository.syncPendingStudents(
            listOf(
                StudentEntity(
                    studentId = dto.studentId,   // server's real UUID — was
                                                  // dto.rollNumber before, which
                                                  // made this entity's PK the
                                                  // wrong value entirely.
                    name = "${dto.firstName} ${dto.lastName}".trim(),
                    studentClass = dto.batchCode ?: dto.batchId,
                    embedding = null,
                    enrollmentStatus = "PENDING",
                    rollNumber = dto.rollNumber,
                    batchCode = dto.batchCode,
                )
            )
        )
    }

    private suspend fun mergeHoliday(dto: SyncHolidayDto) {
        // HolidayEntity only tracks date + name locally today — holiday_type
        // and description from the server are dropped here. Add columns if
        // the on-device UI ever needs to show them.
        templateRepository.insertHoliday(
            HolidayEntity(
                date = dto.holidayDate,
                name = dto.holidayName,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    private fun parseTime(value: String): Pair<Int, Int>? {
        val parts = value.split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return hour to minute
    }

    /**
     * Extension to handle kotlin.Result and throw a custom exception for retries.
     * Uses fully qualified name for Result to avoid conflict with androidx.work.Result.
     */
    private fun <T> kotlin.Result<T>.getOrRetry(): T {
        return getOrElse { throwable ->
            if (throwable is IOException || throwable is HttpException) {
                throw SyncRetryException(throwable.message, throwable)
            }
            throw throwable
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun getAvailableStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
    }

    /**
     * Custom exception to signal that WorkManager should retry the task.
     */
    private class SyncRetryException(message: String?, cause: Throwable?) : Exception(message, cause)
}
