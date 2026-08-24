<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NEmpty, NSelect, NSpin, NTag } from 'naive-ui'
import { useCatalogStore } from '@/stores/catalog'
import { humanizeError } from '@/api/http'
import { appMessage } from '@/utils/feedback'
import { typeCodeLabel } from '@/utils/format'

/**
 * 题库选择页（FR-CAT-01~06）：
 * - 学科 → 题型 → 年份 三级联动，筛选写入 URL query（刷新/分享可恢复）；
 * - 题库详情展示可用年份与题型分布；
 * - "开始刷题"携带 bank_id 与筛选进入工作台。
 */
const route = useRoute()
const router = useRouter()
const catalog = useCatalogStore()

const detailLoading = ref(false)
const bankDetail = ref<Awaited<ReturnType<typeof catalog.fetchBankDetail>> | null>(null)

onMounted(async () => {
  try {
    await catalog.fetchCatalog()
    // 从 URL query 恢复选择（FR-CAT-03）
    const subject = typeof route.query.subject === 'string' ? route.query.subject : null
    const type = typeof route.query.type === 'string' ? route.query.type : null
    const year = typeof route.query.year === 'string' ? Number(route.query.year) : null
    if (subject) catalog.selectSubject(subject)
    if (type) catalog.selectType(type)
    if (year !== null && !Number.isNaN(year)) catalog.selectYear(year)
  } catch (err) {
    appMessage.error(humanizeError(err))
  }
})

const subjectOptions = computed(() =>
  catalog.subjects.map((s) => ({ label: `${s.icon ?? ''} ${s.name}`.trim(), value: s.id })),
)

const typeOptions = computed(() => {
  const subject = catalog.subjects.find((s) => s.id === catalog.selectedSubjectId)
  return (subject?.types ?? []).map((t) => ({ label: t.name, value: t.id }))
})

const visibleBanks = computed(() =>
  catalog.banksOf(catalog.selectedSubjectId, catalog.selectedTypeId),
)

/** 年份选项：来自题库详情或静态区间 */
const yearOptions = computed(() => {
  if (bankDetail.value?.years && bankDetail.value.years.length > 0) {
    return [...bankDetail.value.years].sort((a, b) => b - a).map((y) => ({ label: String(y), value: y }))
  }
  if (bankDetail.value?.year_min && bankDetail.value?.year_max) {
    const out: { label: string; value: number }[] = []
    for (let y = bankDetail.value.year_max; y >= bankDetail.value.year_min; y--) out.push({ label: String(y), value: y })
    return out
  }
  return []
})

async function onPickBank(bankId: string): Promise<void> {
  detailLoading.value = true
  try {
    bankDetail.value = await catalog.fetchBankDetail(bankId)
    const bank = catalog.bankById.get(bankId)
    catalog.currentBankName = bank?.name ?? bankDetail.value.name
  } catch (err) {
    appMessage.error(humanizeError(err))
  } finally {
    detailLoading.value = false
  }
}

function onSubjectChange(value: string | null): void {
  bankDetail.value = null
  catalog.selectSubject(value)
  syncQuery()
}

function onTypeChange(value: string | null): void {
  bankDetail.value = null
  catalog.selectType(value)
  syncQuery()
}

function onYearChange(value: number | null): void {
  catalog.selectYear(value)
  syncQuery()
}

function syncQuery(): void {
  void router.replace({
    query: {
      ...(catalog.selectedSubjectId ? { subject: catalog.selectedSubjectId } : {}),
      ...(catalog.selectedTypeId ? { type: catalog.selectedTypeId } : {}),
      ...(catalog.selectedYear !== null ? { year: String(catalog.selectedYear) } : {}),
    },
  })
}

function startPractice(bankId: string): void {
  localStorage.setItem('toneup:last-bank', bankId)
  void router.push({
    name: 'practice',
    params: { bankId },
    query: {
      ...(catalog.selectedYear !== null ? { year: String(catalog.selectedYear) } : {}),
      ...(catalog.selectedTypeId ? { type_code: catalog.selectedTypeId } : {}),
    },
  })
}

