/** 草稿/进度/标记的 LocalStorage 键设计（需求文档 §8.4） */

export interface DraftPayload {
  answer: unknown
  updatedAt: number
}

export interface ProgressPayload {
  lastIndex: number
  filterHash: string
  updatedAt: number
}

// M-507：localStorage 不可用（配额满/隐私模式）时的会话级内存降级存储，
// 使"内存态"降级名副其实：写失败落入内存副本，读路径自动回查，写成功后清理副本
const memoryFallback = new Map<string, string>()

function safeGet(key: string): string | null {
  try {
    const v = localStorage.getItem(key)
    if (v !== null) return v
  } catch {
    /* localStorage 不可用：走内存降级 */
  }
  return memoryFallback.get(key) ?? null
}

function safeSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
    memoryFallback.delete(key) // M-507：localStorage 写成功后清理同键内存副本，避免读到过期值
  } catch {
    // M-507：配额满或隐私模式：降级为会话内存态
    memoryFallback.set(key, value)
  }
}

function safeRemove(key: string): void {
  memoryFallback.delete(key) // M-507：同步清理内存降级副本
  try {
    localStorage.removeItem(key)
  } catch {
    /* ignore */
  }
}

/** H-157：序列化纳入 try——循环引用/BigInt/抛错的 toJSON 不得让异常冲出写缓存路径 */
function safeSetJson(key: string, value: unknown): void {
  try {
    safeSet(key, JSON.stringify(value)) // M-507：存储降级统一走 safeSet（含内存态）
  } catch {
    /* 不可序列化：静默降级 */
  }
}

/* ---------- 通用 JSON 缓存（EC-05 catalog 持久缓存等） ---------- */

/** 读取 JSON 缓存：缺失/损坏返回 null（安全语义，调用方走网络路径） */
export function readJsonCache<T>(key: string): T | null {
  const raw = safeGet(key)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function writeJsonCache(key: string, value: unknown): void {
  safeSetJson(key, value)
}

export function removeJsonCache(key: string): void {
  safeRemove(key)
}

/* ---------- 单题草稿 ---------- */

export function draftKey(userId: number | string, bankId: string, questionId: number): string {
  return `toneup:draft:${userId}:${bankId}:${questionId}`
}

export function readDraft(userId: number | string, bankId: string, questionId: number): DraftPayload | null {
  const raw = safeGet(draftKey(userId, bankId, questionId))
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<DraftPayload>
    if (!parsed || typeof parsed !== 'object' || !('answer' in parsed)) return null
    return { answer: parsed.answer ?? null, updatedAt: parsed.updatedAt ?? Date.now() }
  } catch {
    return null
  }
}

export function writeDraft(userId: number | string, bankId: string, questionId: number, answer: unknown): void {
  safeSetJson(draftKey(userId, bankId, questionId), { answer, updatedAt: Date.now() })
}

export function clearDraft(userId: number | string, bankId: string, questionId: number): void {
  safeRemove(draftKey(userId, bankId, questionId))
}

/* ---------- 题库级进度 ---------- */

export function progressKey(userId: number | string, bankId: string): string {
  return `toneup:progress:${userId}:${bankId}`
}

export function readProgress(userId: number | string, bankId: string): ProgressPayload | null {
  const raw = safeGet(progressKey(userId, bankId))
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<ProgressPayload>
    if (typeof parsed.lastIndex !== 'number') return null
    return {
      lastIndex: parsed.lastIndex,
      filterHash: typeof parsed.filterHash === 'string' ? parsed.filterHash : '',
      updatedAt: parsed.updatedAt ?? Date.now(),
    }
  } catch {
    return null
  }
}

export function writeProgress(
  userId: number | string,
  bankId: string,
  payload: Omit<ProgressPayload, 'updatedAt'>,
): void {
  safeSetJson(progressKey(userId, bankId), { ...payload, updatedAt: Date.now() })
}

/* ---------- 疑问标记集合 ---------- */

export function markedKey(userId: number | string, bankId: string): string {
  return `toneup:marked:${userId}:${bankId}`
}

