import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ColorTheme = '' | 'morandi-green' | 'warm-beige' | 'starry-purple' | 'mint-fresh' | 'sakura-pink' | 'deep-ocean'

/** 选中后会自动切换 dark 的深色主题集合 */
const AUTO_DARK_THEMES = new Set<ColorTheme>(['starry-purple', 'deep-ocean'])

const UI_KEY = 'toneup:ui'

interface UiPersist {
  themeMode: ThemeMode
  colorTheme: ColorTheme
  sidebarCollapsed: boolean
  motionEnabled: boolean
  shortcutBarVisible: boolean
  analysisSplitRatio: number
  customBackgroundUrl?: string
}

function loadPersist(): Partial<UiPersist> {
  try {
    const raw = localStorage.getItem(UI_KEY)
    return raw ? (JSON.parse(raw) as Partial<UiPersist>) : {}
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
  /** 自定义背景图片 URL（§10.4），空串表示未设置 */
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

  function applyCustomBackground(url: string): void {
    if (url) {
      document.documentElement.style.setProperty('--tu-gradient-hero', `url(${url})`)
    } else {
      document.documentElement.style.removeProperty('--tu-gradient-hero')
    }
  }

  function setCustomBackgroundUrl(url: string): void {
    customBackgroundUrl.value = url
    applyCustomBackground(url)
  }

  function clearCustomBackground(): void {
    setCustomBackgroundUrl('')
  }

  if (customBackgroundUrl.value) {
    applyCustomBackground(customBackgroundUrl.value)
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
        customBackgroundUrl: customBackgroundUrl.value,
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
