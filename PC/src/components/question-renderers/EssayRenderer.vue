<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NAlert, NInput } from 'naive-ui'
import type { QuestionContext } from './types'

/**
 * ESSAY（英语作文，§6.4.9）：
 * - 题目要求面板 + 大输入区；实时词数统计，接近/超出建议词数温和提示；
 * - 草稿强保留由工作台防抖 500ms 落盘承担。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const text = ref(typeof props.ctx.answer === 'string' ? props.ctx.answer : '')

watch(
  () => props.ctx.answer,
  (v) => {
    if (typeof v === 'string' && v !== text.value) text.value = v
  },
)

function onInput(value: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  text.value = value
  props.ctx.onAnswerChange(value)
}

const wordCount = computed(() => text.value.split(/\s+/).filter(Boolean).length)

/** 从题干提取建议词数（如 "about 200 words" / "词数不少于100"） */
const suggestedWords = computed<number | null>(() => {
  const content = props.ctx.question.content ?? ''
  const en = content.match(/(\d{2,4})\s*(?:words|word)/i)
  if (en?.[1]) return Number(en[1])
  const zh = content.match(/(\d{2,4})\s*词/)
  if (zh?.[1]) return Number(zh[1])
  return null
})

const wordHint = computed<'normal' | 'near' | 'over'>(() => {
  if (!suggestedWords.value) return 'normal'
  if (wordCount.value > Math.round(suggestedWords.value * 1.15)) return 'over'
  if (wordCount.value >= Math.round(suggestedWords.value * 0.85)) return 'near'
  return 'normal'
})
</script>

<template>
  <div class="essay-renderer">
    <n-alert
      v-if="suggestedWords"
      :type="wordHint === 'over' ? 'warning' : 'default'"
      :bordered="true"
      class="word-hint"
    >
      建议词数约 {{ suggestedWords }} 词，当前 {{ wordCount }} 词{{ wordHint === 'over' ? '，已超出建议范围' : wordHint === 'near' ? '，接近建议词数' : '' }}
    </n-alert>
    <n-input
      :value="text"
      type="textarea"
      :min-rows="14"
      :max-rows="26"
      :disabled="ctx.disabled || ctx.readonly"
      placeholder="在此完成你的作文。草稿会自动保存，意外关闭后可恢复。"
      @update:value="onInput"
    />
  </div>
</template>

<style scoped>
.essay-renderer {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.word-hint {
  font-size: 13px;
}
</style>
