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

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* 配额满或隐私模式：静默降级为内存态 */
  }
}

function safeRemove(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    /* ignore */
  }
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
  safeSet(draftKey(userId, bankId, questionId), JSON.stringify({ answer, updatedAt: Date.now() }))
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
  safeSet(progressKey(userId, bankId), JSON.stringify({ ...payload, updatedAt: Date.now() }))
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
    return parsed.filter(
      (it): it is UnsubmittedRecord =>
        !!it &&
        typeof it === 'object' &&
        typeof (it as UnsubmittedRecord).clientRequestId === 'string' &&
        typeof (it as UnsubmittedRecord).questionId === 'number',
    )
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

export function enqueueUnsubmitted(userId: number | string, record: UnsubmittedRecord): UnsubmittedRecord[] {
  const all = readUnsubmitted(userId).filter((r) => r.clientRequestId !== record.clientRequestId)
  const next = [...all, record]
  writeUnsubmitted(userId, next)
  return next
}

export function dequeueUnsubmitted(userId: number | string, clientRequestId: string): UnsubmittedRecord[] {
  const next = readUnsubmitted(userId).filter((r) => r.clientRequestId !== clientRequestId)
  writeUnsubmitted(userId, next)
  return next
}

/* ---------- 登出/切账号：清除全部 toneup:* 用户域键（§8.4） ---------- */

export function clearAllUserDomainData(): void {
  try {
    const doomed: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key && key.startsWith('toneup:')) doomed.push(key)
    }
    for (const key of doomed) safeRemove(key)
  } catch {
    /* ignore */
  }
}
