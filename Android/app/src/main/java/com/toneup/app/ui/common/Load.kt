package com.toneup.app.ui.common

import com.toneup.app.data.repository.AppException

/** 页面级加载状态 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

// TODO(M-109)：Failed 仅保留预渲染文案，原始 Throwable（cause/堆栈/HTTP 码）在构造点即被丢弃；
// 构造点分散于各 ViewModel 共 11 处，需统一改为 Failed(message, cause: Throwable?) 才能支撑日志上报，延后处理

// M-110：显式补类型参数并压制擦除下不可避免的 unchecked cast 告警（sealed 体系内该转换运行时安全）
@Suppress("UNCHECKED_CAST")
fun <T> Load<T>.valueOrNull(): T? = (this as? Load.Ready<T>)?.value

// M-111：UI 层对数据层业务异常的既定桥接点——统一取 userMessage 作为最终展示文案，调用方为各 ViewModel
fun Throwable.toLoadMessage(): String = when (this) {
    is AppException -> userMessage
    else -> "加载失败，请稍后重试"
}
