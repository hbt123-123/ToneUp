<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton } from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import type { OptionItem } from '@/api/generated/schema'
import type { QuestionContext } from './types'

/**
 * ORDERING（英语新题型排序，§6.4.7）：
 * - 长段落卡片布局，段落完整展示可展开；
 * - 拖拽为主 + 上移/下移按钮保证键盘可达；序号徽标实时更新；
 * - 答案为卡片标识（label）的顺序数组。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const cards = computed<OptionItem[]>(() => props.ctx.question.options ?? [])

const order = ref<string[]>([])

// 受控同步：ctx.answer 为空时按原始顺序初始化
function currentOrder(): string[] {
  if (Array.isArray(props.ctx.answer) && (props.ctx.answer as unknown[]).length > 0) {
    return props.ctx.answer as string[]
  }
  return cards.value.map((c) => c.label)
}

function commit(next: string[]): void {
  order.value = next
  props.ctx.onAnswerChange([...next])
}

function ensureInit(): void {
  const cur = currentOrder()
  if (order.value.length !== cur.length || order.value.some((v, i) => v !== cur[i])) {
    order.value = cur
  }
}

function move(index: number, delta: -1 | 1): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  ensureInit()
  const target = index + delta
  if (target < 0 || target >= order.value.length) return
  const next = [...order.value]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item as string)
  commit(next)
}

/* 原生拖拽 */
const dragFrom = ref<number | null>(null)
const dragOver = ref<number | null>(null)

function onDragStart(index: number, event: DragEvent): void {
  if (props.ctx.readonly || props.ctx.disabled) {
    event.preventDefault()
    return
  }
  dragFrom.value = index
  event.dataTransfer?.setData('text/plain', String(index))
}

function onDragOver(index: number): void {
  dragOver.value = index
}

function onDrop(index: number): void {
  if (dragFrom.value === null || props.ctx.readonly || props.ctx.disabled) return
  ensureInit()
  const from = dragFrom.value
  const next = [...order.value]
  const [moved] = next.splice(from, 1)
  next.splice(index, 0, moved as string)
  commit(next)
  dragFrom.value = null
  dragOver.value = null
}

function labelOf(id: string): OptionItem | undefined {
  return cards.value.find((c) => c.label === id)
}
</script>

<template>
  <div class="ordering-renderer" @dragover.prevent>
    <p class="hint text-secondary">拖拽卡片排序；也可用每张卡片的 ↑/↓ 按钮（键盘可达）。提交后与正确序列逐位对照。</p>
    <ol class="card-list">
      <li
        v-for="(id, index) in currentOrder()"
        :key="id"
        class="seq-card option-row tu-card"
        :class="{ dragging: dragOver === index && dragFrom !== null && dragFrom !== index }"
        draggable="true"
        @dragstart="onDragStart(index, $event)"
        @dragover.prevent="onDragOver(index)"
        @drop.prevent="onDrop(index)"
        @dragend="() => { dragFrom = null; dragOver = null }"
      >
        <div class="seq-head">
          <span class="seq-badge">{{ index + 1 }}</span>
          <span class="orig-label">卡片 {{ labelOf(id)?.label ?? id }}</span>
          <span v-if="ctx.showAnswer" class="correct-slot text-secondary">标准答案位：{{ index + 1 }}</span>
          <span class="ops">
            <n-button size="tiny" quaternary :disabled="index <= 0 || ctx.disabled || ctx.readonly" aria-label="上移" @click="move(index, -1)">↑</n-button>
            <n-button size="tiny" quaternary :disabled="index >= currentOrder().length - 1 || ctx.disabled || ctx.readonly" aria-label="下移" @click="move(index, 1)">↓</n-button>
          </span>
        </div>
        <RichText :content="labelOf(id)?.text ?? ''" :collapse-lines="8" />
      </li>
    </ol>
  </div>
</template>

<style scoped>
.card-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.seq-card {
  padding: 12px 16px;
  cursor: grab;
  border: 1px solid var(--tu-border);
}

.seq-card.dragging {
  outline: 2px dashed var(--tu-accent);
}

.seq-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.seq-badge {
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--tu-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.orig-label {
  font-size: 13px;
  color: var(--tu-text-secondary);
}

.correct-slot {
  font-size: 12px;
}

.ops {
  margin-left: auto;
  display: flex;
  gap: 2px;
}

.hint {
  font-size: 13px;
  margin-top: 0;
}
</style>
