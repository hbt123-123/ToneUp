import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiLogin, apiMe, apiRegister } from '@/api/endpoints'
import { clearToken, loadToken, saveToken } from '@/api/token'
import { clearAllUserDomainData } from '@/utils/storage'
import type { CurrentUser } from '@/api/generated/schema'

/**
 * auth store（§2.4）：令牌入 localStorage；登出/切账号清理全部用户域缓存。
 * 会话恢复：启动时用本地令牌调 GET /api/auth/me 校验（FR-AUTH-03）。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(loadToken())
  const user = ref<CurrentUser | null>(null)

  const isLoggedIn = computed(() => !!token.value && !!user.value)
  const isAdmin = computed(() => user.value?.role === 'admin')
  const userId = computed(() => user.value?.id ?? -1)

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

  /** 用本地令牌恢复会话；失败返回 false 并清场 */
  async function restoreSession(): Promise<boolean> {
    if (!token.value) return false
    try {
      user.value = await apiMe()
      return true
    } catch {
      logoutLocally()
      return false
    }
  }

  function logoutLocally(): void {
    token.value = null
    user.value = null
    clearToken()
    clearAllUserDomainData()
  }

  function logout(): void {
    logoutLocally()
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
