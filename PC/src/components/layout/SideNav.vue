<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAvatar, NBadge } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useReviewStore } from '@/stores/review'

/** 左侧导航：默认隐藏，鼠标移入左侧触发区或点击触发器后滑入 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const review = useReviewStore()

const visible = ref(false)

/** 移出缓冲：鼠标滑过遮罩后 260ms 内回到侧边栏不关闭，避免误关 */
const CLOSE_GRACE_MS = 260
let closeTimer: ReturnType<typeof setTimeout> | null = null

function cancelScheduledClose(): void {
  if (closeTimer !== null) {
    clearTimeout(closeTimer)
    closeTimer = null
  }
}

function openNav(): void {
  cancelScheduledClose()
  visible.value = true
}

function scheduleClose(): void {
  cancelScheduledClose()
  closeTimer = setTimeout(() => {
    visible.value = false
    closeTimer = null
  }, CLOSE_GRACE_MS)
}

function closeNav(): void {
  cancelScheduledClose()
  visible.value = false
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && visible.value) closeNav()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  cancelScheduledClose()
})

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

const activeTo = computed(() => items.value.find((it) => it.match(String(route.name)))?.to ?? null)

function navTo(to: string): void {
  visible.value = false
  void router.push(to)
}
</script>

<template>
  <!-- 左侧触发区：悬停或点击均唤起侧边栏 -->
  <div class="nav-trigger" @mouseenter="openNav" @click="openNav">
    <span class="trigger-bar" />
  </div>

  <!-- 遮罩：移入稍候自动收起，点击立即收起 -->
  <transition name="overlay">
    <div v-if="visible" class="nav-overlay" @mouseenter="scheduleClose" @click="closeNav" />
  </transition>

  <!-- 侧边栏本体 -->
  <aside class="side-nav" :class="{ visible }" @mouseenter="cancelScheduledClose">
    <!-- 主题装饰：顶部浮动插画（昔涟/天空蓝等） -->
    <div class="side-deco-top" />

    <div class="brand">
      <span class="logo">ToneUp</span>
      <span class="slogan">一潼上岸</span>
    </div>

    <nav class="nav-list" aria-label="主导航">
      <a
        v-for="item in items"
        :key="item.to"
        href="#"
        class="nav-item"
        :class="{ active: activeTo === item.to }"
        @click.prevent="navTo(item.to)"
      >
        <span class="icon" aria-hidden="true">{{ item.icon }}</span>
        <span class="label">{{ item.label }}</span>
        <n-badge
          v-if="item.badge && item.badge() > 0"
          :value="item.badge()"
          :max="99"
          type="warning"
          class="badge"
        />
      </a>
    </nav>

    <!-- 主题装饰：底部插画 -->
    <div class="side-deco-bottom" />

    <div class="user-zone" :title="auth.user?.username">
      <n-avatar v-if="auth.user" round size="small" :style="{ backgroundColor: '#7c3aed' }">
        {{ auth.user.username.slice(0, 1).toUpperCase() }}
      </n-avatar>
      <span v-if="auth.user" class="username">{{ auth.user.username }}</span>
    </div>
  </aside>
</template>

<style scoped>
/* 左侧触发区：悬停/点击唤起侧边栏，宽度保证易命中 */
.nav-trigger {
  position: fixed;
  left: 0;
  top: 0;
  width: 28px;
  height: 100vh;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.trigger-bar {
  width: 5px;
  height: 72px;
  border-radius: 3px;
  background: var(--tu-accent);
  opacity: 0.5;
  box-shadow: 0 0 6px var(--tu-accent-glow);
  transition:
    opacity var(--tu-duration-micro) var(--tu-ease),
    height var(--tu-duration-micro) var(--tu-ease);
  /* 呼吸动画提示把手存在（no-motion 时被全局规则禁用） */
  animation: trigger-breathe 2.8s ease-in-out infinite;
}

@keyframes trigger-breathe {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.65; }
}

.nav-trigger:hover .trigger-bar {
  opacity: 1;
  height: 96px;
}

/* 遮罩层 */
.nav-overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(2px);
  -webkit-backdrop-filter: blur(2px);
}

.overlay-enter-active,
.overlay-leave-active {
  transition: opacity var(--tu-duration-expand) var(--tu-ease);
}

.overlay-enter-from,
.overlay-leave-to {
  opacity: 0;
}

/* 侧边栏本体：默认在屏幕外，visible 时滑入 */
.side-nav {
  position: fixed;
  left: 0;
  top: 0;
  width: var(--tu-sidebar-width);
  height: 100vh;
  z-index: 70;
  display: flex;
  flex-direction: column;
  padding: 20px 14px;
  gap: 18px;
  background: var(--tu-sidebar-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-right: 1px solid var(--tu-border);
  transform: translateX(-100%);
  transition: transform var(--tu-duration-expand) var(--tu-ease);
  overflow: hidden;
}

html.dark .side-nav {
  background: linear-gradient(180deg, #232736 0%, #1a1d27 100%);
}

.side-nav.visible {
  transform: translateX(0);
  box-shadow: var(--tu-shadow-pop);
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
  padding: 8px 12px;
  border-radius: var(--tu-radius-card);
  color: var(--tu-text);
  text-decoration: none;
  white-space: nowrap;
  border: 1px solid transparent;
  transition: background var(--tu-duration-micro) var(--tu-ease),
    border-color var(--tu-duration-micro) var(--tu-ease);
}

.nav-item:hover {
  background: var(--tu-accent-surface);
}

.nav-item.active {
  background: rgba(43, 58, 103, 0.1);
  border-color: rgba(124, 58, 237, 0.35);
  color: var(--tu-primary);
  font-weight: 600;
}

html.dark .nav-item.active {
  background: rgba(124, 58, 237, 0.15);
  color: var(--tu-accent);
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

.user-zone {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-top: 1px solid var(--tu-border);
}

.username {
  font-size: 13px;
  color: var(--tu-text-secondary);
}

/* ---------- 主题装饰（默认隐藏，昔涟/天空蓝规则见 src/styles/*.css） ---------- */

.side-deco-top {
  display: none;
}

.side-deco-bottom {
  display: none;
}

/*
 * 不能在这里用 `:global(html[data-theme=…]) .后代` 写法：scoped 编译会
 * 丢弃 :global() 之后的后代选择器，规则直接命中 <html> 导致整页布局坍塌。
 */
</style>
