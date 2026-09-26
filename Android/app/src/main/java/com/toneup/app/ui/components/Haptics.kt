package com.toneup.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
            // M-123：KEYBOARD_TAP 自 API 27（O_MR1）起可用，原 >=30 门槛使 27~29 设备无谓降级
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
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
    // M-124：performer 仅分配一次，经 rememberUpdatedState 读取最新依赖，lambda 身份稳定，
    // 避免每次重组重分配导致下游以 lambda 为键的 composable 反复失效
    val currentView by rememberUpdatedState(view)
    val currentEnabled by rememberUpdatedState(enabledProvider)
    return remember {
        { haptic: Haptic ->
            if (currentEnabled()) performHaptic(currentView, haptic)
        }
    }
}
