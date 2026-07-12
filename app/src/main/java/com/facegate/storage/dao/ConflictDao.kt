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

    /**
     * Marks a conflict resolved locally. If the server already knows about
     * this row (remoteConflictId set), also flips synced back to 0 so
     * AttendanceSyncWorker.pushPendingConflicts() picks it up and pushes the
     * resolution via PUT /sync/conflicts/{id}/resolve — otherwise the
     * resolution would only ever exist locally. A row that's never been
     * pushed yet (remoteConflictId null) doesn't need that: its eventual
     * create push is what carries the resolved state, once that create path
     * is extended to include it (see AttendanceSyncWorker.pushPendingConflicts
     * doc comment for that known gap).
     */
    @Query("""
        UPDATE conflict_queue
        SET resolved = 1, synced = CASE WHEN remoteConflictId IS NOT NULL THEN 0 ELSE synced END
        WHERE id = :id
    """)
    suspend fun markResolvedAndRequeue(id: Int)

    @Query("SELECT COUNT(*) FROM conflict_queue WHERE resolved = 0")
    suspend fun getUnresolvedCount(): Int

    @Query("""
        SELECT * FROM conflict_queue
        WHERE resolved = 0 AND sessionId = :sessionId AND topStudentId = :topStudentId
        LIMIT 1
    """)
    suspend fun findOpenConflict(sessionId: String, topStudentId: String): ConflictEntity?

    /**
     * Find an open conflict for the same UNORDERED pair of people, regardless of which
     * one currently sits in the top/second slot. This prevents creating a second conflict
     * row when the same two people clash again but with their ranking flipped (e.g. A was
     * top/B was second last time, now B scores higher and is top/A is second).
     */
    @Query("""
        SELECT * FROM conflict_queue
        WHERE resolved = 0 AND sessionId = :sessionId
        AND (
            (topStudentId = :idA AND secondStudentId = :idB) OR
            (topStudentId = :idB AND secondStudentId = :idA)
        )
        LIMIT 1
    """)
    suspend fun findOpenConflictForPair(sessionId: String, idA: String, idB: String): ConflictEntity?

    /**
     * Refresh an existing open conflict row instead of inserting a duplicate.
     * Also allowed to update topStudentId/topStudentName, since the higher-scoring
     * candidate this round may not be the same person who was "top" last time.
     */
    @Query("""
        UPDATE conflict_queue
        SET topStudentId = :topStudentId,
            topStudentName = :topStudentName,
            topScore = :topScore,
            secondStudentId = :secondStudentId,
            secondStudentName = :secondStudentName,
            secondScore = :secondScore,
            reason = :reason,
            timestamp = :timestamp
        WHERE id = :id
    """)
    suspend fun updateConflict(
        id: Int,
        topStudentId: String,
        topStudentName: String,
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

    // ── Backend sync (conflicts flow both ways) ──────────────────────────────

    @Query("SELECT * FROM conflict_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ConflictEntity?

    @Query("SELECT * FROM conflict_queue WHERE remoteConflictId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: String): ConflictEntity?

    /** Device-sourced rows the server doesn't know about (new) or whose resolution hasn't been pushed yet. */
    @Query("SELECT * FROM conflict_queue WHERE synced = 0 AND source = 'DEVICE'")
    suspend fun getUnsyncedConflicts(): List<ConflictEntity>

    @Query("UPDATE conflict_queue SET synced = 1, remoteConflictId = :remoteId WHERE id = :id")
    suspend fun markConflictPushed(id: Int, remoteId: String)

    @Query("UPDATE conflict_queue SET synced = 1 WHERE id = :id")
    suspend fun markConflictSynced(id: Int)

    /** Mirror a website-side resolution down onto a conflict this device already knows about. */
    @Query("UPDATE conflict_queue SET resolved = 1, synced = 1 WHERE remoteConflictId = :remoteId")
    suspend fun resolveByRemoteId(remoteId: String)
}