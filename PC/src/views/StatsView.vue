<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NSelect, NSpin, NTag } from 'naive-ui'
import { useStatsStore } from '@/stores/stats'
import { useCatalogStore } from '@/stores/catalog'
import { formatPercent, typeCodeLabel } from '@/utils/format'

/**
 * 统计页（FR-STAT-01~04）：
 * 概览指标卡、时间范围/学科联动筛选、薄弱知识点榜（可跳转定向练习）、
 * 趋势图（P2：契约暂无时序端点，预留数据位与轻量 SVG 渲染）。
 */
const router = useRouter()
const stats = useStatsStore()
const catalog = useCatalogStore()

onMounted(() => {
  void refreshAll()
})

async function refreshAll(): Promise<void> {
  await Promise.allSettled([stats.fetchOverview(true), stats.fetchWeaknesses(true)])
}

function onFilterChange(): void {
  stats.invalidate()
  void refreshAll()
}

const subjectOptions = computed(() => [
  { label: '全部学科', value: '' },
  ...catalog.subjects.map((s) => ({ label: s.name, value: s.id })),
])

const rangeOptions = [
  { label: '最近 7 天', value: '7d' },
  { label: '最近 30 天', value: '30d' },
  { label: '最近 90 天', value: '90d' },
  { label: '全部', value: 'all' },
]

/** 薄弱项跳转对应题库定向练习（FR-STAT-03） */
function practiceWeakness(item: { bank_id?: string; type_code?: string }): void {
  if (!item.bank_id) {
    router.push('/catalog')
    return
  }
  localStorage.setItem('toneup:last-bank', item.bank_id)
  const bank = catalog.bankById.get(item.bank_id)
  if (bank) catalog.selectSubject(bank.subject_id)
  void router.push({
    name: 'practice',
    params: { bankId: item.bank_id },
    query: item.type_code ? { type_code: item.type_code } : {},
  })
}

interface TrendPoint {
  date: string
  count: number
  accuracy?: number | null
}

/** 趋势数据：后端契约当前未提供时序字段；若未来返回 trend 数组即可渲染 */
const trend = computed<TrendPoint[]>(() => {
  const raw = (stats.overview as typeof stats.overview & { trend?: TrendPoint[] }).trend
  return Array.isArray(raw) ? raw : []
})
</script>

