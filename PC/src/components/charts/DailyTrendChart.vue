<script setup lang="ts">
import { computed } from 'vue'
import { buildTrendGeometry, TREND_VIEW_H, TREND_VIEW_W } from './trendGeometry'
import type { DailyTrendPoint } from './types'

/**
 * 每日趋势折线图（EC-03）：纯展示组件。
 * props 仅 points/height —— 零 store 依赖、零网络请求；
 * 空数据（点数 < 2）渲染"暂无数据"占位，自身不含加载态。
 */
const props = withDefaults(
  defineProps<{
    /** 每日趋势点（日期升序） */
    points: DailyTrendPoint[]
    /** 绘图视口高度（viewBox 高，px），默认 140 */
    height?: number
  }>(),
  { height: TREND_VIEW_H },
)

const geometry = computed(() => buildTrendGeometry(props.points, TREND_VIEW_W, props.height))
</script>

<template>
  <div class="daily-trend-chart-wrap">
    <template v-if="geometry.hasLine">
      <div class="chart-legend">
        <span class="legend-item">
          <i class="legend-line legend-solid" aria-hidden="true" />
          刷题量
        </span>
        <span v-if="geometry.accuracyPoints" class="legend-item">
          <i class="legend-line legend-dashed" aria-hidden="true" />
          正确率
        </span>
      </div>
      <svg
        class="daily-trend-chart"
        :viewBox="`0 0 ${TREND_VIEW_W} ${props.height}`"
        role="img"
        aria-label="每日作答量与正确率趋势"
      >
        <polyline
          :points="geometry.attemptsPoints"
          fill="none"
          stroke="#2B3A67"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <polyline
          v-if="geometry.accuracyPoints"
          :points="geometry.accuracyPoints"
          fill="none"
          stroke="#7C3AED"
          stroke-width="2"
          stroke-dasharray="4 3"
        />
      </svg>
    </template>
    <p v-else class="chart-empty">暂无数据</p>
  </div>
</template>

<style scoped>
.daily-trend-chart-wrap {
  width: 100%;
}

.chart-legend {
  display: flex;
  gap: 16px;
  margin-bottom: 4px;
  font-size: 12px;
  color: #6b7280;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-line {
  display: inline-block;
  width: 18px;
  height: 0;
  border-top-width: 2px;
}

.legend-solid {
  border-top-style: solid;
  border-top-color: #2b3a67;
}

.legend-dashed {
  border-top-style: dashed;
  border-top-color: #7c3aed;
}

.daily-trend-chart {
  width: 100%;
  height: auto;
}

.chart-empty {
  margin: 0;
  padding: 28px 0;
  text-align: center;
  font-size: 13px;
}
</style>
