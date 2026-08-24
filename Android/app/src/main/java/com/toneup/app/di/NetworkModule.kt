package com.toneup.app.di

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.toneup.app.BuildConfig
import com.toneup.app.data.remote.api.AttemptApi
import com.toneup.app.data.remote.api.AiFeedbackApi
import com.toneup.app.data.remote.api.AuthApi
import com.toneup.app.data.remote.api.CatalogApi
import com.toneup.app.data.remote.api.NotesApi
import com.toneup.app.data.remote.api.QuestionApi
import com.toneup.app.data.remote.api.ReviewApi
import com.toneup.app.data.remote.api.StatsApi
import com.toneup.app.data.remote.api.WrongbookApi
import com.toneup.app.data.remote.interceptor.AuthInterceptor
import com.toneup.app.data.remote.interceptor.SanitizedLoggingInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.time.Duration
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val UPLOAD_TIMEOUT_SECONDS = 60L

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun okHttpClient(
        authInterceptor: AuthInterceptor,
        logging: SanitizedLoggingInterceptor
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
            .addInterceptor(authInterceptor)
        logging.create()?.let { builder.addInterceptor(it) }
        return builder.build()
    }

    /** 上传接口单独放宽超时 */
    @Provides
    @Singleton
    @UploadClient
    fun uploadOkHttpClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .readTimeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
            .build()

    @Provides
    @Singleton
    fun retrofit(json: Json, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    @UploadClient
    fun uploadRetrofit(json: Json, @UploadClient client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton fun authApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
    @Provides @Singleton fun catalogApi(retrofit: Retrofit): CatalogApi = retrofit.create(CatalogApi::class.java)
    @Provides @Singleton fun questionApi(retrofit: Retrofit): QuestionApi = retrofit.create(QuestionApi::class.java)
    @Provides @Singleton fun attemptApi(retrofit: Retrofit): AttemptApi = retrofit.create(AttemptApi::class.java)
    @Provides @Singleton fun reviewApi(retrofit: Retrofit): ReviewApi = retrofit.create(ReviewApi::class.java)
    @Provides @Singleton fun statsApi(retrofit: Retrofit): StatsApi = retrofit.create(StatsApi::class.java)
    @Provides @Singleton fun notesApi(retrofit: Retrofit): NotesApi = retrofit.create(NotesApi::class.java)
    @Provides @Singleton fun wrongbookApi(retrofit: Retrofit): WrongbookApi = retrofit.create(WrongbookApi::class.java)

    @Provides
    @Singleton
    fun aiFeedbackApi(@UploadClient retrofit: Retrofit): AiFeedbackApi =
        retrofit.create(AiFeedbackApi::class.java)

    @Provides
    @Singleton
    fun appContext(@ApplicationContext context: Context): Context = context
}
