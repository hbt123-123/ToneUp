/**
 * 令牌存取（H-116 权衡记录）：
 * localStorage 中的令牌可被本源下任意脚本读取——XSS 漏洞或被投毒的第三方依赖
 * 都能窃取。彻底缓解需后端改为 HttpOnly Cookie（配合 CSRF 防护），属架构级改动，
 * 本轮不实施；已知残余风险由 CSP 与依赖审计部分缓解。
 */
const TOKEN_KEY = 'toneup:token'
const REDIRECT_KEY = 'toneup:redirect'

export function loadToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function saveToken(token: string): boolean {
  try {
    localStorage.setItem(TOKEN_KEY, token)
    return true
  } catch {
    // M-369：存储不可用（Safari 隐私模式/禁用存储/配额满）不再静默吞掉，
    // 向调用方报告持久化失败，由调用方决定降级行为（会话仅内存存活）
    return false
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* ignore */
  }
}

/** M-370：防开放重定向——仅接受以单个 “/” 开头、且不以 “//” 或 “/\” 开头的站内相对路径 */
function isSafeRedirectTarget(target: string): boolean {
  return target.startsWith('/') && !target.startsWith('//') && !target.startsWith('/\\')
}

export function saveRedirectPath(path: string): void {
  // M-370：写入侧同样过滤，避免非法目标进入 sessionStorage
  if (!isSafeRedirectTarget(path)) return
  try {
    sessionStorage.setItem(REDIRECT_KEY, path)
  } catch {
    /* ignore */
  }
}

export function takeRedirectPath(): string | null {
  try {
    const p = sessionStorage.getItem(REDIRECT_KEY)
    if (p) sessionStorage.removeItem(REDIRECT_KEY)
    // M-370：存储值可能被篡改，直接作为导航目标存在 open redirect 风险；
    // 非法值回退 null，由调用方（LoginView.safeRedirect）落到默认首页
    return p && isSafeRedirectTarget(p) ? p : null
  } catch {
    return null
  }
}