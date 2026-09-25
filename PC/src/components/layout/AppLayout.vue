<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import BreadcrumbNav from './BreadcrumbNav.vue'
import { useUiStore } from '@/stores/ui'

/**
 * 布局骨架（§4）：左侧导航 + 顶栏 + 主区（内容最大宽 1280px 居中；
 * 刷题工作台通过路由 meta 允许全宽）。
 * 昔涟主题专属：全屏循环 video 背景，透明度 0.3。
 */
const ui = useUiStore()

// M-429：theme / themeOverrides 已随 provider 栈上移至 App.vue

/** 昔涟（sakura-pink）主题开启视频背景；天空蓝（sky-blue）主题开启 webp 图片背景；流萤（firefly）主题开启 hh.svg 图片背景 */
const isXilianTheme = computed(() => ui.colorTheme === 'sakura-pink')
const isSkyTheme = computed(() => ui.colorTheme === 'sky-blue')
const isFireflyTheme = computed(() => ui.colorTheme === 'firefly')

watch(
  () => ui.isDark,
  (dark) => {
    document.documentElement.classList.toggle('dark', dark)
  },
  { immediate: true },
)

watch(
  () => ui.motionEnabled,
  (enabled) => {
    document.documentElement.classList.toggle('no-motion', !enabled)
    document.documentElement.classList.toggle('force-motion', enabled)
  },
  { immediate: true },
)

// 将 theme data 属性同步到 documentElement（昔涟背景 video 的 CSS 依赖此属性）
watch(
  () => ui.colorTheme,
  (t) => {
    if (t) document.documentElement.dataset.theme = t
    else delete document.documentElement.dataset.theme
  },
  { immediate: true },
)

// 防御：浏览器插件/外部脚本可能篡改 data-theme（如被改成 'light' 导致主题 CSS 全部失配），
// 监听到外部改动时立即纠正回 store 中的值
const themeGuard = new MutationObserver(() => {
  // M-442：dataset.theme 缺失时读取为 undefined，与 '' 比较恒不等会误判；
  // 统一归一为 null 口径再比较
  const expected = ui.colorTheme || null
  if ((document.documentElement.dataset.theme ?? null) !== expected) {
    if (expected) document.documentElement.dataset.theme = expected
    else delete document.documentElement.dataset.theme
  }
})
themeGuard.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
onUnmounted(() => themeGuard.disconnect())

// M-444：背景视频在标签页隐藏、系统要求减少动效或应用关闭动效时暂停，回前台恢复，节省 CPU/电量
const bgVideo = ref<HTMLVideoElement | null>(null)
const prefersReducedMotion =
  typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null

function syncBgVideo(): void {
  const video = bgVideo.value
  if (!video) return
  if (document.hidden || (prefersReducedMotion?.matches ?? false) || !ui.motionEnabled) {
    video.pause()
  } else {
    // 静音视频的编程播放不受自动播放策略限制；被拒绝时静默忽略
    void video.play().catch(() => undefined)
  }
}

watch(isXilianTheme, () => void nextTick(syncBgVideo))
watch(
  () => ui.motionEnabled,
  syncBgVideo,
)

onMounted(() => {
  syncBgVideo()
  document.addEventListener('visibilitychange', syncBgVideo)
  prefersReducedMotion?.addEventListener('change', syncBgVideo)
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', syncBgVideo)
  prefersReducedMotion?.removeEventListener('change', syncBgVideo)
})
</script>

<template>
  <!-- M-429：provider 栈已上移至 App.vue，本组件只保留布局骨架与背景层 -->
  <!-- 主题专属全屏背景：昔涟为循环视频，天空蓝为 webp 图片，透明度均 0.3 -->
  <transition name="bg-fade">
    <video
      v-if="isXilianTheme"
      ref="bgVideo"
      class="bg-media"
      src="/background/xilian/cyrene.webm"
      autoplay
      muted
      loop
      playsinline
    />
    <img v-else-if="isSkyTheme" class="bg-media bg-image" src="/background/sky/∞.webp" alt="" />
    <img v-else-if="isFireflyTheme" class="bg-media bg-image" src="/background/Firefly/hh.svg" alt="" />
  </transition>

  <div class="app-shell">
    <SideNav />
    <div class="main-column">
      <top-bar>
        <template #breadcrumb>
          <breadcrumb-nav />
        </template>
      </top-bar>
      <main class="content-area">
        <router-view v-slot="{ Component: PageComponent, route: viewRoute }">
          <transition name="page" mode="out-in">
            <!-- M-445：按路径键控，同组件不同参数（如切换 bankId）时强制重建，避免复用旧实例 -->
            <component :is="PageComponent" :key="viewRoute.path" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  min-height: 100vh;
  position: relative;
  z-index: 1;
}

/* 主题全屏背景媒体（昔涟 video / 天空蓝 webp 图片） */
.bg-media {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  object-fit: cover;
  opacity: 0.3;
  z-index: 0;
  pointer-events: none;
}

/* 天空蓝背景更实一些，星云细节更明显 */
.bg-media.bg-image {
  opacity: 0.45;
}

.bg-fade-enter-active,
.bg-fade-leave-active {
  transition: opacity 0.6s var(--tu-ease);
}

/* M-446：提高转场起止规则特异性（0,2,0）且置于 .bg-media.bg-image 之后，
   否则后者的 opacity:0.45 会覆盖起止态，导致背景图切换时不淡入淡出 */
.bg-media.bg-fade-enter-from,
.bg-media.bg-fade-leave-to {
  opacity: 0;
}

.main-column {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.content-area {
  flex: 1;
  padding: 0;
  min-width: 0;
}

/* 页面转场 300ms（§10.3）；no-motion 时由全局规则覆盖为瞬时 */
.page-enter-active,
.page-leave-active {
  transition:
    opacity var(--tu-duration-page) var(--tu-ease),
    transform var(--tu-duration-page) var(--tu-ease);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>

<style>
/* 全局：主区内容宽度约束；practice 等全宽页面自行解除 */
.content-inner {
  max-width: var(--tu-content-max-width);
  margin: 0 auto;
  width: 100%;
}

/* 带彩色底的主题：让 html 与 body 锁定对应底色，覆盖 Naive UI 注入的 body 背景。
   M-443：以下色值需与 App.vue 的 THEME_BODY_COLORS 及 styles/tokens.css 的 --tu-bg 保持同步 */
html[data-theme="sakura-pink"],
html[data-theme="sakura-pink"] body {
  background: #ffe4ec;
}
html.dark[data-theme="sakura-pink"],
html.dark[data-theme="sakura-pink"] body {
  background: #1c1216;
}
html[data-theme="sky-blue"],
html[data-theme="sky-blue"] body {
  background: #dbeefc;
}
html.dark[data-theme="sky-blue"],
html.dark[data-theme="sky-blue"] body {
  background: #0d1b26;
}
html[data-theme="firefly"],
html[data-theme="firefly"] body {
  background: #eef4e6;
}
html.dark[data-theme="firefly"],
html.dark[data-theme="firefly"] body {
  background: #10140c;
}
</style>
