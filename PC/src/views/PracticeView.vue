<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import {
  NAlert,
  NButton,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NSkeleton,
  NTag,
} from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import QuestionPanel from '@/components/layout/QuestionPanel.vue'
import AnalysisPanel from '@/components/layout/AnalysisPanel.vue'
import { resolveRenderer, UnknownTypeRenderer } from '@/components/question-renderers/registry'
import type { GradingView, QuestionContext } from '@/components/question-renderers/types'
import { usePracticeStore, type QuestionRuntime } from '@/stores/practice'
import { useCatalogStore } from '@/stores/catalog'
import { useReviewStore } from '@/stores/review'
import { useUiStore } from '@/stores/ui'
import { useLayoutMode } from '@/composables/useLayoutMode'
import { useKeyboardShortcuts, SHORTCUT_HINTS } from '@/composables/useKeyboardShortcuts'
import { humanizeError } from '@/api/http'
import { appDialog, appMessage } from '@/utils/feedback'
import { typeCodeLabel } from '@/utils/format'

/**
 * 刷题工作台（FR-PRAC 全部 + FR-ANA-01~07）。
 * 布局（§4.2）：≥1600 宽松三栏；1280~1599 标准；<1280 信息栏抽屉化。
 * 复习模式（FR-REV-02）复用同一工作台与状态机，仅队列来源不同。
 */
const props = defineProps<{ mode?: 'bank' | 'review' }>()
const isReviewMode = computed(() => props.mode === 'review')

const route = useRoute()
const practice = usePracticeStore()
const catalog = useCatalogStore()
const reviewStore = useReviewStore()
const ui = useUiStore()
const { mode: layoutMode, isCompactOrNarrower } = useLayoutMode()

/* ---------- 会话启动 ---------- */

const booting = ref(true)
const bootError = ref<string | null>(null)

onMounted(async () => {
  try {
    if (isReviewMode.value) {
      // 今日复习：加载到期队列后交给同一引擎
      await reviewStore.fetchQueue().catch(() => undefined)
      if (reviewStore.queue.length > 0) {
        practice.startReviewSession(
          reviewStore.queue.map((q) => ({ bankId: q.bank_id, questionId: q.question_id })),
        )
        practice.bankId = ''
      }
      booting.value = false
      return
    }

    const bankId = String(route.params.bankId ?? '')
    const yearQ =
      typeof route.query.year === 'string' && route.query.year !== '' ? Number(route.query.year) : null
    const typeQ =
      typeof route.query.type_code === 'string' && route.query.type_code !== '' ? route.query.type_code : null
    await catalog.fetchCatalog().catch(() => undefined)
    const bank = catalog.bankById.get(bankId)
    if (bank) {
      catalog.selectSubject(bank.subject_id)
      catalog.currentBankName = bank.name
      if (bank.type_id) catalog.selectType(bank.type_id)
    }
    await practice.startBankSession({ bankId, year: yearQ, typeCode: typeQ })
    practice.loadMarked()
    localStorage.setItem('toneup:last-bank', bankId)

    const resumeQ = typeof route.query.resume === 'string' ? Number(route.query.resume) : NaN
    const qidQ = typeof route.query.qid === 'string' ? Number(route.query.qid) : NaN
    if (!Number.isNaN(qidQ)) {
      // 错题重练 / 笔记跳转：定位到指定题目（FR-WRONG-03 / FR-NOTE-03）
      if (!(await practice.gotoQuestion(qidQ))) await practice.gotoIndex(0)
    } else if (!Number.isNaN(resumeQ) && resumeQ >= 0) {
      await practice.gotoIndex(Math.min(resumeQ, Math.max(0, practice.orderedIds.length - 1)))
    } else if (!practice.continueFromSavedProgress()) {
      await practice.gotoIndex(0)
    }
  } catch (err) {
    bootError.value = humanizeError(err)
  } finally {
    booting.value = false
  }
})

/* ---------- 复习模式：暂缓本题（FR-REV-03） ---------- */

async function skipCurrentReview(): Promise<void> {
  const q = question.value
  if (!q) return
  try {
    await reviewStore.skipCurrent({
      ...(q as typeof q & { bank_id: string }),
      bank_id: practice.itemBanks.get(q.question_id) ?? '',
      question_id: q.question_id,
    })
    appMessage.success('已暂缓，明天会再次安排')
  } catch (err) {
    appMessage.error(humanizeError(err))
    return
  }
  if (practice.currentIndex >= practice.orderedIds.length - 1) {
    practice.resetSession()
  } else {
    await goNext()
  }
}

