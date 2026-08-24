import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { apiStatsOverview, apiStatsWeaknesses } from '@/api/endpoints'
import type { StatsOverview, WeaknessItem } from '@/api/generated/schema'

/**
 * stats store（§2.4）：概览指标、薄弱项、时间范围参数。内存缓存 + 手动刷新。
 */
export const useStatsStore = defineStore('stats', () => {
  const overview = reactive<StatsOverview>({})
  const overviewLoaded = ref(false)
  const overviewLoading = ref(false)

  const weaknesses = ref<WeaknessItem[]>([])
  const weaknessLoading = ref(false)

  /** FR-STAT-02 时间范围与学科筛选，联动所有图表 */
  const range = ref<'7d' | '30d' | '90d' | 'all'>('30d')
  const subjectId = ref<string | null>(null)

  const rangeQuery = computed<{ from?: string; to?: string }>(() => {
    if (range.value === 'all') return {}
    const days = range.value === '7d' ? 7 : range.value === '30d' ? 30 : 90
    const to = new Date()
    const from = new Date(to.getTime() - days * 86_400_000)
    return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) }
  })

  async function fetchOverview(force = false): Promise<StatsOverview> {
    if (overviewLoaded.value && !force && !overviewLoading.value) return overview
    overviewLoading.value = true
    try {
      const data = await apiStatsOverview({ ...rangeQuery.value, subject_id: subjectId.value ?? undefined })
      Object.assign(overview, data)
      overviewLoaded.value = true
      return data
    } finally {
      overviewLoading.value = false
    }
  }

  async function fetchWeaknesses(force = false): Promise<void> {
    if (weaknesses.value.length > 0 && !force) return
    weaknessLoading.value = true
    try {
      const data = await apiStatsWeaknesses({ subject_id: subjectId.value ?? undefined, limit: 10 })
      weaknesses.value = data.items ?? []
    } catch {
      /* 拉取失败保持现有列表；页面以空态呈现，可手动刷新重试 */
    } finally {
      weaknessLoading.value = false
    }
  }

  function invalidate(): void {
    overviewLoaded.value = false
    weaknesses.value = []
  }

  function reset(): void {
    invalidate()
    subjectId.value = null
    range.value = '30d'
  }

  return {
    overview,
    overviewLoaded,
    overviewLoading,
    weaknesses,
    weaknessLoading,
    range,
    subjectId,
    rangeQuery,
    fetchOverview,
    fetchWeaknesses,
    invalidate,
    reset,
  }
})
