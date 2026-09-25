import { loadToken } from './token'

/** 统一 API 错误：携带 HTTP 状态码与 request_id（§10.5 开发环境透传） */
export class ApiError extends Error {
  readonly status: number
  readonly requestId?: string
  /** 网络层失败（断网、超时、DNS 等），区别于服务端业务错误 */
  readonly networkError: boolean
  /** M-367：请求被主动取消（外部中止）的标记；取消同样以 ApiError 抛出，统一调用方捕获路径 */
  readonly cancelled: boolean

  constructor(
    message: string,
    status: number,
    requestId?: string,
    networkError = false,
    cancelled = false,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = requestId
    this.networkError = networkError
    this.cancelled = cancelled
  }
}

/** 按契约状态码归类为中文可读提示（FR-AUTH-04 / §7.6） */
export function humanizeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.networkError) return '网络异常，请检查网络连接后重试'
    switch (err.status) {
      case 400:
        return err.message || '请求参数有误'
      case 401:
        // 登录/注册失败携带服务端 message（如账号密码错误），其余 401 用统一提示
        return err.message && err.message !== '登录已失效' ? err.message : '登录已失效，请重新登录'
      case 403:
        return '权限不足，无法执行该操作'
      case 404:
        return err.message || '请求的资源不存在'
      case 429:
        return '操作过于频繁，请稍后再试'
      default:
        if (err.status >= 500) return `服务器开小差了${err.requestId ? `（request_id: ${err.requestId}）` : ''}`
        return err.message || `请求失败（${err.status}）`
    }
  }
  if (err instanceof Error) return err.message
  return '未知错误'
}

export interface ApiEnvelope<T> {
  success: boolean
  data: T
  message: string
  request_id?: string
}

type UnauthorizedHandler = () => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/** 由 main.ts 注入：401 时清除会话并跳转登录（避免 http 层直接依赖 store/router 造成环） */
export function setUnauthorizedHandler(fn: UnauthorizedHandler | null): void {
  unauthorizedHandler = fn
}

export interface RequestOptions {
  method?: string
  /** 查询参数；值类型运行时过滤 */
  query?: Record<string, unknown>
  /** JSON 序列化请求体 */
  json?: unknown
  /** 原始请求体（FormData 等），绕过 JSON 序列化，Content-Type 由浏览器生成 */
  rawBody?: BodyInit
  extraHeaders?: Record<string, string>
  timeoutMs?: number
  signal?: AbortSignal
}

