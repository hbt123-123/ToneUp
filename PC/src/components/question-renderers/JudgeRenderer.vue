<script setup lang="ts">
import { computed } from 'vue'
import type { QuestionContext } from './types'

/** JUDGE（判断，预留扩容，§6.4.3）：对/错两个大按钮（≥44px 命中区），快捷键 A/B */
const props = defineProps<{ ctx: QuestionContext }>()

const answer = computed(() => (props.ctx.answer === 'A' || props.ctx.answer === 'B' ? props.ctx.answer : null))

function choose(value: 'A' | 'B'): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  // 再次点击同一项视为取消选择
  props.ctx.onAnswerChange(answer.value === value ? null : value)
}

const correctLabel = computed<'A' | 'B' | null>(() => {
  const detail = props.ctx.question as typeof props.ctx.question & { answer_text?: string | null }
  const raw = detail.answer_text ?? ''
  if (/^(A|对|正确|true)/i.test(raw.trim())) return 'A'
  if (/^(B|错|错误|false)/i.test(raw.trim())) return 'B'
  return null
})
</script>

<template>
  <div class="judge-renderer">
    <div class="judge-row">
      <button
        type="button"
        class="judge-btn option-row"
        :class="{ active: answer === 'A', good: ctx.showAnswer && correctLabel === 'A' }"
        :disabled="ctx.disabled || ctx.readonly"
        @click="choose('A')"
      >
        <span class="glyph">✓</span>
        <span>正确<span class="key-hint">（A）</span></span>
      </button>
      <button
        type="button"
        class="judge-btn option-row"
        :class="{ active: answer === 'B', bad: ctx.showAnswer && correctLabel === 'B' && answer !== 'B' }"
        :disabled="ctx.disabled || ctx.readonly"
        @click="choose('B')"
      >
        <span class="glyph">✕</span>
        <span>错误<span class="key-hint">（B）</span></span>
      </button>
    </div>
    <div v-if="ctx.showAnswer" class="answer-line text-secondary">
      正确答案：{{ correctLabel === 'B' ? 'B（错误）' : 'A（正确）' }}
    </div>
  </div>
</template>

<style scoped>
.judge-row {
  display: flex;
  gap: 16px;
}

.judge-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 56px;
  font-size: 17px;
  background: var(--tu-surface);
  color: var(--tu-text);
}

.judge-btn.active {
  border-color: var(--tu-accent);
  box-shadow: 0 0 0 2px rgba(124, 58, 237, 0.25);
}

.judge-btn.good {
  border-color: var(--tu-success);
  background: rgba(24, 160, 88, 0.08);
}

.judge-btn.bad {
  border-color: var(--tu-error);
  background: rgba(208, 48, 80, 0.07);
}

.glyph {
  font-size: 20px;
  font-weight: 700;
}

.key-hint {
  color: var(--tu-text-secondary);
  font-size: 13px;
}

.answer-line {
  margin-top: 10px;
  font-size: 14px;
}
</style>
