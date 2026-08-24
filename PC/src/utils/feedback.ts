import { computed, ref } from 'vue'
import { createDiscreteApi, darkTheme, type ConfigProviderProps } from 'naive-ui'

/**
 * 脱离组件上下文的全局反馈通道（http 层、store 内可直接调用）。
 * 主题跟随 ui store 的暗色模式；toast 统一右上角（§10.5）。
 */

const isDark = ref(false)

export function setGlobalFeedbackTheme(dark: boolean): void {
  isDark.value = dark
}

const configProviderProps = computed<ConfigProviderProps>(() => ({
  theme: isDark.value ? darkTheme : null,
}))

const { message, dialog } = createDiscreteApi(
  ['message', 'dialog'],
  { configProviderProps },
)

export { message as appMessage, dialog as appDialog }
