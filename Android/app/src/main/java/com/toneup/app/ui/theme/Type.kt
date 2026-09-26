package com.toneup.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// M-260：补全 Typography 的全部 15 个 Material3 角色（原先仅覆盖 7 个），
// 未设置过的角色回退 Material3 默认样式；新增角色沿用既有风格：仅声明 fontSize，
// 标题类（display/headline）与 headlineMedium 一致使用 SemiBold，title/label 类使用 Medium
val ToneUpTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp),
    displayMedium = TextStyle(fontSize = 45.sp),
    displaySmall = TextStyle(fontSize = 36.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp)
)
