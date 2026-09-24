import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { WrongBookItem } from '@/api/generated/schema'
import { readMarked, writeMarked } from '@/utils/storage'
import {
  fetchWrongQuestions,
  addWrongQuestion,
  removeWrongQuestion,
  syncWrongQuestions,
  type WrongQuestionSyncItem,
} from '@/api/wrongQuestions'
import { ApiError } from '@/api/http'

/**
 * 错题本数据源（FR-WRONG-01）：
 * 契约提供专用查询端点，因此以服务端为数据源，本地仅维护视图缓存。
 * 疑问标记的题目同样收录并与错题区分标识（FR-WRONG-04）。
 *
 * 持久化：服务端为准；断网时写入离线队列 toneup:wrongbook:offline:{userId}，
 * 网络恢复后经 syncWrongQuestions 批量上传。标记集合复用 toneup:marked:{userId}:{bankId}。
 */

const OFFLINE_QUEUE_KEY = (userId: number | string): string => `toneup:wrongbook:offline:${userId}`

/** H-153：online 监听器只注册一次的模块级标志 */
let onlineSyncBound = false

interface WrongRecord extends WrongBookItem {
  /** 服务端错题记录 id（DELETE /wrong-questions/{id} 的路径参数），离线未同步时缺省 */
  id?: number
  question_id: number
  bank_id: string
  /** 派生字段：是否同时被疑问标记（不落盘） */
  marked?: boolean
}

/** 离线队列条目：断网期间待上传的错题记录 */
interface OfflineQueueEntry {
  /** UUID 唯一标识 */
  id: string
  bank_id: string
  question_id: number
  preview?: string
  /** 入队时间 ISO 8601 */
  queuedAt: string
}

/** 将本地 WrongRecord 字段映射为后端 API 请求体字段（H-117：同步请求不携带服务端 id） */
function transformToApiBody(record: WrongRecord): WrongQuestionSyncItem {
  return {
    bank_id: record.bank_id,
    question_id: record.question_id,
    attempt_count: record.wrong_count ?? 1,
    last_wrong_at: record.last_practice_at ?? new Date().toISOString(),
    tags: [],
    preview: record.content ?? record.preview,
  }
}

