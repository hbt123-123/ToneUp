<script setup lang="ts">
import RichText from '@/components/common/RichText.vue'

/**
 * 选项行（§10.3 悬停上浮+光晕；对错状态用图标+文字+颜色三通道表达）。
 */
withDefaults(
  defineProps<{
    label: string
    text: string
    selected?: boolean
    state?: 'none' | 'correct' | 'wrong'
    disabled?: boolean
  }>(),
  { selected: false, state: 'none', disabled: false },
)

const emit = defineEmits<{ select: [] }>()
</script>

<template>
  <button
    type="button"
    class="option-row opt"
    :class="{ selected, correct: state === 'correct', wrong: state === 'wrong', disabled }"
    :disabled="disabled"
    :aria-pressed="selected"
    @click="emit('select')"
  >
    <span class="badge" aria-hidden="true">{{ label }}</span>
    <span class="content"><RichText :content="text" /></span>
    <span v-if="state === 'correct'" class="mark ok" aria-hidden="true">✓</span>
    <span v-else-if="state === 'wrong'" class="mark bad" aria-hidden="true">✕</span>
    <span v-if="state === 'correct'" class="visually-hidden">（正确答案）</span>
    <span v-else-if="state === 'wrong'" class="visually-hidden">（你的错选）</span>
  </button>
</template>

<style scoped>
.opt {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  min-height: 48px;
  padding: 10px 14px;
  margin: 0;
  text-align: left;
  background: var(--tu-surface);
  color: var(--tu-text);
  border: 1px solid var(--tu-border);
  border-radius: var(--tu-radius-card);
  font: inherit;
  line-height: var(--tu-line-height);
}

.opt.selected:not(.correct):not(.wrong) {
  border-color: var(--tu-accent);
  /* M-472：硬编码色改用设计令牌，跟随主题/accent 变体（tokens.css 按主题重定义） */
  box-shadow: 0 0 0 2px var(--tu-accent-hover-surface);
}

.opt.correct {
  border-color: var(--tu-success);
  /* M-472：背景色改用 success 令牌（亮/暗模式各自定义） */
  background: var(--tu-success-surface);
}

.opt.wrong {
  border-color: var(--tu-error);
  /* M-472：背景色改用 error 令牌（亮/暗模式各自定义） */
  background: var(--tu-error-surface);
}

/* M-473：抵消 base.css 全局 .option-row:hover/:active（上浮 + 光晕阴影），
   禁用行不再跟随悬停动效；scoped 属性选择器保证特异性高于全局规则 */
.opt.disabled {
  cursor: default;
}

.opt.disabled:hover,
.opt.disabled:active {
  transform: none;
  box-shadow: none;
}

.badge {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--tu-primary);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
}

.opt.selected:not(.correct):not(.wrong) .badge {
  background: var(--tu-accent);
}

.opt.correct .badge {
  background: var(--tu-success);
}

.opt.wrong .badge {
  background: var(--tu-error);
}

.content {
  flex: 1;
  min-width: 0;
}

.mark {
  flex: none;
  font-weight: 700;
  align-self: center;
  font-size: 18px;
}

.ok {
  color: var(--tu-success);
}

.bad {
  color: var(--tu-error);
}
</style>
