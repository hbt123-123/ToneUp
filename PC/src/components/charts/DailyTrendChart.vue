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
  <svg
    v-if="geometry.hasLine"
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
  <p v-else class="chart-empty">暂无数据</p>
</template>

<style scoped>
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
