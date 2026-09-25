<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NInput } from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import type { QuestionContext } from './types'

/**
 * TRANSLATION（英语翻译，§6.4.8）：
 * - 左原文面板、右译文多行输入；词数统计为空格分词 + CJK 逐字计数（M-482）；
 * - 判分结果（评分/参考译文/批注）由解析视图承载。
 */
const props = defineProps<{ ctx: QuestionContext }>()

const text = ref(typeof props.ctx.answer === 'string' ? props.ctx.answer : '')

watch(
  () => props.ctx.answer,
  (v) => {
    // M-481：外部清空（null/undefined）也同步清空本地值，草稿清空/放弃修改后不再残留旧译文
    const next = typeof v === 'string' ? v : ''
    if (next !== text.value) text.value = next
  },
)

function onInput(value: string): void {
  if (props.ctx.readonly || props.ctx.disabled) return
  text.value = value
  props.ctx.onAnswerChange(value)
}

// M-482：与 EssayRenderer 同一计数逻辑——拉丁词按空白/全角标点分词，CJK 字符逐字计数；
// 中文译文整段无空格不再被计为 1 词
const CJK_CHAR_RE = /[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uac00-\ud7af]/g

function countWords(value: string): number {
  const cjkCount = value.match(CJK_CHAR_RE)?.length ?? 0
  const latinPart = value.replace(CJK_CHAR_RE, ' ')
  const latinWords = latinPart.split(/[\s，、。！？；：,.!?;:]+/).filter(Boolean).length
  return cjkCount + latinWords
}

const wordCount = computed(() => countWords(text.value))
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
