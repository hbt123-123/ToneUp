import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { validateRegistry } from '@/components/question-renderers/registry'
import { setUnauthorizedHandler } from '@/api/http'
import { saveRedirectPath } from '@/api/token'
import { setGlobalFeedbackTheme } from '@/utils/feedback'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { usePracticeStore, bindWrongRecorder } from '@/stores/practice'
import { useWrongBookStore } from '@/stores/wrongbook'
import './styles/base.css'

/**
 * 启动流程（§6.1 / §2.2）：
 * 1. 题型渲染注册表校验（先于路由挂载；开发环境失败阻断启动）
 * 2. Pinia / Router 装配
 * 3. 401 全局拦截与主题反馈通道接线
 * 4. 挂载
 */

// 1. 注册表校验：键集合必须与后端契约枚举完全一致
validateRegistry()

// 2. 应用装配
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// 3. 跨层接线
const ui = useUiStore(pinia)
const auth = useAuthStore(pinia)
setGlobalFeedbackTheme(ui.isDark)
ui.$subscribe(() => setGlobalFeedbackTheme(ui.isDark))

// 401 全局处理（FR-AUTH-04）：清除会话、记录恢复路径、跳登录
setUnauthorizedHandler(() => {
  const currentPath = router.currentRoute.value.fullPath
  if (currentPath && currentPath !== '/login') saveRedirectPath(currentPath)
  auth.logout()
  void router.push({ name: 'login', query: currentPath === '/' ? {} : { redirect: currentPath } })
})

// practice ↔ auth/wrongbook 接线（避免 store 循环依赖，用注入方式）
const wrongbook = useWrongBookStore(pinia)
usePracticeStore(pinia).bindUserId(() => auth.userId)
wrongbook.bindUser(() => auth.userId)
bindWrongRecorder((entry) => {
  wrongbook.recordWrong(entry)
})

app.use(router)

// 4. 挂载前同步应用主题（避免闪烁）；dev 环境无条件强制昔涟主题便于 UI 调试
try {
  if (import.meta.env.DEV) {
    document.documentElement.dataset.theme = 'sakura-pink'
  } else {
    const raw = localStorage.getItem('toneup:ui')
    if (raw) {
      const saved = JSON.parse(raw) as { colorTheme?: string }
      if (saved.colorTheme) document.documentElement.dataset.theme = saved.colorTheme
    }
  }
} catch {
  /* ignore */
}

// 5. 挂载
app.mount('#app')

export default app
