import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { WrongBookItem } from '@/api/generated/schema'
import { readMarked, writeMarked } from '@/utils/storage'

/**
 * 错题本数据源（FR-WRONG-01）：
 * 契约未提供专用查询端点，因此本地缓存"服务端判分为错"的记录聚合呈现；
 * 仅缓存服务端已返回的结果（is_correct=false），不做客户端判分。
 * 疑问标记的题目同样收录并与错题区分标识（FR-WRONG-04）。
 *
 * 持久化键：toneup:wrongbook:{userId}；标记集合复用 toneup:marked:{userId}:{bankId}。
 */

const WRONGBOOK_KEY = (userId: number | string): string => `toneup:wrongbook:${userId}`

interface WrongRecord extends WrongBookItem {
  question_id: number
  bank_id: string
  /** 派生字段：是否同时被疑问标记（不落盘） */
  marked?: boolean
}

function loadRecords(userId: number | string): WrongRecord[] {
  try {
    const raw = localStorage.getItem(WRONGBOOK_KEY(userId))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (it): it is WrongRecord =>
        !!it && typeof it === 'object' && typeof (it as WrongRecord).question_id === 'number',
    )
  } catch {
    return []
  }
}

export const useWrongBookStore = defineStore('wrongbook', () => {
  const records = ref<WrongRecord[]>([])
  const markedOnly = ref(false)
  const keyword = ref('')

  const filtered = computed(() => {
    let list = [...records.value].sort((a, b) => (b.last_practice_at ?? '').localeCompare(a.last_practice_at ?? ''))
    if (markedOnly.value) list = list.filter((r) => r.marked === true)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) {
      list = list.filter(
        (r) =>
          (r.preview ?? r.content ?? '').toLowerCase().includes(kw) ||
          String(r.question_id).includes(kw),
      )
    }
    return list
  })

  function loadForUser(userId: number | string, markedProvider: (bankId: string) => number[]): void {
    const loaded = loadRecords(userId).map((r) => ({ ...r, marked: markedProvider(r.bank_id).includes(r.question_id) }))
    records.value = loaded
  }

  /** 由 practice store 在服务端返回 is_correct=false 时调用 */
  function recordWrong(entry: { bankId: string; questionId: number; preview?: string; year?: number; typeCode?: string; lastPracticeAt?: string }): void {
    const existing = records.value.find((r) => r.bank_id === entry.bankId && r.question_id === entry.questionId)
    if (existing) {
      existing.wrong_count = (existing.wrong_count ?? 1) + 1
      existing.total_attempts = (existing.total_attempts ?? 1) + 1
      existing.last_practice_at = new Date().toISOString()
      existing.preview = entry.preview ?? existing.preview
      persist()
    } else {
      records.value.push({
        bank_id: entry.bankId,
        question_id: entry.questionId,
        wrong_count: 1,
        total_attempts: 1,
        preview: entry.preview,
        year: entry.year,
        type_code: entry.typeCode,
        last_practice_at: entry.lastPracticeAt ?? new Date().toISOString(),
        marked: false,
      })
      persist()
    }
  }

  /** 答对后从错题本移除（以服务端结果为准） */
  function recordCorrect(bankId: string, questionId: number): void {
    const rec = records.value.find((r) => r.bank_id === bankId && r.question_id === questionId)
    if (rec) {
      rec.total_attempts = (rec.total_attempts ?? 0) + 1
      rec.last_practice_at = new Date().toISOString()
      // 掌握后移出列表：口径与后端复习调度一致由服务端管理，这里仅维护本地视图
      records.value = records.value.filter((r) => r !== rec)
      persist()
    }
  }

  function toggleMark(bankId: string, questionId: number, userId: number | string): void {
    const rec = records.value.find((r) => r.bank_id === bankId && r.question_id === questionId)
    if (!rec) return
    rec.marked = !rec.marked
    const ids = new Set(readMarked(userId, bankId))
    if (rec.marked) ids.add(questionId)
    else ids.delete(questionId)
    writeMarked(userId, bankId, [...ids])
    persist()
  }

  function persist(): void {
    const uid = currentUserId()
    if (uid === -1) return // 会话未恢复时不落盘，避免写入 toneup:wrongbook:-1
    /* marked 字段为派生值，落盘前剥离 */
    const plain = records.value.map(({ marked: _m, ...rest }) => rest)
    try {
      localStorage.setItem(WRONGBOOK_KEY(uid), JSON.stringify(plain))
    } catch {
      /* ignore */
    }
  }

  /** 与 practice.bindUserId 同款：传 getter，会话恢复后自动取到真实 userId */
  let currentUserId: () => number | string = () => -1

  function bindUser(provider: () => number | string): void {
    currentUserId = provider
  }

  function reset(): void {
    records.value = []
    markedOnly.value = false
    keyword.value = ''
  }

  return {
    records,
    markedOnly,
    keyword,
    filtered,
    bindUser,
    loadForUser,
    recordWrong,
    recordCorrect,
    toggleMark,
    reset,
  }
})
