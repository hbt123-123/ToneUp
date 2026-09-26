package com.toneup.app.ui.theme

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    secondary = BrandAccent,
    tertiary = BrandAccent,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurface = LightOnSurface
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    secondary = BrandAccentLight,
    tertiary = BrandAccentLight,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurface = DarkOnSurface
)

@Composable
fun ToneUpTheme(
    darkModePolicy: DarkModePolicy = DarkModePolicy.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (darkModePolicy) {
        DarkModePolicy.SYSTEM -> isSystemInDarkTheme()
        DarkModePolicy.LIGHT -> false
        DarkModePolicy.DARK -> true
    }
    // M-257/M-258：移除 API 31+ 的 dynamic 取色分支——
    // 1) M-258：dynamic 分支无条件覆盖品牌色（DarkModePolicy 无 dynamic 入口，用户无法选择），
    //    考研刷题 app 品牌一致性优先，固定使用 LightColors/DarkColors 品牌配色；
    // 2) M-257：原先每次重组都会经 dynamicDarkColorScheme()/dynamicLightColorScheme() 新建
    //    ColorScheme 对象；现两个 scheme 均为顶层单例直接引用，不再产生每次重组的重复分配。
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ToneUpTypography,
        content = content
    )
}

enum class DarkModePolicy(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        private const val TAG = "DarkModePolicy"

        fun fromKey(key: String?): DarkModePolicy {
            // M-259：trim + lowercase 归一化，容忍历史遗留数据中的空白/大小写变体；
            // 无法识别的非空 key 记录 Log.w 留痕，避免静默回退 SYSTEM 掩盖脏数据
            val normalized = key?.trim()?.lowercase()
            val matched = entries.firstOrNull { it.name.lowercase() == normalized }
            if (matched == null && !normalized.isNullOrEmpty()) {
                Log.w(TAG, "无法识别的深色模式 key：$key，已回退为 SYSTEM")
            }
            return matched ?: SYSTEM
        }
    }
}
