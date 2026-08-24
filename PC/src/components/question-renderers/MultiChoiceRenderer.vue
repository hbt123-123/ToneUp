<script setup lang="ts">
import { computed } from 'vue'
import OptionRow from './OptionRow.vue'
import type { QuestionContext } from './types'

/** MULTI（多选，预留扩容，§6.4.2）：复选交互；至少选中一项才允许提交 */
const props = defineProps<{ ctx: QuestionContext }>()

const options = computed(() => props.ctx.question.options ?? [])
const selected = computed<string[]>(() =>
  Array.isArray(props.ctx.answer) ? (props.ctx.answer as string[]) : [],
)

function isSelected(label: string): boolean {
  return selected.value.includes(label)
}

function toggle(label: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  const next = isSelected(label)
    ? selected.value.filter((l) => l !== label)
    : [...selected.value, label].sort()
  props.ctx.onAnswerChange(next)
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
        :state="'none'"
        :disabled="ctx.disabled || ctx.readonly"
        @select="toggle(opt.label)"
      />
    </div>
    <p class="hint">
      已选 {{ selected.length }} 项（可多选）{{ selected.length === 0 ? ' · 至少选中一项才能提交' : '' }}
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
