<script setup lang="ts">
import { ref } from 'vue'
import { NAlert, NButton, NInput, NSpin, NTag } from 'naive-ui'
import {
  apiAdminHealthStart,
  apiAdminHealthStatus,
  apiAdminReloadCatalog,
} from '@/api/endpoints'
import type { HealthTask } from '@/api/generated/schema'
import { humanizeError } from '@/api/http'
import { appDialog, appMessage } from '@/utils/feedback'
import { useCatalogStore } from '@/stores/catalog'
import { usePolling } from '@/composables/usePolling'

/**
 * 管理页（FR-ADM-01~03，仅 admin）：
 * - 目录重载（二次确认 + 本地缓存刷新）；
 * - 健康检查任务发起与轮询结果展示。
 */
const catalog = useCatalogStore()

/* ---------- 目录重载（FR-ADM-02） ---------- */

const reloading = ref(false)

async function reloadCatalog(): Promise<void> {
  const confirmed = await new Promise<boolean>((resolve) => {
    appDialog.warning({
      title: '确认重载目录？',
      content: '将重新读取 manifest.json 并校验全部题库。校验通过前旧索引保持可用。',
      positiveText: '确认重载',
      negativeText: '取消',
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    })
  })
  if (!confirmed || reloading.value) return
  reloading.value = true
  try {
    const summary = await apiAdminReloadCatalog()
    catalog.invalidateAll()
    await catalog.fetchCatalog(true)
    appMessage.success(
      `目录重载完成：启用 ${summary.enabled_banks ?? '?'} 个题库${(summary.warnings ?? []).length > 0 ? `，${summary.warnings!.length} 条告警` : ''}`,
    )
  } catch (err) {
    appMessage.error(humanizeError(err))
  } finally {
    reloading.value = false
  }
}

/* ---------- 健康检查（FR-ADM-03） ---------- */

const bankFilter = ref('')
const starting = ref(false)
const healthTask = ref<HealthTask | null>(null)
const healthError = ref<string | null>(null)

const healthPolling = usePolling<HealthTask>(
  async () => {
    if (!healthTask.value) throw new Error('无进行中的检查任务')
    return apiAdminHealthStatus(healthTask.value.task_id)
  },
  {
    intervalStartMs: 2000,
    intervalMaxMs: 10000,
    until: (t) => t.status === 'succeeded' || t.status === 'failed' || t.status === 'done',
  },
)

async function startHealthCheck(): Promise<void> {
  if (starting.value) return
  starting.value = true
  healthError.value = null
  try {
    const task = await apiAdminHealthStart(bankFilter.value.trim() || undefined)
    healthTask.value = task
    healthPolling.start()
    // 即时结论可能已随响应返回
    if (task.issues || task.summary) healthPolling.stop()
  } catch (err) {
    healthError.value = humanizeError(err)
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div class="content-inner admin-view">
    <n-alert v-if="$route.query.denied === 'admin'" type="warning" title="权限不足" style="margin-bottom: 16px">
      该页面仅管理员可访问。
    </n-alert>

    <section class="tu-card admin-card">
      <h3>目录重载</h3>
      <p class="text-secondary desc">重新读取并校验 manifest.json；校验失败时保留旧索引。</p>
      <n-button type="primary" :loading="reloading" :disabled="reloading" @click="reloadCatalog">
        触发重载
      </n-button>
      <p v-if="catalog.loaded" class="text-secondary state-line">
        当前本地目录：{{ catalog.subjects.length }} 个学科 · {{ catalog.banks.length }} 个题库条目
      </p>
    </section>

    <section class="tu-card admin-card">
      <h3>题库健康检查</h3>
      <p class="text-secondary desc">检查表结构、JSON 合法性、图片 MIME 与解析完整性。大库走异步任务。</p>
      <div class="row">
        <n-input v-model:value="bankFilter" placeholder="按 bank_id 筛选（留空检查全部）" size="small" class="bank-input" />
        <n-button size="small" type="primary" :loading="starting" @click="startHealthCheck">发起检查</n-button>
      </div>

      <n-alert v-if="healthError" type="error" style="margin-top: 12px">{{ healthError }}</n-alert>

      <n-spin v-if="healthTask && (healthPolling.loading.value || !healthTask.issues)" :show="true" style="margin-top: 14px">
        检查进行中… 任务 ID：{{ healthTask.task_id }}
      </n-spin>

      <div v-if="healthTask?.issues && healthTask.issues.length > 0" class="issues" style="margin-top: 12px">
        <div v-for="(issue, i) in healthTask.issues" :key="i" class="issue-row">
          <n-tag size="tiny" :type="issue.level === 'error' ? 'error' : 'warning'">{{ issue.level ?? 'warning' }}</n-tag>
          <span>{{ issue.bank_id ? `[${issue.bank_id}] ` : '' }}{{ issue.message }}</span>
        </div>
      </div>
      <n-alert v-else-if="healthTask?.issues && healthTask.issues.length === 0" type="success" style="margin-top: 12px">
        {{ healthTask.summary ?? '全部通过，未发现问题' }}
      </n-alert>
    </section>
  </div>
</template>

<style scoped>
.admin-view {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 760px;
}

.admin-card {
  padding: 18px 20px;
}

h3 {
  margin: 0 0 6px;
  font-size: 16px;
}

.desc {
  font-size: 13px;
  margin: 0 0 12px;
}

.row {
  display: flex;
  gap: 10px;
}

.bank-input {
  width: 320px;
}

.state-line {
  font-size: 12px;
  margin-top: 10px;
}

.issue-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  overflow-wrap: anywhere;
}
</style>
