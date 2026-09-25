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

// M-474：label→卡片索引表，computed 建一次 Map，替换 labelOf 的 O(n) 每行两次线性扫描
const cardByLabel = computed<Map<string, OptionItem>>(() => {
  const map = new Map<string, OptionItem>()
  for (const c of cards.value) map.set(c.label, c)
  return map
})

// M-476：渲染与交互统一使用净化后的序列——过滤不在选项集内的项（过期服务端序列、损坏草稿）
// 与重复项；空/非数组 answer 回退原始顺序。无效 label 不再整行渲染（M-475 行为修正）
const displayOrder = computed<string[]>(() => {
  const fallback = cards.value.map((c) => c.label)
  const ans = props.ctx.answer
  const list = Array.isArray(ans) && ans.length > 0 ? ans : fallback
  const seen = new Set<string>()
  const out: string[] = []
  for (const id of list) {
    if (typeof id === 'string' && !seen.has(id) && cardByLabel.value.has(id)) {
      seen.add(id)
      out.push(id)
    }
  }
  // 全部无效（极端脏数据）时回退原始顺序，避免渲染空列表
  return out.length > 0 ? out : fallback
})

// 受控同步：以净化序列为唯一事实源（computed 缓存引用，避免每次调用重复分配数组）
function currentOrder(): string[] {
  return displayOrder.value
}

// H-144：删除 order 影子 ref——所有操作直接以 currentOrder()（ctx.answer）为唯一事实源，
// 避免 draft 恢复/换题时影子状态与真实答案漂移错位
function commit(next: string[]): void {
  props.ctx.onAnswerChange([...next])
}

function move(index: number, delta: -1 | 1): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  const cur = currentOrder()
  const target = index + delta
  if (target < 0 || target >= cur.length) return
  const next = [...cur]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item as string)
  commit(next)
}

/* 原生拖拽 */
const dragFromLabel = ref<string | null>(null)
const dragOver = ref<number | null>(null)

function onDragStart(index: number, event: DragEvent): void {
  if (props.ctx.readonly || props.ctx.disabled) {
    event.preventDefault()
    return
  }
  // H-146：记录卡片 label 而非渲染索引——draft 恢复等场景下索引可能失效
  dragFromLabel.value = currentOrder()[index] ?? null
  event.dataTransfer?.setData('text/plain', String(index))
}

function onDragOver(index: number): void {
  dragOver.value = index
}

function onDrop(index: number): void {
  if (dragFromLabel.value === null || props.ctx.readonly || props.ctx.disabled) return
  const next = [...currentOrder()]
  // H-145：以 label 定位起点，防拖拽期间答案被外部更新导致索引漂移
  const from = next.indexOf(dragFromLabel.value)
  if (from === -1) {
    dragFromLabel.value = null
    dragOver.value = null
    return
  }
  const [moved] = next.splice(from, 1)
  next.splice(index, 0, moved as string)
  commit(next)
  dragFromLabel.value = null
  dragOver.value = null
}

// M-474：Map 查找 O(1)；displayOrder 已过滤无效 label，此处 undefined 仅剩防御兜底
function labelOf(id: string): OptionItem | undefined {
  return cardByLabel.value.get(id)
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
        :class="{ dragging: dragOver === index && dragFromLabel !== null && dragFromLabel !== id }"
        draggable="true"
        @dragstart="onDragStart(index, $event)"
        @dragover.prevent="onDragOver(index)"
        @drop.prevent="onDrop(index)"
        @dragend="() => { dragFromLabel = null; dragOver = null }"
      >
        <div class="seq-head">
          <span class="seq-badge">{{ index + 1 }}</span>
          <!-- M-475：无效 label 不再原样回显误导（displayOrder 已过滤），此处仅防御性兜底 -->
          <span class="orig-label">卡片 {{ labelOf(id)?.label ?? '?' }}</span>
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
