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

function load(userId: number | string): NoteIndexEntry[] {
  try {
    const raw = localStorage.getItem(KEY(userId))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed)
      ? parsed.filter(
          (e): e is NoteIndexEntry =>
            !!e && typeof e === 'object' && typeof (e as NoteIndexEntry).questionId === 'number',
        )
      : []
  } catch {
    return []
  }
}

function save(userId: number | string, entries: NoteIndexEntry[]): void {
  try {
    localStorage.setItem(KEY(userId), JSON.stringify(entries))
  } catch {
    /* ignore */
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
