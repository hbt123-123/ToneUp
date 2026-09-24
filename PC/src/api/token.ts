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

export function saveToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch {
    /* 存储不可用时忽略，会话仅存活于内存 */
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* ignore */
  }
}

export function saveRedirectPath(path: string): void {
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
    return p
  } catch {
    return null
  }
}
