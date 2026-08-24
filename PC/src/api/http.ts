import { loadToken } from './token'

/** 统一 API 错误：携带 HTTP 状态码与 request_id（§10.5 开发环境透传） */
export class ApiError extends Error {
  readonly status: number
  readonly requestId?: string
  /** 网络层失败（断网、超时、DNS 等），区别于服务端业务错误 */
  readonly networkError: boolean

  constructor(message: string, status: number, requestId?: string, networkError = false) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = requestId
    this.networkError = networkError
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
        return '登录已失效，请重新登录'
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

function apiBase(): string {
  return (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api'
}

function buildUrl(path: string, query: RequestOptions['query']): string {
  const url = path.startsWith('/api') ? path : `${apiBase()}${path}`
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
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new DOMException('请求超时', 'TimeoutError')), timeoutMs)
  const onOuterAbort = (): void => controller.abort(signal?.reason)
  signal?.addEventListener('abort', onOuterAbort, { once: true })

  try {
    const token = loadToken()
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...extraHeaders,
    }
    let body: BodyInit | undefined
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(json)
    } else if (rawBody !== undefined) {
      body = rawBody
    }

    const response = await fetch(buildUrl(path, query), {
      method: options.method ?? (body !== undefined ? 'POST' : 'GET'),
      headers,
      body,
      signal: controller.signal,
    })

    const { payload, requestId } = await parseBody(response)

    if (response.status === 401) {
      unauthorizedHandler?.()
      throw new ApiError('登录已失效', 401, requestId)
    }

    const envelope = payload as Partial<ApiEnvelope<T>> | null
    // 契约统一外层 {"success","data","message","request_id"}；裸响应直接透传。
    // 判定条件：存在对象型 data 字段且伴随信封特征键，避免把业务 DTO 误判为信封。
    const looksEnvelope =
      !!envelope &&
      typeof envelope === 'object' &&
      'data' in envelope &&
      envelope.data !== null &&
      typeof envelope.data === 'object' &&
      !Array.isArray(envelope.data) &&
      ('success' in envelope || 'message' in envelope || 'request_id' in envelope)

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
    if (err instanceof DOMException && err.name === 'AbortError') throw err
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
