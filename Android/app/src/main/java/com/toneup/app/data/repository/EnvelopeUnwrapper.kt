package com.toneup.app.data.repository

import com.toneup.app.data.remote.dto.ApiEnvelope
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * 统一响应外层解包：
 * - success=true 返回 data（data 为 null 视为业务异常）
 * - success=false / HTTP 非 2xx 抛出分类 [AppException]
 * - 无返回体端点使用 [unwrapUnit]（仅校验 success 与 HTTP 状态）
 */
object EnvelopeUnwrapper {

    suspend fun <T> unwrap(
        json: Json,
        call: suspend () -> ApiEnvelope<T>
    ): T = try {
        val envelope = call()
        if (envelope.success && envelope.data != null) {
            envelope.data
        } else {
            throw AppException.Business(envelope.message ?: "响应数据为空")
        }
    } catch (e: HttpException) {
        throw classifyHttpException(json, e)
    } catch (e: IOException) {
        throw e.asAppException()
    }

    /** 无返回体端点（ApiEnvelope<Unit>）：success=true 即成功，不要求 data 非空 */
    suspend fun unwrapUnit(
        json: Json,
        call: suspend () -> ApiEnvelope<Unit>
    ) {
        val envelope = try {
            call()
        } catch (e: HttpException) {
            throw classifyHttpException(json, e)
        } catch (e: IOException) {
            throw e.asAppException()
        }
        if (!envelope.success) throw AppException.Business(envelope.message ?: "操作失败")
    }

    /**
     * 需要原始 HTTP 状态码的调用（如主观题提交 202 受理）使用此重载。
     * 调用方直接返回 Retrofit [Response]：非 2xx 时 body 恒为 null，
     * 必须读取 errorBody 分类，避免所有 HTTP 错误被误判为"服务端返回为空"。
     */
    suspend fun <T> unwrapWithStatus(
        json: Json,
        call: suspend () -> Response<ApiEnvelope<T>>
    ): Pair<Int, T> = try {
        val response = call()
        if (!response.isSuccessful) throw classifyResponse(json, response)
        val envelope = response.body() ?: throw AppException.Business("服务端返回为空")
        if (envelope.success && envelope.data != null) {
            response.code() to envelope.data
        } else {
            throw AppException.fromHttpCode(response.code(), envelope.message)
        }
    } catch (e: IOException) {
        throw e.asAppException()
    }

    fun classifyHttpException(json: Json, e: HttpException): AppException {
        val serverMessage = runCatching {
            e.response()?.errorBody()?.string()?.let { parseMessage(json, it) }
        }.getOrNull()
        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        return AppException.fromHttpCode(e.code(), serverMessage, retryAfter)
    }

    private fun classifyResponse(json: Json, response: Response<*>): AppException {
        val serverMessage = runCatching {
            response.errorBody()?.string()?.let { parseMessage(json, it) }
        }.getOrNull()
        val retryAfter = response.headers().get("Retry-After")?.toLongOrNull()
        return AppException.fromHttpCode(response.code(), serverMessage, retryAfter)
    }

    private fun parseMessage(json: Json, raw: String): String? = runCatching {
        val obj = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()
}
