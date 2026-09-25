<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { NConfigProvider, NDialogProvider, NGlobalStyle, NMessageProvider, darkTheme, zhCN, dateZhCN } from 'naive-ui'
import type { GlobalTheme } from 'naive-ui'
import AppLayout from '@/components/layout/AppLayout.vue'
import { useUiStore } from '@/stores/ui'

/**
 * 根组件：M-429 将 provider 栈（配置/国际化/全局样式/消息/对话框）从 AppLayout 上移至此，
 * 包住全部路由渲染分支，裸路由（登录页）同样在栈内渲染；
 * 登录页等裸路由不套主布局，其余页面走侧栏+顶栏骨架。
 */
const route = useRoute()
const ui = useUiStore()

// M-428：裸路由判定改为由路由 meta.bare 驱动（login 路由已标注 bare: true），
// 不再硬编码 route.name === 'login'，路由重命名或新增裸页时不会静默失效
// M-430：初始导航未 resolve 时 route.name 为 undefined，此时按裸路由渲染（仅空 router-view），
// 避免直接落在裸页时先挂载 AppLayout 又立即卸载的首渲竞态
const bare = computed(() => route.name === undefined || route.meta.bare === true)

const theme = computed<GlobalTheme | null>(() => (ui.isDark ? darkTheme : null))

// M-443：带彩色底主题的 body 底色 [浅色, 深色]，作为 TS 侧单一来源；
// 需与 AppLayout 的 html[data-theme=...] 全局块及 styles/tokens.css 的 --tu-bg 保持同步
const THEME_BODY_COLORS: Record<string, [string, string]> = {
  'sakura-pink': ['#ffe4ec', '#1c1216'],
  'sky-blue': ['#dbeefc', '#0d1b26'],
  'firefly': ['#eef4e6', '#10140c'],
}

const themeOverrides = computed(() => {
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
        <!-- M-429：裸路由与布局路由统一在 provider 栈内渲染 -->
        <router-view v-if="bare" />
        <AppLayout v-else />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
