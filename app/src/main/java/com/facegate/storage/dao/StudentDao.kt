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

    @Query("SELECT * FROM students WHERE enrollmentStatus != 'PENDING'")
    suspend fun getAllEnrolledStudents(): List<StudentEntity>

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

    @Query("UPDATE students SET name = :name, studentClass = :studentClass WHERE studentId = :studentId")
    suspend fun updateStudentInfo(studentId: String, name: String, studentClass: String)

    @Query("SELECT * FROM students WHERE studentId = :studentId LIMIT 1")
    suspend fun getStudentById(studentId: String): StudentEntity?

    @Query("UPDATE students SET studentId = :newId, name = :name, studentClass = :studentClass WHERE studentId = :oldId")
    suspend fun updateStudentIdAndInfo(oldId: String, newId: String, name: String, studentClass: String)

    @Query("SELECT * FROM students WHERE enrollmentStatus = 'DONE' AND embeddingSynced = 0 AND embedding IS NOT NULL")
    suspend fun getStudentsWithUnsyncedEmbedding(): List<StudentEntity>

    @Query("UPDATE students SET embeddingSynced = 1 WHERE studentId = :studentId")
    suspend fun markEmbeddingSynced(studentId: String)
}