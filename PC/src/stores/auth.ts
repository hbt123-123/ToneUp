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
// H-122：必须显式开启（.env.local 设 VITE_DEV_BYPASS_AUTH=true）；
// 原先 `typeof env === 'undefined' && true` 死代码使 dev 默认放行全部路由，存在误用风险
const DEV_BYPASS_AUTH = !!import.meta.env.DEV && import.meta.env.VITE_DEV_BYPASS_AUTH === 'true'

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
    // M-369：saveToken 现在报告持久化是否成功；失败仅告警，会话降级为仅内存存活
    if (!saveToken(result.access_token)) {
      console.warn('[auth] 令牌持久化失败（存储不可用），关闭页面后需重新登录')
    }
    // 登录后立即拉取用户信息
    try {
      user.value = await apiMe()
    } catch (err) {
      // M-389：区分「令牌被拒（401）」与「me 请求暂时性失败（网络/5xx）」——
      // 刚获取的令牌不应因暂时性失败被丢弃；仅 401 才清场，其余保留令牌待联网后经 restoreSession 重试
      if (err instanceof ApiError && err.status === 401) await logoutLocally()
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
        await logoutLocally() // M-390：等待清理完成再返回
      }
      return false
    }
  }

  /** M-390：登出改为可等待——清完 localStorage 与各业务 store 后才返回，
   *  避免调用方在域数据清理完成前继续导航而命中上一用户的残留缓存 */
  async function logoutLocally(): Promise<void> {
    token.value = DEV_BYPASS_AUTH ? 'dev-bypass-token' : null
    user.value = DEV_BYPASS_AUTH ? MOCK_USER : null
    if (!DEV_BYPASS_AUTH) clearToken()
    clearAllUserDomainData()
    await resetDomainStores()
  }

  function logout(): Promise<void> {
    return logoutLocally()
  }

  /** 清各业务 store 内存态（懒加载避免模块环）：换账号后不得命中上一用户的缓存/残留 */
  async function resetDomainStores(): Promise<void> {
    try {
      // M-390：动态导入与三个 store 重置全部完成后才 resolve（原先 fire-and-forget）
      const [practiceMod, wrongbookMod, reviewMod] = await Promise.all([
        import('@/stores/practice'),
        import('@/stores/wrongbook'),
        import('@/stores/review'),
      ])
      practiceMod.usePracticeStore().resetSession()
      wrongbookMod.useWrongBookStore().reset()
      reviewMod.useReviewStore().reset()
    } catch (err) {
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
    }
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