/** 复习完成反馈（FR-REV-04） */
const reviewCompleted = computed(
  () => isReviewMode.value && !booting.value && practice.orderedIds.length === 0,
)

/* ---------- 当前题目上下文（QuestionContext 契约） ---------- */

const rt = computed<QuestionRuntime | null>(() => practice.currentRuntime)
const question = computed(() => rt.value?.detail ?? rt.value?.listMeta ?? null)
const rendererComp = computed(() => resolveRenderer(question.value?.type_code ?? ''))
const isUnknownType = computed(() => rendererComp.value === UnknownTypeRenderer)

const gradingView = computed<GradingView | null>(() => {
  const g = rt.value?.grading
  if (!g) return null
  return { status: g, feedback: rt.value?.attempt?.feedback ?? null }
})

const ctx = computed<QuestionContext | null>(() => {
  if (!question.value || !rt.value) return null
  return {
    question: question.value,
    answer: rt.value.answer,
    readonly: rt.value.phase === 'submitted',
    disabled: rt.value.phase === 'submitting',
    showAnswer: rt.value.phase === 'submitted',
    showAnalysis: rt.value.phase === 'submitted',
    grading: gradingView.value,
    onAnswerChange: (answer: unknown) => practice.setAnswer(answer),
    onSubmitRequest: () => void handleSubmit(),
  }
})

/* ---------- 提交与切题 ---------- */

async function handleSubmit(): Promise<void> {
  const r = rt.value
  if (!r) return
  if (r.phase === 'submitted') {
    await goNext()
    return
  }
  // error 态重试：网络失败保留答案与同一 client_request_id，重试走成功路径（§8.1）
  if (r.phase === 'error') {
    if (r.answer === null || r.answer === undefined || r.answer === '') {
      appMessage.warning('请先作答，再确认提交')
      return
    }
    await practice.submit()
    return
  }
  if (r.phase !== 'idle' && r.phase !== 'editing') return
  if (r.answer === null || r.answer === undefined || r.answer === '') {
    appMessage.warning('请先作答，再确认提交')
    return
  }
  await practice.submit()
}

async function goNext(): Promise<void> {
  const ok = await practice.next()
  if (!ok) appMessage.info('已经是最后一题了，可前往题号面板查看未答题')
}

/** 错答就近晃动反馈（§10.3）：判定结果变为 false 时触发一次，300ms 后复位 */
const shakeKey = ref(0)
let shakeTimer: ReturnType<typeof setTimeout> | null = null
watch(
  () => rt.value?.attempt?.is_correct,
  (correct, prev) => {
    if (correct === false && prev !== false) {
      shakeKey.value++
      if (shakeTimer !== null) clearTimeout(shakeTimer)
      shakeTimer = setTimeout(() => {
        shakeKey.value = 0
        shakeTimer = null
      }, 350)
    }
  },
)

const answeredIds = computed(() => {
  const set = new Set<number>()
  for (const id of practice.orderedIds) {
    const b = practice.itemBanks.get(id) ?? practice.bankId
    const r = practice.runtimes.get(`${b}:${id}`)
    if (r && (r.phase === 'submitted' || (r.answer !== null && r.answer !== undefined && r.answer !== ''))) {
      set.add(id)
    }
  }
  return set
})

/* 草稿恢复提示（FR-PRAC-07）：每题最多询问一次 */
const draftPromptShownFor = new Set<string>()
watch(
  () => [practice.currentKey, rt.value?.pendingDraft] as const,
  ([key, draft]) => {
    if (!key || draft === null || draft === undefined || draftPromptShownFor.has(key)) return
    draftPromptShownFor.add(key)
    appDialog.info({
      title: '检测到未完成的作答记录',
      content: '本题存在未提交的草稿，是否恢复继续作答？选择"放弃"将清除该草稿。',
      positiveText: '恢复',
      negativeText: '放弃并清除',
      onPositiveClick: () => practice.resolvePendingDraft(true),
      onNegativeClick: () => practice.resolvePendingDraft(false),
    })
  },
)

/* ---------- 题号面板筛选（FR-PRAC-09） ---------- */

const filterUnansweredOnly = ref(false)
const filterMarkedOnly = ref(false)

const markedActive = computed(() => {
  const id = practice.orderedIds[practice.currentIndex]
  return id !== undefined && practice.markedIds.has(id)
})

/* ---------- 离开保护（FR-PRAC-11） ---------- */

