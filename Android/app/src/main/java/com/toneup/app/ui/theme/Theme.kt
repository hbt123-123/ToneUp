package com.toneup.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
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
        fun fromKey(key: String?): DarkModePolicy =
            entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}
