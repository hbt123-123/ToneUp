<script setup lang="ts">
import { NButton, NResult } from 'naive-ui'
</script>

<template>
  <div class="not-found">
    <n-result status="404" title="页面不存在" description="你要找的页面去别的星球了。">
      <template #footer>
        <n-button type="primary" @click="$router.replace('/')">返回首页</n-button>
        <n-button quaternary @click="$router.replace('/catalog')">去题库刷题</n-button>
      </template>
    </n-result>
  </div>
</template>

<style scoped>
.not-found {
  min-height: calc(100vh - var(--tu-topbar-height));
  display: grid;
  place-items: center;
}
</style>
