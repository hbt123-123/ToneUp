package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String
) {
    // M-39：重写 toString 隐藏明文密码，防止日志/调试输出泄漏凭据（不影响序列化与 copy/equals）
    override fun toString(): String = "RegisterRequest(username=$username, password=***)"
}

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
) {
    // M-39：同 RegisterRequest，toString 不回显 password
    override fun toString(): String = "LoginRequest(username=$username, password=***)"
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresInSeconds: Long? = null
)

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val role: String = "user",
    @SerialName("created_at") val createdAt: String? = null
)
