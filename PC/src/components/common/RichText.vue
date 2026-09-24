<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import { renderRichText } from '@/utils/richtext'

/**
 * 统一富文本组件（第 7 章）：markdown → KaTeX → sanitize 管线的唯一出口。
 * 渲染失败不抛错；超长文本折叠并提供"展开全文"（§7.3）。
 */
const props = withDefaults(
  defineProps<{
    content: string | null | undefined
    /** 超过该行数时折叠；0 表示不折叠 */
    collapseLines?: number
  }>(),
  { collapseLines: 0 },
)

const html = ref('')
const failed = ref(false)
const overflowing = ref(false)
const expanded = ref(false)
const bodyRef = ref<HTMLElement | null>(null)

let renderSeq = 0

async function refresh(): Promise<void> {
  const seq = ++renderSeq
  const raw = props.content
  const result = await renderRichText(raw)
  if (seq !== renderSeq) return // 只应用最后一次渲染，防止切题闪烁
  html.value = result
  // H-136：内容本身为空不算渲染失败；只有"有内容却渲染不出"才展示兜底
  failed.value = !!raw && !result
}

async function checkOverflow(): Promise<void> {
  // H-137：v-html 的 DOM patch 在下一个微任务才落盘，先等 nextTick 再量 scrollHeight
  await nextTick()
  const el = bodyRef.value
  if (!el || !props.collapseLines) {
    overflowing.value = false
    return
  }
  const lineHeight = parseFloat(window.getComputedStyle(el).lineHeight) || 28
  overflowing.value = el.scrollHeight > lineHeight * props.collapseLines + 4
}

onMounted(async () => {
  await refresh()
  checkOverflow()
})

watch(
  () => props.content,
  async () => {
    expanded.value = false
    await refresh()
    checkOverflow()
  },
)

const collapsedStyle = computed(() =>
  props.collapseLines > 0 ? { maxHeight: `${props.collapseLines * 1.75}em` } : {},
)
</script>

<template>
  <div class="rich-text-wrap">
    <div v-if="failed" class="rich-fallback">内容渲染异常</div>
    <div
      v-else
      ref="bodyRef"
      class="rich-text"
      :style="overflowing && !expanded ? collapsedStyle : {}"
      :class="{ collapsed: overflowing && !expanded }"
      v-html="html"
    />
    <n-button
      v-if="overflowing"
      quaternary
      size="tiny"
      type="primary"
      class="expand-btn"
      @click="expanded = !expanded"
    >
      {{ expanded ? '收起' : '展开全文' }}
    </n-button>
  </div>
</template>

<style scoped>
.rich-text-wrap {
  position: relative;
  min-width: 0;
}

.rich-text.collapsed {
  overflow: hidden;
  -webkit-mask-image: linear-gradient(to bottom, #000 70%, transparent);
  mask-image: linear-gradient(to bottom, #000 70%, transparent);
}

.expand-btn {
  margin-top: 2px;
}

.rich-fallback {
  color: var(--tu-text-secondary);
  font-size: 14px;
}
</style>
