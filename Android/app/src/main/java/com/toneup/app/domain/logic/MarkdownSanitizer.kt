package com.toneup.app.domain.logic

/**
 * Markdown 安全渲染（需求 §7.7）：
 * 1. 一切 HTML 标签先转义（防御责任在客户端）
 * 2. 仅白名单内联标记：**加粗**、*斜体*、`行内代码`、换行
 * 输出为受控 HTML 片段，供 KaTeX 模板页渲染。
 */
object MarkdownSanitizer {

    fun sanitize(raw: String): String {
        val escaped = escapeHtml(raw)
        return escaped
            .replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
            .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")) { "<i>${it.groupValues[1]}</i>" }
            .replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
    }

    /** 段落化：连续空行分段，单换行转 <br/> */
    fun toParagraphs(sanitized: String): String =
        sanitized.split(Regex("\\n\\s*\\n"))
            .joinToString("</p><p>") { it.replace("\n", "<br/>") }
            .let { "<p>$it</p>" }

    private val htmlEscapes = mapOf(
        "&" to "&amp;",
        "<" to "&lt;",
        ">" to "&gt;",
        "\"" to "&quot;",
        "'" to "&#39;"
    )

    fun escapeHtml(text: String): String =
        buildString(text.length) {
            for (ch in text) append(htmlEscapes[ch.toString()] ?: ch.toString())
        }
}
