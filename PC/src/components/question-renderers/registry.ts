import type { Component } from 'vue'
import SingleChoiceRenderer from './SingleChoiceRenderer.vue'
import MultiChoiceRenderer from './MultiChoiceRenderer.vue'
import JudgeRenderer from './JudgeRenderer.vue'
import FillBlankRenderer from './FillBlankRenderer.vue'
import SolutionRenderer from './SolutionRenderer.vue'
import ClozeRenderer from './ClozeRenderer.vue'
import OrderingRenderer from './OrderingRenderer.vue'
import TranslationRenderer from './TranslationRenderer.vue'
import EssayRenderer from './EssayRenderer.vue'
import UnknownTypeRenderer from './UnknownTypeRenderer.vue'
import { CONTRACT_TYPE_CODES } from './types'

/**
 * 题型渲染注册表（§6.1）：
 * 键为后端契约 type_code，逐字符一致；工作台通过 resolveRenderer 动态挂载。
 */
export const RENDERER_REGISTRY: Record<string, Component> = {
  SINGLE: SingleChoiceRenderer,
  READING: SingleChoiceRenderer, // 英语阅读单选按单选渲染（§6.3）
  MULTI: MultiChoiceRenderer,
  JUDGE: JudgeRenderer,
  FILL_BLANK: FillBlankRenderer,
  SOLUTION: SolutionRenderer,
  CLOZE: ClozeRenderer,
  ORDERING: OrderingRenderer,
  TRANSLATION: TranslationRenderer,
  ESSAY: EssayRenderer,
}

export function resolveRenderer(typeCode: string): Component {
  return RENDERER_REGISTRY[typeCode] ?? UnknownTypeRenderer
}

/** 供工作台识别未知题型（渲染分支用） */
export { UnknownTypeRenderer }

/**
 * 启动校验（§6.1）：main.ts 在路由挂载前调用。
 * 开发环境失败阻断启动；生产输出 error 日志并以降级组件兜底。
 */
export function validateRegistry(env: 'development' | 'production' = import.meta.env.MODE === 'production' ? 'production' : 'development'): void {
  const registered = Object.keys(RENDERER_REGISTRY)
  const expected: string[] = [...CONTRACT_TYPE_CODES]

  // 1. 重复注册检测（合并/展开可能引入）
  const seen = new Set<string>()
  const duplicated: string[] = []
  for (const key of registered) {
    if (seen.has(key)) duplicated.push(key)
    seen.add(key)
  }

  // 2. 缺失检测：契约枚举中存在但注册表没有
  const missing = expected.filter((code) => !seen.has(code))

  // 3. 多余键检测：不在契约枚举中的自定义别名一律视为非法
  const unknownKeys = registered.filter((key) => !expected.includes(key))
  const problems: string[] = []
  if (duplicated.length > 0) problems.push(`重复注册的题型键：${duplicated.join(', ')}`)
  if (missing.length > 0) problems.push(`缺失注册的题型键：${missing.join(', ')}`)
  if (unknownKeys.length > 0) problems.push(`契约之外的题型键（禁止自定义别名）：${unknownKeys.join(', ')}`)

  if (problems.length > 0) {
    const detail = `题型渲染注册表校验失败 —— ${problems.join('；')}`
    if (env === 'development') {
      throw new Error(detail)
    }
    console.error(`[ToneUp] ${detail}（生产模式：未知题型将以降级组件渲染）`)
  }
}
