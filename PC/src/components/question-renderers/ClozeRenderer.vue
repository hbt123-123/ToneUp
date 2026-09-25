<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import type { QuestionContext } from './types'
import type { OptionItem } from '@/api/generated/schema'

/**
 * CLOZE（英语完形填空，§6.4.6）：
 * - passage 面板编号空位高亮；每空对应一个输入位（选项结构存在时为小选择器，否则文本框）；
 * - 空位间快速跳转（上一空/下一空按钮）；答案按空序号数组保存。
 * - M-462：移除「提交后逐空标注对错」——AttemptFeedback 无 per_blank 字段且客观题 grading 为 null，原判定为死代码。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const blankCount = computed(() => {
  const subs = props.ctx.question.sub_questions
  if (Array.isArray(subs) && subs.length > 0) return subs.length
  const passage = props.ctx.question.passage ?? ''
  // M-459：删除被首分支 _{2,}\s*\d* 完全覆盖的死分支 _{2,}
  const matches = passage.match(/_{2,}\s*\d*|\(\s*\d+\s*\)/g)
  return Math.max(1, matches?.length ?? 1)
})

// M-460：options 非空即返回（blankCount 恒 >=1）——单空 cloze 携带 options 时也显示选择器，
// 与 generic 分支行为对齐；多空 cloze 共享选项集（长度可与空数不同）同样按非空渲染选择器
const perBlankOptions = computed<OptionItem[] | null>(() => {
  const opts = props.ctx.question.options
  if (Array.isArray(opts) && opts.length > 0) return opts
  return null
})

const answers = computed<string[]>(() =>
  Array.isArray(props.ctx.answer) ? (props.ctx.answer as string[]) : [],
)
const activeBlank = ref(0)

// M-461：空数变化（换题/重算空位）时将 activeBlank 钳制到 [0, blankCount-1]，避免越界
watch(blankCount, (count) => {
  if (activeBlank.value > count - 1) activeBlank.value = count - 1
})

function valueOf(index: number): string {
  return answers.value[index] ?? ''
}

function update(index: number, value: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  // H-139：历史答案可能比当前空数长（换题/重算空位），数组长度取两者较大值避免截断丢失
  const base = answers.value
  const next = Array.from({ length: Math.max(blankCount.value, base.length) }, (_, i) => base[i] ?? '')
  next[index] = value
  props.ctx.onAnswerChange(next)
}

function focusBlank(index: number): void {
  activeBlank.value = Math.min(blankCount.value - 1, Math.max(0, index))
}
/* M-462：selectState 已删除（AttemptFeedback 无 per_blank 字段，逐空判错为死代码） */
</script>

<template>
  <div class="cloze-renderer">
    <div v-if="ctx.question.passage" class="passage-panel tu-card">
      <div class="panel-title">完形填空 · 原文</div>
      <RichText :content="ctx.question.passage" :collapse-lines="30" />
    </div>

    <div class="blanks-area">
      <!-- M-462：移除逐空判错（selectState / :status / error class） -->
      <div class="blank-grid">
        <div
          v-for="i in blankCount"
          :key="i"
          class="blank-cell"
          :class="{ active: activeBlank === i - 1 }"
        >
          <span class="blank-index">{{ i }}</span>
          <n-select
            v-if="perBlankOptions"
            size="small"
            filterable
            :value="valueOf(i - 1) || null"
            :options="perBlankOptions.map((o) => ({ label: o.label, value: o.label }))"
            :disabled="ctx.disabled || ctx.readonly"
            @update:value="(v: string | null) => update(i - 1, v ?? '')"
            @focus="focusBlank(i - 1)"
          />
          <n-input
            v-else
            size="small"
            :value="valueOf(i - 1)"
            :disabled="ctx.disabled || ctx.readonly"
            placeholder=""
            @update:value="(v: string) => update(i - 1, v)"
            @focus="focusBlank(i - 1)"
          />
        </div>
      </div>

      <div class="jump-bar">
        <n-button size="small" :disabled="activeBlank <= 0" @click="focusBlank(activeBlank - 1)">上一空</n-button>
        <span class="pos text-secondary">{{ activeBlank + 1 }} / {{ blankCount }}</span>
        <n-button size="small" :disabled="activeBlank >= blankCount - 1" @click="focusBlank(activeBlank + 1)">下一空</n-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cloze-renderer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.passage-panel {
  padding: 16px 18px;
  max-height: 46vh;
  overflow-y: auto;
}

.panel-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--tu-primary);
}

.blank-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
}

.blank-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  border-radius: var(--tu-radius-control);
  border: 1px solid transparent;
}

.blank-cell.active {
  border-color: var(--tu-accent);
  background: rgba(124, 58, 237, 0.06);
}

/* M-462：.blank-cell.error 样式随逐空判错一并移除 */

.blank-index {
  flex: none;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 12px;
  background: var(--tu-primary);
  color: #fff;
}

.jump-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pos {
  font-size: 13px;
}
</style>