async function refreshCatalog(): Promise<void> {
  try {
    await catalog.fetchCatalog(true)
    appMessage.success('目录已刷新')
  } catch (err) {
    appMessage.error(humanizeError(err))
  }
}
</script>

<template>
  <div class="content-inner catalog-view">
    <div class="filter-bar tu-card">
      <div class="selects">
        <n-select
          class="sel"
          :value="catalog.selectedSubjectId"
          :options="subjectOptions"
          placeholder="学科"
          clearable
          @update:value="onSubjectChange"
        />
        <n-select
          class="sel"
          :value="catalog.selectedTypeId"
          :options="typeOptions"
          placeholder="题型分类"
          clearable
          :disabled="!catalog.selectedSubjectId"
          @update:value="onTypeChange"
        />
        <n-select
          class="sel"
          :value="catalog.selectedYear"
          :options="yearOptions"
          placeholder="年份"
          clearable
          :disabled="!bankDetail"
          @update:value="onYearChange"
        />
      </div>
      <n-button quaternary size="small" @click="refreshCatalog">刷新目录</n-button>
    </div>

    <n-spin :show="catalog.loading || detailLoading">
      <div v-if="visibleBanks.length > 0" class="bank-grid">
        <n-card
          v-for="bank in visibleBanks"
          :key="bank.id"
          size="small"
          class="tu-card bank-card option-row"
          @click="onPickBank(bank.id)"
        >
          <template #header>{{ bank.name }}</template>
          <template #header-extra>
            <n-tag v-if="bank.enabled === false" type="warning" size="small">未启用</n-tag>
            <n-tag v-else-if="bank.id === bankDetail?.id" type="info" size="small">已选</n-tag>
          </template>
          <p class="text-secondary bank-id">{{ bank.id }}</p>

          <!-- 题库详情（FR-CAT-04） -->
          <div v-if="bank.id === bankDetail?.id" class="detail" @click.stop>
            <div v-if="bankDetail.years?.length || bankDetail.year_min" class="detail-row">
              <span class="label">可用年份：</span>
              <span>{{ bankDetail.years?.length ? `${Math.min(...bankDetail.years)} - ${Math.max(...bankDetail.years)}（${bankDetail.years.length} 套）` : `${bankDetail.year_min} - ${bankDetail.year_max}` }}</span>
            </div>
            <div v-if="(bankDetail.type_distribution ?? []).length > 0" class="dist">
              <n-tag
                v-for="dist in bankDetail.type_distribution"
                :key="String(dist.type_code)"
                size="small"
                round
              >
                {{ dist.label ?? typeCodeLabel(String(dist.type_code)) }} × {{ dist.count ?? '?' }}
              </n-tag>
            </div>
            <n-button type="primary" block @click.stop="startPractice(bank.id)">
              开始刷题{{ catalog.selectedYear !== null ? `（仅 ${catalog.selectedYear} 年）` : '' }}
            </n-button>
          </div>
          <n-button v-else quaternary size="small" class="view-btn" @click.stop="onPickBank(bank.id)">
            查看详情
          </n-button>
        </n-card>
      </div>

      <n-empty
        v-else-if="!catalog.loading"
        description="没有符合条件的题库，换个筛选试试"
        class="empty"
      >
        <template #extra>
          <n-button size="small" @click="router.push('/')">返回首页</n-button>
        </template>
      </n-empty>
    </n-spin>
  </div>
</template>

<style scoped>
.catalog-view {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 16px;
}

.selects {
  display: grid;
  grid-template-columns: repeat(3, minmax(160px, 240px));
  gap: 10px;
  flex: 1;
}

.bank-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}

.bank-card {
  cursor: pointer;
  border: 1px solid var(--tu-border);
}

.bank-id {
  font-size: 12px;
  margin: 0 0 8px;
}

.detail {
  border-top: 1px dashed var(--tu-border);
  padding-top: 10px;
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.detail-row .label {
  color: var(--tu-text-secondary);
  font-size: 13px;
}

.dist {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.view-btn {
  align-self: flex-start;
}

.empty {
  padding: 60px 0;
}
</style>
