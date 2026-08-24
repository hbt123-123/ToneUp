package com.toneup.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * 触感映射（§9.4）：系统触感关闭时静默降级不报错。
 * LIGHT_IMPACT=收藏/标记；WARNING=提交错误；SUCCESS=连续打卡达成。
 */
enum class Haptic { LIGHT_IMPACT, WARNING, SUCCESS }

fun performHaptic(view: View?, haptic: Haptic) {
    view ?: return
    val constant = when (haptic) {
        Haptic.LIGHT_IMPACT ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.KEYBOARD_TAP
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        Haptic.WARNING -> HapticFeedbackConstants.LONG_PRESS
        Haptic.SUCCESS ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
    }
    runCatching { view.performHapticFeedback(constant) }
}

@Composable
fun rememberHapticPerformer(enabledProvider: () -> Boolean = { true }): (Haptic) -> Unit {
    val view = LocalView.current
    return { haptic ->
        if (enabledProvider()) performHaptic(view, haptic)
    }
}
