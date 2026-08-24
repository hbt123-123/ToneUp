package com.toneup.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "toneup_user_prefs"
)

enum class AnimationLevel { FULL, REDUCED }

data class UserPreferences(
    val animationsEnabled: Boolean = true,
    val darkModePolicy: com.toneup.app.ui.theme.DarkModePolicy =
        com.toneup.app.ui.theme.DarkModePolicy.SYSTEM,
    val lastSubjectFilter: String? = null,
    val hapticsEnabled: Boolean = true
)

class UserPreferencesStore(private val context: Context) {

    private object Keys {
        val ANIMATIONS = booleanPreferencesKey("animations_enabled")
        val DARK_MODE = stringPreferencesKey("dark_mode_policy")
        val LAST_SUBJECT = stringPreferencesKey("last_subject_filter")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
    }

    val preferences: Flow<UserPreferences> = context.userPrefsDataStore.data.map { prefs ->
        UserPreferences(
            animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
            darkModePolicy = com.toneup.app.ui.theme.DarkModePolicy.fromKey(prefs[Keys.DARK_MODE]),
            lastSubjectFilter = prefs[Keys.LAST_SUBJECT],
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true
        )
    }

    suspend fun setAnimationsEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.ANIMATIONS] = enabled }
    }

    suspend fun setDarkModePolicy(policy: com.toneup.app.ui.theme.DarkModePolicy) {
        context.userPrefsDataStore.edit { it[Keys.DARK_MODE] = policy.name }
    }

    suspend fun setLastSubjectFilter(subjectId: String?) {
        context.userPrefsDataStore.edit { prefs ->
            if (subjectId == null) prefs.remove(Keys.LAST_SUBJECT)
            else prefs[Keys.LAST_SUBJECT] = subjectId
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { it[Keys.HAPTICS] = enabled }
    }
}
