<script setup lang="ts">
import { computed, onUnmounted, watch } from 'vue'
import { NConfigProvider, NDialogProvider, NMessageProvider, NGlobalStyle, darkTheme, zhCN, dateZhCN } from 'naive-ui'
import type { GlobalTheme } from 'naive-ui'
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

const theme = computed<GlobalTheme | null>(() => (ui.isDark ? darkTheme : null))

/** 昔涟（sakura-pink）主题开启视频背景；天空蓝（sky-blue）主题开启 webp 图片背景 */
const isXilianTheme = computed(() => ui.colorTheme === 'sakura-pink')
const isSkyTheme = computed(() => ui.colorTheme === 'sky-blue')

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
  const expected = ui.colorTheme || ''
  if (document.documentElement.dataset.theme !== expected) {
    if (expected) document.documentElement.dataset.theme = expected
    else delete document.documentElement.dataset.theme
  }
})
themeGuard.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
onUnmounted(() => themeGuard.disconnect())

const themeOverrides = computed(() => {
  // 带彩色底的主题：body 底色按主题+明暗覆盖 Naive UI 默认注入的白色 [浅色, 深色]
  const THEME_BODY_COLORS: Record<string, [string, string]> = {
    'sakura-pink': ['#ffe4ec', '#1c1216'],
    'sky-blue': ['#dbeefc', '#0d1b26'],
  }
  const bodyColors = THEME_BODY_COLORS[ui.colorTheme]
  return {
    common: {
      primaryColor: '#2B3A67',
      primaryColorHover: '#3A4D85',
      primaryColorPressed: '#22305A',
      primaryColorSuppl: '#7C3AED',
      infoColor: '#2080F0',
      successColor: '#18A058',
      warningColor: '#F0A020',
      errorColor: '#D03050',
      borderRadius: '4px',
      borderRadiusSmall: '3px',
      fontSize: '14px',
      lineHeight: '1.75',
      ...(bodyColors ? { bodyColor: ui.isDark ? bodyColors[1] : bodyColors[0] } : {}),
    },
  }
})
</script>

<template>
  <n-config-provider :theme="theme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-message-provider placement="top-right">
      <n-dialog-provider>
        <n-global-style />

        <!-- 主题专属全屏背景：昔涟为循环视频，天空蓝为 webp 图片，透明度均 0.3 -->
        <transition name="bg-fade">
          <video
            v-if="isXilianTheme"
            class="bg-media"
            src="/background/xilian/cyrene.webm"
            autoplay
            muted
            loop
            playsinline
          />
          <img
            v-else-if="isSkyTheme"
            class="bg-media bg-image"
            src="/background/sky/∞.webp"
            alt=""
          />
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
              <router-view v-slot="{ Component: PageComponent }">
                <transition name="page" mode="out-in">
                  <component :is="PageComponent" />
                </transition>
              </router-view>
            </main>
          </div>
        </div>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
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

.bg-fade-enter-from,
.bg-fade-leave-to {
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

/* 带彩色底的主题：让 html 与 body 锁定对应底色，覆盖 Naive UI 注入的 body 背景 */
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
</style>