function hasUnsubmittedWork(): boolean {
  for (const r of practice.runtimes.values()) {
    if ((r.phase === 'editing' && r.answer !== null && r.answer !== undefined && r.answer !== '') || r.pendingRequestId) {
      return true
    }
  }
  return false
}

let forceLeave = false

onBeforeRouteLeave(() => {
  practice.persistProgress()
  practice.flushAllDrafts()
  if (forceLeave || !hasUnsubmittedWork()) return true
  return new Promise<boolean>((resolve) => {
    appDialog.warning({
      title: '有未提交的内容',
      content: '离开前将自动保存草稿（联网后可继续），确定离开吗？',
      positiveText: '保存草稿并离开',
      negativeText: '留在本页',
      closable: false,
      maskClosable: false,
      onPositiveClick: () => {
        forceLeave = true
        resolve(true)
      },
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    })
  })
})

function beforeUnloadHandler(e: BeforeUnloadEvent): void {
  practice.flushAllDrafts()
  if (hasUnsubmittedWork()) {
    e.preventDefault()
    e.returnValue = ''
  }
}

/* ---------- 断网续答（FR-PRAC-12） ---------- */

let onlineBound = false

function onOnline(): void {
  const pending = practice.pendingSyncCount()
  if (pending > 0) {
    appMessage.info(`网络已恢复，发现 ${pending} 条未提交记录，正在同步…`)
    void practice.syncUnsubmitted().then(({ synced, failed }) => {
      if (synced > 0) appMessage.success(`已同步 ${synced} 条记录，结果以服务端为准`)
      if (failed > 0) appMessage.warning(`${failed} 条未能同步，稍后恢复联网时将自动重试`)
    })
  }
}

/* ---------- 键盘快捷键（第 9 章 / FR-PRAC-10） ---------- */

const helpVisible = ref(false)

function handleLetter(letter: string): void {
  const q = question.value
  const r = rt.value
  if (!q || !r || r.phase === 'submitting' || r.phase === 'submitted') return
  if (q.type_code === 'SINGLE' || q.type_code === 'READING') {
    const opt = (q.options ?? []).find((o) => o.label.toUpperCase() === letter)
    if (opt) ctx.value?.onAnswerChange(opt.label)
  } else if (q.type_code === 'JUDGE') {
    if (letter === 'A' || letter === 'B') ctx.value?.onAnswerChange(letter)
  } else if (q.type_code === 'MULTI') {
    const opt = (q.options ?? []).find((o) => o.label.toUpperCase() === letter)
    if (!opt) return
    const cur = Array.isArray(r.answer) ? [...(r.answer as string[])] : []
    const idxAt = cur.indexOf(opt.label)
    if (idxAt >= 0) cur.splice(idxAt, 1)
    else cur.push(opt.label)
    ctx.value?.onAnswerChange(cur.sort())
  }
}

useKeyboardShortcuts(
  {
    onLetter: handleLetter,
    onConfirmOrNext: () => void handleSubmit(),
    onPrev: () => void practice.prev(),
    onNext: () => void goNext(),
    onLongPressMark: () => practice.toggleMark(),
    onHelp: () => {
      helpVisible.value = true
    },
    onEscape: () => {
      helpVisible.value = false
      infoDrawerOpen.value = false
    },
  },
)

/* ---------- 解析分栏拖拽（FR-ANA-01）：默认 6:4，位置记忆 ---------- */

const splitRatio = computed({
  get: () => ui.analysisSplitRatio,
  set: (v: number) => ui.setAnalysisSplitRatio(v),
})

const splitContainer = ref<HTMLElement | null>(null)
let draggingSplit = false

function startSplitDrag(e: MouseEvent): void {
  draggingSplit = true
  e.preventDefault()
}
function onSplitMove(e: MouseEvent): void {
  if (!draggingSplit || !splitContainer.value) return
  const rect = splitContainer.value.getBoundingClientRect()
  splitRatio.value = (e.clientX - rect.left) / rect.width
}
function endSplitDrag(): void {
  draggingSplit = false
}

/* ---------- 抽屉与缓存刷新（FR-PRAC-13） ---------- */

const infoDrawerOpen = ref(false)

function openAnalysisDrawer(): void {
  infoDrawerOpen.value = true
}

function refreshCache(): void {
  if (isReviewMode.value) {
    // 复习会话不能走 bank 刷新（会清空队列）；仅重拉当前题详情
    void practice.retryCurrentDetail()
    appMessage.success('正在重新加载当前题目')
    return
  }
  practice.refreshList()
  appMessage.success('题目缓存已失效，正在重新拉取')
}