export const useWrongBookStore = defineStore('wrongbook', () => {
  const records = ref<WrongRecord[]>([])
  const markedOnly = ref(false)
  const keyword = ref('')
  /** 离线队列同步中标志，防止重入 */
  const syncing = ref(false)

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

  /** 读取离线队列 */
  function readOfflineQueue(userId: number | string): OfflineQueueEntry[] {
    try {
      const raw = localStorage.getItem(OFFLINE_QUEUE_KEY(userId))
      if (!raw) return []
      const parsed: unknown = JSON.parse(raw)
      if (!Array.isArray(parsed)) return []
      return parsed.filter(
        (it): it is OfflineQueueEntry =>
          !!it &&
          typeof it === 'object' &&
          typeof (it as OfflineQueueEntry).id === 'string' &&
          typeof (it as OfflineQueueEntry).question_id === 'number',
      )
    } catch {
      return []
    }
  }

  /** 仅管理离线队列；主错题数据以服务端为准，不再写本地 wrongbook 键 */
  function persist(queue: OfflineQueueEntry[], userId?: number | string): void {
    // H-151：显式传入 userId（入队/同步必须与队列读取同键），避免与 currentUserId() 漂移
    const uid = userId ?? currentUserId()
    if (uid === -1) return // 会话未恢复时不落盘
    try {
      if (queue.length === 0) {
        localStorage.removeItem(OFFLINE_QUEUE_KEY(uid))
      } else {
        localStorage.setItem(OFFLINE_QUEUE_KEY(uid), JSON.stringify(queue))
      }
    } catch {
      /* ignore */
    }
  }

  /** 断网时入队一条待同步记录 */
  function enqueueOffline(userId: number | string, entry: Omit<OfflineQueueEntry, 'id' | 'queuedAt'>): void {
    const queue = readOfflineQueue(userId)
    queue.push({
      ...entry,
      id: crypto.randomUUID(),
      queuedAt: new Date().toISOString(),
    })
    persist(queue, userId) // H-151：与 readOfflineQueue 同键，禁止写往 currentUserId()
  }

  /** 网络恢复后批量上传离线队列 */
  async function syncOfflineQueue(): Promise<void> {
    const uid = currentUserId()
    if (uid === -1 || syncing.value) return
    const queue = readOfflineQueue(uid)
    if (queue.length === 0) return
    syncing.value = true
    try {
      const items: WrongQuestionSyncItem[] = queue.map((e) =>
        transformToApiBody({
          bank_id: e.bank_id,
          question_id: e.question_id,
          preview: e.preview,
          content: e.preview,
          wrong_count: 1,
          last_practice_at: e.queuedAt,
        }),
      )
      const result = await syncWrongQuestions(items)
      // H-126：同步期间新入队的条目不得被清掉——只移除本次已上传的条目
      // H-152：sync 返回 errors 非空说明部分条目被拒，保留整个队列下次重试
      // （sync 端点按 (user, bank, question) 幂等 upsert，重放安全）
      if (result.errors.length === 0) {
        const doneIds = new Set(queue.map((e) => e.id))
        persist(
          readOfflineQueue(uid).filter((e) => !doneIds.has(e.id)),
          uid,
        )
      }
    } catch {
      /* 仍离线，保留队列等待下次重试 */
    } finally {
      syncing.value = false
    }
  }

  async function loadForUser(userId: number | string, markedProvider: (bankId: string) => number[]): Promise<void> {
    try {
      const res = await fetchWrongQuestions()
      const loaded: WrongRecord[] = res.items.map((it) => ({
        id: it.id,
        bank_id: it.bank_id,
        question_id: it.question_id,
        wrong_count: it.attempt_count,
        total_attempts: it.attempt_count,
        last_practice_at: it.last_wrong_at,
        preview: it.preview,
        content: it.preview,
        marked: markedProvider(it.bank_id).includes(it.question_id),
      }))
      records.value = loaded
    } catch (err) {
      if (err instanceof ApiError && err.networkError) {
        // 断网：回退到本地缓存（旧 wrongbook 键，兼容历史数据）
        try {
          const raw = localStorage.getItem(`toneup:wrongbook:${userId}`)
          if (raw) {
            const parsed: unknown = JSON.parse(raw)
            if (Array.isArray(parsed)) {
              records.value = parsed
                .filter(
                  (it): it is WrongRecord =>
                    !!it &&
                    typeof it === 'object' &&
                    typeof (it as WrongRecord).question_id === 'number',
                )
                .map((r) => ({ ...r, marked: markedProvider(r.bank_id).includes(r.question_id) }))
              return
            }
          }
        } catch {
          /* ignore */
        }
        records.value = []
      } else {
        // 服务端业务错误：回退为空列表，避免阻塞视图
        records.value = []
      }
    }
  }

  /** 由 practice store 在服务端返回 is_correct=false 时调用 */
  async function recordWrong(entry: { bankId: string; questionId: number; preview?: string; year?: number; typeCode?: string; lastPracticeAt?: string }): Promise<void> {
    const uid = currentUserId()
    let serverId: number | undefined
    try {
      const created = await addWrongQuestion(entry.bankId, entry.questionId, entry.preview)
      serverId = created.id
    } catch (err) {
      if (err instanceof ApiError && err.networkError && uid !== -1) {
        // 断网：入队离线队列，稍后同步
        enqueueOffline(uid, {
          bank_id: entry.bankId,
          question_id: entry.questionId,
          preview: entry.preview,
        })
      }
      // 非网络错误或无法确定用户：仅维护本地视图
    }
    // 本地视图缓存始终更新
    const existing = records.value.find((r) => r.bank_id === entry.bankId && r.question_id === entry.questionId)
    if (existing) {
      existing.wrong_count = (existing.wrong_count ?? 1) + 1
      existing.total_attempts = (existing.total_attempts ?? 1) + 1
      existing.last_practice_at = new Date().toISOString()
      existing.preview = entry.preview ?? existing.preview
      if (serverId !== undefined) existing.id = serverId
    } else {
      records.value.push({
        id: serverId,
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
    }
  }

  /** 答对后从错题本移除（以服务端结果为准） */
  async function recordCorrect(bankId: string, questionId: number): Promise<void> {
    const rec = records.value.find((r) => r.bank_id === bankId && r.question_id === questionId)
    if (rec) {
      rec.total_attempts = (rec.total_attempts ?? 0) + 1
      rec.last_practice_at = new Date().toISOString()
      // DELETE /wrong-questions/{id} 需要"错题记录 id"而非题目 id；离线未同步的记录无服务端 id，无记录可删
      if (rec.id !== undefined) {
        try {
          await removeWrongQuestion(rec.id)
        } catch {
          return // 删除失败：保留本地条目，待下次答对重试
        }
      }
      // 服务端删除成功（或本就无服务端记录）后才移除本地条目
      records.value = records.value.filter((r) => r !== rec)
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

  // 网络恢复时自动同步离线队列（H-153：模块级防重，store 重复实例化不再叠加监听器）
  if (!onlineSyncBound && typeof window !== 'undefined') {
    onlineSyncBound = true
    window.addEventListener('online', () => {
      void syncOfflineQueue()
    })
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
