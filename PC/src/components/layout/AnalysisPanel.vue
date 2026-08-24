<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NAlert, NButton, NInput, NSpin, NTag, useMessage } from 'naive-ui'
import RichText from '@/components/common/RichText.vue'
import type { QuestionContext, GradingView } from '@/components/question-renderers/types'
import { apiGetNote, apiPutNote } from '@/api/endpoints'
import { humanizeError } from '@/api/http'
import { formatDateTime } from '@/utils/format'
import { upsertNoteIndex } from '@/utils/notesIndex'
import { useAuthStore } from '@/stores/auth'

/**
 * 解析视图右栏（FR-ANA-01~07）：
 * - 判分状态四级展示（D-02）+ 超时自评兜底；
 * - 用户答案 / 正确答案对照（图标+文字+颜色三通道）；
 * - 笔记读写、AI 纠错入口。
 */
const props = defineProps<{
  ctx: QuestionContext & {
    /** 服务端返回的本次作答结果 */
    attemptResult: import('@/api/generated/schema').AttemptResult | null
  }
  selfJudgeReady: boolean
  bankId: string
}>()

const emit = defineEmits<{ 'submit-self-judge': [rating: 'correct' | 'partial' | 'wrong'] }>()

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

const attemptResult = computed(() => props.ctx.attemptResult)

const grading = computed<GradingView | null>(() => props.ctx.grading)

/* ---------- 答案展示格式化 ---------- */

function formatAnswer(answer: unknown): string {
  if (answer === null || answer === undefined || answer === '') return '未作答'
  if (Array.isArray(answer)) {
    return answer.map((a) => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' → ')
  }
  if (typeof answer === 'object') return JSON.stringify(answer)
  if (typeof answer === 'string' && answer.length > 400) return `${answer.slice(0, 400)}…`
  return String(answer)
}

const userAnswerText = computed(() => formatAnswer(props.ctx.answer))

/** 正确答案：详情接口的 answer_text（授权出口），优先 attempt 内回传 */
const correctAnswerText = computed<string>(() => {
  const fromAttempt = props.ctx.attemptResult?.answer_text ?? null
  const fromDetail = (props.ctx.question as typeof props.ctx.question & { answer_text?: string | null }).answer_text ?? null
  return fromAttempt ?? fromDetail ?? ''
})

const solutionText = computed<string>(() => {
  const fromAttempt = props.ctx.attemptResult?.solution ?? null
  const fromDetail = (props.ctx.question as typeof props.ctx.question & { solution?: string | null }).solution ?? null
  return fromAttempt ?? fromDetail ?? ''
})

const isCorrect = computed<boolean | null>(() => props.ctx.attemptResult?.is_correct ?? null)
const scoreLine = computed(() => {
  const a = props.ctx.attemptResult
  if (a?.score === null || a?.score === undefined) return null
  return a.max_score != null ? `${a.score}/${a.max_score} 分` : `${a.score} 分`
})

/* ---------- 自评兜底（FR-ANA-05） ---------- */

function selfJudge(rating: 'correct' | 'partial' | 'wrong'): void {
  emit('submit-self-judge', rating)
}

/* ---------- 笔记（FR-ANA-06） ---------- */

const noteLoading = ref(false)
const noteSaving = ref(false)
const noteText = ref('')
const noteSavedAt = ref<string | null>(null)
const savedSnapshot = ref<string | null>(null)

watch(
  () => props.ctx.question.question_id,
  async () => {
    noteText.value = ''
    noteSavedAt.value = null
    savedSnapshot.value = null
    noteLoading.value = true
    try {
      const note = await apiGetNote(props.ctx.question.question_id, props.bankId)
      noteText.value = note?.note_text ?? ''
      noteSavedAt.value = note?.updated_at ?? null
      savedSnapshot.value = noteText.value
    } catch {
      /* 笔记加载失败不打断解析浏览 */
    } finally {
      noteLoading.value = false
    }
  },
  { immediate: true },
)

const noteDirty = computed(() => noteText.value !== savedSnapshot.value && !(savedSnapshot.value === '' && noteText.value === ''))

async function saveNote(): Promise<void> {
  if (!noteDirty.value || noteSaving.value) return
  // 保存前后内容一致性校验（FR-NOTE-02 口径）
  const content = noteText.value
  noteSaving.value = true
  try {
    const saved = await apiPutNote(props.ctx.question.question_id, props.bankId, content)
    savedSnapshot.value = content
    noteSavedAt.value = saved.updated_at ?? new Date().toISOString()
    upsertNoteIndex(auth.userId, {
      bankId: props.bankId,
      questionId: props.ctx.question.question_id,
      noteText: content,
    })
    message.success('笔记已保存')
  } catch (err) {
    message.error(humanizeError(err))
  } finally {
    noteSaving.value = false
  }
}
</script>

