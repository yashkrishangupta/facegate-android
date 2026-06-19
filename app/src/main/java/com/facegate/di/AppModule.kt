package com.facegate.di

import android.content.Context
import androidx.room.Room
import com.facegate.pipeline.AttendancePipeline
import com.facegate.storage.FaceGateDatabase
import com.facegate.storage.TemplateRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the Room database singleton.
     * Plain Room (no SQLCipher) matching the team's FaceGateDatabase definition.
     */
    @Provides
    @Singleton
    fun provideFaceGateDatabase(
        @ApplicationContext context: Context,
    ): FaceGateDatabase {
        return Room.databaseBuilder(
            context,
            FaceGateDatabase::class.java,
            "facegate_database"
        )
            .addMigrations(FaceGateDatabase.MIGRATION_1_2)
            .build()
    }

    /**
     * Provides TemplateRepository, taking 4 DAOs from the database.
     * ConflictDao added in v2 alongside ConflictEntity / conflict_queue table.
     */
    @Provides
    @Singleton
    fun provideTemplateRepository(
        database: FaceGateDatabase,
    ): TemplateRepository {
        return TemplateRepository(
            studentDao    = database.studentDao(),
            attendanceDao = database.attendanceDao(),
            syncLogDao    = database.syncLogDao(),
            conflictDao   = database.conflictDao(),  // ← added in version 2
        )
    }

    /**
     * Provides the AttendancePipeline singleton.
     * Now takes both Context and TemplateRepository — storage is wired in.
     */
    @Provides
    @Singleton
    fun provideAttendancePipeline(
        @ApplicationContext context: Context,
        repository: TemplateRepository,
    ): AttendancePipeline {
        return AttendancePipeline(context, repository)
    }
}