<script setup lang="ts">
import { computed, watch } from 'vue'
import { NConfigProvider, NDialogProvider, NMessageProvider, NGlobalStyle, darkTheme, zhCN, dateZhCN } from 'naive-ui'
import type { GlobalTheme } from 'naive-ui'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import BreadcrumbNav from './BreadcrumbNav.vue'
import { useUiStore } from '@/stores/ui'

/**
 * 布局骨架（§4）：左侧导航 + 顶栏 + 主区（内容最大宽 1280px 居中；
 * 刷题工作台通过路由 meta 允许全宽）。
 */
const ui = useUiStore()

const theme = computed<GlobalTheme | null>(() => (ui.isDark ? darkTheme : null))

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

const themeOverrides = {
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
  },
}
</script>

<template>
  <n-config-provider :theme="theme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-message-provider placement="top-right">
      <n-dialog-provider>
        <n-global-style />
        <div class="app-shell">
          <SideNav v-model:collapsed="ui.sidebarCollapsed" />
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
}

.main-column {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.content-area {
  flex: 1;
  padding: 20px 24px 40px;
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
</style>
