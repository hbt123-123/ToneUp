package com.toneup.app.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    private const val TAG = "ToneUpAppScope"

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(
        // M-74：现有消费方（目录后台刷新、DataStore 文件 IO）均为阻塞 I/O，改挂 IO 线程池，避免占满 Default 池
        @IoDispatcher dispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(
        // H-24：根协程未捕获异常需兜底记录，避免直接崩进程
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "uncaught exception in application scope", e)
        }
    )
}
