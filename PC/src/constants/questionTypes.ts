/**
 * 题型常量 — 单一事实源（FR-TYPE-01）
 * 所有题型定义集中在此文件，禁止在其他位置硬编码。
 */

export interface QuestionTypeItem {
  typeCode: string
  label: string
  subject: 'math' | 'english' | 'reserved'
}

export const QUESTION_TYPES: readonly QuestionTypeItem[] = [
  { typeCode: 'SINGLE', label: '单选题', subject: 'math' },
  { typeCode: 'FILL_BLANK', label: '填空题', subject: 'math' },
  { typeCode: 'SOLUTION', label: '解答题', subject: 'math' },
  { typeCode: 'CLOZE', label: '完形填空', subject: 'english' },
  { typeCode: 'READING', label: '阅读理解', subject: 'english' },
  { typeCode: 'ORDERING', label: '排序题', subject: 'english' },
  { typeCode: 'TRANSLATION', label: '翻译', subject: 'english' },
  { typeCode: 'ESSAY', label: '作文', subject: 'english' },
  // MULTI/JUDGE 保留但当前无数据
  { typeCode: 'MULTI', label: '多选题', subject: 'reserved' },
  { typeCode: 'JUDGE', label: '判断题', subject: 'reserved' },
] as const

/** 按 typeCode 快速查找 */
export const QUESTION_TYPE_MAP = new Map(
  QUESTION_TYPES.map(t => [t.typeCode, t])
)
