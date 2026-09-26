package com.toneup.app.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "toneup_user_prefs"
)

// M-22：AnimationLevel 枚举全库零引用（实际生效的是布尔值 animationsEnabled），删除死代码

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

    val preferences: Flow<UserPreferences> = context.userPrefsDataStore.data
        .catch { e ->
            // H-12：DataStore 读取失败（文件损坏/不可读）回退空偏好集，避免 collector 直接崩溃
            if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw e
        }
        .map { prefs ->
        // M-23：默认值收敛到 UserPreferences 构造默认值这一单一来源，消除映射处的重复字面量
        UserPreferences(
            animationsEnabled = prefs[Keys.ANIMATIONS] ?: DEFAULTS.animationsEnabled,
            darkModePolicy = com.toneup.app.ui.theme.DarkModePolicy.fromKey(prefs[Keys.DARK_MODE]),
            lastSubjectFilter = prefs[Keys.LAST_SUBJECT],
            hapticsEnabled = prefs[Keys.HAPTICS] ?: DEFAULTS.hapticsEnabled
        )
    }

    suspend fun setAnimationsEnabled(enabled: Boolean) {
        editPrefs { it[Keys.ANIMATIONS] = enabled }
    }

    suspend fun setDarkModePolicy(policy: com.toneup.app.ui.theme.DarkModePolicy) {
        editPrefs { it[Keys.DARK_MODE] = policy.name }
    }

    suspend fun setLastSubjectFilter(subjectId: String?) {
        editPrefs { prefs ->
            if (subjectId == null) prefs.remove(Keys.LAST_SUBJECT)
            else prefs[Keys.LAST_SUBJECT] = subjectId
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        editPrefs { it[Keys.HAPTICS] = enabled }
    }

    /**
     * M-24：DataStore 写入失败（IOException：磁盘满/IO 错误）原先直接冒泡，
     * 会崩掉 UI 侧调用协程；统一在此留痕并放弃本次写入（下次操作自然重试），
     * 取消必须透传，IllegalStateException 等编程错误不吞。
     */
    private suspend fun editPrefs(block: (MutablePreferences) -> Unit) {
        try {
            context.userPrefsDataStore.edit(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "persist user preferences failed", e)
        }
    }

    private companion object {
        const val TAG = "UserPreferencesStore"
        val DEFAULTS = UserPreferences()
    }
}
