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

/** 主题键改名迁移：深海蓝 → 天空蓝（2026-09 改造，旧存的 localStorage 值自动映射） */
const THEME_KEY_MIGRATIONS: Record<string, ColorTheme> = { 'deep-ocean': 'sky-blue', 'morandi-green': 'firefly' }

function loadPersist(): Partial<UiPersist> {
  try {
    const raw = localStorage.getItem(UI_KEY)
    const saved = raw ? (JSON.parse(raw) as Partial<UiPersist>) : {}
    if (saved.colorTheme && saved.colorTheme in THEME_KEY_MIGRATIONS) {
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

export const useUiStore = defineStore('ui', () => {
  const saved = loadPersist()

  const themeMode = ref<ThemeMode>(saved.themeMode ?? 'system')
  const colorTheme = ref<ColorTheme>(saved.colorTheme ?? '')
  const sidebarCollapsed = ref(saved.sidebarCollapsed ?? false)
  const motionEnabled = ref(saved.motionEnabled ?? true)
  const shortcutBarVisible = ref(saved.shortcutBarVisible ?? true)
  /** 解析视图左右分栏比例（FR-ANA-01 记忆位置） */
  const analysisSplitRatio = ref(saved.analysisSplitRatio ?? 0.6)
  /** 当前生效的自定义背景 URL（blob:/data:，不持久化；图片本体存 IndexedDB），空串表示未设置 */
  const customBackgroundUrl = ref(saved.customBackgroundUrl ?? '')

  const systemDark = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false,
  )

  if (typeof window !== 'undefined' && window.matchMedia) {
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

  /** 当前持有的 blob: URL，替换/清除时释放，避免内存泄漏 */
  let activeObjectUrl = ''

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
    void deleteBackground().catch(() => undefined)
  }

  if (customBackgroundUrl.value) {
    // 旧版 base64 数据：先直接应用保证本次可用，再后台迁移进 IndexedDB
    applyCustomBackground(customBackgroundUrl.value)
    void (async () => {
      try {
        const blob = await (await fetch(customBackgroundUrl.value)).blob()
        await saveBackground(blob)
        setCustomBackgroundUrl(URL.createObjectURL(blob))
      } catch {
        /* 迁移失败则本次会话继续用 base64，下次启动重试 */
      }
    })()
  } else if (saved.customBackground) {
    void loadBackground()
      .then((blob) => (blob ? setCustomBackgroundUrl(URL.createObjectURL(blob)) : undefined))
      .catch(() => undefined)
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
      analysisSplitRatio.value = Math.min(0.85, Math.max(0.3, ratio))
    },
  }
})
