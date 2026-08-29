/** 错题本跨端同步 API 客户端 (T4)
 *
 *  新增端点封装，不修改现有 stores/wrongbook.ts 的 public API 签名。
 *  后端契约：POST /api/wrong-questions/sync，接收 { items: WrongQuestionItem[] }
 */
import { request } from './http'

/* ---------- 类型 ---------- */

export interface WrongQuestionItem {
  /** 题目全局唯一 ID（后端生成） */
  id: number
  /** 用户 ID（由 auth token 推断，前端不传） */
  user_id?: string
  /** 所属题库 ID */
  bank_id: string
  /** 题目 ID（题库内唯一） */
  question_id: number
  /** 错误次数 */
  attempt_count: number
  /** 最近错误时间 ISO 8601 */
  last_wrong_at: string
  /** 标签 JSON 数组 */
  tags: string[]
  /** 题目预览文本（用于错题本列表展示） */
  preview?: string
}

export interface SyncResult {
  synced: number
  skipped: number
  errors: string[]
}

/* ---------- API 函数 ---------- */

/**
 * 拉取当前用户的错题列表
 * GET /api/wrong-questions?bank_id=&subject_id=&page=&page_size=
 */
export function fetchWrongQuestions(
  params: { bankId?: string; subjectId?: string; page?: number; pageSize?: number } = {},
  signal?: AbortSignal,
): Promise<{ items: WrongQuestionItem[]; total: number; page: number; page_size: number }> {
  const query: Record<string, unknown> = {}
  if (params.bankId) query.bank_id = params.bankId
  if (params.subjectId) query.subject_id = params.subjectId
  if (params.page) query.page = params.page
  if (params.pageSize) query.page_size = params.pageSize
  return request('/wrong-questions', { query, signal })
}

/**
 * 手动添加一道错题
 * POST /api/wrong-questions
 */
export function addWrongQuestion(
  bankId: string,
  questionId: number,
  preview?: string,
): Promise<WrongQuestionItem> {
  return request('/wrong-questions', {
    method: 'POST',
    json: {
      bank_id: bankId,
      question_id: questionId,
      ...(preview ? { preview } : {}),
    },
  })
}

/**
 * 删除一道错题（标记掌握后调用）
 * DELETE /api/wrong-questions/{id}
 */
export function removeWrongQuestion(id: number): Promise<void> {
  return request(`/wrong-questions/${id}`, { method: 'DELETE' })
}

/**
 * 批量同步错题（离线队列上传）
 * POST /api/wrong-questions/sync
 */
export function syncWrongQuestions(items: WrongQuestionItem[]): Promise<SyncResult> {
  return request('/wrong-questions/sync', {
    method: 'POST',
    json: { items },
  })
}
