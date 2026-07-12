package com.facegate.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey
    val studentId: String,
    val name: String,
    val studentClass: String = "",
    val embedding: String? = null,
    val enrollmentStatus: String = "PENDING",
    val embeddingSynced: Boolean = false,
    // Added for backend sync (server student payload — see SyncDtos.kt).
    // studentId above now holds the server's student_id (UUID) once synced;
    // rollNumber is kept separately since it's what's shown in the UI and
    // used to key manual/admin lookups.
    val rollNumber: String = "",
    val batchCode: String? = null,
    // True for a student created on-device (EnrollmentViewModel) that the
    // server has never seen — studentId is a locally-typed roll number, not
    // a server UUID, until the enrollment upload succeeds. False for students
    // that came down from a server sync (mergeStudent). This is what
    // AttendanceSyncWorker checks to decide between calling the "create +
    // embedding" enroll endpoint vs. the "embedding only" endpoint for a row
    // that's DONE but embeddingSynced = false.
    val isLocalOnly: Boolean = true,

    // ── Column parity with backend `student` — SyncStudentDto already
    // carries all of these; mergeStudent previously dropped everything
    // except name/batch/roll on the floor.

    /** Distinct from rollNumber ("01") — the UNIQUE administrative id ("2024CS001"). */
    val registrationNumber: String = "",
    val email: String? = null,
    val phone: String? = null,
    val gender: String? = null,
    /**
     * CHECK: ACTIVE, GRADUATED, SUSPENDED, DROPPED. Previously not tracked
     * at all — StudentDao.getAllEnrolledStudents() (which feeds face
     * recognition matching) had no way to exclude a student the website
     * marked GRADUATED/SUSPENDED/DROPPED, meaning they'd keep being
     * recognized forever. Defaults to ACTIVE so a locally-enrolled student
     * (isLocalOnly = true, no server status yet) is still usable.
     */
    val studentStatus: String = "ACTIVE",
    val batchId: String? = null,
    val serverUpdatedAt: String? = null,
    // schema.sql: student.gender and admission_year are NOT NULL with no
    // default. Nullable here because rows synced down before this field
    // existed, or a student mid-enrollment on-device, may not have them
    // yet — but AttendanceSyncWorker.pushPendingEnrollments will refuse to
    // upload a student missing either, rather than send a request the
    // insert would reject.
    val admissionYear: Int? = null,
    val dateOfBirth: String? = null,      // "yyyy-MM-dd"
    val profilePhotoUrl: String? = null,

    // ── face_embedding column parity (folded into this entity rather than
    // a separate table, since it's genuinely one-embedding-per-student both
    // here and on the backend — UNIQUE student_id on face_embedding).
    // schema.sql: embedding_version is VARCHAR(20) DEFAULT 'v1.0' — a label,
    // not an integer (was Int in an earlier pass; fixed to match the DDL).
    val embeddingVersion: String = "v1.0",
    val embeddingModelName: String? = null,
    /** Server's embedding_id, once known — set after a successful enroll/embedding upload. */
    val remoteEmbeddingId: String? = null,
)