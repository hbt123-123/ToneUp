<script setup lang="ts" generic="T extends string | number | object">
import { computed } from 'vue'
import { NVirtualList } from 'naive-ui'

/**
 * 虚拟列表包装（§10.4）：超过约 50 条的列表启用；
 * 保留原生滚动条与键盘滚动能力。
 */
const props = withDefaults(
  defineProps<{
    items: T[]
    itemSize: number
    /** 少于该数量时直接渲染，避免虚拟化开销 */
    threshold?: number
  }>(),
  { threshold: 50 },
)

const useVirtual = computed(() => props.items.length >= props.threshold)

type NaiveItemData = Record<string, unknown>

function asItemData(items: T[]): NaiveItemData[] {
  return items.map((it) => (typeof it === 'object' && it !== null ? (it as Record<string, unknown>) : { value: it }))
}
</script>

<template>
  <n-virtual-list
    v-if="useVirtual"
    :items="asItemData(items)"
    :item-size="itemSize"
    :item-resizable="false"
    style="height: 100%"
  >
    <template #default="{ item, index }: { item: T; index: number }">
      <slot :item="item" :index="index" />
    </template>
  </n-virtual-list>
  <div v-else class="plain-list">
    <template v-for="(item, index) in items" :key="index">
      <slot :item="item" :index="index" />
    </template>
  </div>
</template>

<style scoped>
.plain-list {
  height: 100%;
  overflow-y: auto;
}
</style>
