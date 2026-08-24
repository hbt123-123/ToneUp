<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import type { QuestionContext } from './types'
import type { OptionItem } from '@/api/generated/schema'

/**
 * CLOZE（英语完形填空，§6.4.6）：
 * - passage 面板编号空位高亮；每空对应一个输入位（选项结构存在时为小选择器，否则文本框）；
 * - 空位间快速跳转（上一空/下一空按钮）；答案按空序号数组保存；
 * - 提交后逐空标注对错（以服务端返回为准）。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const blankCount = computed(() => {
  const subs = props.ctx.question.sub_questions
  if (Array.isArray(subs) && subs.length > 0) return subs.length
  const passage = props.ctx.question.passage ?? ''
  const matches = passage.match(/_{2,}\s*\d*|_{2,}|\(\s*\d+\s*\)/g)
  return Math.max(1, matches?.length ?? 1)
})

/** 若契约 options 与空数一致则视为逐空选项，渲染选择器 */
const perBlankOptions = computed<OptionItem[] | null>(() => {
  const opts = props.ctx.question.options
  if (Array.isArray(opts) && opts.length === blankCount.value && blankCount.value > 1) return opts
  return null
})

const answers = computed<string[]>(() =>
  Array.isArray(props.ctx.answer) ? (props.ctx.answer as string[]) : [],
)
const activeBlank = ref(0)

function valueOf(index: number): string {
  return answers.value[index] ?? ''
}

function update(index: number, value: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  const next = Array.from({ length: blankCount.value }, (_, i) => valueOf(i))
  next[index] = value
  props.ctx.onAnswerChange(next)
}

function focusBlank(index: number): void {
  activeBlank.value = Math.min(blankCount.value - 1, Math.max(0, index))
}

function selectState(index: number): 'error' | undefined {
  /* 逐空对错以后端契约为准 */
  const fb: unknown = props.ctx.grading?.feedback ?? null
  const per = (fb as { per_blank?: unknown } | null)?.per_blank
  return Array.isArray(per) && per[index] === false ? 'error' : undefined
}
</script>

<template>
  <div class="cloze-renderer">
    <div v-if="ctx.question.passage" class="passage-panel tu-card">
      <div class="panel-title">完形填空 · 原文</div>
      <RichText :content="ctx.question.passage" :collapse-lines="30" />
    </div>

    <div class="blanks-area">
      <div class="blank-grid">
        <div
          v-for="i in blankCount"
          :key="i"
          class="blank-cell"
          :class="{ active: activeBlank === i - 1, error: selectState(i - 1) === 'error' }"
        >
          <span class="blank-index">{{ i }}</span>
          <n-select
            v-if="perBlankOptions"
            size="small"
            filterable
            :value="valueOf(i - 1) || null"
            :options="perBlankOptions.map((o) => ({ label: o.label, value: o.label }))"
            :disabled="ctx.disabled || ctx.readonly"
            :status="selectState(i - 1)"
            @update:value="(v: string) => update(i - 1, String(v))"
            @focus="focusBlank(i - 1)"
          />
          <n-input
            v-else
            size="small"
            :value="valueOf(i - 1)"
            :disabled="ctx.disabled || ctx.readonly"
            :status="selectState(i - 1)"
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

.blank-cell.error {
  border-color: var(--tu-error);
}

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
