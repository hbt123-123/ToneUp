<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NBreadcrumb, NBreadcrumbItem } from 'naive-ui'
import { useCatalogStore } from '@/stores/catalog'
import { typeCodeLabel } from '@/utils/format'

/** 面包屑（§4.3）：题库流程为 学科/题型/年份 三级联动；其他页面显示页面名 */
const route = useRoute()
const router = useRouter()
const catalog = useCatalogStore()

interface Crumb {
  label: string
  to?: string
  /** M-448：点击父级 crumb 时需折叠的下游层级（subject：清题型+年份；type：清年份） */
  resetTo?: 'subject' | 'type'
  subjectId?: string
  typeId?: string
}

const crumbs = computed<Crumb[]>(() => {
  const pageName = (route.meta.title as string) ?? ''
  const inCatalogFlow = ['catalog', 'practice'].includes(String(route.name))
  if (!inCatalogFlow) return [{ label: pageName }]

  const items: Crumb[] = [{ label: '题库', to: '/catalog' }]
  const isPractice = route.name === 'practice'

  // M-447：practice 路由优先取 route 真实参数/查询（bankId / query.year / query.type_code），
  // store 状态仅在 route 数据不可用时回退，避免跨会话/跨题库的残留选择串进面包屑
  const bankId = isPractice ? (route.params.bankId as string | undefined) : undefined
  const bank = bankId ? catalog.bankById.get(bankId) : undefined
  const subjectId = isPractice ? (bank?.subject_id ?? null) : catalog.selectedSubjectId
  const subject = subjectId ? catalog.subjects.find((s) => s.id === subjectId) : undefined
  const typeId = isPractice ? (bank?.type_id ?? null) : catalog.selectedTypeId
  const typeNode = typeId && subject ? subject.types.find((t) => t.id === typeId) : undefined
  // M-449：query.type_code 仅作题型标签兜底（'all' 表示全部题型，不展示；条件沿用并补充 tc != 'all'）
  const tc =
    isPractice &&
    typeof route.query.type_code === 'string' &&
    route.query.type_code !== '' &&
    route.query.type_code !== 'all'
      ? route.query.type_code
      : null
  const yearQ =
    isPractice && typeof route.query.year === 'string' && route.query.year !== '' ? route.query.year : null

  if (subject) {
    // M-448：父级 crumb 链接只携带自身及上游层级参数（下游参数缺失即视为清空），
    // 点击时同步折叠 store 中的下游选择，保证「点击学科/题型即收起下游」
    items.push({
      label: `${subject.icon ?? ''} ${subject.name}`.trim(),
      to: `/catalog?subject=${encodeURIComponent(subject.id)}`,
      resetTo: 'subject',
      subjectId: subject.id,
    })
    // M-449：题型 crumb 位于年份/题库之前，保持 学科 → 题型 → 年份/题库 的正确层级
    if (typeNode) {
      items.push({
        label: typeNode.name,
        to: `/catalog?subject=${encodeURIComponent(subject.id)}&type=${encodeURIComponent(typeNode.id)}`,
        resetTo: 'type',
        subjectId: subject.id,
        typeId: typeNode.id,
      })
    } else if (tc) {
      // 仅带 type_code 的直链入口（如错题重练/笔记跳转）：catalog 题型维度未知，仅展示标签不可点
      items.push({ label: typeCodeLabel(tc) })
    }
    // 末级：题库名优先（route 真实数据优先、store 回退），其次年份
    const bankName = bank?.name ?? catalog.currentBankName
    if (bankName) items.push({ label: bankName })
    else if (yearQ) items.push({ label: yearQ })
    else if (catalog.selectedYear !== null) items.push({ label: String(catalog.selectedYear) })
  } else if (isPractice) {
    // M-447：目录未加载/未知学科时退化为仅展示题库名（route 参数优先、store 回退）
    items.push({ label: bank?.name ?? catalog.currentBankName ?? bankId ?? pageName })
  } else {
    return [{ label: pageName }]
  }

  return items
})

function go(crumb: Crumb): void {
  if (!crumb.to) return
  // M-448：导航前同步折叠 store 下游选择——selectSubject/selectType 在同值时会短路，
  // 必须显式清空下游（题型/年份），否则残留选择与链接参数（已不带下游）不一致
  if (crumb.resetTo === 'subject' && crumb.subjectId) {
    catalog.selectSubject(crumb.subjectId)
    if (catalog.selectedTypeId !== null) catalog.selectType(null)
    catalog.selectYear(null)
  } else if (crumb.resetTo === 'type' && crumb.subjectId) {
    catalog.selectSubject(crumb.subjectId)
    if (crumb.typeId) catalog.selectType(crumb.typeId)
    catalog.selectYear(null)
  }
  void router.push(crumb.to)
}
</script>

<template>
  <nav aria-label="面包屑">
    <n-breadcrumb>
      <n-breadcrumb-item
        v-for="(crumb, i) in crumbs"
        :key="i"
        :clickable="!!crumb.to"
        @click="go(crumb)"
      >
        {{ crumb.label }}
      </n-breadcrumb-item>
    </n-breadcrumb>
  </nav>
</template>
