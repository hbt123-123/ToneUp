package com.toneup.app

import com.toneup.app.domain.logic.ImageRefExtractor
import com.toneup.app.domain.logic.MarkdownSanitizer
import com.toneup.app.domain.logic.PollBackoffPolicy
import com.toneup.app.domain.logic.IdempotencyKeyStore
import com.toneup.app.ui.components.question.RendererRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 白名单安全渲染（§7.7） */
class MarkdownSanitizerTest {

    @Test
    fun `html tags are escaped`() {
        val out = MarkdownSanitizer.sanitize("<script>alert(1)</script>")
        assertFalse(out.contains("<script>"))
        assertTrue(out.contains("&lt;script&gt;"))
    }

    @Test
    fun `whitelist bold italic code`() {
        assertEquals("<b>x</b>", MarkdownSanitizer.sanitize("**x**"))
        assertEquals("<i>y</i>", MarkdownSanitizer.sanitize("*y*"))
        assertEquals("<code>z</code>", MarkdownSanitizer.sanitize("`z`"))
    }

    @Test
    fun `paragraphs wrap and single newline becomes br`() {
        val html = MarkdownSanitizer.toParagraphs(MarkdownSanitizer.sanitize("a\nb\n\nc"))
        assertTrue(html.startsWith("<p>") && html.endsWith("</p>"))
        assertTrue(html.contains("a<br/>b") || html.contains("</p><p>"))
    }
}

/** 题干图片引用抽取（D3 图片端点配合） */
class ImageRefExtractorTest {

    @Test
    fun `markdown image extracted and removed`() {
        val text = "如图所示 ![img](https://api.toneup.com/api/images/12?bank_id=math1) 求解。"
        val result = ImageRefExtractor.extract(text)
        assertEquals(listOf("https://api.toneup.com/api/images/12?bank_id=math1"), result.imageUrls)
        assertFalse(result.cleanedText.contains("![").also { })
        assertFalse(result.cleanedText.contains("/api/images/12"))
    }

    @Test
    fun `text without refs untouched`() {
        val text = "纯文本题干 \$x+y\$"
        val result = ImageRefExtractor.extract(text)
        assertTrue(result.imageUrls.isEmpty())
        assertEquals(text, result.cleanedText)
    }
}

/** 轮询退避（10.1）：2s 起、指数至 5s 封顶、60s 截止 */
class PollBackoffPolicyTest {

    @Test
    fun `delays grow exponentially capped at 5s`() {
        assertEquals(2000L, PollBackoffPolicy.delayForAttempt(0))
        assertEquals(4000L, PollBackoffPolicy.delayForAttempt(1))
        assertEquals(5000L, PollBackoffPolicy.delayForAttempt(2))
        assertEquals(5000L, PollBackoffPolicy.delayForAttempt(10))
    }

    @Test
    fun `deadline at 60s`() {
        assertFalse(PollBackoffPolicy.isDeadlineExceeded(59999))
        assertTrue(PollBackoffPolicy.isDeadlineExceeded(60000))
    }
}

/** 幂等键复用语义（§8.2） */
class IdempotencyKeyStoreTest {

    @Test
    fun `same question reuses key until confirmed`() {
        val store = IdempotencyKeyStore()
        val first = store.keyFor("math1", 100)
        val second = store.keyFor("math1", 100)
        assertEquals(first, second)

        store.confirm("math1", 100)
        val third = store.keyFor("math1", 100)
        assertTrue(third != first)
    }

    @Test
    fun `different questions have different keys`() {
        val store = IdempotencyKeyStore()
        assertTrue(store.keyFor("math1", 1) != store.keyFor("math1", 2))
    }

    @Test
    fun `seed restores in-flight key from persisted queue`() {
        val store = IdempotencyKeyStore()
        store.seed("math1", 7, "fixed-uuid")
        assertEquals("fixed-uuid", store.keyFor("math1", 7))
    }
}

/** 渲染注册表完整性校验（§6.1 / A1 验收第 1 条） */
class RendererRegistryTest {

    @Test
    fun `all ten type codes registered exactly once`() {
        val problems = RendererRegistry.validateIntegrity()
        assertEquals("注册表完整性校验应无缺失: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `every known code resolves to a renderer`() {
        val allCodes = listOf(
            "SINGLE", "MULTI", "JUDGE", "FILL_BLANK", "SOLUTION",
            "CLOZE", "READING", "ORDERING", "TRANSLATION", "ESSAY"
        )
        allCodes.forEach { code ->
            assertTrue("缺少渲染器: $code", RendererRegistry.rendererFor(code) != null)
        }
    }

    @Test
    fun `unknown code returns null for fallback card`() {
        assertNull(RendererRegistry.rendererFor("UNKNOWN_TYPE_X"))
    }
}
