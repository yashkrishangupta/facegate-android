package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.facegate.storage.entity.ConflictEntity

@Dao
interface ConflictDao {

    @Insert
    suspend fun insertConflict(conflict: ConflictEntity)

    @Query("SELECT * FROM conflict_queue WHERE resolved = 0 ORDER BY timestamp DESC")
    suspend fun getUnresolvedConflicts(): List<ConflictEntity>

    @Query("SELECT * FROM conflict_queue ORDER BY timestamp DESC")
    suspend fun getAllConflicts(): List<ConflictEntity>

    @Query("UPDATE conflict_queue SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Int)

    @Query("SELECT COUNT(*) FROM conflict_queue WHERE resolved = 0")
    suspend fun getUnresolvedCount(): Int

    @Query("""
        SELECT * FROM conflict_queue
        WHERE resolved = 0 AND sessionId = :sessionId AND topStudentId = :topStudentId
        LIMIT 1
    """)
    suspend fun findOpenConflict(sessionId: String, topStudentId: String): ConflictEntity?

    /** Refresh an existing open conflict row instead of inserting a duplicate. */
    @Query("""
        UPDATE conflict_queue
        SET topScore = :topScore,
            secondStudentId = :secondStudentId,
            secondStudentName = :secondStudentName,
            secondScore = :secondScore,
            reason = :reason,
            timestamp = :timestamp
        WHERE id = :id
    """)
    suspend fun updateConflict(
        id: Int,
        topScore: Float,
        secondStudentId: String,
        secondStudentName: String,
        secondScore: Float,
        reason: String,
        timestamp: Long,
    )

    @Query("UPDATE conflict_queue SET resolved = 1 WHERE topStudentId = :studentId AND resolved = 0")
    suspend fun resolveAllConflictsForStudent(studentId: String)

    /** Keep historical conflict rows pointing at the right person after a roll-no edit. */
    @Query("UPDATE conflict_queue SET topStudentId = :newId WHERE topStudentId = :oldId")
    suspend fun renameTopStudentId(oldId: String, newId: String)

    @Query("UPDATE conflict_queue SET secondStudentId = :newId WHERE secondStudentId = :oldId")
    suspend fun renameSecondStudentId(oldId: String, newId: String)

    /** Hard-delete ALL conflict rows involving a student (called when student is removed). */
    @Query("DELETE FROM conflict_queue WHERE topStudentId = :studentId OR secondStudentId = :studentId")
    suspend fun deleteAllConflictsForStudent(studentId: String)
}