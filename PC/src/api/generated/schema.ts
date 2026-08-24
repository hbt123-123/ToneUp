/**
 * 契约类型快照（临时）。
 *
 * 来源：《backend/后端目标需求文档.md》V1.0 §6 接口契约 + §4.4 统一题目 DTO。
 * 后端 OpenAPI 可用后，用 openapi-typescript 生成的 schema.gen.ts 替换本文件，
 * 本文件随即删除。字段一律可容忍缺失（可选），客户端不做服务端校验职责之外的假设。
 */

export type TypeCode =
  | 'SINGLE'
  | 'MULTI'
  | 'JUDGE'
  | 'FILL_BLANK'
  | 'SOLUTION'
  | 'CLOZE'
  | 'ORDERING'
  | 'READING'
  | 'TRANSLATION'
  | 'ESSAY'

/** 后端契约 §4.4 统一题目 DTO */
export interface OptionItem {
  label: string
  text: string
}

export interface QuestionDto {
  bank_id: string
  question_id: number
  collection_id: number
  year: number
  type_code: string
  number: number
  content: string
  passage?: string | null
  options?: OptionItem[] | null
  sub_questions?: unknown[] | null
  display_order: number
}

/** 详情接口在列表 DTO 基础上追加答案与解析（授权出口） */
export interface QuestionDetailDto extends QuestionDto {
  answer_text?: string | null
  solution?: string | null
  score?: number | null
}

export interface PageMeta {
  page?: number
  page_size?: number
  total?: number
  has_more?: boolean
}

export interface QuestionListData extends PageMeta {
  items: QuestionDto[]
}

/* ---------- 认证 ---------- */

export interface LoginResult {
  access_token: string
  token_type?: string
  expires_in?: number
}

export interface CurrentUser {
  id: number
  username: string
  role: 'user' | 'admin' | string
}

/* ---------- 目录 ---------- */

export interface SubjectTypeNode {
  id: string
  name: string
  desc?: string
}

export interface SubjectNode {
  id: string
  name: string
  icon?: string
  types: SubjectTypeNode[]
}

export interface BankSummary {
  id: string
  subject_id: string
  type_id?: string
  name: string
  enabled?: boolean
}

export interface CatalogData {
  subjects: SubjectNode[]
  banks?: BankSummary[]
}

export interface BankTypeDistribution {
  type_code: TypeCode | string
  count?: number
  label?: string
}

export interface BankDetail {
  id: string
  name: string
  subject_id?: string
  type_id?: string
  enabled?: boolean
  years?: number[]
  year_min?: number
  year_max?: number
  question_total?: number
  type_distribution?: BankTypeDistribution[]
}

/* ---------- 答题 ---------- */

export type AttemptMode = 'practice' | 'review' | 'self_judge'

export type GradingStatus = 'queued' | 'pending' | 'processing' | 'succeeded' | 'failed'

export interface AttemptFeedback {
  is_correct?: boolean | null
  score?: number | null
  error_reason?: string | null
  comment?: string | null
  tag_ids?: number[] | null
  tags?: { id: number; name?: string }[] | null
}

export interface AttemptResult {
  attempt_id: number
  /** 客观题同步出结果时为 true/false；主观题待判分为 null/缺省 */
  is_correct?: boolean | null
  score?: number | null
  max_score?: number | null
  /** 主观题异步判分状态载体（§8.2）：queued/pending/processing/succeeded/failed */
  grading_status?: GradingStatus | null
  status?: GradingStatus | null
  feedback?: AttemptFeedback | null
  answer_text?: string | null
  solution?: string | null
}

export interface SubmitAttemptBody {
  bank_id: string
  question_id: number
  /** 结构随题型：标签 / 标签数组 / 字符串 / 长文本 / 自评结论对象 */
  answer: unknown
  time_spent: number
  mode: AttemptMode
  client_request_id: string
}

/* ---------- 复习 ---------- */

export interface ReviewItem extends QuestionDto {
  confidence_level?: number
  next_review_at?: string | null
}

export interface ReviewTodayData extends PageMeta {
  items: ReviewItem[]
}

export interface SkipResult {
  question_id?: number
  bank_id?: string
  next_review_at?: string | null
}

/* ---------- 统计 ---------- */

export interface StatsOverview {
  accuracy_rate?: number
  total_attempts?: number
  streak_days?: number
  today_attempts?: number
  today_goal?: number
  due_review_count?: number
}

export interface WeaknessItem {
  subject_id?: string
  subject_name?: string
  type_code?: string
  bank_id?: string
  tag_id?: number
  tag_name?: string
  attempts?: number
  correct_rate?: number
  accuracy_rate?: number
  wrong_count?: number
}

export interface WeaknessListData extends PageMeta {
  items: WeaknessItem[]
}

/* ---------- 笔记 ---------- */

export interface NoteData {
  bank_id?: string
  question_id?: number
  note_text: string
  updated_at?: string
}

/* ---------- 错题本 ---------- */

export interface WrongBookItem {
  bank_id: string
  question_id: number
  content?: string
  year?: number
  type_code?: string
  wrong_count?: number
  total_attempts?: number
  last_practice_at?: string
  preview?: string
}

export interface WrongBookData extends PageMeta {
  items: WrongBookItem[]
}

/* ---------- AI 反馈 ---------- */

export type AiFeedbackStatus = 'queued' | 'pending' | 'processing' | 'succeeded' | 'failed'

export interface AiFeedbackTask {
  feedback_id: string
  status: AiFeedbackStatus
  is_correct?: boolean | null
  error_reason?: string | null
  comment?: string | null
  tag_ids?: number[] | null
  tags?: { id: number; name?: string }[] | null
  error_message?: string | null
  created_at?: string
  completed_at?: string
}

/* ---------- 管理 ---------- */

export interface AdminReloadSummary {
  enabled_banks?: number
  disabled_banks?: number
  warnings?: string[]
  reloaded_at?: string
}

export interface HealthIssue {
  bank_id?: string
  level?: 'error' | 'warning' | string
  code?: string
  message: string
}

export interface HealthTask {
  task_id: string
  status?: AiFeedbackStatus | 'running' | 'done'
  ok?: boolean
  summary?: string
  checked_banks?: number
  issues?: HealthIssue[]
}