<template>
  <div class="content-inner stats-view">
    <!-- 筛选条（FR-STAT-02） -->
    <div class="filter-bar tu-card">
      <n-select
        v-model:value="stats.range"
        :options="rangeOptions"
        size="small"
        class="f-sel"
        @update:value="onFilterChange"
      />
      <n-select
        v-model:value="stats.subjectId"
        :options="subjectOptions"
        size="small"
        class="f-sel"
        placeholder="学科"
        clearable
        @update:value="onFilterChange"
      />
      <n-button size="small" quaternary @click="onFilterChange">刷新</n-button>
    </div>

    <!-- 概览指标卡（FR-STAT-01） -->
    <div class="cards">
      <n-card size="small" class="tu-card">
        <p class="label text-secondary">正确率</p>
        <p class="value">{{ formatPercent(stats.overview.accuracy_rate) }}</p>
      </n-card>
      <n-card size="small" class="tu-card">
        <p class="label text-secondary">刷题总量</p>
        <p class="value">{{ stats.overview.total_attempts ?? '—' }}</p>
      </n-card>
      <n-card size="small" class="tu-card">
        <p class="label text-secondary">连续学习</p>
        <p class="value accent">{{ stats.overview.streak_days ?? 0 }}<span class="unit"> 天</span></p>
      </n-card>
    </div>

    <div class="two-col">
      <!-- 薄弱知识点榜（FR-STAT-03） -->
      <section class="tu-card weak-section">
        <h3>薄弱知识点榜</h3>
        <n-spin :show="stats.weaknessLoading">
          <div v-if="stats.weaknesses.length > 0" class="weak-list">
            <button
              v-for="(w, i) in stats.weaknesses"
              :key="i"
              type="button"
              class="weak-item option-row"
              @click="practiceWeakness(w)"
            >
              <span class="rank">{{ i + 1 }}</span>
              <span class="w-name">
                {{ w.tag_name ?? w.subject_name ?? w.bank_id ?? '未知维度' }}
                <n-tag v-if="w.type_code" size="tiny" round>{{ typeCodeLabel(w.type_code) }}</n-tag>
              </span>
              <span class="w-meta text-secondary">
                {{ w.attempts ?? '?' }} 次作答 · 正确率
                <b class="bad">{{ formatPercent(w.accuracy_rate ?? w.correct_rate) }}</b>
              </span>
            </button>
          </div>
          <n-empty-lite v-else-if="!stats.weaknessLoading" text="暂无薄弱项数据（需作答次数 ≥5 且正确率 <60%）" />
        </n-spin>
      </section>

      <!-- 趋势图（FR-STAT-04，P2） -->
      <section class="tu-card trend-section">
        <h3>趋势</h3>
        <template v-if="trend.length >= 2">
          <svg class="trend-svg" viewBox="0 0 320 140" role="img" aria-label="刷题量与正确率趋势">
            <polyline
              :points="trend.map((p, i) => `${(i / (trend.length - 1)) * 300 + 10},${130 - Math.min(120, p.count)} `).join('')"
              fill="none"
              stroke="#2B3A67"
              stroke-width="2.5"
              stroke-linejoin="round"
            />
            <polyline
              v-if="trend.some((p) => p.accuracy != null)"
              :points="trend.map((p, i) => `${(i / (trend.length - 1)) * 300 + 10},${130 - (p.accuracy ?? 0) * 120} `).join('')"
              fill="none"
              stroke="#7C3AED"
              stroke-width="2"
              stroke-dasharray="4 3"
            />
          </svg>
          <p class="legend-line text-secondary">
            <span class="lg solid" /> 刷题量　<span class="lg dash" /> 正确率
          </p>
        </template>
        <n-empty-lite v-else text="契约尚未提供趋势时序端点，图表将在数据可用后自动展示" />
      </section>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, h } from 'vue'

const NEmptyLite = defineComponent({
  props: { text: { type: String, required: true } },
  setup(props) {
    return () => h('p', { class: 'empty-lite' }, props.text)
  },
})

export default { components: { NEmptyLite } }
</script>

<style scoped>
.stats-view {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 12px 16px;
}

.f-sel {
  width: 160px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
}

.label {
  font-size: 13px;
  margin: 0 0 4px;
}

.value {
  font-size: 30px;
  font-weight: 700;
  margin: 0;
}

.value.accent,
.unit {
  color: var(--tu-accent);
}

.value .unit {
  font-size: 13px;
}

.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 18px;
}

@media (max-width: 1100px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}

.weak-section,
.trend-section {
  padding: 16px 18px;
}

h3 {
  margin: 0 0 12px;
  font-size: 15px;
}

.weak-list {
  display: flex;
  flex-direction: column;
}

.weak-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border: none;
  border-bottom: 1px solid var(--tu-border);
  background: none;
  text-align: left;
  font: inherit;
  color: inherit;
  cursor: pointer;
  min-height: 48px;
}

.rank {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(124, 58, 237, 0.12);
  color: var(--tu-accent);
  font-size: 13px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.w-name {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-wrap: anywhere;
}

.w-meta {
  flex: none;
  font-size: 12px;
}

.bad {
  color: var(--tu-error);
}

.trend-svg {
  width: 100%;
  height: auto;
}

.legend-line {
  font-size: 12px;
}

.lg {
  display: inline-block;
  width: 18px;
  height: 3px;
  vertical-align: middle;
  border-radius: 2px;
}

.lg.solid {
  background: #2b3a67;
}

.lg.dash {
  background: repeating-linear-gradient(90deg, #7c3aed 0 4px, transparent 4px 7px);
}

:deep(.empty-lite) {
  color: var(--tu-text-secondary);
  font-size: 13px;
  padding: 28px 0;
  text-align: center;
}
</style>