/* ---------- 全局监听绑定 ---------- */

onMounted(() => {
  window.addEventListener('online', onOnline)
  window.addEventListener('beforeunload', beforeUnloadHandler)
  window.addEventListener('mousemove', onSplitMove)
  window.addEventListener('mouseup', endSplitDrag)
  onlineBound = true
})

onBeforeUnmount(() => {
  if (onlineBound) {
    window.removeEventListener('online', onOnline)
    window.removeEventListener('beforeunload', beforeUnloadHandler)
  }
  window.removeEventListener('mousemove', onSplitMove)
  window.removeEventListener('mouseup', endSplitDrag)
  practice.resetSession()
})
</script>

<template>
  <div class="practice-view">
    <!-- 启动/列表加载骨架屏（FR-PRAC-01） -->
    <div v-if="booting || practice.listLoading" class="content-inner skeletons">
      <n-skeleton height="28px" width="40%" :sharp="false" />
      <n-skeleton height="220px" width="100%" :sharp="false" />
      <n-skeleton height="48px" width="100%" :sharp="false" />
    </div>

    <!-- 列表失败重试 -->
    <div v-else-if="practice.listError || bootError" class="content-inner error-state">
      <n-result-lite
        :message="bootError ?? practice.listError ?? ''"
        @retry="refreshCache"
      />
    </div>

    <!-- 复习完成鼓励态（FR-REV-04） -->
    <div v-else-if="reviewCompleted" class="content-inner review-done tu-card">
      <div class="done-emoji" aria-hidden="true">🎉</div>
      <h2>今日复习已完成！</h2>
      <p class="text-secondary">保持节奏，明天会按记忆曲线安排新的复习内容。</p>
      <div class="done-actions">
        <n-button type="primary" @click="$router.push('/stats')">查看统计</n-button>
        <n-button quaternary @click="$router.push('/catalog')">去刷题</n-button>
      </div>
    </div>

    <!-- 空题库 -->
    <n-empty
      v-else-if="practice.orderedIds.length === 0"
      description="该筛选条件下没有题目"
      class="content-inner empty-pad"
    >
      <template #extra>
        <n-button size="small" @click="$router.push('/catalog')">返回题库重新选择</n-button>
      </template>
    </n-empty>

    <!-- 工作区 -->
    <div v-else class="workspace" :class="{ loose: layoutMode === 'loose' }">
      <section class="question-col tu-card">
        <!-- 题头信息 -->
        <header class="q-header">
          <div class="q-meta">
            <span class="q-no">第 {{ practice.currentIndex + 1 }} 题</span>
            <n-tag size="small" round>{{ typeCodeLabel(question?.type_code) }}</n-tag>
            <n-tag v-if="question?.year" size="small" round type="info">{{ question.year }}</n-tag>
            <span
              v-if="rt?.phase === 'submitted'"
              class="phase-tag"
              :class="(rt.attempt?.is_correct ?? null) === false ? 'bad' : 'ok'"
            >
              {{ rt.attempt?.is_correct === true ? '✓ 回答正确' : rt.attempt?.is_correct === false ? '✕ 回答错误' : '已提交' }}
            </span>
          </div>
          <div class="q-actions-head">
            <n-button
              size="small"
              :type="markedActive ? 'warning' : 'default'"
              secondary
              :title="markedActive ? '取消疑问标记' : '标记为有疑问'"
              @click="practice.toggleMark()"
            >
              {{ markedActive ? '★ 已标记' : '☆ 标记疑问' }}
            </n-button>
            <n-button v-if="isCompactOrNarrower" size="small" quaternary @click="infoDrawerOpen = true">
              ☰ 题号面板
            </n-button>
          </div>
        </header>

        <!-- 题干（FR-PRAC-02）；shakeKey>0 时施加一次性晃动动画 -->
        <div class="stem-wrap" :class="{ 'shake-once': shakeKey > 0 }">
          <rich-text :content="question?.content ?? ''" />
        </div>

        <!-- 题型渲染宿主（§6.1） -->
        <div class="renderer-host">
          <component
            :is="rendererComp"
            v-if="ctx && !isUnknownType"
            :key="`${practice.bankId}:${question?.question_id}`"
            :ctx="ctx"
          />
          <unknown-type-renderer
            v-else-if="isUnknownType"
            :type-code="question?.type_code ?? '?'"
            @retry="refreshCache"
            @skip="goNext"
          />
          <div v-else-if="rt?.phase === 'error'" class="detail-error">
            <p class="err-text">{{ rt.errorMessage ?? '题目加载失败' }}</p>
            <n-button size="small" type="primary" @click="refreshCache">重试加载</n-button>
          </div>
          <div v-else class="loading-detail text-secondary">题目加载中…</div>
        </div>

        <!-- 提交/判分/自评错误横幅（题目数据正常时展示；error 态由确认按钮重试） -->
        <n-alert
          v-if="ctx && rt?.errorMessage"
          type="error"
          closable
          class="submit-err"
          @close="practice.currentRuntime && (practice.currentRuntime.errorMessage = null)"
        >
          {{ rt.errorMessage }}
        </n-alert>

        <!-- 底部操作条（§12.2 高频操作固定右下） -->
        <footer class="action-bar">
          <n-button :disabled="practice.currentIndex <= 0 || (rt?.phase ?? '') === 'submitting'" @click="practice.prev()">
            ← 上一题
          </n-button>
          <n-button v-if="isReviewMode && rt?.phase !== 'submitted'" tertiary type="warning" size="small" @click="skipCurrentReview">
            暂缓本题
          </n-button>
          <div class="spacer" />
          <n-button
            v-if="(rt?.phase ?? '') !== 'submitted'"
            type="primary"
            size="large"
            :loading="(rt?.phase ?? '') === 'submitting'"
            :disabled="(rt?.phase ?? '') === 'submitting'"
            @click="handleSubmit"
          >
            确认答案（Enter）
          </n-button>
          <n-button v-else type="primary" size="large" @click="goNext">下一题（Enter）</n-button>
        </footer>
      </section>

      <!-- 右侧信息栏：未答=题号面板；已提交=解析视图（FR-ANA-01 分栏） -->
      <aside
        v-if="!isCompactOrNarrower"
        ref="splitContainer"
        class="side-col"
        :style="{ flexBasis: `${(1 - splitRatio) * 100}%` }"
      >
        <div
          class="split-divider"
          role="separator"
          aria-label="拖动调整分栏比例"
          @mousedown="startSplitDrag"
        />
        <div class="side-scroll">
          <question-panel
            v-if="rt?.phase !== 'submitted'"
            v-model:unanswered-only="filterUnansweredOnly"
            v-model:marked-only="filterMarkedOnly"
            :ids="practice.orderedIds"
            :current-index="practice.currentIndex"
            :answered-ids="answeredIds"
            :marked-ids="practice.markedIds"
            @jump="(i: number) => practice.gotoIndex(i)"
          />
          <analysis-panel
            v-else-if="ctx"
            :ctx="{ ...ctx, attemptResult: rt?.attempt ?? null }"
            :self-judge-ready="rt?.selfJudgeReady ?? false"
            :bank-id="practice.itemBanks.get(practice.orderedIds[practice.currentIndex]!) ?? practice.bankId"
            @submit-self-judge="(rating) => practice.submitSelfJudge(rating)"
          />
        </div>
      </aside>

      <!-- 快捷键精简条（可关） -->
      <div v-if="ui.shortcutBarVisible && layoutMode !== 'narrow'" class="shortcut-bar">
        <span>A/B/C/D 选答案 · Enter 提交/下一题 · ←→ 切题 · Space 长按标记 · ？帮助</span>
        <button type="button" class="close-bar" aria-label="关闭快捷键提示" @click="ui.shortcutBarVisible = false">×</button>
      </div>
    </div>

    <!-- 窄屏抽屉：题号面板 / 解析 -->
    <n-drawer v-model:show="infoDrawerOpen" :width="420" placement="right">
      <n-drawer-content title="练习面板" closable>
        <div v-if="rt?.phase !== 'submitted'">
          <question-panel
            v-model:unanswered-only="filterUnansweredOnly"
            v-model:marked-only="filterMarkedOnly"
            :ids="practice.orderedIds"
            :current-index="practice.currentIndex"
            :answered-ids="answeredIds"
            :marked-ids="practice.markedIds"
            @jump="(i: number) => { void practice.gotoIndex(i); infoDrawerOpen = false }"
          />
        </div>
        <analysis-panel
          v-else-if="ctx"
          :ctx="{ ...ctx, attemptResult: rt?.attempt ?? null }"
          :self-judge-ready="rt?.selfJudgeReady ?? false"
          :bank-id="practice.itemBanks.get(practice.orderedIds[practice.currentIndex]!) ?? practice.bankId"
          @submit-self-judge="(rating) => practice.submitSelfJudge(rating)"
        />
      </n-drawer-content>
    </n-drawer>

    <!-- 快捷键完整面板（? 唤出，Esc 关闭） -->
    <help-modal v-model:show="helpVisible" :hints="SHORTCUT_HINTS" />

    <!-- 已提交后窄屏查看解析按钮 -->
    <n-button
      v-if="isCompactOrNarrower && rt?.phase === 'submitted'"
      class="fab-analysis"
      circle
      type="primary"
      size="large"
      aria-label="查看解析"
      @click="openAnalysisDrawer"
    >
      📖
    </n-button>
  </div>
