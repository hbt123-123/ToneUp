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

/** 缓存包装结果：避免模板内每次重渲染都新建数组导致 NVirtualList 整段重建 */
const wrappedItems = computed(() => asItemData(props.items))

/**
 * M-439：非虚拟分支的 key 改用项自身标识——对象优先取 id/key 字段，原始值直接用值本身，
 * 避免 :key="index" 使组件身份依赖位置，在重排/插入/删除时复用错位的组件状态；
 * 无稳定标识字段的对象退回 index（与原行为一致）。
 */
function itemKey(item: T, index: number): string | number {
  if (typeof item === 'object' && item !== null) {
    const rec = item as Record<string, unknown>
    const id = rec.id ?? rec.key
    if (typeof id === 'string' || typeof id === 'number') return id
    return index
  }
  return item
}
// vue-tsc 2.2.4 对泛型组件（generic 属性）模板中引用 setup 函数误报 TS6133（模板 :key 处实际已使用），显式读取绕过
void itemKey
</script>

<template>
  <n-virtual-list
    v-if="useVirtual"
    :items="wrappedItems"
    :item-size="itemSize"
    :item-resizable="false"
    style="height: 100%"
  >
    <!-- 用 index 回读原始项：NVirtualList 的 item 是包装对象，
         直接透传会给原始类型列表传 { value: it }（C-16） -->
    <template #default="{ index }: { index: number }">
      <slot :item="props.items[index]" :index="index" />
    </template>
  </n-virtual-list>
  <div v-else class="plain-list">
    <template v-for="(item, index) in items" :key="itemKey(item, index)">
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