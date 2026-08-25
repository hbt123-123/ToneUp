import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 键盘快捷键（需求文档第 9 章）。
 * 守卫规则：
 * - 目标为 input/textarea/contenteditable/Naive UI 输入组件聚焦时不劫持任何按键；
 * - 携带 Ctrl/Alt/Meta 的组合键一律放行浏览器；
 * - Space 长按 ≥600ms 触发标记，按下期间阻止默认滚动。
 */

export interface ShortcutHandlers {
  /** A~F 选项选择（SINGLE 直选；MULTI 累加；JUDGE 用 A/B） */
  onLetter?: (letter: string) => void
  /** Enter：idle/editing 确认提交；submitted 下一题 */
  onConfirmOrNext?: () => void
  onPrev?: () => void
  onNext?: () => void
  /** Space 长按标记 */
  onLongPressMark?: () => void
  /** ? 打开快捷键提示浮层 */
  onHelp?: () => void
  /** Esc 关闭浮层/抽屉/确认框 */
  onEscape?: () => void
}

const LONG_PRESS_MS = 600

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return target.isContentEditable || target.getAttribute('contenteditable') === 'true'
}

/** Tab 聚焦后按 Space 应触发元素默认激活（按钮/开关/下拉等），不劫持 */
function isActivatable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  if (tag === 'BUTTON' || tag === 'A' || tag === 'SUMMARY') return true
  const role = target.getAttribute('role')
  return !!role && ACTIVATABLE_ROLES.includes(role)
}

const ACTIVATABLE_ROLES = [
  'button',
  'switch',
  'checkbox',
  'radio',
  'option',
  'menuitem',
  'menuitemradio',
  'tab',
  'link',
]

/** Naive UI 弹层（对话框/模态）打开时不劫持按键，避免 Enter/Space 穿透到底层页面 */
function hasOpenOverlay(): boolean {
  return document.querySelector('.n-modal, .n-dialog') !== null
}

export function useKeyboardShortcuts(handlers: ShortcutHandlers, enabled: () => boolean = () => true): void {
  let spaceTimer: ReturnType<typeof setTimeout> | null = null

  function clearSpaceTimer(): void {
    if (spaceTimer !== null) {
      clearTimeout(spaceTimer)
      spaceTimer = null
    }
  }

  function onKeyDown(e: KeyboardEvent): void {
    if (!enabled()) return
    if (e.ctrlKey || e.altKey || e.metaKey) return
    const key = e.key

    if (key === 'Escape') {
      handlers.onEscape?.()
      return
    }
    // 弹层打开时放行所有按键（Escape 已在上方处理，仍可关闭浮层）
    if (hasOpenOverlay()) return
    if (isEditable(e.target)) return

    switch (true) {
      case /^[a-fA-F]$/.test(key):
        handlers.onLetter?.(key.toUpperCase())
        break
      case key === 'Enter':
        e.preventDefault()
        handlers.onConfirmOrNext?.()
        break
      case key === 'ArrowLeft':
        e.preventDefault()
        handlers.onPrev?.()
        break
      case key === 'ArrowRight':
        e.preventDefault()
        handlers.onNext?.()
        break
      case key === ' ': {
        // 长按计时仅在非交互元素上生效；交互元素的 Space 默认激活行为放行
        if (isActivatable(e.target)) break
        e.preventDefault()
        if (!e.repeat && spaceTimer === null) {
          spaceTimer = setTimeout(() => {
            spaceTimer = null
            handlers.onLongPressMark?.()
          }, LONG_PRESS_MS)
        }
        break
      }
      case key === '?':
        e.preventDefault()
        handlers.onHelp?.()
        break
      default:
        break
    }
  }

  function onKeyUp(e: KeyboardEvent): void {
    if (e.key === ' ') {
      clearSpaceTimer()
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('keyup', onKeyUp)
    window.addEventListener('blur', clearSpaceTimer)
  })

  onBeforeUnmount(() => {
    clearSpaceTimer()
    window.removeEventListener('keydown', onKeyDown)
    window.removeEventListener('keyup', onKeyUp)
    window.removeEventListener('blur', clearSpaceTimer)
  })
}

/** 快捷键提示数据（精简条 + 完整面板共用） */
export const SHORTCUT_HINTS: { keys: string; desc: string }[] = [
  { keys: 'A B C D', desc: '选择对应选项（判断题用 A/B）' },
  { keys: 'Enter', desc: '确认提交 / 已答时进入下一题' },
  { keys: '← →', desc: '上一题 / 下一题' },
  { keys: 'Space 长按', desc: '标记/取消当前题疑问（≥600ms）' },
  { keys: '?', desc: '打开快捷键提示' },
  { keys: 'Esc', desc: '关闭浮层与抽屉' },
]
