<script setup lang="ts">
import { computed } from 'vue'
import RichText from '@/components/common/RichText.vue'
import OptionRow from './OptionRow.vue'
import type { QuestionContext } from './types'
import { useLayoutMode } from '@/composables/useLayoutMode'

/**
 * SINGLE（数学单选 / 英语阅读单选 READING 同渲染，§6.4.1）：
 * - 选项横排展示，内容走公式与富文本渲染管线；
 * - 点击即选中并上报 answer；
 * - 英语阅读场景先渲染 passage 面板（宽屏左右分栏，窄屏上文下题）；
 * - showAnswer 后正确选项高亮、用户错选标红。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const { isCompactOrNarrower } = useLayoutMode()

const options = computed(() => props.ctx.question.options ?? [])

// M-477：label 统一 trim+upper 归一化——extractCorrectLabel 输出大写，而 opt.label 为原值，
// 直接 === 会因大小写/首尾空白差异漏配
function normalizeLabel(label: string): string {
  return label.trim().toUpperCase()
}

function isSelected(label: string): boolean {
  // M-477：answer（受控 unknown）先收窄字符串，再与 label 归一化比较
  return typeof props.ctx.answer === 'string' && normalizeLabel(props.ctx.answer) === normalizeLabel(label)
}

function optionState(label: string): 'none' | 'correct' | 'wrong' {
  if (!props.ctx.showAnswer) return 'none'
  const correctLabel = extractCorrectLabel(props.ctx)
  // M-477：统一归一化后比较；命中 correct 分支已返回，此处无需再比原值
  if (correctLabel && normalizeLabel(label) === correctLabel) return 'correct'
  if (isSelected(label) && correctLabel) return 'wrong'
  return 'none'
}

/** 正确答案标签提取：M-478 修正优先级——attempt 反馈载荷的 correct_answer 优先（契约未固化的
 * 运行时扩展字段，收窄读取），回退 question.answer_text 的 "A" 形态；输出统一 trim+upper（M-477 口径） */
function extractCorrectLabel(ctx: QuestionContext): string | null {
  const fb: unknown = ctx.grading?.feedback ?? null
  const fbCorrect = (fb as { correct_answer?: unknown } | null)?.correct_answer
  const detail = ctx.question as QuestionContext['question'] & { answer_text?: string | null }
  const raw =
    typeof fbCorrect === 'string' && fbCorrect.trim()
      ? fbCorrect
      : (detail.answer_text ?? '')
  if (!raw) return null
  const match = raw.trim().match(/^([A-Za-z])\b/)
  return match?.[1]?.toUpperCase() ?? null
}

function select(label: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  props.ctx.onAnswerChange(label)
}

/** 解析文本：来自详情接口的 solution（授权出口） */
const solutionText = computed<string>(
  () => (props.ctx.question as typeof props.ctx.question & { solution?: string | null }).solution ?? '',
)
</script>

<template>
  <div class="single-renderer" :class="{ split: !!ctx.question.passage && !isCompactOrNarrower }">
    <div v-if="ctx.question.passage" class="passage-panel tu-card">
      <div class="panel-title">阅读文章</div>
      <RichText :content="ctx.question.passage" :collapse-lines="26" />
    </div>
    <div class="options-area">
      <div class="option-grid">
        <OptionRow
          v-for="opt in options"
          :key="opt.label"
          :label="opt.label"
          :text="opt.text"
          :selected="isSelected(opt.label)"
          :state="optionState(opt.label)"
          :disabled="ctx.disabled || ctx.readonly"
          @select="select(opt.label)"
        />
      </div>
      <p v-if="!ctx.readonly && !ctx.disabled" class="hint">点击选项即选中；A/B/C/D 快捷键等效；Enter 确认提交</p>
      <div v-if="ctx.showAnalysis && solutionText" class="analysis-block">
        <RichText :content="solutionText" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.single-renderer.split {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.passage-panel {
  padding: 16px 18px;
  max-height: 70vh;
  overflow-y: auto;
}

.panel-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--tu-primary);
}

.option-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
}

.hint {
  margin-top: 8px;
  font-size: 13px;
  color: var(--tu-text-secondary);
}

.analysis-block {
  margin-top: 12px;
}
</style>