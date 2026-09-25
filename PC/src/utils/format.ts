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

/**
 * 比率 → 百分比文本。
 * M-421/M-501：契约明确为 0-1 比率（accuracy_rate=0.85 渲染 "85%"），不再猜测单位——
 * 原 ratio<=1 启发式会把"1%"渲染成"100%"、把 1.5 当作已是百分比直通，同一刻度两种语义；
 * 超界值 clamp 到 [0,1]，NaN/±Infinity 返回占位符。
 * 调用点（StatsView.vue 传 accuracy_rate/correct_rate）均符合 0-1 契约，无需调整。
 */
export function formatPercent(ratio: number | null | undefined, digits = 0): string {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return '—'
  const clamped = Math.min(1, Math.max(0, ratio))
  // 防御 digits 越界（toFixed 对负数/超 100 会抛 RangeError）
  const safeDigits = Number.isFinite(digits) ? Math.max(0, Math.min(20, Math.floor(digits))) : 0
  return `${(clamped * 100).toFixed(safeDigits)}%`
}

export function truncateText(text: string, maxLen: number): string {
  // M-422/M-502：移除不可达分支 \[a-zA-Z]+ —— 首分支 [#* $ \] 已包含反斜杠，
  // 交替从左到右，反斜杠永远被首分支消费，第二分支永不命中
  const clean = text.replace(/[#*`$\\]/g, '').trim()
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