package com.facegate.di

import android.content.Context
import androidx.room.Room
import com.facegate.pipeline.AttendancePipeline
import com.facegate.storage.FaceGateDatabase
import com.facegate.storage.TemplateRepository
import com.facegate.sync.DeviceApi
import com.facegate.sync.DeviceAuthInterceptor
import com.facegate.sync.SyncApi
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ⚠️ Placeholder — point this at your actual backend before shipping.
    private const val SYNC_BASE_URL = "https://facegate-backend-production.up.railway.app"

    // ── Database / storage ──────────────────────────────────────────────────

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
            // Real device data (enrolled embeddings, attendance) now exists —
            // try the explicit migration first; destructive fallback only
            // covers a schema jump this migration doesn't (e.g. skipping
            // versions), not the normal upgrade path.
            .addMigrations(com.facegate.storage.MIGRATION_4_5)
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
        weeklyOffDao  = database.weeklyOffDao(),
        syncStateDao  = database.syncStateDao(),
    )

    @Provides
    @Singleton
    fun provideAttendancePipeline(
        @ApplicationContext context: Context,
        repository: TemplateRepository,
    ): AttendancePipeline = AttendancePipeline(context, repository)

    // ── Networking / sync ────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
    fun provideOkHttpClient(deviceAuthInterceptor: DeviceAuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(deviceAuthInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(SYNC_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi =
        retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi =
        retrofit.create(DeviceApi::class.java)
}