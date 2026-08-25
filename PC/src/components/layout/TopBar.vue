<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NDropdown } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { usePracticeStore } from '@/stores/practice'
import { useReviewStore } from '@/stores/review'
import { useStatsStore } from '@/stores/stats'
import { useUiStore } from '@/stores/ui'
import { useWrongBookStore } from '@/stores/wrongbook'

/** 顶栏（§4.1）：左面包屑，右用户菜单（退出、主题切换、动效开关） */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const stats = useStatsStore()
const ui = useUiStore()
const review = useReviewStore()
const wrongbook = useWrongBookStore()
const practice = usePracticeStore()

type DropdownOption = { key: string; label?: string; type?: 'divider' }

const userOptions = computed<DropdownOption[]>(() => [
  { key: 'theme', label: `主题：${ui.isDark ? '暗色' : '浅色'}` },
  { key: 'motion', label: `动效：${ui.motionEnabled ? '开' : '关'}` },
  { key: 'divider', type: 'divider' },
  { key: 'logout', label: '退出登录' },
])

function onUserAction(key: string | number): void {
  if (key === 'theme') ui.toggleTheme()
  else if (key === 'motion') {
    ui.motionEnabled = !ui.motionEnabled
    document.documentElement.classList.toggle('no-motion', !ui.motionEnabled)
    document.documentElement.classList.toggle('force-motion', ui.motionEnabled)
  } else if (key === 'logout') {
    auth.logout()
    catalog.reset()
    stats.reset()
    // 清理其余 per-user 状态，防止同浏览器换账号后残留上一用户的徽标/缓存
    review.reset()
    wrongbook.reset()
    practice.resetSession()
    void router.push('/login')
  }
}

/* 非题库流程页面显示页面名 */
const showBreadcrumbFlow = computed(() => ['catalog', 'practice'].includes(String(route.name)))
</script>

<template>
  <header class="topbar">
    <div class="left">
      <slot name="breadcrumb">
        <span class="page-name">{{ (route.meta.title as string) ?? '' }}</span>
      </slot>
      <slot v-if="showBreadcrumbFlow" name="breadcrumb-catalog" />
    </div>
    <div class="right">
      <n-dropdown trigger="click" :options="userOptions" @select="onUserAction">
        <button type="button" class="user-btn option-row">
          <span class="username">{{ auth.user?.username ?? '未登录' }}</span>
          <span class="caret" aria-hidden="true">▾</span>
        </button>
      </n-dropdown>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: var(--tu-topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
  border-bottom: 1px solid var(--tu-border);
  background: var(--tu-surface);
  position: sticky;
  top: 0;
  z-index: 20;
}

.left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.page-name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  font: inherit;
  color: var(--tu-text);
  padding: 6px 10px;
  border-radius: var(--tu-radius-control);
  min-height: 44px;
}

.username {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret {
  font-size: 12px;
  color: var(--tu-text-secondary);
}
</style>
