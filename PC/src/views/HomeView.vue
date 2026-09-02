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
 * 首页：全屏分页式布局，每个功能独占一屏，滚动切入动画
 */
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const review = useReviewStore()
const stats = useStatsStore()
const ui = useUiStore()

const booting = ref(true)
const lastSession = ref<{ bankId: string; bankName: string; lastIndex: number } | null>(null)

const formattedDate = computed(() => {
  const now = new Date()
  const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`
})

const streakDays = computed(() => stats.overview.streak_days ?? 0)
const todayAttempts = computed(() => stats.overview.today_attempts ?? 0)
const reviewCount = computed(() => review.remainingCount)

onMounted(async () => {
  // 全屏分页滚动吸附挂在真正的滚动容器 html 上（仅首页挂载期间启用）
  document.documentElement.classList.add('home-snap')
  try {
    await Promise.allSettled([
      catalog.fetchCatalog(),
      stats.fetchOverview().catch(() => undefined),
      review.fetchQueue(50).catch(() => undefined),
    ])
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
    await nextTick()
    initScrollAnimation()
  }
})

/* ---------- IntersectionObserver 滚动入场动画 ---------- */

let observer: IntersectionObserver | null = null

function initScrollAnimation(): void {
  if (!ui.motionEnabled) {
    document.querySelectorAll<HTMLElement>('.section').forEach((el) => {
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
    },
    { threshold: 0.15 },
  )

  document.querySelectorAll<HTMLElement>('.section').forEach((el) => {
    observer?.observe(el)
  })
}

onUnmounted(() => {
  document.documentElement.classList.remove('home-snap')
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
      <div class="section skeleton-section">
        <n-skeleton height="100%" width="100%" :sharp="false" />
      </div>
    </template>

    <template v-else>
      <!-- Section 0: Hero 海报区 -->
      <section class="section hero-section">
        <div class="hero-content">
          <p class="hero-eyebrow">{{ formattedDate }}</p>
          <h1 class="hero-title">ToneUp</h1>
          <p class="hero-slogan">一潼上岸 · 智能备考引擎</p>
          <p class="hero-streak">
            <template v-if="streakDays > 0">
              已连续打卡 <strong>{{ streakDays }}</strong> 天 · 今日已刷 <strong>{{ todayAttempts }}</strong> 题
            </template>
            <template v-else>
              开启你的上岸之旅
            </template>
          </p>
        </div>
        <div class="scroll-hint">
          <span class="hint-text">向下滚动探索</span>
          <span class="hint-arrow" />
        </div>
      </section>

      <!-- Section 1: 今日目标 -->
      <section class="section feature-section" @click="router.push('/catalog')">
        <div class="feature-inner feature-right">
          <div class="feature-visual">
            <img class="feature-icon icon-target" src="/background/sky/icon_target_today.svg" alt="" draggable="false" />
          </div>
          <div class="feature-body">
            <p class="section-tag">01</p>
            <h2 class="feature-title">今日目标</h2>
            <p class="feature-desc">
              继续刷题，坚持就是胜利。每一次练习都是向梦想靠近的一步。
            </p>
            <div class="feature-stats">
              <div class="stat-pill">
                <span class="stat-num">{{ todayAttempts }}</span>
                <span class="stat-label">今日已刷</span>
              </div>
              <div class="stat-pill" v-if="streakDays > 0">
                <span class="stat-num">{{ streakDays }}</span>
                <span class="stat-label">连续打卡</span>
              </div>
            </div>
            <button class="enter-btn">开始练习 →</button>
          </div>
        </div>
      </section>

      <!-- Section 2: 继续上次 -->
      <section class="section feature-section" @click="continuePractice">
        <div class="feature-inner feature-left">
          <div class="feature-body">
            <p class="section-tag">02</p>
            <h2 class="feature-title">继续上次</h2>
            <template v-if="lastSession">
              <p class="feature-desc">
                上次做到第 {{ lastSession.lastIndex + 1 }} 题，继续未完成的练习。
              </p>
              <p class="feature-meta">{{ lastSession.bankName }}</p>
            </template>
            <template v-else>
              <p class="feature-desc">
                还没有进行中的练习，去题库选一个开始吧。
              </p>
            </template>
            <button class="enter-btn">继续刷题 →</button>
          </div>
          <div class="feature-visual">
            <img class="feature-icon icon-book" src="/background/sky/icon_continue_book.svg" alt="" draggable="false" />
          </div>
        </div>
      </section>

      <!-- Section 3: 学科入口 -->
      <section class="section feature-section" @click="router.push('/catalog')">
        <div class="feature-inner feature-right">
          <div class="feature-visual">
            <img class="feature-icon icon-subjects" src="/background/sky/icon_subject_books.svg" alt="" draggable="false" />
          </div>
          <div class="feature-body">
            <p class="section-tag">03</p>
            <h2 class="feature-title">学科入口</h2>
            <p class="feature-desc">
              {{ catalog.subjects.length }} 个学科，2000+ 题目，涵盖你需要的所有考点。
            </p>
            <button class="enter-btn">浏览全部学科 →</button>
          </div>
        </div>
      </section>

      <!-- Section 4: 今日复习 -->
      <section class="section feature-section" @click="router.push('/review/today')">
        <div class="feature-inner feature-left">
          <div class="feature-body">
            <p class="section-tag">04</p>
            <h2 class="feature-title">今日复习</h2>
            <template v-if="reviewCount > 0">
              <p class="feature-desc">
                {{ reviewCount }} 题待复习，趁记忆还在，巩固一遍。
              </p>
            </template>
            <template v-else>
              <p class="feature-desc">
                今日暂无到期复习，保持节奏，继续前行。
              </p>
            </template>
            <button class="enter-btn">开始复习 →</button>
          </div>
          <div class="feature-visual">
            <img class="feature-icon icon-review" src="/background/sky/icon_review_refresh.svg" alt="" draggable="false" />
          </div>
        </div>
      </section>

      <!-- Section 5: 更多功能 -->
      <section class="section more-section">
        <h2 class="more-title">还有更多</h2>
        <div class="more-grid">
          <div class="more-card" @click="router.push('/wrong-book')">
            <span class="more-icon">📕</span>
            <span class="more-label">错题本</span>
          </div>
          <div class="more-card" @click="router.push('/stats')">
            <span class="more-icon">📊</span>
            <span class="more-label">统计</span>
          </div>
          <div class="more-card" @click="router.push('/notes')">
            <span class="more-icon">✏️</span>
            <span class="more-label">笔记</span>
          </div>
          <div class="more-card" @click="router.push('/ai-feedback')">
            <span class="more-icon">🤖</span>
            <span class="more-label">AI 纠错</span>
          </div>
          <div v-if="auth.isAdmin" class="more-card" @click="router.push('/admin')">
            <span class="more-icon">🛠️</span>
            <span class="more-label">管理</span>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.home-view {
  width: 100%;
}

/* ---------- 通用 section ---------- */

.section {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 8vw;
  position: relative;
  scroll-snap-align: start;
}

/* 滚动动画基础状态 */
.section {
  opacity: 0;
  transform: translateY(40px);
  transition: opacity 0.8s var(--tu-ease), transform 0.8s var(--tu-ease);
}

.section.visible {
  opacity: 1;
  transform: translateY(0);
}

/* ---------- Hero ---------- */

.hero-section {
  flex-direction: column;
  background: var(--tu-gradient-hero);
  /* 不用 fixed 附着：4MB 级海报图滚动时反复重绘，导致掉帧 */
  text-align: center;
  gap: 20px;
  position: relative;
  isolation: isolate;
}

/* 海报图叠加渐变色遮罩，保证文字在任何图上都清晰可读 */
.hero-section::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(255, 255, 255, 0.15) 0%,
    rgba(255, 255, 255, 0.55) 50%,
    rgba(255, 255, 255, 0.3) 100%
  );
  z-index: -1;
}

html.dark .hero-section::before {
  background: linear-gradient(
    180deg,
    rgba(15, 15, 25, 0.3) 0%,
    rgba(15, 15, 25, 0.65) 50%,
    rgba(15, 15, 25, 0.4) 100%
  );
}

.hero-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.hero-eyebrow {
  font-size: 15px;
  color: var(--tu-text-secondary);
  margin: 0;
  letter-spacing: 1px;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}

.hero-title {
  font-size: clamp(56px, 10vw, 96px);
  font-weight: 900;
  margin: 0;
  color: var(--tu-text);
  letter-spacing: -2px;
  line-height: 1;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.18);
}

.hero-slogan {
  font-size: clamp(16px, 2vw, 20px);
  color: var(--tu-text-secondary);
  margin: 0;
  letter-spacing: 2px;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}

.hero-streak {
  font-size: clamp(16px, 2vw, 18px);
  color: var(--tu-text);
  margin: 8px 0 0;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}

.hero-streak strong {
  color: var(--tu-accent);
  font-weight: 800;
  font-size: 1.15em;
}

/* 滚动提示 */
.scroll-hint {
  position: absolute;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  opacity: 0.5;
}

.hint-text {
  font-size: 12px;
  color: var(--tu-text-secondary);
  letter-spacing: 1px;
}

.hint-arrow {
  width: 18px;
  height: 18px;
  border-right: 2px solid var(--tu-text-secondary);
  border-bottom: 2px solid var(--tu-text-secondary);
  transform: rotate(45deg);
  animation: bounce-down 2s infinite var(--tu-ease);
}

@keyframes bounce-down {
  0%, 100% { transform: rotate(45deg) translate(0, 0); }
  50% { transform: rotate(45deg) translate(4px, 4px); }
}

/* ---------- Feature section ---------- */

.feature-section {
  cursor: pointer;
  transition: opacity 0.8s var(--tu-ease), transform 0.8s var(--tu-ease), background 0.4s var(--tu-ease);
}

.feature-section:nth-child(odd) {
  background: var(--tu-surface);
}

.feature-section:nth-child(even) {
  background: var(--tu-bg);
}

html.dark .feature-section:nth-child(odd) {
  background: var(--tu-surface);
}

html.dark .feature-section:nth-child(even) {
  background: var(--tu-bg);
}

.feature-inner {
  display: flex;
  align-items: center;
  gap: 8vw;
  max-width: 1100px;
  width: 100%;
}

.feature-right .feature-visual { order: 1; }
.feature-right .feature-body { order: 2; }
.feature-left .feature-body { order: 1; }
.feature-left .feature-visual { order: 2; }

.feature-visual {
  flex: none;
  width: clamp(140px, 22vw, 220px);
  height: clamp(140px, 22vw, 220px);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--tu-accent-surface);
  border: 2px solid var(--tu-accent-border);
  transition: transform 0.4s var(--tu-ease);
}

.feature-section:hover .feature-visual {
  transform: scale(1.05);
}

/* 功能图标：SVG 贴纸图（PC/public/background/sky/icon_*.svg） */
.feature-icon {
  width: 62%;
  height: 62%;
  object-fit: contain;
  user-select: none;
  filter: drop-shadow(0 4px 10px rgba(30, 60, 90, 0.18));
  transition: transform 0.4s var(--tu-ease);
}

.feature-section:hover .feature-icon {
  transform: scale(1.08) rotate(-3deg);
}

.feature-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-tag {
  font-size: 14px;
  font-weight: 700;
  color: var(--tu-accent);
  margin: 0;
  letter-spacing: 2px;
}

.feature-title {
  font-size: clamp(32px, 5vw, 48px);
  font-weight: 800;
  margin: 0;
  color: var(--tu-text);
  line-height: 1.1;
}

.feature-desc {
  font-size: clamp(15px, 2vw, 18px);
  color: var(--tu-text-secondary);
  line-height: 1.6;
  margin: 0;
  max-width: 520px;
}

.feature-meta {
  font-size: 14px;
  color: var(--tu-text-disabled);
  margin: 0;
}

.feature-stats {
  display: flex;
  gap: 16px;
  margin-top: 4px;
}

.stat-pill {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 20px;
  border-radius: 12px;
  background: var(--tu-primary-surface);
  min-width: 80px;
}

.stat-num {
  font-size: 24px;
  font-weight: 800;
  color: var(--tu-primary);
}

.stat-label {
  font-size: 12px;
  color: var(--tu-text-secondary);
}

.enter-btn {
  align-self: flex-start;
  margin-top: 8px;
  padding: 12px 28px;
  border: none;
  border-radius: 24px;
  background: var(--tu-accent);
  color: var(--tu-text-on-accent);
  font: inherit;
  font-weight: 600;
  font-size: 15px;
  cursor: pointer;
  transition: transform var(--tu-duration-micro) var(--tu-ease),
    box-shadow var(--tu-duration-micro) var(--tu-ease);
}

.enter-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px var(--tu-accent-glow);
}

/* ---------- More section ---------- */

.more-section {
  flex-direction: column;
  gap: 40px;
  background: var(--tu-bg);
  text-align: center;
}

.more-title {
  font-size: clamp(28px, 4vw, 40px);
  font-weight: 800;
  color: var(--tu-text);
  margin: 0;
}

.more-grid {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 24px;
  max-width: 800px;
}

.more-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 28px 36px;
  border-radius: 16px;
  background: var(--tu-surface);
  border: 1px solid var(--tu-border);
  cursor: pointer;
  transition: transform var(--tu-duration-micro) var(--tu-ease),
    box-shadow var(--tu-duration-micro) var(--tu-ease),
    border-color var(--tu-duration-micro) var(--tu-ease);
}

.more-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--tu-shadow-pop);
  border-color: var(--tu-accent-border);
}

.more-icon {
  font-size: 40px;
}

.more-label {
  font-size: 15px;
  font-weight: 600;
  color: var(--tu-text);
}

/* ---------- 骨架屏 ---------- */

.skeleton-section {
  min-height: 100vh;
}

/* ---------- 响应式 ---------- */

@media (max-width: 768px) {
  .feature-inner {
    flex-direction: column;
    gap: 30px;
  }

  .feature-right .feature-visual,
  .feature-left .feature-visual,
  .feature-right .feature-body,
  .feature-left .feature-body {
    order: unset;
  }

  .feature-visual {
    width: 120px;
    height: 120px;
  }
}

/*
 * 昔涟主题的 SVG 装饰样式在 src/styles/xilian.css（全局）。
 * 不能在这里用 `:global(html[data-theme=…]) .后代` 写法：scoped 编译会
 * 丢弃 :global() 之后的后代选择器，规则直接命中 <html> 导致整页布局坍塌。
 */
</style>
