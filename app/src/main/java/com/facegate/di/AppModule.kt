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
            .fallbackToDestructiveMigration() // safe for development — remove before production
            .build()
    }

    /**
     * Provides TemplateRepository, taking 3 DAOs from the database.
     * TemplateRepository(studentDao, attendanceDao, syncLogDao) matches the team's constructor.
     */
    @Provides
    @Singleton
    fun provideTemplateRepository(
        database: FaceGateDatabase,
    ): TemplateRepository {
        return TemplateRepository(
            studentDao     = database.studentDao(),
            attendanceDao  = database.attendanceDao(),
            syncLogDao     = database.syncLogDao(),
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