<script setup lang="ts">
import { computed } from 'vue'
import { NInput } from 'naive-ui'
import type { QuestionContext } from './types'

/**
 * FILL_BLANK（数学填空，§6.4.4）：
 * - 多空按 sub_questions 或 content 占位顺序生成输入框；
 * - V1 不做实时公式预览，提示"支持纯文本表达"；
 * - 答案为按空序的字符串数组。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const blankCount = computed(() => {
  const subs = props.ctx.question.sub_questions
  if (Array.isArray(subs) && subs.length > 0) return subs.length
  const content = props.ctx.question.content ?? ''
  const markers = content.match(/_{3,}/g)
  return Math.max(1, markers?.length ?? 1)
})

// M-465：answer 元素类型不可信（后端可能下发 number/null），逐项归一化 String(x)；
// null/undefined 归一化为 '' 占位保持空位索引，不再混入非字符串元素
const answers = computed<string[]>(() =>
  Array.isArray(props.ctx.answer)
    ? props.ctx.answer.map((v) => (v === null || v === undefined ? '' : String(v)))
    : [],
)

function valueOf(index: number): string {
  return answers.value[index] ?? ''
}

function update(index: number, value: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  // H-142：与 ClozeRenderer 一致，历史答案可能比当前空数长，取较大长度避免截断丢失
  const base = answers.value
  const next = Array.from({ length: Math.max(blankCount.value, base.length) }, (_, i) => base[i] ?? '')
  next[index] = value
  props.ctx.onAnswerChange(next)
}

function isBlankWrong(index: number): boolean {
  /* 逐空判分结构以后端契约为准：若反馈携带 per_blank 布尔数组则展示，否则不标错 */
  const fb: unknown = props.ctx.grading?.feedback ?? null
  const per = (fb as { per_blank?: unknown } | null)?.per_blank
  if (!Array.isArray(per)) return false
  // M-466：严格解析——仅 boolean true 视为该空正确；false/null/0/'false' 等均标错，
  // 越界（undefined，反馈未覆盖该空）不标错
  const v = per[index]
  return v !== undefined && v !== true
}
</script>

<template>
  <div class="fill-renderer">
    <div class="blanks">
      <label v-for="i in blankCount" :key="i" class="blank-row">
        <span class="blank-label">第 {{ i }} 空</span>
        <n-input
          :value="valueOf(i - 1)"
          :disabled="ctx.disabled || ctx.readonly"
          placeholder="输入答案，支持纯文本表达"
          :status="isBlankWrong(i - 1) ? 'error' : undefined"
          @update:value="(v: string) => update(i - 1, v)"
        />
      </label>
    </div>
    <p class="hint text-secondary">支持纯文本表达式；提交后由系统判分</p>
    <slot name="analysis" />
  </div>
</template>

<style scoped>
.blanks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 560px;
}

.blank-row {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 12px;
}

.blank-label {
  font-size: 14px;
  color: var(--tu-text-secondary);
  white-space: nowrap;
}

.hint {
  margin-top: 8px;
  font-size: 13px;
}
</style>
