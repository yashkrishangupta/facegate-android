package com.facegate.di

import android.content.Context
import com.facegate.pipeline.AttendancePipeline
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
    fun provideAttendancePipeline(
        @ApplicationContext context: Context,
    ): AttendancePipeline = AttendancePipeline(context)

    // TODO: add these when storage/ is ready — no other changes needed
    // @Provides @Singleton fun provideDatabase(...): FaceGateDatabase
    // @Provides @Singleton fun provideRepository(...): TemplateRepository
}