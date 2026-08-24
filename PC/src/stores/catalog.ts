import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiBankDetail, apiCatalog } from '@/api/endpoints'
import type { BankDetail, BankSummary, CatalogData, SubjectNode } from '@/api/generated/schema'

/**
 * catalog store（§2.4）：学科/题型/题库目录树 + 当前选择路径（面包屑数据源）。
 * 会话级内存缓存，手动刷新（FR-CAT-06 / §8.5）。
 */
export const useCatalogStore = defineStore('catalog', () => {
  const subjects = ref<SubjectNode[]>([])
  const banks = ref<BankSummary[]>([])
  const loaded = ref(false)
  const loading = ref(false)

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

  /** 管理侧重载后手动刷新本地缓存（FR-ADM-02） */
  function invalidateAll(): void {
    bankDetailCache.clear()
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
