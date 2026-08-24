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
}

const crumbs = computed<Crumb[]>(() => {
  const pageName = (route.meta.title as string) ?? ''
  const inCatalogFlow = ['catalog', 'practice'].includes(String(route.name))
  if (!inCatalogFlow) return [{ label: pageName }]

  const items: Crumb[] = [{ label: '题库', to: '/catalog' }]
  const subject = catalog.subjects.find((s) => s.id === catalog.selectedSubjectId)
  const typeNode = subject?.types.find((t) => t.id === catalog.selectedTypeId)

  if (subject) {
    items.push({ label: `${subject.icon ?? ''} ${subject.name}`.trim(), to: '/catalog' })
    if (typeNode) items.push({ label: typeNode.name, to: '/catalog' })
    if (catalog.currentBankName) items.push({ label: catalog.currentBankName })
    else if (catalog.selectedYear !== null) items.push({ label: String(catalog.selectedYear) })
  } else if (route.name === 'practice') {
    const bank = route.params.bankId as string | undefined
    const known = bank ? catalog.bankById.get(bank) : undefined
    items.push({ label: known?.name ?? bank ?? pageName })
  } else {
    return [{ label: pageName }]
  }

  if (route.name === 'practice') {
    const tc = route.query.type_code as string | undefined
    if (tc && tc !== catalog.selectedTypeId) items.push({ label: typeCodeLabel(tc) })
  }
  return items
})

function go(crumb: Crumb): void {
  if (crumb.to) router.push(crumb.to)
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
