package com.facegate.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.facegate.storage.entity.AuthUserEntity

@Dao
interface AuthUserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<AuthUserEntity>)

    @Query("DELETE FROM auth_users")
    suspend fun clear()

    /**
     * Wholesale replace, not an upsert — see AuthUserEntity's doc comment
     * for why this table always reflects exactly what the last sync
     * returned, rather than accumulating rows that never get removed.
     */
    @Transaction
    suspend fun replaceAll(users: List<AuthUserEntity>) {
        clear()
        insertAll(users)
    }

    @Query("SELECT * FROM auth_users WHERE role IN ('ADMIN', 'SUPER_ADMIN')")
    suspend fun getAdmins(): List<AuthUserEntity>

    @Query("SELECT * FROM auth_users WHERE facultyId = :facultyId")
    suspend fun getByFacultyId(facultyId: String): AuthUserEntity?

    @Query("SELECT COUNT(*) FROM auth_users")
    suspend fun count(): Int
}
