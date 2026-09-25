import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { deleteBackground, loadBackground, saveBackground } from '@/utils/backgroundStore'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ColorTheme = '' | 'firefly' | 'warm-beige' | 'starry-purple' | 'mint-fresh' | 'sakura-pink' | 'sky-blue'

/** 选中后会自动切换 dark 的深色主题集合 */
const AUTO_DARK_THEMES = new Set<ColorTheme>(['starry-purple'])

const UI_KEY = 'toneup:ui'

interface UiPersist {
  themeMode: ThemeMode
  colorTheme: ColorTheme
  sidebarCollapsed: boolean
  motionEnabled: boolean
  shortcutBarVisible: boolean
  analysisSplitRatio: number
  /** 是否启用自定义背景（图片本体存 IndexedDB） */
  customBackground?: boolean
  /** 旧版 base64 背景数据：仅兼容读取，启动后自动迁移进 IndexedDB 并清除 */
  customBackgroundUrl?: string
}

/**
 * 主题键改名迁移表：旧键 → 新键（loadPersist 时自动映射旧 localStorage 值）。
 * 主题重命名时在此追加一行即可，如 `'old-key': 'new-key'`。
 */
const THEME_KEY_MIGRATIONS: Record<string, ColorTheme> = { 'deep-ocean': 'sky-blue', 'morandi-green': 'firefly' }

/** M-406/491：分栏比例统一钳制——持久化恢复与 setAnalysisSplitRatio 走同一套校验（非法值回退默认，再夹到 [0.3, 0.85]） */
function clampAnalysisSplitRatio(ratio: number): number {
  const safe = Number.isFinite(ratio) ? ratio : 0.6
  return Math.min(0.85, Math.max(0.3, safe))
}

function loadPersist(): Partial<UiPersist> {
  try {
    const raw = localStorage.getItem(UI_KEY)
    const saved = raw ? (JSON.parse(raw) as Partial<UiPersist>) : {}
    // M-405/490：in 会沿原型链误判（"constructor"/"toString"/"valueOf" 等命中 Object.prototype），
    // 改用 Object.hasOwn 仅匹配自身键，防止把原型属性值当作迁移目标写入主题
    if (saved.colorTheme && Object.hasOwn(THEME_KEY_MIGRATIONS, saved.colorTheme)) {
      saved.colorTheme = THEME_KEY_MIGRATIONS[saved.colorTheme]
    }
    return saved
  } catch {
    return {}
  }
}

function persist(state: UiPersist): void {
  try {
    localStorage.setItem(UI_KEY, JSON.stringify(state))
  } catch {
    /* ignore */
  }
}

/** M-492：matchMedia change 监听器模块级只注册一次，防止 HMR/多次实例化累积监听器 */
let systemDarkListenerBound = false
/** M-492：blob: URL 引用提升到模块级——store 重新实例化时仍持有引用，替换/清除时可正确 revoke，避免泄漏 */
let activeObjectUrl = ''

