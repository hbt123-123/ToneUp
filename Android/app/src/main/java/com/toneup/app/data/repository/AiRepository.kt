package com.toneup.app.data.repository

import com.toneup.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 图片 URL 构造：客户端只使用 bank_id，绝不拼接服务器文件路径（D4/契约备注） */
@Singleton
class ImageUrlBuilder @Inject constructor() {
    fun imageUrl(imageId: String, bankId: String): String =
        "${BuildConfig.BASE_URL.trimEnd('/')}/api/images/$imageId?bank_id=${bankId}"

    /** 内容中出现的相对路径补全 */
    fun absolute(url: String): String {
        if (url.startsWith("http")) return url
        return BuildConfig.BASE_URL.trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
    }
}

sealed class AiUploadOutcome {
    /** 小图同步返回结果 */
    data class Succeeded(val result: AiFeedbackDetailResult) : AiUploadOutcome()

    /** 202 受理，需轮询 */
    data class Accepted(val feedbackId: String, val status: String) : AiUploadOutcome()
}

data class AiFeedbackDetailResult(
    val isCorrect: Boolean?,
    val score: Double?,
    val errorReason: String?,
    val tagIds: List<Long>
)

@Singleton
class AiRepository @Inject constructor(
    private val aiFeedbackApi: com.toneup.app.data.remote.api.AiFeedbackApi,
    private val jsonProvider: JsonProvider
) {
    /**
     * multipart 上传创建诊断任务：
     * 后端可能同步返回结果（200），也可能 202 + feedback_id 需轮询。
     */
    suspend fun upload(
        file: File,
        bankId: String,
        questionId: Long,
        attemptId: Long?
    ): AiUploadOutcome {
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }.toMediaType()
        val filePart = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody(mime)
        )
        val bankPart = bankId.toPlainBody()
        val questionPart = questionId.toString().toPlainBody()
        val attemptPart = attemptId?.toString()?.toPlainBody()

        val (code, created) = EnvelopeUnwrapper.unwrapWithStatus(jsonProvider.json) {
            aiFeedbackApi.upload(filePart, bankPart, questionPart, attemptPart)
        }
        return if (code == 202 || created.status == "queued" || created.status == "processing") {
            AiUploadOutcome.Accepted(created.feedbackId, created.status)
        } else {
            AiUploadOutcome.Succeeded(
                AiFeedbackDetailResult(created.isCorrect, null, created.errorReason, created.tagIds)
            )
        }
    }

    suspend fun feedback(feedbackId: String): com.toneup.app.data.remote.dto.AiFeedbackDetailDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { aiFeedbackApi.feedback(feedbackId) }

    private fun String.toPlainBody(): okhttp3.RequestBody =
        toRequestBody("text/plain".toMediaType())
}
