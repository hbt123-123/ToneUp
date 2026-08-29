#!/usr/bin/env node
/**
 * 从 questionTypes.ts 常量生成 Markdown 文档。
 * 用法: node scripts/gen-type-docs.mjs
 */

import { writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

// 手动定义题型数据（与 questionTypes.ts 保持一致）
const QUESTION_TYPES = [
  { typeCode: 'SINGLE', label: '单选题', subject: 'math' },
  { typeCode: 'FILL_BLANK', label: '填空题', subject: 'math' },
  { typeCode: 'SOLUTION', label: '解答题', subject: 'math' },
  { typeCode: 'CLOZE', label: '完形填空', subject: 'english' },
  { typeCode: 'READING', label: '阅读理解', subject: 'english' },
  { typeCode: 'ORDERING', label: '排序题', subject: 'english' },
  { typeCode: 'TRANSLATION', label: '翻译', subject: 'english' },
  { typeCode: 'ESSAY', label: '作文', subject: 'english' },
  { typeCode: 'MULTI', label: '多选题', subject: 'reserved' },
  { typeCode: 'JUDGE', label: '判断题', subject: 'reserved' },
]

const SUBJECT_LABELS = {
  math: '数学',
  english: '英语',
  reserved: '保留',
}

const md = `# 题型定义表

> 此文件由 \`scripts/gen-type-docs.mjs\` 自动生成，请勿手动编辑。
> 数据来源：\`src/constants/questionTypes.ts\`

| typeCode | 名称 | 学科 | 说明 |
| :-- | :-- | :-- | :-- |
${QUESTION_TYPES.map(t =>
  `| \`${t.typeCode}\` | ${t.label} | ${SUBJECT_LABELS[t.subject]} | ${t.subject === 'reserved' ? '保留题型，当前无数据' : '已启用'} |`
).join('\n')}

## 统计

- 总计：${QUESTION_TYPES.length} 种题型
- 数学：${QUESTION_TYPES.filter(t => t.subject === 'math').length} 种
- 英语：${QUESTION_TYPES.filter(t => t.subject === 'english').length} 种
- 保留：${QUESTION_TYPES.filter(t => t.subject === 'reserved').length} 种
`

const outputPath = resolve(__dirname, '..', 'docs', 'question-types.md')
writeFileSync(outputPath, md, 'utf-8')
console.log(`✅ 已生成: ${outputPath}`)
