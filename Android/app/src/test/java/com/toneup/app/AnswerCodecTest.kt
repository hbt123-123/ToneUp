package com.toneup.app

import com.toneup.app.data.remote.dto.OptionDto
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.logic.AnswerCodec
import com.toneup.app.domain.logic.CorrectAnswerParser
import com.toneup.app.domain.logic.SubQuestionParser
import com.toneup.app.domain.model.AnswerValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 答案编解码与服务端口径（后端契约 §7）一致性 */
class AnswerCodecTest {

    private val json = Json

    @Test
    fun `single choice encodes label`() {
        val encoded = AnswerCodec.encode(AnswerValue.Choice("B"), "SINGLE")
        assertEquals("B", json.parseToJsonElement(encoded.toString()).jsonObject["value"]!!.toString().trim('"'))
        assertEquals(
            AnswerValue.Choice("B"),
            AnswerCodec.decode(encoded.jsonObject)
        )
    }

    @Test
    fun `cloze encodes ordered labels array`() {
        val answer = AnswerValue.BlankLabels(mapOf(0 to "A", 1 to "D"))
        val encoded = AnswerCodec.encode(answer, "CLOZE")
        val decoded = AnswerCodec.decode(encoded.jsonObject)
        assertEquals(answer, decoded)
        // 数组形状 ["A","D"]
        assertTrue(encoded.toString().contains("[\"A\",\"D\"]"))
    }

    @Test
    fun `ordering encodes sequence array`() {
        val answer = AnswerValue.Order(listOf("3", "1", "2"))
        val decoded = AnswerCodec.decode(AnswerCodec.encode(answer, "ORDERING").jsonObject)
        assertEquals(answer, decoded)
    }

    @Test
    fun `fill blank encodes map by index`() {
        val answer = AnswerValue.Blanks(mapOf(0 to "\\frac{1}{2}", 2 to "x=3"))
        val decoded = AnswerCodec.decode(AnswerCodec.encode(answer, "FILL_BLANK").jsonObject)
        assertEquals(answer, decoded)
    }

    @Test
    fun `subjective text roundtrip`() {
        val answer = AnswerValue.Text("解：由题意得……")
        val decoded = AnswerCodec.decode(AnswerCodec.encode(answer, "SOLUTION").jsonObject)
        assertEquals(answer, decoded)
    }

    @Test
    fun `decode null or unknown returns null`() {
        assertNull(AnswerCodec.decode(null))
        assertNull(AnswerCodec.decode(Json.parseToJsonElement("{}").jsonObject))
    }
}

class CorrectAnswerParserTest {

    @Test
    fun `single label from plain text`() {
        assertEquals("A", CorrectAnswerParser.singleLabel("A"))
        assertEquals("B", CorrectAnswerParser.singleLabel("\"B\""))
        assertEquals(null, CorrectAnswerParser.singleLabel(null))
    }

    @Test
    fun `multi labels from json array`() {
        assertEquals(listOf("A", "C"), CorrectAnswerParser.multiLabels("""["A","C"]"""))
    }

    @Test
    fun `cloze labels fallback keeps size`() {
        assertEquals(listOf("B", ""), CorrectAnswerParser.clozeLabels("""["B"]""", 2))
    }

    @Test
    fun `ordering sequence from comma text`() {
        assertEquals(listOf("3", "1", "2"), CorrectAnswerParser.orderingSequence("3, 1, 2"))
    }
}

class SubQuestionParserTest {

    private fun question(content: String, passage: String? = null) = QuestionDto(
        bankId = "math1",
        questionId = 1,
        typeCode = "FILL_BLANK",
        content = content,
        passage = passage
    )

    @Test
    fun `blank count falls back to underscores`() {
        assertEquals(2, SubQuestionParser.blankCount(question("求 ____ 与 ____ 的值")))
        assertEquals(1, SubQuestionParser.blankCount(question("无占位")))
    }

    @Test
    fun `options parse from object shape`() {
        val obj = Json.parseToJsonElement("""{"options":{"A":"1","B":"2"}}""").jsonObject
        val options = SubQuestionParser.optionsOf(obj)!!
        assertEquals(listOf("A", "B"), options.map { it.label })
    }

    @Test
    fun `options parse from array shape`() {
        val obj = Json.parseToJsonElement(
            """{"options":[{"label":"A","text":"$..$"},{"label":"B","text":""}]}"""
        ).jsonObject
        val options = SubQuestionParser.optionsOf(obj)!!
        assertEquals(2, options.size)
        assertEquals(OptionDto("A", "$..$"), options[0])
    }
}
