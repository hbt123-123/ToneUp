/** 错题本跨端同步 API 客户端 (T4)
 *
 *  新增端点封装，不修改现有 stores/wrongbook.ts 的 public API 签名。
 *  后端契约：POST /api/wrong-questions/sync，接收 { items: WrongQuestionItem[] }
 */
import { request } from './http'

/* ---------- 类型 ---------- */

/**
 * 同步/上传请求体条目（H-117）：id 由服务端生成，离线上传时不存在，
 * 请求与响应拆分为两个类型，避免请求体被迫伪造 id: 0。
 */
export interface WrongQuestionSyncItem {
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

export interface WrongQuestionItem extends WrongQuestionSyncItem {
  /** 错题记录 ID（后端生成，DELETE /wrong-questions/{id} 的路径参数） */
  id: number
  /** 用户 ID（由 auth token 推断，前端不传） */
  user_id?: string
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
  // M-371：改用 !== undefined 判断，避免 page: 0 / pageSize: 0 等合法 falsy 值被静默丢弃
  //（空串等无效值仍由 http.buildUrl 过滤）
  if (params.bankId !== undefined) query.bank_id = params.bankId
  if (params.subjectId !== undefined) query.subject_id = params.subjectId
  if (params.page !== undefined) query.page = params.page
  if (params.pageSize !== undefined) query.page_size = params.pageSize
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

/** M-372：单次 sync 请求的条目上限，超出自动分块顺序上传 */
const SYNC_CHUNK_SIZE = 100

/**
 * 批量同步错题（离线队列上传）
 * POST /api/wrong-questions/sync
 * M-372：补齐上传防护——空数组短路返回；按 (bank_id, question_id) 去重；
 * 显式构造请求体，剥离服务端专属字段（id/user_id，防止调用方传入 WrongQuestionItem 时一并上送）；
 * 超过上限自动分块并聚合结果
 */
export async function syncWrongQuestions(items: WrongQuestionSyncItem[]): Promise<SyncResult> {
  if (items.length === 0) return { synced: 0, skipped: 0, errors: [] }
  const seen = new Set<string>()
  const payload: WrongQuestionSyncItem[] = []
  for (const it of items) {
    const key = `${it.bank_id}\u0000${it.question_id}`
    if (seen.has(key)) continue
    seen.add(key)
    payload.push({
      bank_id: it.bank_id,
      question_id: it.question_id,
      attempt_count: it.attempt_count,
      last_wrong_at: it.last_wrong_at,
      tags: it.tags,
      ...(it.preview !== undefined ? { preview: it.preview } : {}),
    })
  }
  const merged: SyncResult = { synced: 0, skipped: 0, errors: [] }
  for (let i = 0; i < payload.length; i += SYNC_CHUNK_SIZE) {
    const res = await request<SyncResult>('/wrong-questions/sync', {
      method: 'POST',
      json: { items: payload.slice(i, i + SYNC_CHUNK_SIZE) },
    })
    merged.synced += res.synced
    merged.skipped += res.skipped
    merged.errors.push(...res.errors)
  }
  return merged
}