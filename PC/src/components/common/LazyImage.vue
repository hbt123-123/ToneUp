<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { NImage, NSkeleton } from 'naive-ui'
import { imageUrl } from '@/api/endpoints'
import { loadToken } from '@/api/token'

/**
 * 题图懒加载（D-03 / §10.6）：进入视口才请求；固定宽高比占位骨架；
 * 失败显示可点击重试占位；点击放大查看。图片端点需鉴权头，故用 fetch+blob。
 */
const props = withDefaults(
  defineProps<{
    imageId: string | number
    bankId: string
    alt?: string
    aspectRatio?: number
  }>(),
  { aspectRatio: 4 / 3 },
)

type State = 'idle' | 'loading' | 'ready' | 'error'
const state = ref<State>('idle')
const objectUrl = ref<string | null>(null)
const holder = ref<HTMLElement | null>(null)

let observer: IntersectionObserver | null = null
let seq = 0

async function fetchImage(): Promise<void> {
  const mySeq = ++seq
  state.value = 'loading'
  try {
    const token = loadToken()
    const response = await fetch(imageUrl(props.imageId, props.bankId), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) throw new Error(`图片加载失败（HTTP ${response.status}）`)
    const blob = await response.blob()
    if (mySeq !== seq) return
    revoke()
    objectUrl.value = URL.createObjectURL(blob)
    state.value = 'ready'
  } catch {
    if (mySeq === seq) state.value = 'error'
  }
}

function revoke(): void {
  if (objectUrl.value) {
    URL.revokeObjectURL(objectUrl.value)
    objectUrl.value = null
  }
}

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          void fetchImage()
          observer?.disconnect()
          observer = null
        }
      }
    },
    { rootMargin: '200px' },
  )
  if (holder.value) observer.observe(holder.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  // 使在途请求失效：否则卸载后完成的 fetch 会给已卸载组件创建永不回收的 blob URL
  seq++
  revoke()
})

const skeletonStyle = computed(() => ({ aspectRatio: String(props.aspectRatio) }))
</script>

<template>
  <div ref="holder" class="lazy-image">
    <div v-if="state !== 'ready'" class="placeholder tu-card" :style="skeletonStyle">
      <template v-if="state === 'loading' || state === 'idle'">
        <n-skeleton width="100%" height="100%" :sharp="false" class="skeleton-fill" />
        <span class="ph-text">图片加载中…</span>
      </template>
      <button v-else type="button" class="retry" @click="fetchImage">
        图片加载失败，点击重试
      </button>
    </div>
    <n-image
      v-else-if="objectUrl"
      :src="objectUrl"
      :alt="alt ?? '题目配图'"
      object-fit="contain"
      class="img"
      lazy
    />
  </div>
</template>

<style scoped>
.lazy-image {
  max-width: min(560px, 100%);
  margin: 8px 0;
}

.placeholder {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--tu-radius-card);
  background: rgba(127, 127, 140, 0.08);
}

.skeleton-fill {
  position: absolute;
  inset: 0;
}

.ph-text {
  position: relative;
  z-index: 1;
  color: var(--tu-text-secondary);
  font-size: 13px;
}

.retry {
  border: none;
  background: none;
  color: var(--tu-accent);
  cursor: pointer;
  font-size: 14px;
  padding: 8px 16px;
}

.img {
  max-width: 100%;
  border-radius: var(--tu-radius-card);
}
</style>
