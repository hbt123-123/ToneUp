import type MarkdownIt from 'markdown-it'

/**
 * 富文本渲染管线（需求文档第 7 章，顺序固定）：
 *   原始字符串 → markdown-it(html:false) → KaTeX($/$$) → DOMPurify 白名单 → 注入 DOM
 *
 * 约束：
 * - 输入永远视为不可信文本；markdown-it 关闭 html 选项杜绝内联 HTML 直通。
 * - KaTeX 语法错误：该片段回退原样文本（throwOnError=false + errorColor）。
 * - markdown 渲染异常：整体回退为 HTML 转义后的纯文本。
 * - sanitize 后为空：显示"内容渲染异常"占位。
 * - 所有失败都不抛出阻塞切题。
 */

const ALLOWED_TAGS = [
  // 文档白名单 §7.2
  'p', 'br', 'strong', 'em', 'code', 'pre',
  'ul', 'ol', 'li',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'span', 'div',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'sup', 'sub',
  // 题图：仅允许指向本服务 /api/images/ 端点（见下方 normalizeImages）
  'img',
  // KaTeX 生成的 MathML/SVG 节点
  'math', 'semantics', 'annotation', 'mrow', 'mi', 'mn', 'mo', 'ms', 'mtext',
  'msup', 'msub', 'msubsup', 'mfrac', 'msqrt', 'mroot', 'mstyle',
  'munder', 'mover', 'munderover', 'mpadded', 'mphantom', 'mspace',
  'mtable', 'mtr', 'mtd', 'mlabeledtr', 'mmultiscripts', 'mprescripts', 'none',
  'svg', 'path', 'line',
]

const ALLOWED_ATTR = [
  'class', 'style', 'aria-hidden', 'role', 'encoding', 'mathvariant', 'display',
  'xmlns', 'width', 'height', 'viewBox', 'd', 'preserveAspectRatio', 'x', 'y',
  'x1', 'x2', 'y1', 'y2', 'transform', 'fill', 'stroke', 'stroke-width',
  'src', 'alt', 'loading',
]

/** 题目图片只允许来自图片端点；其余一律剥离（§7.2 约束） */
function normalizeImages(holder: HTMLElement): void {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api'
  holder.querySelectorAll('img').forEach((img) => {
    const src = img.getAttribute('src') ?? ''
    try {
      const url = new URL(src, window.location.origin)
      const pathOk = url.pathname.startsWith(`${base}/images/`)
      if (!pathOk || url.origin !== window.location.origin) {
        img.remove()
        return
      }
      img.setAttribute('loading', 'lazy')
    } catch {
      img.remove()
    }
  })
}

/** KaTeX 生成节点：class 含 katex 前缀，或属于 MathML/SVG 标签集合 */
const KATEX_STYLE_TAGS = new Set([
  'math', 'semantics', 'annotation', 'mrow', 'mi', 'mn', 'mo', 'ms', 'mtext',
  'msup', 'msub', 'msubsup', 'mfrac', 'msqrt', 'mroot', 'mstyle',
  'munder', 'mover', 'munderover', 'mpadded', 'mphantom', 'mspace',
  'mtable', 'mtr', 'mtd', 'mlabeledtr', 'mmultiscripts', 'svg', 'path', 'line',
])

/** 危险样式值：KaTeX 输出不会包含这些模式，命中即整条剥离（纵深防御） */
const DANGEROUS_STYLE_RE = /url\(|position\s*:|z-index\s*:/i

/**
 * style 属性加固（§7.2）：仅 KaTeX 生成的节点保留 inline style，
 * 其余标签一律剥离，杜绝 position:fixed 遮罩 / z-index 覆盖 / background:url() 外链探测。
 */
function hardenStyles(root: HTMLElement): void {
  root.querySelectorAll<HTMLElement | SVGElement>('*').forEach((el) => {
    if (!el.hasAttribute('style')) return
    const cls = el.getAttribute('class') ?? ''
    const isKatex = cls.includes('katex') || KATEX_STYLE_TAGS.has(el.tagName.toLowerCase())
    if (!isKatex || DANGEROUS_STYLE_RE.test(el.getAttribute('style') ?? '')) {
      el.removeAttribute('style')
    }
  })
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

interface RichTextPipeline {
  md: MarkdownIt
  renderMath: (el: HTMLElement) => void
  sanitize: (html: string) => string
}

let pipelinePromise: Promise<RichTextPipeline> | null = null

/** 按需加载三个渲染库：独立 chunk，首屏不携带（§12.1 性能预算） */
function loadPipeline(): Promise<RichTextPipeline> {
  if (!pipelinePromise) {
    pipelinePromise = Promise.all([
      import('markdown-it'),
      import('katex'),
      import('katex/contrib/auto-render'),
      import('dompurify'),
      // KaTeX 样式随独立 chunk 注入，不进首屏（§12.1）
      import('katex/dist/katex.min.css'),
    ]).then(([mdMod, , autoRenderMod, dpMod]) => {
      const md = mdMod.default({ html: false, linkify: false, breaks: false })
      const renderMath = (el: HTMLElement): void => {
        autoRenderMod.default(el, {
          delimiters: [
            { left: '$$', right: '$$', display: true },
            { left: '$', right: '$', display: false },
          ],
          throwOnError: false,
          errorColor: '#d03050',
          ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
        })
      }
      const dompurify = dpMod.default
      const sanitize = (html: string): string =>
        dompurify.sanitize(html, {
          ALLOWED_TAGS,
          ALLOWED_ATTR,
          KEEP_CONTENT: true,
          RETURN_DOM_FRAGMENT: false,
        })
      return { md, renderMath, sanitize }
    })
  }
  return pipelinePromise
}

/** 同步降级：整段按转义纯文本输出 */
function fallbackEscaped(raw: string): string {
  return `<p>${escapeHtml(raw).replace(/\n/g, '<br>')}</p>`
}

export async function renderRichText(raw: string | null | undefined): Promise<string> {
  if (!raw) return ''
  try {
    const pipeline = await loadPipeline()
    let html: string
    try {
      html = pipeline.md.render(raw)
    } catch {
      return fallbackEscaped(raw)
    }
    const holder = document.createElement('div')
    holder.innerHTML = html
    try {
      pipeline.renderMath(holder)
    } catch {
      /* KaTeX 整体失败时保留 markdown 结果，片段级错误已由 throwOnError=false 兜底 */
    }
    normalizeImages(holder)
    const sanitizedHtml = pipeline.sanitize(holder.innerHTML)
    const cleanHolder = document.createElement('div')
    cleanHolder.innerHTML = sanitizedHtml
    hardenStyles(cleanHolder)
    const safe = cleanHolder.innerHTML
    if (!safe.replace(/<br\s*\/?>|&nbsp;|\s/g, '')) {
      return '<p class="tu-rich-error">内容渲染异常</p>'
    }
    return safe
  } catch {
    return fallbackEscaped(raw)
  }
}
