<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAvatar, NBadge } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useReviewStore } from '@/stores/review'

/** 左侧导航（§4.1）：240px 可折叠至 64px；管理项仅 admin 可见 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const review = useReviewStore()

interface NavItem {
  label: string
  icon: string
  to: string
  match: (name: string) => boolean
  badge?: () => number
}

const items = computed<NavItem[]>(() => {
  const base: NavItem[] = [
    { label: '首页', icon: '🏠', to: '/', match: (n) => n === 'home' },
    { label: '题库', icon: '📚', to: '/catalog', match: (n) => ['catalog', 'practice'].includes(n) },
    {
      label: '今日复习',
      icon: '⏰',
      to: '/review/today',
      match: (n) => n === 'review-today',
      badge: () => review.remainingCount,
    },
    { label: '错题本', icon: '📕', to: '/wrong-book', match: (n) => n === 'wrong-book' },
    { label: '统计', icon: '📊', to: '/stats', match: (n) => n === 'stats' },
    { label: '笔记', icon: '✏️', to: '/notes', match: (n) => n === 'notes' },
    { label: 'AI 纠错', icon: '🤖', to: '/ai-feedback', match: (n) => n === 'ai-feedback' },
  ]
  if (auth.isAdmin) {
    base.push({ label: '管理', icon: '🛠️', to: '/admin', match: (n) => n === 'admin' })
  }
  return base
})

const collapsedModel = defineModel<boolean>('collapsed', { default: false })

const activeTo = computed(() => items.value.find((it) => it.match(String(route.name)))?.to ?? null)
</script>

<template>
  <aside class="side-nav" :class="{ collapsed: collapsedModel }">
    <div class="brand" :title="collapsedModel ? 'ToneUp · 一潼上岸' : undefined">
      <span class="logo">ToneUp</span>
      <span v-if="!collapsedModel" class="slogan">一潼上岸</span>
    </div>

    <nav class="nav-list" aria-label="主导航">
      <a
        v-for="item in items"
        :key="item.to"
        href="#"
        class="nav-item option-row"
        :class="{ active: activeTo === item.to }"
        :title="collapsedModel ? `${item.label}${item.badge && item.badge() > 0 ? `（${item.badge()}）` : ''}` : undefined"
        @click.prevent="router.push(item.to)"
      >
        <span class="icon" aria-hidden="true">{{ item.icon }}</span>
        <span v-if="!collapsedModel" class="label">{{ item.label }}</span>
        <n-badge
          v-if="!collapsedModel && item.badge && item.badge() > 0"
          :value="item.badge()"
          :max="99"
          type="warning"
          class="badge"
        />
      </a>
    </nav>

    <div class="collapse-zone" :title="auth.user?.username">
      <n-avatar v-if="auth.user" round size="small" :style="{ backgroundColor: '#7c3aed' }">
        {{ auth.user.username.slice(0, 1).toUpperCase() }}
      </n-avatar>
    </div>
  </aside>
</template>

<style scoped>
.side-nav {
  display: flex;
  flex-direction: column;
  width: var(--tu-sidebar-width);
  flex: none;
  height: 100vh;
  position: sticky;
  top: 0;
  padding: 16px 10px;
  gap: 18px;
  background: var(--tu-sidebar-bg);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-right: 1px solid var(--tu-border);
  transition: width var(--tu-duration-expand) var(--tu-ease);
  overflow: hidden;
}

html.dark .side-nav {
  background: linear-gradient(180deg, #232736 0%, #1a1d27 100%);
}

.side-nav.collapsed {
  width: var(--tu-sidebar-collapsed-width);
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 0 8px;
  white-space: nowrap;
}

.logo {
  font-weight: 800;
  font-size: 20px;
  color: var(--tu-primary);
  letter-spacing: 0.5px;
}

.slogan {
  font-size: 12px;
  color: var(--tu-text-secondary);
}

.nav-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 8px 10px;
  border-radius: var(--tu-radius-card);
  color: var(--tu-text);
  text-decoration: none;
  white-space: nowrap;
  border: 1px solid transparent;
}

.nav-item.active {
  background: rgba(43, 58, 103, 0.1);
  border-color: rgba(124, 58, 237, 0.35);
  color: var(--tu-primary);
  font-weight: 600;
}

.icon {
  font-size: 18px;
  width: 24px;
  text-align: center;
  flex: none;
}

.label {
  flex: 1;
}

.badge {
  flex: none;
}

.collapse-zone {
  padding: 0 8px;
}
</style>