// M-360：导出 API 基址解析，供 imageUrl 等场景复用，消除多处重复实现
export function apiBase(): string {
  return (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api'
}

function buildUrl(path: string, query: RequestOptions['query']): string {
  // H-115：统一以 apiBase 为前缀（全库调用方均传相对 path，如 '/auth/login'）。
  // 原先 `path.startsWith('/api')` 的启发式会把 VITE_API_BASE 指向其它 origin 的配置
  // 静默降级为同源请求，且 '/apixxx' 这类路径也会误判。
  const url = `${apiBase()}${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') params.set(k, String(v))
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

async function parseBody(response: Response): Promise<{ payload: unknown; requestId?: string }> {
  const text = await response.text().catch(() => '')
  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text) as unknown
    } catch {
      payload = text
    }
  }
  const envelope = payload as Partial<ApiEnvelope<unknown>> | null
  return { payload, requestId: envelope?.request_id ?? response.headers.get('x-request-id') ?? undefined }
}

async function execute<T>(path: string, options: RequestOptions): Promise<T> {
  const { query, json, rawBody, extraHeaders, timeoutMs = 15000, signal } = options
  // 外层 signal 在进入前已中止：addEventListener 不会再触发，直接拒绝。
  // M-367：取消统一以 ApiError（cancelled=true）抛出，调用方无需再区分 DOMException
  if (signal?.aborted) {
    throw new ApiError('请求已取消', 0, undefined, false, true)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new DOMException('请求超时', 'TimeoutError')), timeoutMs)
  const onOuterAbort = (): void => controller.abort(signal?.reason)
  signal?.addEventListener('abort', onOuterAbort, { once: true })
  // M-364：关闭「已中止检查 → 注册监听」之间的竞态窗口——若 abort 恰在两者之间触发，
  // 事件已错过、监听器永远不会执行，请求将一直挂到超时；此处补一次最终检查并立即
  // 中止内部控制器（fetch 随即以 AbortError 拒绝，进入下方统一归一化）
  if (signal?.aborted) controller.abort()

  try {
    let headers: Record<string, string>
    let body: BodyInit | undefined
    try {
      // M-368：令牌读取与 JSON 序列化失败（如循环引用）不属于网络异常，
      // 就地转为语义明确的 ApiError，避免落入外层 catch 被误报为「网络异常」
      const token = loadToken()
      headers = {
        Accept: 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...extraHeaders,
      }
      if (json !== undefined) {
        headers['Content-Type'] = 'application/json'
        body = JSON.stringify(json)
      } else if (rawBody !== undefined) {
        body = rawBody
      }
    } catch (err) {
      throw new ApiError(
        `请求构造失败：${err instanceof Error ? err.message : String(err)}`,
        0,
        undefined,
        false,
      )
    }

    const response = await fetch(buildUrl(path, query), {
      method: options.method ?? (body !== undefined ? 'POST' : 'GET'),
      headers,
      body,
      signal: controller.signal,
    })

    const { payload, requestId } = await parseBody(response)

    if (response.status === 401) {
      // 登录/注册失败后端同样返回 401：不触发全局登出跳转，透传服务端 message。
      // M-365：改为精确路径匹配，防止 /auth/login-callback 等无关路由误命中抑制逻辑
      const isAuthEndpoint = path === '/auth/login' || path === '/auth/register'
      if (!isAuthEndpoint) unauthorizedHandler?.()
      const envMessage = isAuthEndpoint
        ? ((payload as Partial<ApiEnvelope<unknown>> | null)?.message ?? null)
        : null
      throw new ApiError(
        typeof envMessage === 'string' && envMessage ? envMessage : '登录已失效',
        401,
        requestId,
      )
    }

    const envelope = payload as Partial<ApiEnvelope<T>> | null
    // 契约统一外层 {"success","data","message","request_id"}；裸响应直接透传。
    // M-366：信封识别收紧——success 必须为严格 boolean 且对象必须含 data 键，
    // 避免业务载荷恰含 message/request_id 等特征键时被误判为信封而丢失真实数据
    const looksEnvelope =
      !!envelope &&
      typeof envelope === 'object' &&
      typeof envelope.success === 'boolean' &&
      'data' in envelope

    if (!response.ok || (envelope && envelope.success === false)) {
      const message =
        (envelope && typeof envelope.message === 'string' && envelope.message) ||
        `请求失败（HTTP ${response.status}）`
      throw new ApiError(message, response.status, requestId)
    }

    return looksEnvelope ? ((envelope as ApiEnvelope<T>).data as T) : (payload as T)
  } catch (err) {
    if (err instanceof ApiError) throw err
    if (err instanceof DOMException && err.name === 'TimeoutError') {
      throw new ApiError('请求超时，请重试', 0, undefined, true)
    }
    // M-367：取消（外部中止）也归一化为 ApiError（cancelled=true），
    // 与其它失败走同一错误通道；networkError 保持 false，避免触发离线重试/网络提示
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new ApiError('请求已取消', 0, undefined, false, true)
    }
    throw new ApiError('网络异常，请检查网络连接后重试', 0, undefined, true)
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onOuterAbort)
  }
}

export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return execute<T>(path, options)
}

/** multipart 场景（AI 图片上传）：不设置 Content-Type，由浏览器补 boundary */
export function requestForm<T>(path: string, form: FormData, options: RequestOptions = {}): Promise<T> {
  return execute<T>(path, { ...options, method: options.method ?? 'POST', rawBody: form })
}