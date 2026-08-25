<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NInput, NSelect, NSwitch, NTag } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useWrongBookStore } from '@/stores/wrongbook'
import { useCatalogStore } from '@/stores/catalog'
import VirtualList from '@/components/common/VirtualList.vue'
import { formatRelative, truncateText, typeCodeLabel } from '@/utils/format'
import { readMarked } from '@/utils/storage'

/**
 * 错题本（FR-WRONG-01~04）：
 * - 数据来源为本地缓存的服务端判分结果（契约未提供专用端点）；
 * - 学科/题型/年份筛选、题干预览、错误次数、最近作答时间；
 * - 疑问标记题目同样收录并区分标识；支持重练与跳转原题。
 */
const router = useRouter()
const auth = useAuthStore()
const wrongbook = useWrongBookStore()
const catalog = useCatalogStore()

onMounted(async () => {
  await catalog.fetchCatalog().catch(() => undefined)
  wrongbook.bindUser(() => auth.userId)
  wrongbook.loadForUser(auth.userId, (bankId) => readMarked(auth.userId, bankId))
})

const subjectOptions = computed(() => [
  { label: '全部学科', value: '' },
  ...catalog.subjects.map((s) => ({ label: s.name, value: s.id })),
])

const typeOptions = computed(() => [
  { label: '全部题型', value: '' },
  ...[...new Set(wrongbook.records.map((r) => r.type_code).filter(Boolean))].map((tc) => ({
    label: typeCodeLabel(tc as string),
    value: tc as string,
  })),
])

const yearOptions = computed(() => {
  const years = [...new Set(wrongbook.records.map((r) => r.year).filter((y): y is number => typeof y === 'number'))]
  return [{ label: '全部年份', value: -1 }, ...years.sort((a, b) => b - a).map((y) => ({ label: String(y), value: y }))]
})

const subjectFilter = ref('')
const typeFilter = ref('')
const yearFilter = ref(-1)

const filtered = computed(() =>
  wrongbook.filtered.filter(
    (r) =>
      (!subjectFilter.value ||
        catalog.bankById.get(r.bank_id)?.subject_id === subjectFilter.value) &&
      (!typeFilter.value || r.type_code === typeFilter.value) &&
      (yearFilter.value < 0 || r.year === yearFilter.value),
  ),
)

function repractice(bankId: string, questionId?: number): void {
  localStorage.setItem('toneup:last-bank', bankId)
  const bank = catalog.bankById.get(bankId)
  if (bank) catalog.selectSubject(bank.subject_id)
  void router.push({
    name: 'practice',
    params: { bankId },
    query: questionId !== undefined ? { qid: String(questionId) } : {},
  })
}
</script>

<template>
  <div class="content-inner wrong-view">
    <div class="toolbar tu-card">
      <n-select v-model:value="subjectFilter" :options="subjectOptions" class="f-sel" size="small" />
      <n-select v-model:value="typeFilter" :options="typeOptions" class="f-sel" size="small" />
      <n-select v-model:value="yearFilter" :options="yearOptions" class="f-sel" size="small" />
      <label class="marked-toggle">
        <n-switch v-model:value="wrongbook.markedOnly" size="small" />
        <span>只看疑问标记</span>
      </label>
      <n-input v-model:value="wrongbook.keyword" placeholder="搜索关键词" size="small" clearable class="kw" />
    </div>

    <div v-if="filtered.length > 0" class="list-wrap tu-card">
      <virtual-list :items="filtered" :item-size="96">
        <template #default="{ item }">
          <div class="wrong-item">
            <div class="wi-main">
              <p class="preview">{{ truncateText(item.preview ?? `题目 #${item.question_id}`, 72) }}</p>
              <div class="meta-row text-secondary">
                <span>{{ catalog.bankById.get(item.bank_id)?.name ?? item.bank_id }}</span>
                <n-tag v-if="item.year" size="tiny" round>{{ item.year }}</n-tag>
                <n-tag size="tiny" round>{{ typeCodeLabel(item.type_code) }}</n-tag>
                <n-tag v-if="item.marked" size="tiny" round type="warning">★ 疑问标记</n-tag>
                <span>最近作答 {{ formatRelative(item.last_practice_at) }}</span>
              </div>
            </div>
            <div class="wi-side">
              <span class="wrong-count">✕ {{ item.wrong_count ?? 1 }} 次错误</span>
              <n-button size="small" type="primary" secondary @click="repractice(item.bank_id, item.question_id)">
                重练此题
              </n-button>
            </div>
          </div>
        </template>
      </virtual-list>
    </div>

    <n-empty v-else description="错题本是空的，继续加油保持！" class="empty-pad">
      <template #extra>
        <n-button size="small" @click="$router.push('/catalog')">去刷题</n-button>
      </template>
    </n-empty>
  </div>
</template>

<style scoped>
.wrong-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.f-sel {
  width: 150px;
}

.kw {
  width: 200px;
  margin-left: auto;
}

.marked-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.list-wrap {
  padding: 8px;
  height: calc(100vh - 260px);
  min-height: 300px;
  overflow-y: auto;
}

.wrong-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 12px;
  border-bottom: 1px solid var(--tu-border);
}

.preview {
  margin: 0 0 6px;
  font-size: 15px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  flex-wrap: wrap;
}

.wi-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex: none;
}

.wrong-count {
  color: var(--tu-error);
  font-size: 13px;
  font-weight: 600;
}

.empty-pad {
  padding: 80px 0;
}
</style>
