package com.toneup.app.ui.common

/** 页面级加载状态 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun <T> Load<T>.valueOrNull(): T? = (this as? Load.Ready)?.value

fun Throwable.toLoadMessage(): String = when (this) {
    is com.toneup.app.data.repository.AppException -> userMessage
    else -> "加载失败，请稍后重试"
}
