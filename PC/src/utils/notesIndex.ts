/**
 * 笔记本地索引（FR-NOTE-01）：
 * 契约仅提供按题读写笔记端点（GET/PUT /api/questions/{id}/notes），
 * 无列表查询能力；因此本地维护"有笔记的题"索引用于列表页呈现，
 * 正文以服务端为准，进入编辑时再拉取完整内容。
 */

export interface NoteIndexEntry {
  bankId: string
  questionId: number
  updatedAt: number
  snippet: string
}

const KEY = (userId: number | string): string => `toneup:notes-index:${userId}`

// M-503：持久化条目完整校验——原仅查 questionId，缺 bankId/updatedAt/snippet 的
// 损坏或旧版数据会以残缺形态混入索引，进而污染列表页
function isValidEntry(e: unknown): e is NoteIndexEntry {
  if (!e || typeof e !== 'object') return false
  const rec = e as Partial<NoteIndexEntry>
  return (
    typeof rec.bankId === 'string' &&
    rec.bankId !== '' &&
    typeof rec.questionId === 'number' &&
    Number.isFinite(rec.questionId) &&
    typeof rec.updatedAt === 'number' &&
    Number.isFinite(rec.updatedAt) &&
    typeof rec.snippet === 'string'
  )
}

function load(userId: number | string): NoteIndexEntry[] {
  try {
    const raw = localStorage.getItem(KEY(userId))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isValidEntry) : []
  } catch {
    return []
  }
}

function save(userId: number | string, entries: NoteIndexEntry[]): void {
  try {
    localStorage.setItem(KEY(userId), JSON.stringify(entries))
  } catch (err) {
    // M-504：配额满/隐私模式等写失败不可完全静默——本地索引将与服务端失同步，
    // 至少输出 warn 便于排查（维持 void 契约，不向调用方抛出）
    console.warn('[notesIndex] 笔记索引写入 localStorage 失败', err)
  }
}

export function upsertNoteIndex(
  userId: number | string,
  entry: { bankId: string; questionId: number; noteText: string },
): void {
  const all = load(userId).filter((e) => !(e.bankId === entry.bankId && e.questionId === entry.questionId))
  if (entry.noteText.trim() !== '') {
    all.unshift({
      bankId: entry.bankId,
      questionId: entry.questionId,
      updatedAt: Date.now(),
      snippet: entry.noteText.replace(/\s+/g, ' ').slice(0, 80),
    })
  }
  save(userId, all)
}

export function listNoteIndex(userId: number | string): NoteIndexEntry[] {
  return load(userId).sort((a, b) => b.updatedAt - a.updatedAt)
}