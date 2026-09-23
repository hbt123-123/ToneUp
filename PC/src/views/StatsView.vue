<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NSelect, NSpin, NTag } from 'naive-ui'
import { useStatsStore } from '@/stores/stats'
import { useCatalogStore } from '@/stores/catalog'
import { formatPercent, typeCodeLabel } from '@/utils/format'
import DailyTrendChart from '@/components/charts/DailyTrendChart.vue'

/**
 * 统计页（FR-STAT-01~04）：
 * 概览指标卡、时间范围/学科联动筛选、薄弱知识点榜（可跳转定向练习）、
 * 趋势折线图（daily-trend 端点 §6.11）。
 */
const router = useRouter()
const stats = useStatsStore()
const catalog = useCatalogStore()

onMounted(() => {
  void refreshAll()
})

async function refreshAll(): Promise<void> {
  await Promise.allSettled([
    stats.fetchOverview(true),
    stats.fetchWeaknesses(true),
    stats.fetchDailyTrend(undefined, true),
  ])
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

      <!-- 趋势图（FR-STAT-04，§6.11 daily-trend） -->
      <section class="tu-card trend-section">
        <h3>趋势</h3>
        <n-spin :show="stats.dailyTrendLoading">
          <daily-trend-chart :points="stats.dailyTrend" />
        </n-spin>
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

:deep(.empty-lite) {
  color: var(--tu-text-secondary);
  font-size: 13px;
  padding: 28px 0;
  text-align: center;
}
</style>
