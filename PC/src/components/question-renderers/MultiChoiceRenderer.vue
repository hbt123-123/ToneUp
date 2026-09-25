<script setup lang="ts">
import { computed } from 'vue'
import OptionRow from './OptionRow.vue'
import type { QuestionContext } from './types'

/** MULTI（多选，预留扩容，§6.4.2）：复选交互；至少选中一项才允许提交 */
const props = defineProps<{ ctx: QuestionContext }>()

const options = computed(() => props.ctx.question.options ?? [])

// M-477 同口径：label 统一 trim+upper 归一化比较（与 SingleChoiceRenderer 一致）
function normLabel(label: string): string {
  return label.trim().toUpperCase()
}

// 原始已选项（M-465 同源防御：answer 元素类型不可信，仅收窄字符串项）
const selectedItems = computed<string[]>(() =>
  Array.isArray(props.ctx.answer)
    ? props.ctx.answer.filter((v): v is string => typeof v === 'string')
    : [],
)

// 归一化后的已选集合，供 isSelected O(1) 查询
const selectedNorm = computed<ReadonlySet<string>>(() => new Set(selectedItems.value.map(normLabel)))

function isSelected(label: string): boolean {
  return selectedNorm.value.has(normLabel(label))
}

// M-470：正确答案集合解析——优先 attempt 反馈载荷的 correct_answer（运行时收窄，契约未固化），
// 回退 question.answer_text；支持 "ABD" 与 "A,B,D" 两种形态，统一大写归一化
function extractCorrectLabels(): Set<string> {
  const fb: unknown = props.ctx.grading?.feedback ?? null
  const fbCorrect = (fb as { correct_answer?: unknown } | null)?.correct_answer
  const detail = props.ctx.question as typeof props.ctx.question & { answer_text?: string | null }
  const raw =
    typeof fbCorrect === 'string' && fbCorrect.trim()
      ? fbCorrect
      : (detail.answer_text ?? '')
  return new Set(raw.match(/[A-Za-z]/g)?.map((ch) => ch.toUpperCase()) ?? [])
}

// M-470：补 optionState 计算（参照 SingleChoiceRenderer）：showAnswer 后正确项高亮、错选标红
function optionState(label: string): 'none' | 'correct' | 'wrong' {
  if (!props.ctx.showAnswer) return 'none'
  const correct = extractCorrectLabels()
  if (correct.size === 0) return 'none'
  const norm = normLabel(label)
  if (correct.has(norm)) return 'correct'
  if (isSelected(label)) return 'wrong'
  return 'none'
}

function toggle(label: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  let next: string[]
  if (isSelected(label)) {
    // M-477：归一化移除，避免大小写差异残留重复项
    const norm = normLabel(label)
    next = selectedItems.value.filter((l) => normLabel(l) !== norm).sort()
  } else {
    next = [...selectedItems.value, label].sort()
  }
  // M-471：全部取消后上报 null（未作答语义），空选择不再以 [] 形态绕过工作台空答案拦截；
  // 底部提示已同步展示"至少选中一项才能提交"
  props.ctx.onAnswerChange(next.length > 0 ? next : null)
}
</script>

<template>
  <div class="multi-renderer">
    <div class="option-grid">
      <OptionRow
        v-for="opt in options"
        :key="opt.label"
        :label="opt.label"
        :text="opt.text"
        :selected="isSelected(opt.label)"
        :state="optionState(opt.label)"
        :disabled="ctx.disabled || ctx.readonly"
        @select="toggle(opt.label)"
      />
    </div>
    <p class="hint">
      已选 {{ selectedItems.length }} 项（可多选）{{ selectedItems.length === 0 ? ' · 至少选中一项才能提交' : '' }}
    </p>
  </div>
</template>

<style scoped>
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
</style>