package com.toneup.app.data.repository

import com.toneup.app.data.remote.dto.ApiEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
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
            // M-65：message 为空白（如 ""）也视为缺失，回退默认文案
            throw AppException.Business(envelope.message.orDefault("响应数据为空"))
        }
    } catch (e: CancellationException) {
        // M-66：取消必须透传，吞掉会破坏结构化并发
        throw e
    } catch (e: HttpException) {
        throw classifyHttpException(json, e)
    } catch (e: IOException) {
        throw e.asAppException()
    } catch (e: SerializationException) {
        // M-66：响应体反序列化失败（契约不符）归为服务端异常，不再裸抛崩溃
        throw AppException.Server(e.message ?: "服务端返回格式异常")
    }

    /**
     * 无返回体/不消费返回体的端点：success=true 即成功，不要求 data 非空。
     * 泛型化原因：DELETE /wrong-questions/{id} 的 data 为 {"id","deleted"} 对象
     * （H-13 的 WrongQuestionDeleteDto），固定 ApiEnvelope<Unit> 会编译不匹配。
     */
    suspend fun <T> unwrapUnit(
        json: Json,
        call: suspend () -> ApiEnvelope<T>
    ) {
        val envelope = try {
            call()
        } catch (e: CancellationException) {
            // M-66：取消必须透传
            throw e
        } catch (e: HttpException) {
            throw classifyHttpException(json, e)
        } catch (e: IOException) {
            throw e.asAppException()
        } catch (e: SerializationException) {
            // M-66：响应体反序列化失败归为服务端异常
            throw AppException.Server(e.message ?: "服务端返回格式异常")
        }
        // M-65：message 为空白也视为缺失，回退默认文案
        if (!envelope.success) throw AppException.Business(envelope.message.orDefault("操作失败"))
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
            // M-65：message 为空白也视为缺失
            throw AppException.fromHttpCode(response.code(), envelope.message.orDefault(null))
        }
    } catch (e: CancellationException) {
        // M-66：取消必须透传
        throw e
    } catch (e: IOException) {
        throw e.asAppException()
    } catch (e: SerializationException) {
        // M-66：响应体反序列化失败归为服务端异常
        throw AppException.Server(e.message ?: "服务端返回格式异常")
    }

    fun classifyHttpException(json: Json, e: HttpException): AppException {
        val serverMessage = runCatching {
            e.response()?.errorBody()?.string()?.let { parseMessage(json, it) }
        }.getOrNull()?.takeIf { it.isNotBlank() } // M-65：空白 message 视为缺失
        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        return AppException.fromHttpCode(e.code(), serverMessage, retryAfter)
    }

    private fun classifyResponse(json: Json, response: Response<*>): AppException {
        val serverMessage = runCatching {
            response.errorBody()?.string()?.let { parseMessage(json, it) }
        }.getOrNull()?.takeIf { it.isNotBlank() } // M-65：空白 message 视为缺失
        val retryAfter = response.headers().get("Retry-After")?.toLongOrNull()
        return AppException.fromHttpCode(response.code(), serverMessage, retryAfter)
    }

    /**
     * M-65：服务器 message 为空白（如 ""）视为缺失；
     * [default] 为 null 时返回 null，交由 AppException 各子类默认文案兜底
     */
    private fun String?.orDefault(default: String?): String? =
        takeIf { !it.isNullOrBlank() } ?: default

    private fun parseMessage(json: Json, raw: String): String? = runCatching {
        val obj = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()
}