</template>

<script lang="ts">
/* 拆分出的小型内部组件：错误重试与快捷键帮助弹窗 */
import { defineComponent, h, type PropType } from 'vue'
import { NModal } from 'naive-ui'

const NResultLite = defineComponent({
  props: { message: { type: String, required: true } },
  emits: ['retry'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'error-box' }, [
        h('p', { class: 'err-text' }, props.message || '加载失败'),
        h(NButton, { size: 'small', onClick: () => emit('retry') }, { default: () => '重试' }),
      ])
  },
})

const HelpModal = defineComponent({
  props: {
    show: { type: Boolean, required: true },
    hints: { type: Array as PropType<{ keys: string; desc: string }[]>, required: true },
  },
  emits: ['update:show'],
  setup(props, { emit }) {
    return () =>
      h(
        NModal,
        {
          show: props.show,
          'onUpdate:show': (v: boolean) => emit('update:show', v),
          preset: 'card',
          title: '键盘快捷键',
          style: { maxWidth: '460px' },
        },
        {
          default: () =>
            h(
              'table',
              { class: 'hint-table' },
              props.hints.map((hint) =>
                h('tr', {}, [
                  h('td', {}, h('kbd', {}, hint.keys)),
                  h('td', {}, hint.desc),
                ]),
              ),
            ),
        },
      )
  },
})

