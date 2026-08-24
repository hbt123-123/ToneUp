import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const UI_KEY = 'toneup:ui'

interface UiPersist {
  themeMode: ThemeMode
  sidebarCollapsed: boolean
  motionEnabled: boolean
  shortcutBarVisible: boolean
  analysisSplitRatio: number
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
  const sidebarCollapsed = ref(saved.sidebarCollapsed ?? false)
  const motionEnabled = ref(saved.motionEnabled ?? true)
  const shortcutBarVisible = ref(saved.shortcutBarVisible ?? true)
  /** 解析视图左右分栏比例（FR-ANA-01 记忆位置） */
  const analysisSplitRatio = ref(saved.analysisSplitRatio ?? 0.6)

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

  watch(
    [themeMode, sidebarCollapsed, motionEnabled, shortcutBarVisible, analysisSplitRatio],
    () => {
      persist({
        themeMode: themeMode.value,
        sidebarCollapsed: sidebarCollapsed.value,
        motionEnabled: motionEnabled.value,
        shortcutBarVisible: shortcutBarVisible.value,
        analysisSplitRatio: analysisSplitRatio.value,
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
    isDark,
    sidebarCollapsed,
    motionEnabled,
    shortcutBarVisible,
    analysisSplitRatio,
    toggleTheme,
    toggleSidebar,
    setAnalysisSplitRatio(ratio: number): void {
      analysisSplitRatio.value = Math.min(0.85, Math.max(0.3, ratio))
    },
  }
})
