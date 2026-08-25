import { getCurrentScope, onScopeDispose, shallowRef, ref, type Ref } from 'vue'

/**
 * 通用轮询：起始间隔 2s，逐步退避至上限；until 返回 true 或 scope 销毁即停止。
 * 提供 manualRefresh（FR-AI-03 手动刷新）。
 */
export interface PollingOptions<T> {
  intervalStartMs?: number
  intervalMaxMs?: number
  factor?: number
  /** 首次立即执行 */
  immediate?: boolean
  /** 返回 true 时停止轮询 */
  until?: (data: T) => boolean
}

export interface PollingHandle<T> {
  data: Ref<T | null>
  error: Ref<string | null>
  loading: Ref<boolean>
  start: () => void
  stop: () => void
  manualRefresh: () => Promise<void>
}

export function usePolling<T>(
  task: () => Promise<T>,
  options: PollingOptions<T> = {},
): PollingHandle<T> {
  const {
    intervalStartMs = 2000,
    intervalMaxMs = 10000,
    factor = 1.6,
    immediate = false,
    until,
  } = options

  const data = shallowRef<T | null>(null)
  const error = ref<string | null>(null)
  const loading = ref(false)

  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = true
  let delay = intervalStartMs
  /** 代次 token：start() 自增；await 恢复后代次不一致的结果/排程一律丢弃 */
  let generation = 0

  async function tick(): Promise<void> {
    const gen = generation
    if (stopped) return
    loading.value = true
    try {
      const result = await task()
      if (gen !== generation) return // 期间发生 restart，过期结果不写回共享 ref
      data.value = result
      error.value = null
      if (!until?.(result)) {
        delay = Math.min(intervalMaxMs, Math.round(delay * factor))
        timer = setTimeout(tick, delay)
      } else {
        stopped = true
      }
    } catch (err) {
      if (gen !== generation) return
      error.value = err instanceof Error ? err.message : String(err)
      // 出错仍继续退避重试，除非已停止
      if (!stopped) {
        delay = Math.min(intervalMaxMs, Math.round(delay * factor))
        timer = setTimeout(tick, delay)
      }
    } finally {
      if (gen === generation) loading.value = false
    }
  }

  function start(): void {
    generation++
    stop()
    stopped = false
    delay = intervalStartMs
    if (immediate) void tick()
    else timer = setTimeout(tick, delay)
  }

  function stop(): void {
    stopped = true
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  async function manualRefresh(): Promise<void> {
    const gen = generation
    loading.value = true
    try {
      const result = await task()
      if (gen !== generation) return // restart 后过期结果不写回
      data.value = result
      error.value = null
      if (until?.(result)) stop()
    } catch (err) {
      if (gen !== generation) return
      error.value = err instanceof Error ? err.message : String(err)
    } finally {
      if (gen === generation) loading.value = false
    }
  }

  if (getCurrentScope()) {
    onScopeDispose(stop)
  }

  return { data, error, loading, start, stop, manualRefresh }
}
