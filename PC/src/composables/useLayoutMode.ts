import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/** 视口断点（§4.2）：宽松三栏 / 标准 / 紧凑（侧栏折叠+抽屉）/ 窄屏保底 */
export type LayoutMode = 'loose' | 'standard' | 'compact' | 'narrow'

const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1920)
let listening = false
let listenerCount = 0

function ensureListener(): void {
  if (listening || typeof window === 'undefined') return
  window.addEventListener('resize', onResize, { passive: true })
  listening = true
}

function onResize(): void {
  width.value = window.innerWidth
}

export function useLayoutMode() {
  onMounted(() => {
    ensureListener()
    listenerCount++
    onResize()
  })
  onBeforeUnmount(() => {
    listenerCount--
    if (listenerCount <= 0 && listening) {
      window.removeEventListener('resize', onResize)
      listening = false
    }
  })

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
