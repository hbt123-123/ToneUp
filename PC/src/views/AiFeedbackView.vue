<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NAlert, NButton, NInput, NTag, NUpload, useMessage } from 'naive-ui'
import type { UploadFileInfo } from 'naive-ui'
import { apiAiFeedbackStatus, apiCreateAiFeedback } from '@/api/endpoints'
import type { AiFeedbackTask } from '@/api/generated/schema'
import { humanizeError } from '@/api/http'
import { usePolling } from '@/composables/usePolling'
import { formatDateTime } from '@/utils/format'

/**
 * AI 纠错上传页（FR-AI-01~06）：
 * - 前置校验：jpg/png/webp、≤5MB（契约阈值）；
 * - 创建任务后按 2s 起步退避至 10s 轮询，可手动刷新，离开即停；
 * - queued/pending/processing/succeeded/failed 全状态展示；失败一键重发。
 */
const route = useRoute()
const message = useMessage()

/* 从解析视图带入的上下文自动填充（FR-AI-02） */
const presetBankId = typeof route.query.bank_id === 'string' ? route.query.bank_id : ''
const presetQuestionId = typeof route.query.question_id === 'string' ? route.query.question_id : ''
const attemptIdFromQuery = typeof route.query.attempt_id === 'string' ? route.query.attempt_id : ''

const bankIdInput = ref(presetBankId)
const questionIdInput = ref<string>(presetQuestionId ?? '')
const fileRef = ref<File | null>(null)
const uploading = ref(false)
const task = ref<AiFeedbackTask | null>(null)
/** 本次会话历史任务列表（FR-AI-06） */
const history = ref<{ id: string; status: string; summary: string }[]>([])

/* ---------- 文件预校验（FR-AI-01） ---------- */

const MAX_SIZE_MB = 5
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']

function beforeUpload(options: { file: UploadFileInfo }): boolean {
  const raw = options.file.file
  if (!raw) return false
  if (!ALLOWED_TYPES.includes(raw.type)) {
    message.error('仅支持 jpg / png / webp 格式的图片')
    return false
  }
  if (raw.size > MAX_SIZE_MB * 1024 * 1024) {
    message.error(`图片大小不能超过 ${MAX_SIZE_MB}MB`)
    return false
  }
  fileRef.value = raw
  return false // 阻止 naive-ui 自动上传，由提交动作统一发起
}

const canSubmit = computed(() => !!fileRef.value)

async function createTask(): Promise<void> {
  const file = fileRef.value
  if (!file || uploading.value) return
  uploading.value = true
  try {
    const result = await apiCreateAiFeedback({
      file,
      bankId: bankIdInput.value || undefined,
      questionId: questionIdInput.value ? Number(questionIdInput.value) : undefined,
      attemptId: attemptIdFromQuery ? Number(attemptIdFromQuery) : undefined,
    })
    task.value = result
    startPolling(result.feedback_id)
    message.success('任务已创建')
  } catch (err) {
    message.error(humanizeError(err))
  } finally {
    uploading.value = false
  }
}

/* ---------- 轮询（FR-AI-03）：2s → 10s 退避；离开页面自动停止 ---------- */

const polling = usePolling<AiFeedbackTask>(
  async () => {
    if (!task.value) throw new Error('无进行中的任务')
    return apiAiFeedbackStatus(task.value.feedback_id)
  },
  {
    intervalStartMs: 2000,
    intervalMaxMs: 10000,
    until: (t) => t.status === 'succeeded' || t.status === 'failed',
  },
)

/* 轮询结果同步到本地状态与历史列表；离开页面时轮询随作用域自动停止 */
watch(polling.data, (latest) => {
  if (!latest) return
  task.value = latest
  recordHistory(latest)
})

function startPolling(feedbackId: string): void {
  history.value.unshift({ id: feedbackId, status: 'queued', summary: '排队中' })
  polling.start()
}

/* 手动刷新（FR-AI-03） */
async function manualRefresh(): Promise<void> {
  await polling.manualRefresh()
  if (polling.data.value) {
    task.value = polling.data.value
    recordHistory(task.value)
  }
}

function recordHistory(t: AiFeedbackTask): void {
  const found = history.value.find((h) => h.id === t.feedback_id)
  const summary =
    t.status === 'succeeded'
      ? t.is_correct === true
        ? 'AI 判定：正确'
        : t.is_correct === false
          ? `AI 判定：有误${t.error_reason ? ` · ${t.error_reason.slice(0, 30)}` : ''}`
          : '已完成'
      : t.status === 'failed'
        ? `失败：${t.error_message?.slice(0, 40) ?? '未知原因'}`
        : t.status === 'processing'
          ? '判分中'
          : '排队中'
  if (found) {
    found.status = t.status
    found.summary = summary
  }
}
</script>

