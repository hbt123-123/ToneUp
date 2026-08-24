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

  async function fetchQueue(limit = 50, subjectId?: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const data = await apiReviewsToday({ limit, subject_id: subjectId }, undefined)
      queue.value = data.items ?? []
      loadedAt.value = Date.now()
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  /** 暂缓本题（FR-REV-03）：从队列移除并提示下次时间 */
  async function skipCurrent(question: ReviewItem): Promise<string | null> {
    await apiSkipReview(question.question_id, question.bank_id)
    queue.value = queue.value.filter((q) => q.question_id !== question.question_id)
    return null
  }

  function removeFromQueue(questionId: number): void {
    queue.value = queue.value.filter((q) => q.question_id !== questionId)
  }

  function reset(): void {
    queue.value = []
    loadedAt.value = null
    error.value = null
  }

  return { queue, loading, error, loadedAt, remainingCount, fetchQueue, skipCurrent, removeFromQueue, reset }})
