import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** 视口断点（§4.2）：宽松三栏 / 标准 / 紧凑（侧栏折叠+抽屉）/ 窄屏保底 */
export type LayoutMode = 'loose' | 'standard' | 'compact' | 'narrow'

/** M-382：SSR 环境读不到视口时的兜底宽度（服务端渲染恒为 loose，客户端挂载后由 onResize 纠正） */
const SSR_FALLBACK_WIDTH = 1920

let widthRef: Ref<number> | null = null

/**
 * M-382：width 原在模块 import 时一次性初始化——import 时机过早可能拿到过期视口，
 * 且模块仅求值一次。改为首次调用 useLayoutMode 时惰性读取当前视口；
 * 非浏览器环境仍回退 SSR_FALLBACK_WIDTH。
 */
function getWidth(): Ref<number> {
  if (widthRef === null) {
    widthRef = ref(typeof window !== 'undefined' ? window.innerWidth : SSR_FALLBACK_WIDTH)
  }
  return widthRef
}

let listening = false
let listenerCount = 0

/** M-384：返回监听器是否确实已挂载，供调用方决定是否计数（非浏览器环境不挂载也不计数） */
function ensureListener(): boolean {
  if (typeof window === 'undefined') return false
  if (listening) return true
  window.addEventListener('resize', onResize, { passive: true })
  listening = true
  return true
}

function onResize(): void {
  // M-383：与 ensureListener 的非浏览器守卫保持一致，SSR/测试环境不再触碰 window
  if (typeof window === 'undefined') return
  getWidth().value = window.innerWidth
}

export function useLayoutMode() {
  onMounted(() => {
    // M-384：仅当监听器确实可用（新挂载或已挂载）时才计数，避免计数虚增后永远移除不掉
    if (ensureListener()) listenerCount++
    onResize()
  })
  onBeforeUnmount(() => {
    // M-384：对称递减并防负数；归零且监听器在挂时才移除
    if (listenerCount > 0) listenerCount--
    if (listenerCount <= 0 && listening) {
      window.removeEventListener('resize', onResize)
      listening = false
    }
  })

  const width = getWidth()

  const mode = computed<LayoutMode>(() => {
    const w = width.value
    if (w >= 1600) return 'loose'
    if (w >= 1280) return 'standard'
    if (w >= 1024) return 'compact'
    return 'narrow'
  })

  const isCompactOrNarrower = computed(() => mode.value === 'compact' || mode.value === 'narrow')

  return { mode, width, isCompactOrNarrower }
}