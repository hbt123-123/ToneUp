<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NBadge, NButton, NCard, NEmpty, NSkeleton, NTag } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useReviewStore } from '@/stores/review'
import { useStatsStore } from '@/stores/stats'
import { formatPercent } from '@/utils/format'
import { readProgress } from '@/utils/storage'

/**
 * 首页（FR-HOME-01~05）：
 * 学习计划卡 / 连续打卡卡 / 继续上次刷题 / 学科入口卡 / 今日复习提醒。
 */
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const review = useReviewStore()
const stats = useStatsStore()

const booting = ref(true)
const lastSession = ref<{ bankId: string; bankName: string; lastIndex: number } | null>(null)

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
  }
})

const subjects = computed(() => catalog.subjects)

function openSubject(subjectId: string): void {
  catalog.selectSubject(subjectId)
  void router.push({ path: '/catalog', query: { subject: subjectId } })
}

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
  <div class="content-inner home-view">
    <div v-if="booting" class="skeleton-row">
      <n-skeleton height="120px" width="100%" :sharp="false" />
      <n-skeleton height="120px" width="100%" :sharp="false" />
    </div>

    <template v-else>
      <div class="cards-grid">
        <!-- 学习计划卡（FR-HOME-01） -->
        <n-card title="今日目标" size="small" class="tu-card">
          <template #header-extra>
            <n-tag :type="(stats.overview.today_goal ?? 0) > 0 && (stats.overview.today_attempts ?? 0) >= stats.overview.today_goal! ? 'success' : 'info'" size="small">
              {{ (stats.overview.today_attempts ?? 0) }}/{{ stats.overview.today_goal ?? '—' }}
            </n-tag>
          </template>
          <p class="metric-big">{{ formatPercent(stats.overview.accuracy_rate) }}</p>
          <p class="text-secondary">累计正确率 · 今日已刷 {{ stats.overview.today_attempts ?? 0 }} 题</p>
        </n-card>

        <!-- 连续打卡卡（FR-HOME-02，P0） -->
        <n-card title="连续打卡" size="small" class="tu-card">
          <p class="metric-big accent">{{ stats.overview.streak_days ?? 0 }}<span class="unit"> 天</span></p>
          <p class="text-secondary">连续学习天数，坚持就是胜利</p>
        </n-card>

        <!-- 继续上次刷题（FR-HOME-03，P0） -->
        <n-card title="继续上次刷题" size="small" class="tu-card">
          <template v-if="lastSession">
            <p class="session-name">{{ lastSession.bankName }}</p>
            <p class="text-secondary">上次进行到第 {{ lastSession.lastIndex + 1 }} 题</p>
            <n-button type="primary" @click="continuePractice">继续练习</n-button>
          </template>
          <template v-else>
            <p class="text-secondary">还没有进行中的练习</p>
            <n-button tertiary @click="router.push('/catalog')">去题库选题</n-button>
          </template>
        </n-card>

        <!-- 今日复习提醒（FR-HOME-05） -->
        <n-card title="今日复习" size="small" class="tu-card">
          <template #header-extra>
            <n-badge v-if="review.remainingCount > 0" :value="review.remainingCount" type="warning" />
          </template>
          <template v-if="review.remainingCount > 0">
            <p class="text-secondary">有 {{ review.remainingCount }} 题等待复习</p>
            <n-button type="primary" secondary @click="router.push('/review/today')">开始复习</n-button>
          </template>
          <template v-else>
            <p class="text-secondary">今日暂无到期复习，保持节奏 ✨</p>
          </template>
        </n-card>
      </div>

      <!-- 学科入口卡（FR-HOME-04，P0） -->
      <section class="subjects">
        <h2 class="section-title">学科入口</h2>
        <div class="subject-grid">
          <n-button
            v-for="s in subjects"
            :key="s.id"
            class="subject-card option-row tu-card"
            @click="openSubject(s.id)"
          >
            <div class="subject-inner">
              <span class="icon" aria-hidden="true">{{ s.icon ?? '📘' }}</span>
              <span class="name">{{ s.name }}</span>
              <span class="desc text-secondary">
                {{ catalog.banksOf(s.id, null).length }} 个题库
              </span>
            </div>
          </n-button>
          <n-empty
            v-if="subjects.length === 0"
            description="目录加载为空，请刷新或联系管理员"
            class="span-all"
          >
            <template #extra>
              <n-button size="small" @click="catalog.fetchCatalog(true)">重试</n-button>
            </template>
          </n-empty>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.home-view {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.skeleton-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.metric-big {
  font-size: 32px;
  font-weight: 700;
  margin: 4px 0;
}

.metric-big.accent {
  color: var(--tu-accent);
}

.unit {
  font-size: 14px;
  font-weight: 400;
  color: var(--tu-text-secondary);
}

.session-name {
  font-weight: 600;
  margin-bottom: 2px;
}

.section-title {
  font-size: 17px;
  font-weight: 600;
  margin: 0 0 12px;
}

.subject-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}

.subject-card {
  height: auto;
  padding: 18px;
  border: 1px solid var(--tu-border);
  text-align: left;
  --n-height: auto;
}

.subject-inner {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.icon {
  font-size: 30px;
}

.name {
  font-size: 17px;
  font-weight: 600;
}

.desc {
  font-size: 13px;
}

.span-all {
  grid-column: 1 / -1;
}
</style>
