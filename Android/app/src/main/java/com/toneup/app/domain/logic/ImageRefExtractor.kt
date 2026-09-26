package com.toneup.app.domain.logic

/**
 * 题干图片引用抽取：
 * 后端已把库内引用改写为 GET /api/images/{image_id}?bank_id=... URL。
 * 支持 markdown 图片 ![alt](url) 与裸 URL；命中后从文本中移除，
 * 由宿主用 Coil 渲染（懒加载+占位+磁盘缓存），不进 WebView。
 */
object ImageRefExtractor {

    // H-26：仅匹配库内图片引用（/api/images/），与类契约及 PC 端行为一致，
    // 不再把外链图片（如 CDN/插图）误抽为宿主渲染
    private val mdImage = Regex("!\\[([^\\]]*)]\\((https?://[^)\\s]*/api/images/[^)\\s]*)\\)")
    private val bareUrl = Regex("(?<![\"'(=])((?:https?:)?//[^\\s)\"'<>]+/api/images/[^\\s)\"'<>?]+(?:\\?[^\\s)\"'<>]*)?)")

    data class Result(val cleanedText: String, val imageUrls: List<String>)

    fun extract(text: String): Result {
        if (!text.contains("/api/images/") && !text.contains("![")) return Result(text, emptyList())
        val found = LinkedHashSet<String>()

        // H-27：统一“先收集匹配、从后往前按 range 删除”，
        // 避免按字面量 replace 误删正文中与 URL 相同但未被匹配的子串
        var working = text
        val mdMatches = mdImage.findAll(working).toList()
        // M-85：markdown 分支与裸 URL 分支统一过 normalizeUrl（去尾标点/补 scheme），消除处理不一致
        mdMatches.forEach { found.add(normalizeUrl(it.groupValues[2])) }
        for (m in mdMatches.sortedByDescending { it.range.first }) {
            working = working.removeRange(m.range.first, m.range.last + 1)
        }

        val bareMatches = bareUrl.findAll(working).toList()
        bareMatches.forEach { found.add(normalizeUrl(it.groupValues[1])) }
        for (m in bareMatches.sortedByDescending { it.range.first }) {
            working = working.removeRange(m.range.first, m.range.last + 1)
        }

        return Result(working.trim(), found.toList())
    }

    /** 相对路径 /api/images/x 补全 host 由调用方处理；此处仅去尾标点 */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trimEnd(',', '.', ')', ']')
        // M-84：bareUrl 允许省略 scheme，协议相对地址（//host/...）补全后才能被宿主加载
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }
}
