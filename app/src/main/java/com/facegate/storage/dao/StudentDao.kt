package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.StudentEntity

@Dao
interface StudentDao {

    @Insert
    suspend fun insertStudent(student: StudentEntity)

    @Query("SELECT * FROM students")
    suspend fun getAllStudents(): List<StudentEntity>

    @Query("DELETE FROM students")
    suspend fun deleteAllStudents()
}