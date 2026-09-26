package com.toneup.app.ui.feature.aiphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * 本地压缩（§10.1 步骤3）：最长边 ≤1600px、JPEG 质量 80、体积 ≤5MB。
 * IO 线程执行；质量不足时逐级降质兜底。
 */
object ImageCompressor {

    const val MAX_LONG_EDGE = 1600
    const val INITIAL_QUALITY = 80
    const val MAX_BYTES = 5L * 1024 * 1024

    fun compress(context: Context, source: File, output: File): File {
        // 1. 采样读取避免 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_LONG_EDGE * 2) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: throw IllegalStateException("cannot decode image")

        // 2. EXIF 方向校正（CameraX 竖拍 JPEG 通常带 90° 旋转标记，
        //    不校正会导致上传图与预览方向不一致，影响 AI 诊断）
        // M-138：补全镜像/转置类 EXIF 方向（FLIP_HORIZONTAL/FLIP_VERTICAL/TRANSPOSE/TRANSVERSE）
        val transform = readExifTransform(source)
        val upright = if (!transform.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        } else {
            bitmap
        }

        // 3. 精确缩放至最长边 ≤1600
        val longEdge = max(upright.width, upright.height)
        val scaled = if (longEdge > MAX_LONG_EDGE) {
            val ratio = MAX_LONG_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * ratio).toInt().coerceAtLeast(1),
                (upright.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            upright
        }

        // 4. JPEG 压缩，超限逐级降质
        try {
            var quality = INITIAL_QUALITY
            do {
                output.outputStream().use { stream ->
                    // H-40：编码失败（返回 false）意味着输出文件无效，
                    // 必须抛错终止，不得把损坏文件交给上传链路
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        "JPEG encode failed (quality=$quality)"
                    }
                }
                quality -= 10
            } while (output.length() > MAX_BYTES && quality >= 30)
            // M-136：质量降至下限仍超限时显式失败（1600px@q30 超 5MB 仅见于极端噪点图），
            // 不得把超出 §10.1 体积契约的文件静默交给上传链路（服务端将拒绝）
            check(output.length() <= MAX_BYTES) {
                "compressed image still exceeds ${MAX_BYTES / (1024 * 1024)}MB (quality=$quality)"
            }
        } finally {
            // M-137：异常安全——压缩中途抛错时同样回收中间 Bitmap，避免 native 内存泄漏
            if (scaled !== upright) scaled.recycle()
            if (upright !== bitmap) upright.recycle()
            bitmap.recycle()
        }
        return output
    }

    /**
     * M-138：读取 EXIF 方向并映射为完整变换矩阵。
     * 除纯旋转外补全镜像/转置类方向（按 EXIF 常量表，经像素级验证）：
     * FLIP_HORIZONTAL 水平翻转、FLIP_VERTICAL 垂直翻转、
     * TRANSPOSE 主对角线翻转（= 水平翻转后逆时针 90°）、
     * TRANSVERSE 副对角线翻转（= 水平翻转后顺时针 90°）。
     */
    private fun readExifTransform(source: File): Matrix = runCatching {
        val matrix = Matrix()
        when (
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
        }
        matrix
    }.getOrDefault(Matrix())
}