<template>
  <div class="analysis-panel">
    <!-- 判分结果横幅 -->
    <n-alert v-if="isCorrect === true" type="success" :show-icon="true" title="回答正确" />
    <n-alert v-else-if="isCorrect === false" type="error" :show-icon="true" title="回答错误" />

    <!-- 主观题判分状态机（§8.2） -->
    <div v-if="grading && grading.status !== 'succeeded'" class="grading-block tu-card">
      <div v-if="grading.status === 'queued' || grading.status === 'pending'" class="grading-state">
        <n-spin size="small" />
        <span>已受理，排队等待 AI 批改…</span>
        <span class="dim">可先继续浏览其他题目</span>
      </div>
      <div v-else-if="grading.status === 'processing'" class="grading-state pulsing">
        <n-spin size="small" />
        <span>AI 正在批改，请稍候…</span>
        <span class="dim">预计需要几十秒到几分钟</span>
      </div>
      <div v-else-if="grading.status === 'failed'" class="grading-state failed">
        <span class="fail-icon" aria-hidden="true">⚠️</span>
        <span>AI 判分失败{{ grading.feedback?.error_reason ? `：${grading.feedback.error_reason}` : '' }}</span>
      </div>

      <div v-if="selfJudgeReady" class="self-judge">
        <p class="sj-title">AI 暂时无法给出结论，你可以对照标准答案自行评定：</p>
        <div class="sj-buttons">
          <n-button size="small" @click="selfJudge('correct')">完全正确</n-button>
          <n-button size="small" @click="selfJudge('partial')">部分正确</n-button>
          <n-button size="small" type="warning" secondary @click="selfJudge('wrong')">错误</n-button>
        </div>
      </div>
    </div>

    <!-- 主观题成功结果 -->
    <n-alert
      v-if="grading?.status === 'succeeded'"
      :type="isCorrect === false ? 'warning' : 'success'"
      title="AI 批改完成"
      :show-icon="true"
    >
      <p v-if="scoreLine" class="score-line">{{ scoreLine }}</p>
      <p v-if="grading.feedback?.comment">{{ grading.feedback.comment }}</p>
      <p v-if="grading.feedback?.error_reason" class="err-reason">失分原因：{{ grading.feedback.error_reason }}</p>
    </n-alert>

    <!-- 答案对照（FR-ANA-02） -->
    <section class="compare">
      <h4 class="sec-title">你的答案</h4>
      <div class="answer-box user-answer">
        <n-tag :bordered="false" size="small">🧑 你的答案</n-tag>
        <RichText :content="userAnswerText" />
      </div>

      <h4 class="sec-title">
        正确答案
        <n-tag v-if="scoreLine" size="small" type="info" round>{{ scoreLine }}</n-tag>
      </h4>
      <div class="answer-box correct-answer">
        <n-tag :bordered="false" size="small" type="success">✔ 正确答案</n-tag>
        <RichText :content="correctAnswerText || '暂无（等待服务端返回）'" />
      </div>
    </section>

    <!-- 官方解析 -->
    <section v-if="solutionText" class="solution-sec">
      <h4 class="sec-title">官方解析</h4>
      <div class="tu-card solution-card">
        <RichText :content="solutionText" :collapse-lines="24" />
      </div>
    </section>

    <!-- 笔记（FR-ANA-06） -->
    <section class="notes-sec">
      <div class="notes-head">
        <h4 class="sec-title">本题笔记</h4>
        <span v-if="noteSavedAt" class="text-secondary saved-at">更新于 {{ formatDateTime(noteSavedAt) }}</span>
      </div>
      <n-input
        v-model:value="noteText"
        type="textarea"
        :rows="3"
        placeholder="记录思路、易错点或总结…（保存后多端同步）"
      />
      <div class="notes-foot">
        <n-button size="small" type="primary" :loading="noteSaving" :disabled="!noteDirty" @click="saveNote">
          保存笔记
        </n-button>
        <n-button
          size="small"
          quaternary
          @click="router.push({
            path: '/ai-feedback',
            query: {
              bank_id: bankId,
              question_id: String(ctx.question.question_id),
              ...(attemptResult?.attempt_id ? { attempt_id: String(attemptResult.attempt_id) } : {}),
            },
          })"
        >
          AI 纠错此题
        </n-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.analysis-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.grading-block {
  padding: 14px 16px;
}

.grading-state {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

.grading-state .dim,
.dim {
  color: var(--tu-text-secondary);
  font-size: 12px;
}

.grading-state.failed {
  color: var(--tu-error);
}

.fail-icon {
  font-size: 18px;
}

.self-judge {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--tu-border);
}

.sj-title {
  margin: 0 0 8px;
  font-size: 14px;
}

.sj-buttons {
  display: flex;
  gap: 8px;
}

.score-line {
  font-weight: 700;
  margin: 0 0 4px;
}

.err-reason {
  margin-top: 4px;
}

.sec-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 6px;
}

.answer-box {
  border-radius: var(--tu-radius-card);
  padding: 10px 12px;
  border: 1px solid var(--tu-border);
  background: var(--tu-surface);
  max-height: 30vh;
  overflow-y: auto;
}

.user-answer {
  background: rgba(127, 127, 140, 0.06);
}

.correct-answer {
  border-color: rgba(24, 160, 88, 0.35);
  background: rgba(24, 160, 88, 0.05);
}

.solution-card {
  padding: 12px 14px;
  max-height: 40vh;
  overflow-y: auto;
}

.notes-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.saved-at {
  font-size: 12px;
}

.notes-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
</style>
