import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiBankDetail, apiCatalog } from '@/api/endpoints'
import type { BankDetail, BankSummary, CatalogData, SubjectNode } from '@/api/generated/schema'
import { readJsonCache, removeJsonCache, writeJsonCache } from '@/utils/storage'

/** EC-05 目录持久缓存键：登录态缓存，不登记 DEVICE_LEVEL_KEYS 豁免，登出随用户域清除 */
const CATALOG_CACHE_KEY = 'toneup:catalog:cache'

interface CatalogCachePayload {
  subjects: SubjectNode[]
  banks: BankSummary[]
}

/**
 * catalog store（§2.4）：学科/题型/题库目录树 + 当前选择路径（面包屑数据源）。
 * cache-first 水合（EC-05）：初始化即从 localStorage 恢复目录，随后网络刷新覆盖。
 */
export const useCatalogStore = defineStore('catalog', () => {
  const subjects = ref<SubjectNode[]>([])
  const banks = ref<BankSummary[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  // EC-05 cache-first：store 创建时立即水合持久缓存（损坏/缺失 → null 走网络路径）
  const cached = readJsonCache<CatalogCachePayload>(CATALOG_CACHE_KEY)
  if (cached && Array.isArray(cached.subjects) && Array.isArray(cached.banks)) {
    subjects.value = cached.subjects
    banks.value = cached.banks
    loaded.value = true
  }

  /** 面包屑三级联动数据源：学科 / 题型 / 年份（§4.3） */
  const selectedSubjectId = ref<string | null>(null)
  const selectedTypeId = ref<string | null>(null)
  const selectedYear = ref<number | null>(null)
  const currentBankName = ref<string | null>(null)

  const bankById = computed<Map<string, BankSummary>>(() => {
    const map = new Map<string, BankSummary>()
    for (const b of banks.value) map.set(b.id, b)
    return map
  })

  function banksOf(subjectId: string | null, typeId: string | null): BankSummary[] {
    return banks.value.filter(
      (b) => b.enabled !== false && (!subjectId || b.subject_id === subjectId) && (!typeId || b.type_id === typeId),
    )
  }

  async function fetchCatalog(force = false): Promise<void> {
    if (loaded.value && !force) return
    loading.value = true
    try {
      const data: CatalogData = await apiCatalog()
      subjects.value = data?.subjects ?? []
      banks.value = data?.banks ?? []
      loaded.value = true
      writeJsonCache(CATALOG_CACHE_KEY, { subjects: subjects.value, banks: banks.value } satisfies CatalogCachePayload)
    } finally {
      loading.value = false
    }
  }

  /** 学科变化则题型与年份重置（§4.3 联动规则） */
  function selectSubject(subjectId: string | null): void {
    if (selectedSubjectId.value === subjectId) return
    selectedSubjectId.value = subjectId
    selectedTypeId.value = null
    selectedYear.value = null
  }

  function selectType(typeId: string | null): void {
    if (selectedTypeId.value === typeId) return
    selectedTypeId.value = typeId
    selectedYear.value = null
  }

  function selectYear(year: number | null): void {
    selectedYear.value = year
  }

  /** 题库详情会话级缓存（§8.5 元数据缓存） */
  const bankDetailCache = new Map<string, BankDetail>()

  async function fetchBankDetail(bankId: string, force = false): Promise<BankDetail> {
    if (!force && bankDetailCache.has(bankId)) {
      return bankDetailCache.get(bankId)!
    }
    const detail = await apiBankDetail(bankId)
    bankDetailCache.set(bankId, detail)
    return detail
  }

  /** 管理侧重载后手动刷新本地缓存（FR-ADM-02）：内存与持久键一并清除 */
  function invalidateAll(): void {
    bankDetailCache.clear()
    removeJsonCache(CATALOG_CACHE_KEY)
    loaded.value = false
    subjects.value = []
    banks.value = []
  }

  function reset(): void {
    selectedSubjectId.value = null
    selectedTypeId.value = null
    selectedYear.value = null
    currentBankName.value = null
  }

  return {
    subjects,
    banks,
    loaded,
    loading,
    selectedSubjectId,
    selectedTypeId,
    selectedYear,
    currentBankName,
    bankById,
    banksOf,
    fetchCatalog,
    selectSubject,
    selectType,
    selectYear,
    fetchBankDetail,
    invalidateAll,
    reset,
  }
})
