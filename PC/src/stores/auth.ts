import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiLogin, apiMe, apiRegister } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { clearToken, loadToken, saveToken } from '@/api/token'
import { clearAllUserDomainData } from '@/utils/storage'
import type { CurrentUser } from '@/api/generated/schema'

/**
 * 开发环境绕过登录（UI 测试用）：
 * 若 VITE_DEV_BYPASS_AUTH 为 true 且当前为 dev，则直接注入 mock 登录态，
 * 跳过所有路由守卫校验，无需后端运行即可浏览全部页面。
 */
const DEV_BYPASS_AUTH =
  !!import.meta.env.DEV &&
  (import.meta.env.VITE_DEV_BYPASS_AUTH === 'true' ||
    (typeof (import.meta as unknown as { env?: Record<string, unknown> }).env?.VITE_DEV_BYPASS_AUTH ===
      'undefined' &&
      true)) // 默认 dev 环境也启用，方便测试

const MOCK_USER: CurrentUser = {
  id: 1,
  username: 'dev-user',
  role: 'admin',
}

/**
 * auth store（§2.4）：令牌入 localStorage；登出/切账号清理全部用户域缓存。
 * 会话恢复：启动时用本地令牌调 GET /api/auth/me 校验（FR-AUTH-03）。
 */
export const useAuthStore = defineStore('auth', () => {
  // 开发模式：直接 mock 一个 token 并写入本地，让 http 层也带 Authorization，避免 401 触发跳转
  const storedToken = loadToken()
  const initialToken = DEV_BYPASS_AUTH ? storedToken ?? 'dev-bypass-token' : storedToken
  const token = ref<string | null>(initialToken)
  const user = ref<CurrentUser | null>(DEV_BYPASS_AUTH ? MOCK_USER : null)

  const isLoggedIn = computed(() =>
    DEV_BYPASS_AUTH ? true : !!token.value && !!user.value,
  )
  const isAdmin = computed(() =>
    DEV_BYPASS_AUTH ? MOCK_USER.role === 'admin' : user.value?.role === 'admin',
  )
  const userId = computed(() =>
    DEV_BYPASS_AUTH ? MOCK_USER.id : user.value?.id ?? -1,
  )

  async function login(username: string, password: string): Promise<void> {
    const result = await apiLogin(username, password)
    if (!result?.access_token) throw new Error('登录响应缺少访问令牌')
    token.value = result.access_token
    saveToken(result.access_token)
    // 登录后立即拉取用户信息，失败则回滚会话
    try {
      user.value = await apiMe()
    } catch (err) {
      logoutLocally()
      throw err
    }
  }

  async function register(username: string, password: string): Promise<void> {
    await apiRegister(username, password)
    // 注册成功直接登录，减少一步操作
    await login(username, password)
  }

  /** 用本地令牌恢复会话；仅鉴权失败（401）清场，网络类错误保留令牌待联网重试 */
  async function restoreSession(): Promise<boolean> {
    // dev bypass：直接以 mock 用户视为"已恢复"，不访问后端
    if (DEV_BYPASS_AUTH) {
      user.value = MOCK_USER
      return true
    }
    if (!token.value) return false
    try {
      user.value = await apiMe()
      return true
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        logoutLocally()
      }
      return false
    }
  }

  function logoutLocally(): void {
    token.value = DEV_BYPASS_AUTH ? 'dev-bypass-token' : null
    user.value = DEV_BYPASS_AUTH ? MOCK_USER : null
    if (!DEV_BYPASS_AUTH) clearToken()
    clearAllUserDomainData()
    resetDomainStores()
  }

  function logout(): void {
    logoutLocally()
  }

  /** 清各业务 store 内存态（懒加载避免模块环）：换账号后不得命中上一用户的缓存/残留 */
  function resetDomainStores(): void {
    void Promise.all([
      import('@/stores/practice'),
      import('@/stores/wrongbook'),
      import('@/stores/review'),
    ])
      .then(([practiceMod, wrongbookMod, reviewMod]) => {
        practiceMod.usePracticeStore().resetSession()
        wrongbookMod.useWrongBookStore().reset()
        reviewMod.useReviewStore().reset()
      })
      .catch((err: unknown) => {
        // chunk 加载失败也不能让残留缓存跨账号泄漏：降级为整页刷新（带节流防循环）
        console.error('resetDomainStores failed, reloading', err)
        try {
          const last = Number(sessionStorage.getItem('toneup:chunk-reload-at') ?? 0)
          if (Date.now() - last < 10_000) return
          sessionStorage.setItem('toneup:chunk-reload-at', String(Date.now()))
        } catch {
          /* sessionStorage 不可用时直接刷新 */
        }
        window.location.reload()
      })
  }

  return {
    token,
    user,
    isLoggedIn,
    isAdmin,
    userId,
    login,
    register,
    restoreSession,
    logout,
  }
})