export default { components: { NResultLite, HelpModal } }
</script>

<style scoped>
.practice-view {
  min-height: calc(100vh - var(--tu-topbar-height));
}

.skeletons,
.error-state,
.empty-pad {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 8px;
}

.workspace {
  position: relative;
  display: flex;
  gap: 18px;
  align-items: stretch;
}

.question-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 22px;
  margin-bottom: 44px;
}

.q-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.q-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.q-no {
  font-weight: 700;
  font-size: 17px;
}

.phase-tag.ok {
  color: var(--tu-success);
  font-size: 13px;
  font-weight: 600;
}

.phase-tag.bad {
  color: var(--tu-error);
  font-size: 13px;
  font-weight: 600;
}

.stem-wrap {
  border-bottom: 1px dashed var(--tu-border);
  padding-bottom: 12px;
}

.renderer-host {
  flex: 1;
  min-height: 160px;
}

.detail-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 24px 0;
}

.err-text {
  margin: 0;
  color: var(--tu-error);
}

.submit-err {
  --n-padding-left: 14px;
}

.action-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 6px;
  border-top: 1px solid var(--tu-border);
}

.spacer {
  flex: 1;
}

.side-col {
  position: relative;
  flex: none;
  min-width: 320px;
  max-width: 46%;
  display: flex;
}

.split-divider {
  width: 8px;
  cursor: col-resize;
  border-radius: 4px;
  background: transparent;
  transition: background var(--tu-duration-micro) var(--tu-ease);
  flex: none;
}

.split-divider:hover {
  background: rgba(124, 58, 237, 0.25);
}

.side-scroll {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  max-height: calc(100vh - var(--tu-topbar-height) - 60px);
  padding-right: 4px;
}

.shortcut-bar {
  position: fixed;
  bottom: 0;
  left: var(--tu-sidebar-width);
  right: 0;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 12px;
  color: var(--tu-text-secondary);
  background: rgba(127, 127, 140, 0.08);
  backdrop-filter: blur(8px);
  border-top: 1px solid var(--tu-border);
  z-index: 30;
}

.close-bar {
  position: absolute;
  right: 10px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 16px;
  color: var(--tu-text-secondary);
}

.fab-analysis {
  position: fixed;
  right: 26px;
  bottom: 56px;
  z-index: 40;
  box-shadow: var(--tu-shadow-pop);
}

.review-done {
  max-width: 560px;
  margin: 48px auto;
  text-align: center;
  padding: 42px 30px;
}

.done-emoji {
  font-size: 52px;
}

.done-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 16px;
}
</style>
