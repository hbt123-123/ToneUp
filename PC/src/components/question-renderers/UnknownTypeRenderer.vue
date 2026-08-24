<script setup lang="ts">
import { NButton, NResult } from 'naive-ui'

/** 未知题型降级占位（§6.4.10）：显示 type_code、重试与跳过入口；绝不影响相邻题目 */
defineProps<{ typeCode: string }>()

const emit = defineEmits<{ retry: []; skip: [] }>()
</script>

<template>
  <div class="unknown-renderer tu-card">
    <n-result status="warning" title="暂不支持渲染该题型" :description="`题型代码：${typeCode}。可能是题库新增题型，客户端尚未适配。`">
      <template #footer>
        <n-button type="primary" @click="emit('retry')">重试加载</n-button>
        <n-button quaternary @click="emit('skip')">跳过此题</n-button>
      </template>
    </n-result>
  </div>
</template>

<style scoped>
.unknown-renderer {
  padding: 24px;
}
</style>
