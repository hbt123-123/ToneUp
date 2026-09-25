<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NInput } from 'naive-ui'
import type { QuestionContext } from './types'

/**
 * SOLUTION（数学解答，§6.4.5）：
 * - 多行输入自适应高度（约 8~20 行），字符计数；
 * - 提交后判分状态由解析视图承载；右栏展示官方 solution 与作答对照。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const MIN_ROWS = 8
const MAX_ROWS = 20
// M-480：输入长度上限——超大粘贴内容不再无限膨胀 ref/DOM 与后续 AI 判分载荷
const MAX_CHARS = 5000
const text = ref(typeof props.ctx.answer === 'string' ? props.ctx.answer : '')

watch(
  () => props.ctx.answer,
  (v) => {
    // H-147：外部清空（null/undefined）也要同步本地镜像，否则输入框残留旧答案
    const next = typeof v === 'string' ? v : ''
    if (next !== text.value) text.value = next
  },
)

function onInput(value: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  text.value = value
  // M-479：空白输入不外发——trim 后为空上报 null（未作答语义；QuestionContext：输入校验在组件内），
  // 草稿与提交载荷不再残留纯空白
  props.ctx.onAnswerChange(value.trim() ? value : null)
}

const charCount = computed(() => text.value.length)
</script>

<template>
  <div class="solution-renderer">
    <n-input
      :value="text"
      type="textarea"
      :disabled="ctx.disabled || ctx.readonly"
      :min-rows="MIN_ROWS"
      :max-rows="MAX_ROWS"
      :maxlength="MAX_CHARS"
      placeholder="写下完整解答过程：关键步骤、推导与结论"
      @update:value="onInput"
    />
    <div class="meta-line text-secondary">
      <span>{{ charCount }} 字符</span>
      <span>提交后将由 AI 批改，可继续浏览其他题目</span>
    </div>
  </div>
</template>

<style scoped>
.meta-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
  font-size: 13px;
}
</style>
