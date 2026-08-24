<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/components/layout/AppLayout.vue'

/** 登录页不套主布局，其余页面走侧栏+顶栏骨架 */
const route = useRoute()
const bare = computed(() => route.name === 'login')
</script>

<template>
  <router-view v-if="bare" />
  <AppLayout v-else />
</template>