export const useUiStore = defineStore('ui', () => {
  const saved = loadPersist()

  const themeMode = ref<ThemeMode>(saved.themeMode ?? 'system')
  const colorTheme = ref<ColorTheme>(saved.colorTheme ?? '')
  const sidebarCollapsed = ref(saved.sidebarCollapsed ?? false)
  const motionEnabled = ref(saved.motionEnabled ?? true)
  const shortcutBarVisible = ref(saved.shortcutBarVisible ?? true)
  /** 解析视图左右分栏比例（FR-ANA-01 记忆位置）；M-406/491：恢复时同样钳制，损坏/被篡改的持久值不再直接溢出容器 */
  const analysisSplitRatio = ref(clampAnalysisSplitRatio(saved.analysisSplitRatio ?? 0.6))
  /** 当前生效的自定义背景 URL（blob:/data:，不持久化；图片本体存 IndexedDB），空串表示未设置 */
  const customBackgroundUrl = ref(saved.customBackgroundUrl ?? '')

  const systemDark = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false,
  )

  if (typeof window !== 'undefined' && window.matchMedia && !systemDarkListenerBound) {
    // M-492：模块级守卫防重复注册（store 重复实例化不再叠加监听器）
    systemDarkListenerBound = true
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
      systemDark.value = e.matches
    })
  }

  const isDark = computed(() => (themeMode.value === 'system' ? systemDark.value : themeMode.value === 'dark'))

  function setColorTheme(theme: ColorTheme): void {
    colorTheme.value = theme
    document.documentElement.dataset.theme = theme
    if (AUTO_DARK_THEMES.has(theme)) {
      themeMode.value = 'dark'
    }
  }

  function applyCustomBackground(url: string): void {
    if (url) {
      if (activeObjectUrl && activeObjectUrl !== url) URL.revokeObjectURL(activeObjectUrl)
      activeObjectUrl = url.startsWith('blob:') ? url : ''
      // data URL 含 ";"/"," 等字符，必须加引号才是合法 CSS url()
      // 转义 url 中的双引号和反斜杠，防止 CSS 注入
      const escapedUrl = url.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
      document.documentElement.style.setProperty('--tu-gradient-hero', `url("${escapedUrl}")`)
    } else {
      if (activeObjectUrl) {
        URL.revokeObjectURL(activeObjectUrl)
        activeObjectUrl = ''
      }
      document.documentElement.style.removeProperty('--tu-gradient-hero')
    }
  }

  function setCustomBackgroundUrl(url: string): void {
    customBackgroundUrl.value = url
    applyCustomBackground(url)
  }

  function clearCustomBackground(): void {
    setCustomBackgroundUrl('')
    // M-493：删除是用户显式操作，失败不能完全吞掉——否则 UI 显示已清除、下次启动背景"复活"
    void deleteBackground().catch((err) => console.warn('[ui] 自定义背景删除失败，可能于下次启动重新出现', err))
  }

  if (customBackgroundUrl.value) {
    // 旧版 base64 数据：先直接应用保证本次可用，再后台迁移进 IndexedDB
    applyCustomBackground(customBackgroundUrl.value)
    void (async () => {
      // H-150：迁移是异步的，期间用户可能已清除/更换背景；记录源 URL，
      // await 后若已漂移则丢弃迁移结果（并清掉刚写入的 IndexedDB），防止旧背景复活
      const sourceUrl = customBackgroundUrl.value
      try {
        const blob = await (await fetch(sourceUrl)).blob()
        await saveBackground(blob)
        if (customBackgroundUrl.value !== sourceUrl) {
          void deleteBackground().catch(() => undefined)
          return
        }
        setCustomBackgroundUrl(URL.createObjectURL(blob))
      } catch (err) {
        // M-407/494：迁移失败则本次会话继续用 base64；persist 现会保留 data: URL，
        // "下次启动重试"真正成立（旧实现会被任意偏好变更覆盖丢失）；打日志便于排查损坏数据
        console.warn('[ui] 自定义背景 base64 → IndexedDB 迁移失败，将在下次启动重试', err)
      }
    })()
  } else if (saved.customBackground) {
    void loadBackground()
      .then((blob) => (blob ? setCustomBackgroundUrl(URL.createObjectURL(blob)) : undefined))
      .catch((err) => {
        console.warn('[ui] IndexedDB 自定义背景读取失败，已回退默认背景', err)
      })
  }

  watch(
    [themeMode, colorTheme, sidebarCollapsed, motionEnabled, shortcutBarVisible, analysisSplitRatio, customBackgroundUrl],
    () => {
      persist({
        themeMode: themeMode.value,
        colorTheme: colorTheme.value,
        sidebarCollapsed: sidebarCollapsed.value,
        motionEnabled: motionEnabled.value,
        shortcutBarVisible: shortcutBarVisible.value,
        analysisSplitRatio: analysisSplitRatio.value,
        customBackground: customBackgroundUrl.value !== '',
        // M-407/494：base64 → IndexedDB 迁移完成前（含迁移失败），旧 base64 必须随状态持久化——
        // 否则用户改动任意偏好就会把它覆盖丢失，"下次启动重试"沦为空话；blob: URL 会话失效，不可持久化
        ...(customBackgroundUrl.value.startsWith('data:')
          ? { customBackgroundUrl: customBackgroundUrl.value }
          : {}),
      })
    },
    { deep: true },
  )

  function toggleTheme(): void {
    themeMode.value = isDark.value ? 'light' : 'dark'
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return {
    themeMode,
    colorTheme,
    isDark,
    sidebarCollapsed,
    motionEnabled,
    shortcutBarVisible,
    analysisSplitRatio,
    customBackgroundUrl,
    toggleTheme,
    setColorTheme,
    toggleSidebar,
    setCustomBackgroundUrl,
    clearCustomBackground,
    setAnalysisSplitRatio(ratio: number): void {
      // M-406/491：与持久化恢复路径共用同一 clamp 逻辑
      analysisSplitRatio.value = clampAnalysisSplitRatio(ratio)
    },
  }
})