export function readMarked(userId: number | string, bankId: string): number[] {
  const raw = safeGet(markedKey(userId, bankId))
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((n): n is number => typeof n === 'number')
  } catch {
    return []
  }
}

export function writeMarked(userId: number | string, bankId: string, ids: number[]): void {
  safeSet(markedKey(userId, bankId), JSON.stringify(ids))
}

/* ---------- 断网续答：未提交记录队列（§8.4 / FR-PRAC-12） ---------- */

export interface UnsubmittedRecord {
  bankId: string
  questionId: number
  mode: 'practice' | 'review'
  answer: unknown
  timeSpent: number
  clientRequestId: string
  queuedAt: number
}

export function unsubmittedKey(userId: number | string): string {
  return `toneup:unsubmitted:${userId}`
}

export function readUnsubmitted(userId: number | string): UnsubmittedRecord[] {
  const raw = safeGet(unsubmittedKey(userId))
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((it): it is UnsubmittedRecord => {
      if (!it || typeof it !== 'object') return false
      // M-508：全字段类型校验——原仅查 clientRequestId/questionId，残缺记录会在
      // 重放时发出缺 bankId/mode/timeSpent 的非法请求
      const rec = it as Partial<UnsubmittedRecord>
      return (
        typeof rec.clientRequestId === 'string' &&
        rec.clientRequestId !== '' &&
        typeof rec.bankId === 'string' &&
        rec.bankId !== '' &&
        (rec.mode === 'practice' || rec.mode === 'review') &&
        'answer' in it &&
        typeof rec.timeSpent === 'number' &&
        Number.isFinite(rec.timeSpent) &&
        typeof rec.queuedAt === 'number' &&
        Number.isFinite(rec.queuedAt)
      )
    })
  } catch {
    return []
  }
}

export function writeUnsubmitted(userId: number | string, records: UnsubmittedRecord[]): void {
  if (records.length === 0) {
    safeRemove(unsubmittedKey(userId))
    return
  }
  safeSet(unsubmittedKey(userId), JSON.stringify(records))
}

// M-509：enqueue/dequeue 对同一 localStorage 键做"读-改-写"，快速连续调用或多标签页
// 并发时会互相覆盖丢记录——模块级 promise 链把所有队列操作串行化。
// 注意：两函数因此返回 Promise（调用方 practice.ts 均为 fire-and-forget，不消费返回值）
let queueChain: Promise<unknown> = Promise.resolve()

function serializeQueueOp<T>(op: () => T): Promise<T> {
  const run = queueChain.then(op, op) // 前序失败也继续执行后续操作
  queueChain = run.then(
    () => undefined,
    () => undefined,
  )
  return run
}

export function enqueueUnsubmitted(
  userId: number | string,
  record: UnsubmittedRecord,
): Promise<UnsubmittedRecord[]> {
  return serializeQueueOp(() => {
    const all = readUnsubmitted(userId).filter((r) => r.clientRequestId !== record.clientRequestId)
    const next = [...all, record]
    writeUnsubmitted(userId, next)
    return next
  })
}

export function dequeueUnsubmitted(
  userId: number | string,
  clientRequestId: string,
): Promise<UnsubmittedRecord[]> {
  return serializeQueueOp(() => {
    const next = readUnsubmitted(userId).filter((r) => r.clientRequestId !== clientRequestId)
    writeUnsubmitted(userId, next)
    return next
  })
}

/* ---------- 登出/切账号：清除全部 toneup:* 用户域键（§8.4） ---------- */

/** 设备级偏好不随用户域清除（stores/ui.ts 的主题/侧栏/动效等） */
const DEVICE_LEVEL_KEYS = new Set(['toneup:ui'])

export function clearAllUserDomainData(): void {
  try {
    const doomed: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key && key.startsWith('toneup:') && !DEVICE_LEVEL_KEYS.has(key)) doomed.push(key)
    }
    for (const key of doomed) safeRemove(key)
    // M-507：内存降级副本同样按用户域清理，避免登出后残留会话数据
    for (const key of Array.from(memoryFallback.keys())) {
      if (key.startsWith('toneup:') && !DEVICE_LEVEL_KEYS.has(key)) memoryFallback.delete(key)
    }
  } catch {
    /* ignore */
  }
}