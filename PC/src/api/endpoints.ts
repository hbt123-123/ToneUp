import { request, requestForm } from './http'
import type {
  AdminReloadSummary,
  AiFeedbackTask,
  AttemptResult,
  BankDetail,
  CatalogData,
  CurrentUser,
  HealthTask,
  LoginResult,
  NoteData,
  QuestionDetailDto,
  QuestionListData,
  ReviewTodayData,
  SkipResult,
  StatsOverview,
  SubmitAttemptBody,
  WeaknessListData,
} from './generated/schema'

/* ---------- 认证（M1） ---------- */

export function apiRegister(username: string, password: string): Promise<{ id?: number }> {
  return request('/auth/register', { method: 'POST', json: { username, password } })
}

export function apiLogin(username: string, password: string): Promise<LoginResult> {
  return request('/auth/login', { method: 'POST', json: { username, password } })
}

export function apiMe(): Promise<CurrentUser> {
  return request('/auth/me')
}

/* ---------- 目录与题库（M1） ---------- */

export function apiCatalog(signal?: AbortSignal): Promise<CatalogData> {
  return request('/catalog', { signal })
}

export function apiBankDetail(bankId: string, signal?: AbortSignal): Promise<BankDetail> {
  return request(`/question-banks/${encodeURIComponent(bankId)}`, { signal })
}

export interface QuestionListQuery {
  [key: string]: unknown
  year?: number
  type_code?: string
  page?: number
  page_size?: number
}

/** 分页拉题：列表不含 answer/solution */
export function apiQuestionList(
  bankId: string,
  query: QuestionListQuery = {},
  signal?: AbortSignal,
): Promise<QuestionListData> {
  return request(`/question-banks/${encodeURIComponent(bankId)}/questions`, { query, signal })
}

/** 题目详情：按权限含答案/解析；也用于相邻题预取 */
export function apiQuestionDetail(bankId: string, questionId: number, signal?: AbortSignal): Promise<QuestionDetailDto> {
  return request(`/question-banks/${encodeURIComponent(bankId)}/questions/${questionId}`, { signal })
}

/* ---------- 图片（M1）：二进制走 LazyImage 的 fetch，不在此封装 ---------- */

export function imageUrl(imageId: string | number, bankId: string): string {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api'
  return `${base}/images/${imageId}?bank_id=${encodeURIComponent(bankId)}`
}

/* ---------- 答题（M1） ---------- */

export function apiSubmitAttempt(body: SubmitAttemptBody): Promise<AttemptResult> {
  return request('/attempts', { method: 'POST', json: body })
}

export function apiAttemptResult(attemptId: number, signal?: AbortSignal): Promise<AttemptResult> {
  return request(`/attempts/${attemptId}`, { signal })
}

/* ---------- 复习（M2） ---------- */

export function apiReviewsToday(query: { limit?: number; subject_id?: string } = {}, signal?: AbortSignal): Promise<ReviewTodayData> {
  return request('/reviews/today', { query, signal })
}

export function apiSkipReview(questionId: number, bankId: string, nextReviewAt?: string): Promise<SkipResult> {
  return request(`/reviews/${questionId}/skip`, {
    method: 'POST',
    json: { bank_id: bankId, ...(nextReviewAt ? { next_review_at: nextReviewAt } : {}) },
  })
}

/* ---------- 统计（M2） ---------- */

export function apiStatsOverview(
  query: { from?: string; to?: string; subject_id?: string } = {},
  signal?: AbortSignal,
): Promise<StatsOverview> {
  return request('/stats/overview', { query, signal })
}

export function apiStatsWeaknesses(
  query: { subject_id?: string; limit?: number } = {},
  signal?: AbortSignal,
): Promise<WeaknessListData> {
  return request('/stats/weaknesses', { query, signal })
}

/* ---------- 错题本（M2）：FR-WRONG-01 约定——契约未提供专用查询端点，
   数据来源为本地缓存的服务端判分结果（见 stores/wrongbook），不引入表外路径 ---------- */

/* ---------- 笔记（M2） ---------- */

export function apiGetNote(questionId: number, bankId: string, signal?: AbortSignal): Promise<NoteData | null> {
  return request(`/questions/${questionId}/notes`, { query: { bank_id: bankId }, signal })
}

export function apiPutNote(questionId: number, bankId: string, noteText: string): Promise<NoteData> {
  return request(`/questions/${questionId}/notes`, {
    method: 'PUT',
    json: { bank_id: bankId, note_text: noteText },
  })
}

/* ---------- AI 反馈（M3） ---------- */

export interface AiFeedbackUploadParams {
  file: File
  bankId?: string
  questionId?: number
  attemptId?: number
}

export function apiCreateAiFeedback(params: AiFeedbackUploadParams): Promise<AiFeedbackTask> {
  const form = new FormData()
  form.append('file', params.file)
  if (params.bankId) form.append('bank_id', params.bankId)
  if (params.questionId !== undefined) form.append('question_id', String(params.questionId))
  if (params.attemptId !== undefined) form.append('attempt_id', String(params.attemptId))
  return requestForm<AiFeedbackTask>('/ai/feedback', form)
}

export function apiAiFeedbackStatus(feedbackId: string, signal?: AbortSignal): Promise<AiFeedbackTask> {
  return request(`/ai/feedback/${encodeURIComponent(feedbackId)}`, { signal })
}

/* ---------- 管理（M3，admin only） ---------- */

export function apiAdminReloadCatalog(): Promise<AdminReloadSummary> {
  return request('/admin/catalog/reload', { method: 'POST' })
}

export function apiAdminHealthStart(bankId?: string): Promise<HealthTask> {
  return request('/admin/health', { query: { bank_id: bankId } })
}

export function apiAdminHealthStatus(taskId: string, signal?: AbortSignal): Promise<HealthTask> {
  return request(`/admin/health/${encodeURIComponent(taskId)}`, { signal })
}
