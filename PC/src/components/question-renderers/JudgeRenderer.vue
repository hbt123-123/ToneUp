<script setup lang="ts">
import { computed } from 'vue'
import type { QuestionContext } from './types'

/** JUDGE（判断，预留扩容，§6.4.3）：对/错两个大按钮（≥44px 命中区），快捷键 A/B */
const props = defineProps<{ ctx: QuestionContext }>()

// M-467：持久化答案的大小写/别名归一化（'a'、'B '→B，'对'/'true'→A，'错'/'false'→B），
// 恢复草稿/旧编码答案不再被判为空
function normalizeJudgeAnswer(v: unknown): 'A' | 'B' | null {
  if (typeof v !== 'string') return null
  const s = v.trim().toLowerCase()
  if (s === 'a' || s === '对' || s === '正确' || s === 'true') return 'A'
  if (s === 'b' || s === '错' || s === '错误' || s === 'false') return 'B'
  return null
}

const answer = computed(() => normalizeJudgeAnswer(props.ctx.answer))

function choose(value: 'A' | 'B'): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  // M-468：点击已选项保持选中，不再上报 null 静默清空（避免草稿被清、提交被空答案拦截）
  props.ctx.onAnswerChange(value)
}

const correctLabel = computed<'A' | 'B' | null>(() => {
  const detail = props.ctx.question as typeof props.ctx.question & { answer_text?: string | null }
  // M-469：先剥离「正确答案/参考答案/标准答案/答案」前缀（兼容全/半角冒号与空白）再取 A/B，
  // "答案：A"、"参考答案: B" 等常见形态不再 fall through；"不正确/不对" 归入 B 侧
  const raw = (detail.answer_text ?? '')
    .trim()
    .replace(/^(?:正确答案|参考答案|标准答案|答案)\s*[：:]\s*/, '')
    .trim()
  if (/^(A|对|正确|true)/i.test(raw)) return 'A'
  if (/^(B|错|错误|不正确|不对|false)/i.test(raw)) return 'B'
  return null
})

const correctAnswerText = computed<string>(() => {
  if (correctLabel.value === 'A') return 'A（正确）'
  if (correctLabel.value === 'B') return 'B（错误）'
  return '暂无法解析正确答案'
})
</script>

<template>
  <div class="judge-renderer">
    <div class="judge-row">
      <button
        type="button"
        class="judge-btn option-row"
        :class="{
          active: answer === 'A',
          good: ctx.showAnswer && correctLabel === 'A',
          bad: ctx.showAnswer && correctLabel === 'B' && answer === 'A',
        }"
        :disabled="ctx.disabled || ctx.readonly"
        @click="choose('A')"
      >
        <span class="glyph">✓</span>
        <span>正确<span class="key-hint">（A）</span></span>
      </button>
      <button
        type="button"
        class="judge-btn option-row"
        :class="{
          active: answer === 'B',
          good: ctx.showAnswer && correctLabel === 'B',
          bad: ctx.showAnswer && correctLabel === 'A' && answer === 'B',
        }"
        :disabled="ctx.disabled || ctx.readonly"
        @click="choose('B')"
      >
        <span class="glyph">✕</span>
        <span>错误<span class="key-hint">（B）</span></span>
      </button>
    </div>
    <div v-if="ctx.showAnswer" class="answer-line text-secondary">
      正确答案：{{ correctAnswerText }}
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
