import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { apiStatsDailyTrend, apiStatsOverview, apiStatsWeaknesses } from '@/api/endpoints'
import type { StatsOverview, WeaknessItem } from '@/api/generated/schema'
import type { DailyTrendPoint } from '@/components/charts/types'

/**
 * stats store（§2.4）：概览指标、薄弱项、每日趋势、时间范围参数。内存缓存 + 手动刷新。
 */
export const useStatsStore = defineStore('stats', () => {
  const overview = reactive<StatsOverview>({})
  const overviewLoaded = ref(false)
  const overviewLoading = ref(false)

  const weaknesses = ref<WeaknessItem[]>([])
  const weaknessLoading = ref(false)

  /** 每日趋势（§6.11 daily-trend），空数组 = 未加载/加载失败占位 */
  const dailyTrend = ref<DailyTrendPoint[]>([])
  const dailyTrendLoading = ref(false)

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

  /** 每日趋势窗口天数：随 range 联动；端点上限 60，故 90d/all 夹到 60 */
  const trendDays = computed(() => (range.value === '7d' ? 7 : range.value === '30d' ? 30 : 60))

  async function fetchDailyTrend(days = trendDays.value, force = false): Promise<DailyTrendPoint[]> {
    if (dailyTrend.value.length > 0 && !force) return dailyTrend.value
    dailyTrendLoading.value = true
    try {
      const data = await apiStatsDailyTrend({ days })
      dailyTrend.value = data.points ?? []
    } catch {
      /* 失败保留现有数据；图表以占位/旧数据呈现，可手动刷新重试 */
    } finally {
      dailyTrendLoading.value = false
    }
    return dailyTrend.value
  }

  function invalidate(): void {
    overviewLoaded.value = false
    weaknesses.value = []
    dailyTrend.value = []
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
    dailyTrend,
    dailyTrendLoading,
    range,
    subjectId,
    rangeQuery,
    trendDays,
    fetchOverview,
    fetchWeaknesses,
    fetchDailyTrend,
    invalidate,
    reset,
  }
})
