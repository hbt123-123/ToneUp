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
// H-DEV-BYPASS：开发绕过模式下不跳登录，避免无后端时 API 401 把人踢出去
const DEV_BYPASS_AUTH = import.meta.env.DEV && import.meta.env.VITE_DEV_BYPASS_AUTH === 'true'
setUnauthorizedHandler(() => {
  if (DEV_BYPASS_AUTH) {
    console.warn('[dev-bypass] 捕获到 401，开发模式下忽略，不跳登录')
    return
  }
  const currentRoute = router.currentRoute.value
  // M-386：已在登录页时仅清理会话，不再记录/跳转——登录页触发的 401
  //（如携带过期令牌的 /auth/me 会话恢复失败）此前会因 currentPath !== '/' 判断不成立，
  // 反复产生 redirect=/login 的跳转与历史记录污染
  if (currentRoute.name === 'login') {
    void auth.logout()
    return
  }
  const currentPath = currentRoute.fullPath
  if (currentPath && currentPath !== '/login') saveRedirectPath(currentPath)
  void auth.logout()
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

// 4. 挂载前同步应用持久化的颜色主题（避免闪烁）
// M-387：启动期恢复必须应用与 stores/ui.ts 相同的旧主题键迁移，
// 否则旧键（deep-ocean 等）会把 data-theme 设成不存在的主题导致样式回退。
// ui.ts 未导出 THEME_KEY_MIGRATIONS，此处本地维护一份，需与其保持同步
const THEME_KEY_MIGRATIONS: Record<string, string> = {
  'deep-ocean': 'sky-blue',
  'morandi-green': 'firefly',
}
try {
  const raw = localStorage.getItem('toneup:ui')
  if (raw) {
    const saved = JSON.parse(raw) as { colorTheme?: string }
    if (saved.colorTheme) {
      document.documentElement.dataset.theme = THEME_KEY_MIGRATIONS[saved.colorTheme] ?? saved.colorTheme
    }
  }
} catch {
  /* ignore */
}

// 5. 挂载
app.mount('#app')

export default app