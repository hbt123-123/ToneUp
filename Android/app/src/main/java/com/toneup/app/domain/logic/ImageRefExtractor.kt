package com.toneup.app.domain.logic

/**
 * 题干图片引用抽取：
 * 后端已把库内引用改写为 GET /api/images/{image_id}?bank_id=... URL。
 * 支持 markdown 图片 ![alt](url) 与裸 URL；命中后从文本中移除，
 * 由宿主用 Coil 渲染（懒加载+占位+磁盘缓存），不进 WebView。
 */
object ImageRefExtractor {

    private val mdImage = Regex("!\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)")
    private val bareUrl = Regex("(?<![\"'(=])((?:https?:)?//[^\\s)\"'<>]+/api/images/[^\\s)\"'<>?]+(?:\\?[^\\s)\"'<>]*)?)")

    data class Result(val cleanedText: String, val imageUrls: List<String>)

    fun extract(text: String): Result {
        if (!text.contains("/api/images/") && !text.contains("![")) return Result(text, emptyList())
        val found = LinkedHashSet<String>()
        var working = text
        mdImage.findAll(text).forEach { m ->
            found.add(m.groupValues[2])
            working = working.replace(m.value, "")
        }
        bareUrl.findAll(working).forEach { m ->
            found.add(normalizeUrl(m.groupValues[1]))
            // 按匹配值替换：findAll 是基于原串的惰性序列，replaceRange(m.range) 在
            // 串被缩短后区间失效会删错位置（与上方 mdImage 分支保持一致）
            working = working.replace(m.value, "")
        }
        return Result(working.trim(), found.toList())
    }

    /** 相对路径 /api/images/x 补全 host 由调用方处理；此处仅去尾标点 */
    private fun normalizeUrl(url: String): String = url.trimEnd(',', '.', ')', ']')
}
