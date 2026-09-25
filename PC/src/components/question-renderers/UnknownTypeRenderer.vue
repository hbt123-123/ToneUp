<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NResult } from 'naive-ui'

/** 未知题型降级占位（§6.4.10）：显示 type_code、重试与跳过入口；绝不影响相邻题目 */
defineProps<{ typeCode: string }>()

const emit = defineEmits<{ retry: []; skip: [] }>()

// M-483：in-flight 防重入——在途时两个按钮 loading/禁用，双击不再重复触发 retry/skip
// （父级 handler 无重入保护）；父级成功切换后组件随题目卸载，操作失败 1.5s 后自动恢复可重试
const inFlight = ref(false)
let releaseTimer: ReturnType<typeof setTimeout> | null = null

function fire(action: 'retry' | 'skip'): void {
  if (inFlight.value) return
  inFlight.value = true
  if (releaseTimer !== null) clearTimeout(releaseTimer)
  releaseTimer = setTimeout(() => {
    inFlight.value = false
  }, 1500)
  // defineEmits 的事件调用为逐事件重载，不接受联合字面量参数，需按分支调用
  if (action === 'retry') emit('retry')
  else emit('skip')
}
</script>

<template>
  <div class="unknown-renderer tu-card">
    <n-result status="warning" title="暂不支持渲染该题型" :description="`题型代码：${typeCode}。可能是题库新增题型，客户端尚未适配。`">
      <template #footer>
        <!-- M-483：loading/禁用防重入 -->
        <n-button type="primary" :loading="inFlight" @click="fire('retry')">重试加载</n-button>
        <n-button quaternary :disabled="inFlight" @click="fire('skip')">跳过此题</n-button>
      </template>
    </n-result>
  </div>
</template>

<style scoped>
.unknown-renderer {
  padding: 24px;
}
</style>