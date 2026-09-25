<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NDropdown, NModal } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useStatsStore } from '@/stores/stats'
import { useUiStore } from '@/stores/ui'
import ThemeSwitcher from '@/components/common/ThemeSwitcher.vue'

/** 顶栏（§4.1）：左面包屑，右用户菜单（主题切换、退出登录） */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const stats = useStatsStore()
const ui = useUiStore()

const showThemePanel = ref(false)

type DropdownOption = { key: string; label?: string; type?: 'divider' }

const userOptions = computed<DropdownOption[]>(() => [
  { key: 'themePanel', label: '🎨 主题设置' },
  { key: 'theme', label: `🌓 ${ui.isDark ? '浅色模式' : '暗色模式'}` },
  { key: 'divider', type: 'divider' },
  { key: 'logout', label: '退出登录' },
])

async function onUserAction(key: string | number): Promise<void> {
  if (key === 'themePanel') showThemePanel.value = true
  else if (key === 'theme') ui.toggleTheme()
  else if (key === 'logout') {
    // M-458：复用 auth.logout（async）——其内部已 clearAllUserDomainData 清用户域持久数据，
    // 并重置 practice/wrongbook/review 三个 store，不再在此手动重复调用这三个 reset；
    // await 清理完成后才跳转登录页，避免导航后命中上一用户的残留缓存
    await auth.logout()
    // auth.logout 未覆盖的内存态：目录选择与统计视图（其持久化部分已随清理移除）
    catalog.reset()
    stats.reset()
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

    <!-- 主题设置弹窗 -->
    <n-modal v-model:show="showThemePanel" preset="card" title="🎨 主题设置" style="width: 400px">
      <ThemeSwitcher />
    </n-modal>
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