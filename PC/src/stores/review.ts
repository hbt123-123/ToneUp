import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiReviewsToday, apiSkipReview } from '@/api/endpoints'
import type { ReviewItem } from '@/api/generated/schema'

/**
 * review store（§2.4）：今日复习列表与完成进度；作答引擎复用 practice store。
 */
export const useReviewStore = defineStore('review', () => {
  const queue = ref<ReviewItem[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const loadedAt = ref<number | null>(null)

  const remainingCount = computed(() => queue.value.length)

  /** M-394：请求代序号——并发/快速连续调用时仅最新一次的响应可写入队列与状态 */
  let fetchSeq = 0

  async function fetchQueue(limit = 50, subjectId?: string): Promise<void> {
    const seq = ++fetchSeq
    loading.value = true
    error.value = null
    try {
      const data = await apiReviewsToday({ limit, subject_id: subjectId }, undefined)
      if (seq !== fetchSeq) return // M-394：await 期间已有更新的请求，丢弃过期响应防止旧数据覆盖
      queue.value = data.items ?? []
      loadedAt.value = Date.now()
    } catch (err) {
      if (seq !== fetchSeq) return // M-394：过期请求的失败不污染最新状态
      // M-395：存储人类可读的提示，不把原始 err.message 泄漏给 UI；控制台留痕便于排查
      error.value = '复习队列加载失败，请稍后重试'
      console.warn('[review] 复习队列拉取失败', err)
      // M-395：保留抛出契约——PracticeView 启动流程依赖它区分"加载失败"与"队列确实为空"
      throw err
    } finally {
      // M-394：仅最新一次请求负责复位 loading，旧请求不得提前关闭新请求的加载态
      if (seq === fetchSeq) loading.value = false
    }
  }

  /** 暂缓本题（FR-REV-03）：从队列移除并提示下次时间 */
  async function skipCurrent(question: ReviewItem): Promise<string | null> {
    // H-124：返回服务端给出的下次复习时间（此前被丢弃，恒为 null）
    const result = await apiSkipReview(question.question_id, question.bank_id)
    // M-396：ReviewItem 以 (bank_id, question_id) 复合定位，仅按 question_id 过滤会误删跨题库同 id 条目
    queue.value = queue.value.filter((q) => !(q.question_id === question.question_id && q.bank_id === question.bank_id))
    return result?.next_review_at ?? null
  }

  /** M-396：改为复合键定位，避免误删其他题库中相同 question_id 的条目（当前无外部调用方，签名安全收紧） */
  function removeFromQueue(bankId: string, questionId: number): void {
    queue.value = queue.value.filter((q) => !(q.question_id === questionId && q.bank_id === bankId))
  }

  function reset(): void {
    queue.value = []
    loadedAt.value = null
    error.value = null
  }

  return { queue, loading, error, loadedAt, remainingCount, fetchQueue, skipCurrent, removeFromQueue, reset }
})