package com.toneup.app.di

import android.content.Context
import com.toneup.app.data.local.CipherAdapter
import com.toneup.app.data.local.KeystoreCipherAdapter
import com.toneup.app.data.local.SecureTokenStore
import com.toneup.app.data.local.UserPreferencesStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun cipherAdapter(): CipherAdapter = KeystoreCipherAdapter()

    @Provides
    @Singleton
    fun secureTokenStore(@ApplicationContext context: Context, cipher: CipherAdapter) =
        SecureTokenStore(context, cipher)

    @Provides
    @Singleton
    fun userPreferencesStore(@ApplicationContext context: Context) = UserPreferencesStore(context)
}