<template>
  <div class="content-inner ai-view">
    <section class="upload-section tu-card">
      <h3>上传手写答案照片，让 AI 帮你诊断错误</h3>
      <p class="text-secondary tip">
        支持 jpg / png / webp，大小 ≤ {{ MAX_SIZE_MB }}MB。可关联题库与题目，便于精准对照。
      </p>
      <n-upload
        :max="1"
        :default-upload="false"
        accept="image/jpeg,image/png,image/webp"
        list-type="image-card"
        @before-upload="beforeUpload"
      >
        点击选择图片
      </n-upload>
      <div class="context-row">
        <n-input v-model:value="bankIdInput" placeholder="关联题库 bank_id（可选）" size="small" class="ctx-input" />
        <n-input v-model:value="questionIdInput" placeholder="关联题目 ID（可选）" size="small" class="ctx-input" />
        <n-tag v-if="attemptIdFromQuery" size="small" round>已关联本次作答 #{{ attemptIdFromQuery }}</n-tag>
      </div>
      <n-button type="primary" block :loading="uploading" :disabled="!canSubmit || uploading" @click="createTask">
        创建诊断任务
      </n-button>
    </section>

    <!-- 任务状态展示（FR-AI-04） -->
    <section v-if="task" class="status-section tu-card">
      <div class="status-head">
        <h3>诊断结果</h3>
        <span class="text-secondary">任务 ID：{{ task.feedback_id }}</span>
        <n-button size="tiny" quaternary @click="manualRefresh">手动刷新</n-button>
      </div>

      <n-alert v-if="task.status === 'queued'" type="info">排队中，请稍候…</n-alert>
      <n-alert v-else-if="task.status === 'pending'" type="info">已受理，等待处理…</n-alert>
      <n-alert v-else-if="task.status === 'processing'" type="warning" class="pulsing">AI 正在分析你的作答…</n-alert>

      <template v-else-if="task.status === 'succeeded'">
        <n-alert :type="task.is_correct === false ? 'error' : 'success'" :title="task.is_correct === false ? '判定：作答有误' : '判定：作答正确'">
          <p v-if="task.error_reason">{{ task.error_reason }}</p>
          <p v-if="task.comment">{{ task.comment }}</p>
        </n-alert>
        <div v-if="(task.tag_ids ?? []).length > 0" class="tags">
          <n-tag v-for="tid in task.tag_ids" :key="tid" size="small" round type="info">知识点 #{{ tid }}</n-tag>
        </div>
      </template>

      <template v-else-if="task.status === 'failed'">
        <n-alert type="error" title="任务失败">
          {{ task.error_message ?? 'AI 服务暂时不可用' }}
        </n-alert>
        <!-- 失败重试（FR-AI-05）：新建任务 -->
        <n-button type="primary" secondary :disabled="!canSubmit" @click="createTask">重新发起</n-button>
        <span v-if="!canSubmit" class="text-secondary retry-hint">请重新选择图片后再发起</span>
      </template>

      <p v-if="task.completed_at" class="text-secondary done-at">完成于 {{ formatDateTime(task.completed_at) }}</p>
    </section>

    <!-- 会话历史（FR-AI-06） -->
    <section v-if="history.length > 0" class="history tu-card">
      <h3>本次会话的任务</h3>
      <ul>
        <li v-for="h in history" :key="h.id">
          <span class="hid">#{{ h.id.slice(0, 8) }}</span>
          <n-tag size="tiny" :type="h.status === 'succeeded' ? 'success' : h.status === 'failed' ? 'error' : 'info'">
            {{ h.status }}
          </n-tag>
          <span class="text-secondary">{{ h.summary }}</span>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.ai-view {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 760px;
}

.upload-section,
.status-section,
.history {
  padding: 18px 20px;
}

h3 {
  margin: 0 0 8px;
  font-size: 16px;
}

.tip {
  font-size: 13px;
  margin: 0 0 12px;
}

.context-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.ctx-input {
  width: 240px;
}

.status-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.tags {
  display: flex;
  gap: 6px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.done-at {
  font-size: 12px;
  margin-top: 8px;
}

.retry-hint {
  font-size: 12px;
}

.history ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.hid {
  font-family: monospace;
}
</style>
