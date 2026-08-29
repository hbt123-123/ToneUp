<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NSkeleton } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useReviewStore } from '@/stores/review'
import { useStatsStore } from '@/stores/stats'
import { useUiStore } from '@/stores/ui'
import { readProgress } from '@/utils/storage'

/**
 * 首页（FR-HOME-01~05）：
 * 顶部 Hero 海报区 + 功能卡片网格 + 滚动入场动画
 */
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const review = useReviewStore()
const stats = useStatsStore()
const ui = useUiStore()

const booting = ref(true)
const lastSession = ref<{ bankId: string; bankName: string; lastIndex: number } | null>(null)

/** 格式化日期为中文格式：2026年8月28日 星期五 */
const formattedDate = computed(() => {
  const now = new Date()
  const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`
})

/** 连续打卡天数 */
const streakDays = computed(() => stats.overview.streak_days ?? 0)

/** 今日已刷题数 */
const todayAttempts = computed(() => stats.overview.today_attempts ?? 0)

/** 待复习题数 */
const reviewCount = computed(() => review.remainingCount)

onMounted(async () => {
  try {
    await Promise.allSettled([
      catalog.fetchCatalog(),
      stats.fetchOverview().catch(() => undefined),
      review.fetchQueue(50).catch(() => undefined),
    ])
    // 继续上次刷题：读取本地进度草稿（FR-HOME-03）
    const lastBankId = localStorage.getItem('toneup:last-bank') ?? ''
    if (lastBankId) {
      const saved = readProgress(auth.userId, lastBankId)
      const bank = saved ? catalog.bankById.get(lastBankId) : undefined
      if (saved && bank) {
        lastSession.value = {
          bankId: bank.id,
          bankName: bank.name,
          lastIndex: saved.lastIndex,
        }
      }
    }
  } finally {
    booting.value = false
    // 等 DOM 更新后初始化滚动动画
    await nextTick()
    initScrollAnimation()
  }
})

/* ---------- IntersectionObserver 滚动入场动画 ---------- */

let observer: IntersectionObserver | null = null

function initScrollAnimation(): void {
  // motionEnabled 为 false 时跳过动画，卡片直接可见
  if (!ui.motionEnabled) {
    document.querySelectorAll<HTMLElement>('.card').forEach((el) => {
      el.classList.add('visible')
    })
    return
  }

  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible')
          observer?.unobserve(entry.target)
        }
      }
      // 全部卡片可见后断开 observer
      const remaining = document.querySelectorAll('.card:not(.visible)')
      if (remaining.length === 0) {
        observer?.disconnect()
        observer = null
      }
    },
    { threshold: 0.1 },
  )

  document.querySelectorAll<HTMLElement>('.card').forEach((el) => {
    observer?.observe(el)
  })
}

onUnmounted(() => {
  observer?.disconnect()
  observer = null
})

/* ---------- 导航 ---------- */

function continuePractice(): void {
  if (!lastSession.value) return
  localStorage.setItem('toneup:last-bank', lastSession.value.bankId)
  void router.push({
    name: 'practice',
    params: { bankId: lastSession.value.bankId },
    query: { resume: String(lastSession.value.lastIndex) },
  })
}
</script>

<template>
  <div class="home-view">
    <!-- 骨架屏 -->
    <template v-if="booting">
      <div class="hero skeleton-hero">
        <n-skeleton height="100%" width="100%" :sharp="false" />
      </div>
      <div class="card-grid skeleton-grid">
        <n-skeleton height="140px" width="100%" :sharp="false" />
        <n-skeleton height="140px" width="100%" :sharp="false" />
        <n-skeleton height="140px" width="100%" :sharp="false" />
        <n-skeleton height="140px" width="100%" :sharp="false" />
      </div>
    </template>

    <template v-else>
      <!-- Part A: Hero 海报区 -->
      <section class="hero">
        <h1 class="hero-title">ToneUp</h1>
        <p class="hero-date">{{ formattedDate }}</p>
        <p class="hero-streak">
          <template v-if="streakDays > 0">
            已连续打卡 <strong>{{ streakDays }}</strong> 天
          </template>
          <template v-else>
            开始你的学习之旅
          </template>
        </p>
      </section>

      <!-- Part B + D: 响应式卡片网格 -->
      <div class="card-grid">
        <!-- 卡片 1: 今日目标 → /catalog -->
        <div class="card" @click="router.push('/catalog')">
          <div class="card-icon">🎯</div>
          <h3 class="card-title">今日目标</h3>
          <p class="card-desc">继续刷题，坚持就是胜利</p>
          <p class="card-meta">今日已刷 {{ todayAttempts }} 题</p>
        </div>

        <!-- 卡片 2: 继续上次 → /practice/:bankId -->
        <div class="card" @click="continuePractice">
          <div class="card-icon">📖</div>
          <h3 class="card-title">继续上次</h3>
          <template v-if="lastSession">
            <p class="card-desc">上次做到第 {{ lastSession.lastIndex + 1 }} 题</p>
            <p class="card-meta">{{ lastSession.bankName }}</p>
          </template>
          <template v-else>
            <p class="card-desc">还没有进行中的练习</p>
            <p class="card-meta">点击去题库选题</p>
          </template>
        </div>

        <!-- 卡片 3: 学科入口 → /catalog -->
        <div class="card" @click="router.push('/catalog')">
          <div class="card-icon">📚</div>
          <h3 class="card-title">学科入口</h3>
          <p class="card-desc">
            {{ catalog.subjects.length }} 个学科，2000+ 题
          </p>
          <p class="card-meta">浏览全部学科</p>
        </div>

        <!-- 卡片 4: 今日复习 → /review/today -->
        <div class="card" @click="router.push('/review/today')">
          <div class="card-icon">🔄</div>
          <h3 class="card-title">今日复习</h3>
          <template v-if="reviewCount > 0">
            <p class="card-desc">{{ reviewCount }} 题待复习</p>
            <p class="card-meta">点击开始复习</p>
          </template>
          <template v-else>
            <p class="card-desc">今日暂无到期复习</p>
            <p class="card-meta">保持节奏</p>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.home-view {
  max-width: var(--tu-content-max-width);
  margin: 0 auto;
  padding: 0 20px 40px;
}

/* ---------- Part A: Hero 海报区 ---------- */

.hero {
  height: 40vh;
  min-height: 280px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin: 0 -20px 32px;
  background: var(--tu-gradient-hero);
  border-radius: 0;
  text-align: center;
}

.hero-title {
  font-size: 48px;
  font-weight: 800;
  margin: 0;
  color: var(--tu-text);
  letter-spacing: -1px;
}

.hero-date {
  font-size: 16px;
  margin: 0;
  color: var(--tu-text-secondary);
}

.hero-streak {
  font-size: 20px;
  font-weight: 500;
  margin: 0;
  color: var(--tu-text);
}

.hero-streak strong {
  color: var(--tu-accent);
  font-weight: 700;
}

/* ---------- Part B + D: 卡片网格 ---------- */

.card-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.card {
  background: var(--tu-surface);
  border: 1px solid var(--tu-border);
  border-radius: var(--tu-radius-card);
  box-shadow: var(--tu-shadow-card);
  padding: 24px;
  cursor: pointer;
  transition: all var(--tu-duration-page) var(--tu-ease);

  /* Part C: 滚动动画初始状态 */
  opacity: 0;
  transform: translateY(20px);
}

/* Part C: 滚动动画可见状态 */
.card.visible {
  opacity: 1;
  transform: translateY(0);
}

.card:hover {
  transform: translateY(-2px);
  box-shadow: var(--tu-shadow-pop);
}

/* hover 时不覆盖 visible 状态的 translateY(0) */
.card.visible:hover {
  transform: translateY(-2px);
}

.card-icon {
  font-size: 32px;
  margin-bottom: 12px;
  line-height: 1;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 8px;
  color: var(--tu-text);
}

.card-desc {
  font-size: 14px;
  margin: 0 0 8px;
  color: var(--tu-text-secondary);
  line-height: 1.5;
}

.card-meta {
  font-size: 13px;
  margin: 0;
  color: var(--tu-text-disabled);
}

/* ---------- 骨架屏 ---------- */

.skeleton-hero {
  min-height: 280px;
}

.skeleton-grid {
  grid-template-columns: repeat(2, 1fr);
}

/* ---------- 响应式 ---------- */

@media (max-width: 768px) {
  .hero-title {
    font-size: 36px;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
