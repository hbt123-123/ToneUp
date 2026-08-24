<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NInput } from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import type { QuestionContext } from './types'

/**
 * TRANSLATION（英语翻译，§6.4.8）：
 * - 左原文面板、右译文多行输入；英文按空白分词计数；
 * - 判分结果（评分/参考译文/批注）由解析视图承载。
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
</script>

<template>
  <div class="translation-renderer">
    <div v-if="ctx.question.passage || ctx.question.content" class="source-panel tu-card">
      <div class="panel-title">原文</div>
      <RichText :content="ctx.question.passage ?? ctx.question.content" :collapse-lines="18" />
    </div>
    <div class="target-area">
      <n-input
        :value="text"
        type="textarea"
        :min-rows="7"
        :max-rows="16"
        :disabled="ctx.disabled || ctx.readonly"
        placeholder="在此输入你的译文"
        @update:value="onInput"
      />
      <span class="count text-secondary">{{ wordCount }} 词</span>
    </div>
  </div>
</template>

<style scoped>
.translation-renderer {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
}

@media (min-width: 1280px) {
  .translation-renderer {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    align-items: start;
  }
}

.source-panel {
  padding: 14px 16px;
  max-height: 52vh;
  overflow-y: auto;
}

.panel-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--tu-primary);
}

.target-area {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.count {
  align-self: flex-end;
  font-size: 13px;
}
</style>
