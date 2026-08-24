<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NProgress, NSwitch } from 'naive-ui'
import { NVirtualList } from 'naive-ui'

/**
 * 题号面板（FR-PRAC-09）：
 * 当前进度环、未答/标记筛选、格子跳转；长列表虚拟滚动（§10.4）。
 */
const props = defineProps<{
  ids: number[]
  currentIndex: number
  answeredIds: Set<number>
  markedIds: Set<number>
}>()

const emit = defineEmits<{ jump: [index: number] }>()

const filterUnanswered = defineModel<boolean>('unansweredOnly', { default: false })
const filterMarked = defineModel<boolean>('markedOnly', { default: false })

const visibleIndices = computed<number[]>(() => {
  const out: number[] = []
  props.ids.forEach((id, index) => {
    if (filterUnanswered.value && !props.answeredIds.has(id)) return
    if (filterMarked.value && !props.markedIds.has(id)) return
    out.push(index)
  })
  return out
})

/* 虚拟化：>50 条时按行列块渲染（每行 8 格） */
const COLS_PER_ROW = 8
const VIRTUAL_THRESHOLD = 50

const rows = computed<number[][]>(() => {
  const out: number[][] = []
  for (let i = 0; i < visibleIndices.value.length; i += COLS_PER_ROW) {
    out.push(visibleIndices.value.slice(i, i + COLS_PER_ROW))
  }
  return out
})

function cellClass(index: number): string[] {
  const id = props.ids[index]
  const classes: string[] = []
  if (index === props.currentIndex) classes.push('current')
  if (id !== undefined && props.answeredIds.has(id)) classes.push('answered')
  if (id !== undefined && props.markedIds.has(id)) classes.push('marked')
  return classes
}

const progressPercent = computed(() =>
  props.ids.length === 0 ? 0 : Math.round((props.answeredIds.size / props.ids.length) * 100),
)
</script>

<template>
  <div class="question-panel">
    <div class="progress-row">
      <n-progress
        type="circle"
        :percentage="progressPercent"
        :stroke-width="10"
        :show-indicator="true"
        style="width: 92px"
        color="#7C3AED"
        rail-color="rgba(124,58,237,0.15)"
      />
      <div class="stats">
        <p class="big">{{ currentIndex + 1 }}<span class="dim"> / {{ ids.length }}</span></p>
        <p class="text-secondary">已答 {{ answeredIds.size }} 题 · 完成 {{ progressPercent }}%</p>
      </div>
    </div>

    <div class="filters">
      <label class="filter-item">
        <n-switch v-model:value="filterUnanswered" size="small" />
        <span>只看未答</span>
      </label>
      <label class="filter-item">
        <n-switch v-model:value="filterMarked" size="small" />
        <span>只看标记</span>
      </label>
    </div>

    <div class="legend text-secondary">
      <span><i class="dot current" />当前</span>
      <span><i class="dot answered" />已答</span>
      <span><i class="dot marked" />疑问标记</span>
    </div>

    <n-virtual-list v-if="rows.length > VIRTUAL_THRESHOLD / COLS_PER_ROW" :items="rows" :item-size="40" class="grid-virtual">
      <template #default="{ item }: { item: number[] }">
        <div class="grid-row">
          <n-button
            v-for="idx in item"
            :key="idx"
            size="small"
            class="cell option-row"
            :class="cellClass(idx)"
            :aria-label="`第 ${idx + 1} 题`"
            @click="emit('jump', idx)"
          >
            {{ idx + 1 }}
          </n-button>
        </div>
      </template>
    </n-virtual-list>

    <div v-else class="grid-wrap">
      <div v-for="(row, ri) in rows" :key="ri" class="grid-row">
        <n-button
          v-for="idx in row"
          :key="idx"
          size="small"
          class="cell option-row"
          :class="cellClass(idx)"
          :aria-label="`第 ${idx + 1} 题`"
          @click="emit('jump', idx)"
        >
          {{ idx + 1 }}
        </n-button>
      </div>
    </div>

    <p v-if="visibleIndices.length === 0" class="text-secondary empty-hint">没有符合筛选的题目</p>
  </div>
</template>

<style scoped>
.question-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stats .big {
  font-size: 26px;
  font-weight: 700;
  margin: 0;
}

.dim {
  font-size: 15px;
  color: var(--tu-text-secondary);
  font-weight: 400;
}

.filters {
  display: flex;
  gap: 18px;
}

.filter-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
}

.legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 4px;
  border: 1px solid var(--tu-border);
}

.dot.current {
  background: var(--tu-accent);
  border-color: var(--tu-accent);
}

.dot.answered {
  background: rgba(43, 58, 103, 0.35);
}

.dot.marked {
  background: var(--tu-warning);
  border-color: var(--tu-warning);
}

.grid-wrap,
.grid-virtual {
  max-height: 46vh;
  overflow-y: auto;
}

.grid-row {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 6px;
  margin-bottom: 6px;
}

.cell {
  min-width: 36px;
  min-height: 32px;
  padding: 2px;
  justify-content: center;
  position: relative;
  font-variant-numeric: tabular-nums;
}

.cell.answered {
  background: rgba(43, 58, 103, 0.14);
  border-color: transparent;
}

.cell.current {
  background: var(--tu-accent);
  color: #fff;
  box-shadow: 0 0 0 2px rgba(124, 58, 237, 0.3);
}

.cell.marked::after {
  content: '';
  position: absolute;
  top: 3px;
  right: 3px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--tu-warning);
}

.empty-hint {
  font-size: 13px;
}
</style>
