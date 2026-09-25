import { defineComponent, h, shallowRef, ref } from 'vue'
import type { Component } from 'vue'
import UnknownTypeRenderer from './UnknownTypeRenderer.vue'
import { CONTRACT_TYPE_CODES } from './types'

/**
 * EC-04 懒加载包装：chunk 加载失败兜底
 * （部署发版后旧标签页引用已失效的 chunk 文件名 → 404，渲染占位而非白屏崩溃）。
 * H-118：不用 defineAsyncComponent——其 onError 里 fail() 之后 retry 闭包即失效，
 * 内置错误态无法可靠重试；改为自管加载状态，重试时重新触发 loader。
 */
function lazyRenderer(loader: () => Promise<Component>): Component {
  return defineComponent({
    name: 'AsyncRenderer',
    setup() {
      const state = ref<'loading' | 'ready' | 'error'>('loading')
      const resolved = shallowRef<Component | null>(null)

      async function load(): Promise<void> {
        state.value = 'loading'
        try {
          resolved.value = await loader()
          state.value = 'ready'
        } catch (error) {
          console.error('[ToneUp] 题型渲染组件加载失败，已降级为占位', error)
          state.value = 'error'
        }
      }

      void load()

      return () => {
        if (state.value === 'ready' && resolved.value) return h(resolved.value)
        if (state.value === 'error') {
          return h(
            'div',
            { class: 'renderer-load-error', style: 'padding:24px;text-align:center;font-size:13px;' },
            [
              h('p', { style: 'margin:0 0 8px;color:var(--tu-error,#d03050);' }, '组件加载失败'),
              h(
                'button',
                { type: 'button', onClick: () => void load(), style: 'padding:4px 14px;cursor:pointer;' },
                '加载失败，点击重试',
              ),
            ],
          )
        }
        return h(
          'div',
          { style: 'padding:24px;text-align:center;font-size:13px;color:var(--tu-text-secondary);' },
          '组件加载中…',
        )
      }
    },
  })
}

/** 单选渲染器：SINGLE 与 READING 共享同一异步实例（别名随 Single，§6.3） */
const singleChoiceRenderer = lazyRenderer(() => import('./SingleChoiceRenderer.vue'))

/**
 * 题型渲染注册表（§6.1）：
 * 键为后端契约 type_code，逐字符一致；工作台通过 resolveRenderer 动态挂载。
 * 除 UnknownTypeRenderer（身份判断用）与 OptionRow（支撑件）外，9 个真实渲染器按需加载。
 */
export const RENDERER_REGISTRY: Record<string, Component> = {
  SINGLE: singleChoiceRenderer,
  READING: singleChoiceRenderer, // 英语阅读单选按单选渲染（§6.3）
  MULTI: lazyRenderer(() => import('./MultiChoiceRenderer.vue')),
  JUDGE: lazyRenderer(() => import('./JudgeRenderer.vue')),
  FILL_BLANK: lazyRenderer(() => import('./FillBlankRenderer.vue')),
  SOLUTION: lazyRenderer(() => import('./SolutionRenderer.vue')),
  CLOZE: lazyRenderer(() => import('./ClozeRenderer.vue')),
  ORDERING: lazyRenderer(() => import('./OrderingRenderer.vue')),
  TRANSLATION: lazyRenderer(() => import('./TranslationRenderer.vue')),
  ESSAY: lazyRenderer(() => import('./EssayRenderer.vue')),
}

export function resolveRenderer(typeCode: string): Component {
  return RENDERER_REGISTRY[typeCode] ?? UnknownTypeRenderer
}

/** 供工作台识别未知题型（渲染分支用） */
export { UnknownTypeRenderer }

/**
 * 启动校验（§6.1）：main.ts 在路由挂载前调用。
 * 开发环境失败阻断启动；生产输出 error 日志并以降级组件兜底。
 */
export function validateRegistry(env: 'development' | 'production' = import.meta.env.MODE === 'production' ? 'production' : 'development'): void {
  const registered = Object.keys(RENDERER_REGISTRY)
  const expected: string[] = [...CONTRACT_TYPE_CODES]

  // M-376：原「重复注册检测」已移除——RENDERER_REGISTRY 为对象字面量，
  // Object.keys() 依 JS 语义恒返回唯一键，且 TS 会对字面量重复键报编译错误，该检测永不触发。
  // 缺失检测：契约枚举中存在但注册表没有
  const missing = expected.filter((code) => !registered.includes(code))

  // 多余键检测：不在契约枚举中的自定义别名一律视为非法
  const unknownKeys = registered.filter((key) => !expected.includes(key))
  const problems: string[] = []
  if (missing.length > 0) problems.push(`缺失注册的题型键：${missing.join(', ')}`)
  if (unknownKeys.length > 0) problems.push(`契约之外的题型键（禁止自定义别名）：${unknownKeys.join(', ')}`)

  if (problems.length > 0) {
    const detail = `题型渲染注册表校验失败 —— ${problems.join('；')}`
    if (env === 'development') {
      throw new Error(detail)
    }
    console.error(`[ToneUp] ${detail}（生产模式：未知题型将以降级组件渲染）`)
  }
}