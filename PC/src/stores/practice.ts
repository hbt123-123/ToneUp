import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import {
  apiAttemptResult,
  apiQuestionDetail,
  apiQuestionList,
  apiSubmitAttempt,
} from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { AttemptResult, GradingStatus, QuestionDto } from '@/api/generated/schema'
import {
  clearDraft,
  dequeueUnsubmitted,
  enqueueUnsubmitted,
  readDraft,
  readMarked,
  readProgress,
  readUnsubmitted,
  writeDraft,
  writeMarked,
  writeProgress,
} from '@/utils/storage'
import { hashString, uuidV4 } from '@/utils/uuid'

/**
 * practice store（§2.4 / 第 8 章）：
 * - 每题独立状态机 loading/idle/editing/submitting/submitted/error（§8.1）
 * - client_request_id 幂等（§8.3）：进入 submitting 固化，网络错误沿用，成功或业务失败后释放
 * - 草稿防抖 500ms 落盘 + 强制 flush；进度与标记实时持久化（§8.4）
 * - 断网续答：失败入未提交队列，联网后以原幂等键重放（FR-PRAC-12）
 */

export type QuestionPhase = 'loading' | 'idle' | 'editing' | 'submitting' | 'submitted' | 'error'

export interface SessionItem {
  bankId: string
  questionId: number
}

export interface QuestionRuntime {
  phase: QuestionPhase
  listMeta: QuestionDto | null
  detail: QuestionDto | null
  answer: unknown
  attempt: AttemptResult | null
  grading: GradingStatus | null
  /** 提交失败保留的幂等键：重试沿用同一 id */
  pendingRequestId: string | null
  errorMessage: string | null
  /** 待恢复草稿：进入题目时检测到非空草稿（FR-PRAC-07） */
  pendingDraft: unknown | null
  /** 自评兜底入口可见（failed 或超时，§8.2） */
  selfJudgeReady: boolean
  timeSpent: number
}

const DRAFT_DEBOUNCE_MS = 500
/** 轮询退避：起始 2s，逐步退避至 10s（§8.2 / FR-AI-03 同节奏） */
const POLL_START_MS = 2000
const POLL_MAX_MS = 10000
/** 超时兜底阈值（§8.2）：pending>60s 或 processing>300s 展示自评入口 */
const PENDING_SELF_JUDGE_MS = 60_000
const PROCESSING_SELF_JUDGE_MS = 300_000
/** LRU 列表会话缓存上限（§8.5） */
const MAX_LIST_SESSIONS = 20

function qKey(bankId: string, questionId: number): string {
  return `${bankId}:${questionId}`
}

export interface StartBankOptions {
  bankId: string
  year?: number | null
  typeCode?: string | null
  pageSize?: number
}

