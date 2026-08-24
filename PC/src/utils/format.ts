export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatRelative(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return String(iso)
  const diff = now - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 30 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return formatDateTime(iso).slice(0, 10)
}

export function formatPercent(ratio: number | null | undefined, digits = 0): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '—'
  const pct = ratio <= 1 ? ratio * 100 : ratio
  return `${pct.toFixed(digits)}%`
}

export function truncateText(text: string, maxLen: number): string {
  const clean = text.replace(/[#*`$\\]|\\[a-zA-Z]+/g, '').trim()
  if (clean.length <= maxLen) return clean
  return `${clean.slice(0, maxLen)}…`
}

export const TYPE_CODE_LABELS: Record<string, string> = {
  SINGLE: '单选题',
  MULTI: '多选题',
  JUDGE: '判断题',
  FILL_BLANK: '填空题',
  SOLUTION: '解答题',
  CLOZE: '完形填空',
  ORDERING: '排序题',
  READING: '阅读理解',
  TRANSLATION: '翻译题',
  ESSAY: '作文题',
}

export function typeCodeLabel(code: string | undefined | null): string {
  if (!code) return '未知题型'
  return TYPE_CODE_LABELS[code] ?? code
}
