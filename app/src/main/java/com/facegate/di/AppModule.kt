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

    @Provides
    @Singleton
    fun provideFaceGateDatabase(
        @ApplicationContext context: Context,
    ): FaceGateDatabase {
        return Room.databaseBuilder(
            context,
            FaceGateDatabase::class.java,
            "facegate_database",
        )
            // No real data yet — safe to wipe on schema change.
            // Switch to .addMigrations(...) before production.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideTemplateRepository(
        database: FaceGateDatabase,
    ): TemplateRepository = TemplateRepository(
        studentDao    = database.studentDao(),
        attendanceDao = database.attendanceDao(),
        syncLogDao    = database.syncLogDao(),
        conflictDao   = database.conflictDao(),
        timetableDao  = database.timetableDao(),
        sessionDao    = database.sessionDao(),
        overrideDao   = database.overrideDao(),
        holidayDao    = database.holidayDao(),
    )

    @Provides
    @Singleton
    fun provideAttendancePipeline(
        @ApplicationContext context: Context,
        repository: TemplateRepository,
    ): AttendancePipeline = AttendancePipeline(context, repository)
}