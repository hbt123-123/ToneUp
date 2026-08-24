package com.toneup.app.ui.components.question

import androidx.compose.runtime.Composable
import com.toneup.app.domain.model.QuestionType
import com.toneup.app.ui.feature.practice.renderers.ClozeRenderer
import com.toneup.app.ui.feature.practice.renderers.EssayRenderer
import com.toneup.app.ui.feature.practice.renderers.FillBlankRenderer
import com.toneup.app.ui.feature.practice.renderers.FallbackRenderer
import com.toneup.app.ui.feature.practice.renderers.JudgeRenderer
import com.toneup.app.ui.feature.practice.renderers.MultiRenderer
import com.toneup.app.ui.feature.practice.renderers.OrderingRenderer
import com.toneup.app.ui.feature.practice.renderers.ReadingRenderer
import com.toneup.app.ui.feature.practice.renderers.SingleRenderer
import com.toneup.app.ui.feature.practice.renderers.SolutionRenderer
import com.toneup.app.ui.feature.practice.renderers.TranslationRenderer

/**
 * 题型渲染注册表（§6.1）：
 * 键为密封类 [QuestionType]，值为渲染 Composable；键名与 type_code 完全一致。
 * 新增题型 = 新增密封子项 + 渲染器 + 注册一行。
 */
object RendererRegistry {

    private val renderers: Map<QuestionType, @Composable (QuestionContext) -> Unit> = mapOf(
        QuestionType.Single to { SingleRenderer(it) },
        QuestionType.Multi to { MultiRenderer(it) },
        QuestionType.Judge to { JudgeRenderer(it) },
        QuestionType.FillBlank to { FillBlankRenderer(it) },
        QuestionType.Solution to { SolutionRenderer(it) },
        QuestionType.Cloze to { ClozeRenderer(it) },
        QuestionType.Reading to { ReadingRenderer(it) },
        QuestionType.Ordering to { OrderingRenderer(it) },
        QuestionType.Translation to { TranslationRenderer(it) },
        QuestionType.Essay to { EssayRenderer(it) }
    )

    /** 注册表中不存在的 type_code 返回 null，调用方渲染降级卡（§6.4） */
    fun rendererFor(typeCode: String): (@Composable (QuestionContext) -> Unit)? =
        QuestionType.fromCode(typeCode)?.let { renderers[it] }

    /**
     * 启动完整性校验：每个密封子项必须在注册表恰好有一个渲染器。
     * 缺失或重复时返回含 typeCode 的 ERROR 文案列表。
     */
    fun validateIntegrity(): List<String> {
        val problems = mutableListOf<String>()
        val registeredCodes = mutableMapOf<String, Int>()
        renderers.keys.forEach { type ->
            registeredCodes[type.typeCode] = registeredCodes.getOrDefault(type.typeCode, 0) + 1
        }
        QuestionType.all.forEach { type ->
            val count = registeredCodes.getOrDefault(type.typeCode, 0)
            if (count != 1) {
                problems += "RendererRegistry missing: ${type.typeCode} (found $count)"
            }
            registeredCodes.remove(type.typeCode)
        }
        registeredCodes.keys.forEach { extra ->
            problems += "RendererRegistry unknown key not in sealed class: $extra"
        }
        return problems
    }
}