export const usePracticeStore = defineStore('practice', () => {
  /* ---------- 会话 ---------- */
  const sessionKind = ref<'bank' | 'review'>('bank')
  const bankId = ref('')
  const year = ref<number | null>(null)
  const typeCode = ref<string | null>(null)

  const orderedIds = ref<number[]>([])
  const itemBanks = ref<Map<number, string>>(new Map())

  const currentIndex = ref(-1)
  const listLoading = ref(false)
  const listError = ref<string | null>(null)
  const total = ref(0)
  const hasMore = ref(false)
  const nextPage = ref(1)
  const pageSize = ref(20)

  const runtimes = reactive(new Map<string, QuestionRuntime>())
  /** 题目详情缓存（含相邻题预取结果） */
  const detailCache = reactive(new Map<string, QuestionDto>())
  const markedIds = ref<Set<number>>(new Set())

  const currentKey = computed(() => {
    if (currentIndex.value < 0 || currentIndex.value >= orderedIds.value.length) return null
    const id = orderedIds.value[currentIndex.value]!
    return qKey(itemBanks.value.get(id) ?? bankId.value, id)
  })
  const currentRuntime = computed(() => (currentKey.value ? runtimes.get(currentKey.value) ?? null : null))

  /* ---------- 内部工具 ---------- */

  let userIdProvider: () => number | string = () => -1
  function bindUserId(provider: () => number | string): void {
    userIdProvider = provider
  }

  function runtimeFor(key: string): QuestionRuntime {
    let rt = runtimes.get(key)
    if (!rt) {
      rt = {
        phase: 'loading',
        listMeta: null,
        detail: null,
        answer: null,
        attempt: null,
        grading: null,
        pendingRequestId: null,
        errorMessage: null,
        pendingDraft: null,
        selfJudgeReady: false,
        timeSpent: 0,
      }
      runtimes.set(key, rt)
    }
    return rt
  }

  /** §8.5 缓存键 = bankId + 年份 + 题型（缺一不可，防止跨题库命中同一哈希） */
  function filterHash(): string {
    return hashString(JSON.stringify({ b: bankId.value, y: year.value ?? null, t: typeCode.value ?? null }))
  }

  /* ---------- 题目列表：分页窗口 + LRU 缓存（§8.5，键=filterHash） ---------- */

  interface ListSession {
    ids: number[]
    banks: Map<number, string>
    total: number
    hasMore: boolean
    nextPage: number
  }

  const listSessions = new Map<string, ListSession>()

  function rememberListSession(session: ListSession): void {
    const hash = filterHash()
    if (listSessions.has(hash)) listSessions.delete(hash)
    listSessions.set(hash, { ...session, banks: new Map(session.banks) })
    if (listSessions.size > MAX_LIST_SESSIONS) {
      const oldest = listSessions.keys().next().value
      if (oldest !== undefined) listSessions.delete(oldest)
    }
  }

  async function appendPage(page: number): Promise<void> {
    const data = await apiQuestionList(bankId.value, {
      year: year.value ?? undefined,
      type_code: typeCode.value ?? undefined,
      page,
      page_size: pageSize.value,
    })
    const items = data.items ?? []
    for (const dto of items) {
      if (!orderedIds.value.includes(dto.question_id)) {
        orderedIds.value.push(dto.question_id)
        itemBanks.value.set(dto.question_id, dto.bank_id || bankId.value)
        const key = qKey(dto.bank_id || bankId.value, dto.question_id)
        const rt = runtimeFor(key)
        if (!rt.listMeta) {
          rt.listMeta = dto
          if (!rt.detail) rt.detail = dto
        }
      }
    }
    total.value = typeof data.total === 'number' ? data.total : orderedIds.value.length
    hasMore.value = data.has_more !== false && items.length > 0
    nextPage.value = page + 1
    rememberListSession({
      ids: [...orderedIds.value],
      banks: new Map(itemBanks.value),
      total: total.value,
      hasMore: hasMore.value,
      nextPage: nextPage.value,
    })
  }

  async function ensureWindow(index: number): Promise<void> {
    while (index >= orderedIds.value.length && hasMore.value) {
      await appendPage(nextPage.value)
    }
  }

  /* ---------- 详情加载 ---------- */

  async function loadDetailInto(key: string, force = false): Promise<void> {
    const [bId, qIdStr] = key.split(':')
    const qId = Number(qIdStr)
    const rt = runtimeFor(key)
    if (!force && rt.detail) return
    try {
      const detail = detailCache.get(key) ?? (await apiQuestionDetail(bId!, qId))
      detailCache.set(key, detail)
      rt.detail = detail
      if (rt.listMeta === null) rt.listMeta = detail
      if (rt.phase === 'loading') rt.phase = 'idle'
    } catch (err) {
      rt.errorMessage = err instanceof Error ? err.message : String(err)
      rt.phase = 'error'
    }
  }

  /** 相邻题空闲预取（FR-PRAC-06）：不预取图片 */
  function prefetchNeighbors(index: number): void {
    const idle = (cb: () => void): void => {
      if ('requestIdleCallback' in window) requestIdleCallback(cb, { timeout: 1500 })
      else setTimeout(cb, 350)
    }
    idle(() => {
      for (const offset of [-1, 1]) {
        const idx = index + offset
        if (idx < 0 || idx >= orderedIds.value.length) continue
        const id = orderedIds.value[idx]!
        const b = itemBanks.value.get(id) ?? bankId.value
        const key = qKey(b, id)
        if (detailCache.has(key)) continue
        const rt = runtimes.get(key)
        if (rt?.detail) continue
        apiQuestionDetail(b, id).then((d) => {
          detailCache.set(key, d)
          const target = runtimes.get(key)
          if (target && !target.detail) target.detail = d
        }).catch(() => {/* 预取失败静默 */})
      }
    })
  }

  /* ---------- 计时 ---------- */

  let enterTime = 0
  function accumulateCurrentTime(): void {
    const rt = currentRuntime.value
    if (!rt || !enterTime) return
    rt.timeSpent += Math.round((Date.now() - enterTime) / 1000)
    enterTime = Date.now()
  }

  /* ---------- 切题 ---------- */

  function persistProgress(): void {
    if (!bankId.value || sessionKind.value !== 'bank') return
    const uid = userIdProvider()
    if (uid === -1) return
    writeProgress(uid, bankId.value, {
      lastIndex: Math.max(0, currentIndex.value),
      filterHash: filterHash(),
    })
  }

  async function gotoIndex(index: number, opts: { skipEnsure?: boolean } = {}): Promise<void> {
    accumulateCurrentTime()
    flushDraftFor(currentKey.value)
    if (!opts.skipEnsure) await ensureWindow(index)
    if (index < 0 || index >= orderedIds.value.length) return
    currentIndex.value = index
    const id = orderedIds.value[index]!
    const b = itemBanks.value.get(id) ?? bankId.value
    const key = qKey(b, id)
    const rt = runtimeFor(key)

    // 已提交或进行中的题保留现场不闪烁；否则进入加载态并尝试详情
    if (!rt.detail && rt.phase !== 'submitted') rt.phase = 'loading'
    enterTime = Date.now()

    // 草稿检测（FR-PRAC-07）：仅未提交的题提示恢复
    const uid = userIdProvider()
    if (uid !== -1 && rt.phase !== 'submitted') {
      const draft = readDraft(uid, b, id)
      rt.pendingDraft = draft ? draft.answer : null
    }
    void loadDetailInto(key).then(() => {
      if (currentIndex.value === index) prefetchNeighbors(index)
    })
    persistProgress()
  }

  /** 定位到指定题目（错题重练/笔记跳转，FR-WRONG-03 / FR-NOTE-03）：按需加载后续页 */
  async function gotoQuestion(questionId: number): Promise<boolean> {
    let idx = orderedIds.value.indexOf(questionId)
    while (idx === -1 && hasMore.value) {
      await appendPage(nextPage.value)
      idx = orderedIds.value.indexOf(questionId)
    }
    if (idx === -1) return false
    await gotoIndex(idx)
    return true
  }

  async function next(): Promise<boolean> {
    if (currentIndex.value + 1 >= orderedIds.value.length && !hasMore.value) return false
    await gotoIndex(currentIndex.value + 1)
    return true
  }

  async function prev(): Promise<void> {
    if (currentIndex.value > 0) await gotoIndex(currentIndex.value - 1)
  }

  /* ---------- 作答与草稿 ---------- */

  let draftTimers = new Map<string, ReturnType<typeof setTimeout>>()

  function scheduleDraftPersist(key: string): void {
    const existing = draftTimers.get(key)
    if (existing) clearTimeout(existing)
    draftTimers.set(
      key,
      setTimeout(() => {
        draftTimers.delete(key)
        flushDraftFor(key)
      }, DRAFT_DEBOUNCE_MS),
    )
  }

  function flushDraftFor(key: string | null): void {
    if (!key) return
    const timer = draftTimers.get(key)
    if (timer) {
      clearTimeout(timer)
      draftTimers.delete(key)
    }
    const rt = runtimes.get(key)
    if (!rt || rt.phase === 'submitted') return
    const [bId, qIdStr] = key.split(':')
    const uid = userIdProvider()
    if (uid === -1 || rt.answer === null || rt.answer === undefined) return
    writeDraft(uid, bId!, Number(qIdStr), rt.answer)
  }

  function setAnswer(answer: unknown): void {
    const rt = currentRuntime.value
    const key = currentKey.value
    if (!rt || !key) return
    if (rt.phase === 'submitted' || rt.phase === 'submitting') return
    rt.answer = answer
    if (rt.phase === 'idle' || rt.phase === 'loading') rt.phase = 'editing'
    if (rt.pendingDraft !== null) rt.pendingDraft = null
    scheduleDraftPersist(key)
  }

  /** 恢复草稿（用户确认后调用）；拒绝则清除该题草稿 */
  function resolvePendingDraft(restore: boolean): void {
    const rt = currentRuntime.value
    const key = currentKey.value
    if (!rt || !key) return
    if (restore && rt.pendingDraft !== null) {
      rt.answer = rt.pendingDraft
      if (rt.phase === 'idle') rt.phase = 'editing'
    } else if (!restore) {
      const [bId, qIdStr] = key.split(':')
      const uid = userIdProvider()
      if (uid !== -1) clearDraft(uid, bId!, Number(qIdStr))
    }
    rt.pendingDraft = null
  }

  function discardDraftOf(key: string): void {
    const [bId, qIdStr] = key.split(':')
    const uid = userIdProvider()
    if (uid !== -1) clearDraft(uid, bId!, Number(qIdStr))
  }

  /* ---------- 疑问标记（FR-PRAC-08） ---------- */

  function loadMarked(): void {
    const uid = userIdProvider()
    if (uid === -1 || !bankId.value) return
    markedIds.value = new Set(readMarked(uid, bankId.value))
  }

  function toggleMark(): void {
    const id = orderedIds.value[currentIndex.value]
    if (id === undefined || !bankId.value) return
    const nextSet = new Set(markedIds.value)
    if (nextSet.has(id)) nextSet.delete(id)
    else nextSet.add(id)
    markedIds.value = nextSet
    const uid = userIdProvider()
    if (uid !== -1) writeMarked(uid, bankId.value, [...nextSet])
  }

  /* ---------- 提交（幂等 + 状态机，§8.3） ---------- */

  async function submit(): Promise<void> {
    const key = currentKey.value
    const rt = currentRuntime.value
    if (!key || !rt) return
    if (rt.phase === 'submitting' || rt.phase === 'submitted') return
    if (rt.answer === null || rt.answer === undefined || rt.answer === '') {
      rt.errorMessage = '请先作答再提交'
      return
    }
    accumulateCurrentTime()
    rt.errorMessage = null
    rt.phase = 'submitting'
    // 幂等键固化：错误重试沿用同一 id（§8.3）
    const requestId = rt.pendingRequestId ?? uuidV4()
    rt.pendingRequestId = requestId
    flushDraftFor(key)

    const [bId, qIdStr] = key.split(':')
    const body = {
      bank_id: bId!,
      question_id: Number(qIdStr),
      answer: rt.answer,
      time_spent: Math.max(1, rt.timeSpent || 1),
      mode: (sessionKind.value === 'review' ? 'review' : 'practice') as 'review' | 'practice',
      client_request_id: requestId,
    }
    try {
      const result = await apiSubmitAttempt(body)
      applyAttemptResult(key, result)
    } catch (err) {
      rt.phase = 'error'
      const isApi = err instanceof ApiError
      const isNetwork = isApi && err.networkError
      rt.errorMessage =
        err instanceof Error
          ? isNetwork
            ? '网络异常，作答已保留，可稍后重试'
            : err.message
          : String(err)
      if (isNetwork) {
        const uid = userIdProvider()
        if (uid !== -1) {
          enqueueUnsubmitted(uid, {
            bankId: bId!,
            questionId: Number(qIdStr),
            mode: body.mode,
            answer: rt.answer,
            timeSpent: body.time_spent,
            clientRequestId: requestId,
            queuedAt: Date.now(),
          })
        }
      } else if (isApi && err.status >= 400 && err.status < 500) {
        // §8.3：收到明确业务失败后释放幂等键；用户修正作答后以新键重新提交
        rt.pendingRequestId = null
      }
    }
  }

  function applyAttemptResult(key: string, result: AttemptResult): void {
    const rt = runtimeFor(key)
    rt.attempt = result
    rt.pendingRequestId = null
    rt.phase = 'submitted'
    const status = normalizeGrading(result)
    rt.grading = status
    discardDraftOf(key)
    if (status === 'queued' || status === 'pending' || status === 'processing') {
      void startGradingPoll(key, result.attempt_id)
    } else if (status === 'failed') {
      rt.selfJudgeReady = true
    }
    // 服务端判定为错 → 写入本地错题本缓存；答对 → 移出（FR-WRONG-01 数据源）
    if (result.is_correct === false) {
      recordWrongAnswer(key)
    }
  }

  async function startGradingPoll(key: string, attemptId: number): Promise<void> {
    const startedAt = Date.now()
    let delay = POLL_START_MS
    for (;;) {
      const rt = runtimes.get(key)
      if (!rt || rt.phase !== 'submitted') return // 组件卸载/切走后停止轮询（M2 验收 4）
      const status = rt.grading
      if (status !== 'queued' && status !== 'pending' && status !== 'processing') return
      // 超时兜底：主动展示自评入口，但不阻塞切题
      const elapsed = Date.now() - startedAt
      if ((status === 'pending' && elapsed > PENDING_SELF_JUDGE_MS) || (status === 'processing' && elapsed > PROCESSING_SELF_JUDGE_MS)) {
        rt.selfJudgeReady = true
      }
      await sleep(delay)
      delay = Math.min(POLL_MAX_MS, Math.round(delay * 1.6))
      try {
        const latest = await apiAttemptResult(attemptId)
        const cur = runtimes.get(key)
        if (!cur) return
        cur.attempt = { ...cur.attempt, ...latest, attempt_id: attemptId }
        const s = normalizeGrading(latest)
        cur.grading = s
        if (s === 'succeeded' || s === 'failed') {
          if (s === 'failed') cur.selfJudgeReady = true
          if (latest.is_correct === false) recordWrongAnswer(key)
          return
        }
      } catch {
        /* 单次轮询失败忽略，下一轮重试 */
      }
    }
  }

  /** 自评兜底回传（§8.2 / 契约 mode=self_judge） */
  async function submitSelfJudge(rating: 'correct' | 'partial' | 'wrong'): Promise<void> {
    const key = currentKey.value
    const rt = currentRuntime.value
    if (!key || !rt) return
    const [bId, qIdStr] = key.split(':')
    // 原始作答已 submitted；自评请求失败不能回到 error 态，
    // 否则确认按钮会以新幂等键重放原答案，产生重复流水
    rt.phase = 'submitting'
    try {
      const result = await apiSubmitAttempt({
        bank_id: bId!,
        question_id: Number(qIdStr),
        answer: { self_rating: rating },
        time_spent: 0,
        mode: 'self_judge',
        client_request_id: uuidV4(),
      })
      rt.attempt = { ...(rt.attempt ?? {}), ...result }
      rt.grading = 'succeeded'
      rt.selfJudgeReady = false
      rt.errorMessage = null
      rt.phase = 'submitted'
    } catch (err) {
      rt.phase = 'submitted'
      rt.grading = 'failed'
      rt.selfJudgeReady = true
      rt.errorMessage =
        err instanceof Error ? `自评提交失败：${err.message}` : `自评提交失败：${String(err)}`
    }
  }

  /* ---------- 断网续答同步（FR-PRAC-12） ---------- */

  function pendingSyncCount(): number {
    const uid = userIdProvider()
    return uid === -1 ? 0 : readUnsubmitted(uid).length
  }

  /** 联网后重放未提交记录：逐条以原 client_request_id 重放，结果以服务端为准 */
  async function syncUnsubmitted(): Promise<{ synced: number; failed: number }> {
    const uid = userIdProvider()
    if (uid === -1) return { synced: 0, failed: 0 }
    const records = readUnsubmitted(uid)
    let synced = 0
    let failed = 0
    for (const rec of records) {
      try {
        const result = await apiSubmitAttempt({
          bank_id: rec.bankId,
          question_id: rec.questionId,
          answer: rec.answer,
          time_spent: rec.timeSpent,
          mode: rec.mode,
          client_request_id: rec.clientRequestId,
        })
        synced++
        dequeueUnsubmitted(uid, rec.clientRequestId)
        const key = qKey(rec.bankId, rec.questionId)
        if (runtimes.has(key)) applyAttemptResult(key, result)
      } catch (err) {
        const isNetwork = err instanceof ApiError && err.networkError
        if (isNetwork) {
          failed++
          break // 仍然离线，保留剩余队列下次再试
        }
        // 明确业务失败：丢弃该条避免死循环重放
        dequeueUnsubmitted(uid, rec.clientRequestId)
        failed++
      }
    }
    return { synced, failed }
  }

  /** 服务端判错 → 记入本地错题本缓存（FR-WRONG-01 数据源） */
  function recordWrongAnswer(key: string): void {
    const [bId, qIdStr] = key.split(':')
    const rt = runtimes.get(key)
    const meta = rt?.listMeta ?? rt?.detail
    wrongRecorder?.({
      bankId: bId!,
      questionId: Number(qIdStr),
      preview: meta?.content?.slice(0, 100),
      year: meta?.year,
      typeCode: meta?.type_code,
      lastPracticeAt: new Date().toISOString(),
    })
  }

  /* ---------- 会话启动 / 结束 ---------- */

  async function startBankSession(opts: StartBankOptions): Promise<void> {
    sessionKind.value = 'bank'
    bankId.value = opts.bankId
    year.value = opts.year ?? null
    typeCode.value = opts.typeCode ?? null
    orderedIds.value = []
    itemBanks.value = new Map()
    nextPage.value = 1
    hasMore.value = true
    total.value = 0
    currentIndex.value = -1
    listError.value = null

    // §8.5 缓存命中：相同 filterHash 的列表会话直接复用，不重复请求
    const cached = listSessions.get(filterHash())
    if (cached && cached.ids.length > 0) {
      orderedIds.value = [...cached.ids]
      itemBanks.value = new Map(cached.banks)
      total.value = cached.total
      hasMore.value = cached.hasMore
      nextPage.value = cached.nextPage
      return
    }

    listLoading.value = true
    try {
      await appendPage(1)
    } catch (err) {
      listError.value = err instanceof Error ? err.message : String(err)
    } finally {
      listLoading.value = false
    }
  }

  function continueFromSavedProgress(): boolean {
    const uid = userIdProvider()
    if (uid === -1 || !bankId.value) return false
    const saved = readProgress(uid, bankId.value)
    if (!saved || saved.lastIndex <= 0) return false
    void gotoIndex(Math.min(saved.lastIndex, Math.max(0, orderedIds.value.length - 1)))
    return true
  }

  function startReviewSession(items: SessionItem[]): void {
    sessionKind.value = 'review'
    orderedIds.value = items.map((i) => i.questionId)
    const m = new Map<number, string>()
    for (const i of items) m.set(i.questionId, i.bankId)
    itemBanks.value = m
    hasMore.value = false
    total.value = items.length
    currentIndex.value = -1
    void gotoIndex(0)
  }

  function resetSession(): void {
    accumulateCurrentTime()
    for (const timer of draftTimers.values()) clearTimeout(timer)
    draftTimers = new Map()
    runtimes.clear()
    orderedIds.value = []
    itemBanks.value = new Map()
    currentIndex.value = -1
    bankId.value = ''
    markedIds.value = new Set()
  }

  /** 离开/刷新前强制落盘全部草稿（FR-PRAC-11） */
  function flushAllDrafts(): void {
    for (const key of runtimes.keys()) flushDraftFor(key)
  }

  /** 当前题详情重试（未知题型降级卡片 / 详情加载失败，§6.4.10）：两种会话模式通用 */
  async function retryCurrentDetail(): Promise<boolean> {
    const key = currentKey.value
    if (!key) return false
    detailCache.delete(key)
    const rt = runtimeFor(key)
    rt.errorMessage = null
    if (!rt.detail) {
      rt.phase = 'loading'
      await loadDetailInto(key, true)
      return runtimes.get(key)?.phase !== 'error'
    }
    return true
  }

  function refreshList(): void {
    listSessions.clear()
    detailCache.clear()
    const keepIndex = currentIndex.value
    void startBankSession({ bankId: bankId.value, year: year.value, typeCode: typeCode.value }).then(
      () => {
        if (keepIndex >= 0 && orderedIds.value.length > 0) {
          void gotoIndex(Math.min(keepIndex, orderedIds.value.length - 1))
        }
      },
    )
  }

  return {
    bankId,
    orderedIds,
    itemBanks,
    currentIndex,
    listLoading,
    listError,
    runtimes,
    markedIds,
    currentKey,
    currentRuntime,
    bindUserId,
    startBankSession,
    continueFromSavedProgress,
    startReviewSession,
    gotoIndex,
    gotoQuestion,
    next,
    prev,
    setAnswer,
    resolvePendingDraft,
    toggleMark,
    submit,
    submitSelfJudge,
    pendingSyncCount,
    syncUnsubmitted,
    resetSession,
    refreshList,
    retryCurrentDetail,
    flushAllDrafts,
    loadMarked,
    persistProgress,
  }
})

function normalizeGrading(result: AttemptResult | null | undefined): GradingStatus | null {
  if (!result) return null
  const raw = result.grading_status ?? result.status
  if (!raw) return result.is_correct == null ? null : 'succeeded'
  if (raw === 'queued') return 'queued'
  return raw
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/* 错题本记录由 stores/wrongbook 注入，避免循环依赖 */
interface WrongEntry {
  bankId: string
  questionId: number
  preview?: string
  year?: number
  typeCode?: string
  lastPracticeAt?: string
}
type WrongRecorder = (entry: WrongEntry) => void
let wrongRecorder: WrongRecorder | null = null
export function bindWrongRecorder(fn: WrongRecorder | null): void {
  wrongRecorder = fn
}
