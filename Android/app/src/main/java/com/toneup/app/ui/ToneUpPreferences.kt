package com.toneup.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** 全局偏好（动效/触感/深色策略），根组件收集后下发 */
data class ToneUpPreferences(
    val animationsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true
)

val LocalToneUpPreferences = staticCompositionLocalOf { ToneUpPreferences() }
