package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facegate.storage.entity.StudentEntity

@Dao
interface StudentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: StudentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingStudent(student: StudentEntity)

    @Query("SELECT * FROM students ORDER BY name ASC")
    suspend fun getAllStudents(): List<StudentEntity>

    // Face recognition matching (AttendancePipeline.getEnrolledStudents) relies
    // on this to exclude a student the website marked GRADUATED/SUSPENDED/
    // DROPPED — previously there was no studentStatus column at all, so a
    // student in any of those states kept being recognized and marked
    // present indefinitely. See API_CONTRACT.md's own warning: "check
    // is_active/student_status client-side, don't assume returned means
    // still valid."
    @Query("SELECT * FROM students WHERE enrollmentStatus != 'PENDING' AND studentStatus = 'ACTIVE'")
    suspend fun getAllEnrolledStudents(): List<StudentEntity>

    @Query("UPDATE students SET studentStatus = :status WHERE studentId = :studentId")
    suspend fun updateStudentStatus(studentId: String, status: String)

    @Query("""
        UPDATE students
        SET remoteEmbeddingId = COALESCE(:embeddingId, remoteEmbeddingId), embeddingModelName = :modelName, embeddingVersion = :version
        WHERE studentId = :studentId
    """)
    suspend fun updateEmbeddingMetadata(studentId: String, embeddingId: String?, modelName: String, version: String)

    /**
     * Applies an embedding synced down from the server (see
     * AttendanceSyncWorker.mergeEmbeddingDown) — marks it DONE/synced since
     * it's already known server-side, no local capture or upload needed.
     */
    @Query("""
        UPDATE students
        SET embedding = :embeddingCsv, enrollmentStatus = 'DONE', embeddingSynced = 1,
            embeddingVersion = :embeddingVersion, embeddingModelName = :modelName
        WHERE studentId = :studentId
    """)
    suspend fun applyServerEmbedding(
        studentId: String,
        embeddingCsv: String,
        embeddingVersion: String,
        modelName: String,
    )

    @Query("SELECT * FROM students WHERE studentClass = :studentClass ORDER BY name ASC")
    suspend fun getStudentsByClass(studentClass: String): List<StudentEntity>

    @Query("SELECT DISTINCT studentClass FROM students ORDER BY studentClass ASC")
    suspend fun getAllClasses(): List<String>

    @Query("SELECT COUNT(*) FROM students")
    suspend fun getStudentCount(): Int

    @Query("DELETE FROM students WHERE studentId = :studentId")
    suspend fun deleteStudent(studentId: String)

    @Query("DELETE FROM students")
    suspend fun deleteAllStudents()

    @Query("""
        UPDATE students
        SET name = :name, studentClass = :studentClass, rollNumber = :rollNumber,
            registrationNumber = :registrationNumber, gender = :gender, email = :email, phone = :phone,
            dateOfBirth = :dateOfBirth, profilePhotoUrl = :profilePhotoUrl
        WHERE studentId = :studentId
    """)
    suspend fun updateStudentInfo(
        studentId: String,
        name: String,
        studentClass: String,
        rollNumber: String,
        registrationNumber: String,
        gender: String,
        email: String?,
        phone: String?,
        dateOfBirth: String?,
        profilePhotoUrl: String?,
    )

    @Query("SELECT * FROM students WHERE studentId = :studentId LIMIT 1")
    suspend fun getStudentById(studentId: String): StudentEntity?

    /**
     * Finds a not-yet-synced on-device capture that this pulled-down
     * server student is actually the same person as — matched by roll
     * number within the same batch (the only stable shared identity
     * before the local row has a server id). Used by mergeStudent to
     * reassign the local row's id instead of letting insertPendingStudent
     * add a second, embedding-less row under the server id: see
     * TemplateRepository.reassignLocalStudentId.
     */
    @Query("""
        SELECT * FROM students
        WHERE isLocalOnly = 1 AND rollNumber = :rollNumber
          AND (
              (:batchId IS NOT NULL AND batchId = :batchId)
              OR (:batchId IS NULL AND batchCode = :batchCode)
          )
        LIMIT 1
    """)
    suspend fun findLocalOnlyByBatchAndRoll(rollNumber: String, batchId: String?, batchCode: String?): StudentEntity?

    /**
     * Reassigns a local-only row's primary key to the server's id once a
     * pull confirms this student now exists server-side under it — unlike
     * completeEnrollmentSync, this doesn't touch embeddingSynced, since the
     * local embedding (if any) still hasn't actually reached the server;
     * it should still be picked up by the ordinary pushPendingEmbeddings
     * path afterward, not marked done here.
     */
    @Query("""
        UPDATE students
        SET studentId = :newId, isLocalOnly = 0
        WHERE studentId = :oldId
    """)
    suspend fun reassignLocalId(oldId: String, newId: String)

    @Query("UPDATE students SET studentId = :newId, name = :name, studentClass = :studentClass WHERE studentId = :oldId")
    suspend fun updateStudentIdAndInfo(oldId: String, newId: String, name: String, studentClass: String)

    @Query("SELECT * FROM students WHERE enrollmentStatus = 'DONE' AND embeddingSynced = 0 AND embedding IS NOT NULL")
    suspend fun getStudentsWithUnsyncedEmbedding(): List<StudentEntity>

    @Query("UPDATE students SET embeddingSynced = 1 WHERE studentId = :studentId")
    suspend fun markEmbeddingSynced(studentId: String)

    /**
     * Completes an enrollment upload: swaps the locally-typed roll number id
     * for the server-issued student_id UUID, and flips isLocalOnly/embeddingSynced
     * now that both the record and its embedding are known to the server. The
     * caller (AttendanceSyncWorker) must still cascade the id change into
     * attendance_records/conflict_queue itself — see
     * TemplateRepository.completeStudentEnrollmentSync.
     */
    @Query("""
        UPDATE students
        SET studentId = :newId, isLocalOnly = 0, embeddingSynced = 1
        WHERE studentId = :oldId
    """)
    suspend fun completeEnrollmentSync(oldId: String, newId: String)
}