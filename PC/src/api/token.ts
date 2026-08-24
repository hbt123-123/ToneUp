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
