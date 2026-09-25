import { computed, ref } from 'vue'
import {
  createDiscreteApi,
  darkTheme,
  type ConfigProviderProps,
  type MessageProviderProps,
} from 'naive-ui'

/**
 * 脱离组件上下文的全局反馈通道（http 层、store 内可直接调用）。
 * 主题跟随 ui store 的暗色模式；toast 统一右上角（§10.5）。
 */

const isDark = ref(false)

export function setGlobalFeedbackTheme(dark: boolean): void {
  isDark.value = dark
}

// M-420：themeOverrides/locale 由 App.vue 的 <n-config-provider> 各自维护，此处不重复定义；
// 离散弹层仅依赖主题明暗与 message 位置，如要求主色等完全一致应提取共享 overrides 模块
const configProviderProps = computed<ConfigProviderProps>(() => ({
  theme: isDark.value ? darkTheme : null,
}))

// M-420：补 messageProviderProps，与 App.vue 的 <n-message-provider placement="top-right"> 对齐；
// 不传时离散 message 落在 naive 默认 top，违背文档「toast 统一右上角（§10.5）」
const messageProviderProps = computed<MessageProviderProps>(() => ({
  placement: 'top-right',
}))

const { message, dialog } = createDiscreteApi(
  ['message', 'dialog'],
  { configProviderProps, messageProviderProps },
)

export { message as appMessage, dialog as appDialog }