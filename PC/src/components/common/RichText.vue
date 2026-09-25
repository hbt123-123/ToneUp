<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
// M-435：观察外层容器尺寸用的根元素与句柄
const wrapRef = ref<HTMLElement | null>(null)
let resizeObserver: ResizeObserver | null = null
// M-436：实测行高（px），折叠阈值与折叠高度统一以它为唯一来源；lineHeight 取不到时兜底 28
const lineHeightPx = ref(28)

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
  const rawLineHeight = parseFloat(window.getComputedStyle(el).lineHeight)
  lineHeightPx.value = Number.isFinite(rawLineHeight) && rawLineHeight > 0 ? rawLineHeight : 28
  overflowing.value = el.scrollHeight > lineHeightPx.value * props.collapseLines + 4
}

/** M-435：容器尺寸变化后重新测量（供 ResizeObserver / resize 监听共用） */
function scheduleCheckOverflow(): void {
  void checkOverflow()
}

onMounted(async () => {
  await refresh()
  checkOverflow()
  // M-435：容器宽度变化（窗口缩放/侧栏折叠/字体加载完成）会让溢出判定过期，
  // 观察外层容器尺寸重新测量；无 ResizeObserver 的环境退化为 window resize 监听
  if (typeof ResizeObserver !== 'undefined' && wrapRef.value) {
    resizeObserver = new ResizeObserver(scheduleCheckOverflow)
    resizeObserver.observe(wrapRef.value)
  } else {
    window.addEventListener('resize', scheduleCheckOverflow)
  }
})

// M-435：折叠行数变化直接影响阈值与折叠高度，需重新测量
watch(
  () => props.collapseLines,
  () => {
    void checkOverflow()
  },
)

watch(
  () => props.content,
  async () => {
    expanded.value = false
    await refresh()
    checkOverflow()
  },
)

// M-435：卸载时解除尺寸监听，防止观察器泄漏
onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  window.removeEventListener('resize', scheduleCheckOverflow)
})

// M-436：折叠高度改用实测行高换算（与 checkOverflow 阈值同源），
// 不再依赖 1.75em 的近似值，避免"判定溢出却裁掉更多/更少行"的不一致
const collapsedStyle = computed(() =>
  props.collapseLines > 0 ? { maxHeight: `${props.collapseLines * lineHeightPx.value}px` } : {},
)
</script>

<template>
  <!-- M-435：根元素加 wrapRef，供尺寸观察 -->
  <div ref="wrapRef" class="rich-text-wrap">
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