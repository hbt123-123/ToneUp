import type { AttemptFeedback, QuestionDto } from '@/api/generated/schema'

/** 主观题异步判分状态载荷（§6.2 / D-02） */
export interface GradingView {
  status: 'queued' | 'pending' | 'processing' | 'succeeded' | 'failed'
  feedback?: AttemptFeedback | null
}

/**
 * QuestionContext 契约（§6.2）：
 * 所有题型组件只通过该接口接收数据与回调，
 * 不直接调用任何 API，不感知路由与持久化。
 * 输入合法性校验在组件内完成；提交调度统一在工作台。
 */
export interface QuestionContext {
  /** 统一题目 DTO，字段以后端契约为准 */
  question: QuestionDto
  /** 当前用户答案（受控读写），结构随题型不同 */
  answer: unknown
  /** 只读态：已提交或复盘场景 */
  readonly: boolean
  /** 禁用态：submitting 等互斥期间禁用输入 */
  disabled: boolean
  /** 展示正确答案（配合解析视图） */
  showAnswer: boolean
  /** 展示解析（配合解析视图） */
  showAnalysis: boolean
  /** 主观题判分状态；客观题为 null */
  grading: GradingView | null
  /** 答案变更上报，工作台负责草稿持久化与状态迁移 */
  onAnswerChange: (answer: unknown) => void
  /** 请求提交，由工作台统一调度幂等与状态机 */
  onSubmitRequest: () => void
}

/** 启动注册表校验所需的契约枚举（与后端 type_code 一致；READING 为英语阅读单选） */
export const CONTRACT_TYPE_CODES = [
  'SINGLE',
  'MULTI',
  'JUDGE',
  'FILL_BLANK',
  'SOLUTION',
  'CLOZE',
  'ORDERING',
  'TRANSLATION',
  'ESSAY',
  'READING',
] as const

/** 客观题：提交即出结果 */
export const OBJECTIVE_TYPES: ReadonlySet<string> = new Set([
  'SINGLE',
  'MULTI',
  'JUDGE',
  'READING',
  'CLOZE',
  'ORDERING',
])

/** 主观题：AI 判分为主 + 自评兜底（D-02） */
export const SUBJECTIVE_TYPES: ReadonlySet<string> = new Set([
  'FILL_BLANK',
  'SOLUTION',
  'TRANSLATION',
  'ESSAY',
])

export function isObjectiveType(typeCode: string): boolean {
  return OBJECTIVE_TYPES.has(typeCode)
}
