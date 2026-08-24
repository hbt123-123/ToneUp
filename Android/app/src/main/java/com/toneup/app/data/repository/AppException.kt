package com.toneup.app.data.repository

import java.io.IOException

/** 业务异常分类：UI 层按 [userMessage] 展示中文提示 */
sealed class AppException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val userMessage: String

    /** 网络异常：无连接、超时、DNS 等 */
    class Network(cause: Throwable) : AppException("网络异常", cause) {
        override val userMessage: String = "网络连接不可用，请检查网络后重试"
    }

    /** 参数错误 400 */
    class BadRequest(serverMessage: String?) : AppException(serverMessage ?: "参数错误") {
        override val userMessage: String = message ?: "请求参数有误"
    }

    /** 未认证 401 */
    class Unauthorized : AppException("未登录或登录已过期") {
        override val userMessage: String = "登录已过期，请重新登录"
    }

    /** 无权限 403 */
    class Forbidden : AppException("无权限") {
        override val userMessage: String = "没有权限执行此操作"
    }

    /** 不存在 404 */
    class NotFound(serverMessage: String?) : AppException(serverMessage ?: "资源不存在") {
        override val userMessage: String = message ?: "内容不存在或已被删除"
    }

    /** 限流 429 */
    class RateLimited(retryAfterSeconds: Long? = null) : AppException("请求过于频繁") {
        override val userMessage: String = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            "操作过于频繁，请 ${retryAfterSeconds} 秒后再试"
        } else {
            "操作过于频繁，请稍后再试"
        }
    }

    /** 服务端异常 5xx */
    class Server(serverMessage: String?) : AppException(serverMessage ?: "服务端异常") {
        override val userMessage: String = "服务器开小差了，请稍后重试"
    }

    /** success=false 等业务失败（非 HTTP 错误） */
    class Business(serverMessage: String?) : AppException(serverMessage ?: "操作失败") {
        override val userMessage: String = message ?: "操作失败"
    }

    companion object {
        fun fromHttpCode(code: Int, serverMessage: String?, retryAfter: Long? = null): AppException =
            when (code) {
                400 -> BadRequest(serverMessage)
                401 -> Unauthorized()
                403 -> Forbidden()
                404 -> NotFound(serverMessage)
                429 -> RateLimited(retryAfter)
                in 500..599 -> Server(serverMessage)
                else -> Business(serverMessage)
            }
    }
}

fun IOException.asAppException(): AppException.Network = AppException.Network(this)
