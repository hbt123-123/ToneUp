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

  /** M-397/402：Range→天数共用查找表，取代重复的嵌套三元（审查规则禁止嵌套三元）；
   *  all 无窗口限制，趋势侧仅取端点上限 60 天，rangeQuery 侧返回 {} */
  const RANGE_DAYS: Record<'7d' | '30d' | '90d' | 'all', number> = { '7d': 7, '30d': 30, '90d': 90, all: 60 }

  /** M-398：toISOString 按 UTC 截取日期，非 UTC 时区（如 UTC+8 零点前后）from/to 会偏移一天；改为本地时区格式化 */
  function formatLocalDate(d: Date): string {
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${month}-${day}`
  }

  const rangeQuery = computed<{ from?: string; to?: string }>(() => {
    if (range.value === 'all') return {}
    const days = RANGE_DAYS[range.value]
    const to = new Date()
    const from = new Date(to.getTime() - days * 86_400_000)
    return { from: formatLocalDate(from), to: formatLocalDate(to) }
  })

  /** M-399：整体替换 overview 内容——响应中已消失的字段不得残留上一次请求的旧值 */
  function replaceOverview(data: StatsOverview): void {
    for (const key of Object.keys(overview) as (keyof StatsOverview)[]) {
      delete overview[key]
    }
    Object.assign(overview, data)
  }

  async function fetchOverview(force = false): Promise<StatsOverview> {
    // H-125：已在加载中时不重复发起请求（原先条件漏了 overviewLoading，会并发双请求）
    if ((overviewLoaded.value || overviewLoading.value) && !force) return overview
    overviewLoading.value = true
    try {
      const data = await apiStatsOverview({ ...rangeQuery.value, subject_id: subjectId.value || undefined })
      replaceOverview(data) // M-399：整体替换而非 Object.assign
      overviewLoaded.value = true
      return data
    } finally {
      overviewLoading.value = false
    }
  }

  /** M-400：薄弱项缓存与拉取时的学科筛选绑定，切换学科后旧缓存不再短路返回 */
  const weaknessesCacheSubject = ref<string | null>(null)

  async function fetchWeaknesses(force = false): Promise<void> {
    if (!force && weaknesses.value.length > 0 && weaknessesCacheSubject.value === (subjectId.value ?? '')) return
    weaknessLoading.value = true
    try {
      // H-164：''（"全部学科"）与 null 均归一为不携带 subject_id
      const data = await apiStatsWeaknesses({ subject_id: subjectId.value || undefined, limit: 10 })
      weaknesses.value = data.items ?? []
      weaknessesCacheSubject.value = subjectId.value ?? ''
    } catch (err) {
      // M-401：失败保持现有列表，但必须在控制台留痕，便于区分"加载为空"与"加载失败"
      console.warn('[stats] 薄弱项拉取失败', err)
    } finally {
      weaknessLoading.value = false
    }
  }

  /** 每日趋势窗口天数：随 range 联动（all 映射 60），端点上限 60 夹紧（M-397/402：复用 RANGE_DAYS，去除嵌套三元） */
  const trendDays = computed(() => Math.min(60, RANGE_DAYS[range.value]))

  /** M-403：记录当前趋势缓存对应的窗口天数，不同 days 的请求不得命中同一份静态缓存 */
  const dailyTrendDays = ref<number | null>(null)

  async function fetchDailyTrend(days = trendDays.value, force = false): Promise<DailyTrendPoint[]> {
    if (!force && dailyTrend.value.length > 0 && dailyTrendDays.value === days) return dailyTrend.value
    dailyTrendLoading.value = true
    try {
      const data = await apiStatsDailyTrend({ days })
      dailyTrend.value = data.points ?? []
      dailyTrendDays.value = days
    } catch (err) {
      // M-401：失败保留现有数据，但控制台留痕，不再把"失败"伪装成"正常空数据"
      console.warn('[stats] 每日趋势拉取失败', err)
    } finally {
      dailyTrendLoading.value = false
    }
    return dailyTrend.value
  }

  function invalidate(): void {
    // M-404：overview 内容与各 loading 标志一并复位——否则请求在途中失效后会残留脏数据/悬挂加载态
    replaceOverview({})
    overviewLoaded.value = false
    overviewLoading.value = false
    weaknesses.value = []
    weaknessesCacheSubject.value = null
    weaknessLoading.value = false
    dailyTrend.value = []
    dailyTrendDays.value = null
    dailyTrendLoading.value = false
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