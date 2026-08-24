package com.toneup.app.ui.feature.aiphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        // 2. 精确缩放至最长边 ≤1600
        val longEdge = max(bitmap.width, bitmap.height)
        val scaled = if (longEdge > MAX_LONG_EDGE) {
            val ratio = MAX_LONG_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        // 3. JPEG 压缩，超限逐级降质
        var quality = INITIAL_QUALITY
        do {
            output.outputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
            quality -= 10
        } while (output.length() > MAX_BYTES && quality >= 30)

        if (scaled !== bitmap) {
            scaled.recycle()
            bitmap.recycle()
        } else {
            bitmap.recycle()
        }
        return output
    }
}
