#!/usr/bin/env node
/**
 * 从 questionTypes.ts 常量生成 Markdown 文档。
 * 用法: node scripts/gen-type-docs.mjs
 *
 * H-114：不再内联题型副本——直接读取 src/constants/questionTypes.ts 解析，
 * 避免源文件更新后文档漂移（单一事实源）。
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

const sourcePath = resolve(__dirname, '..', 'src', 'constants', 'questionTypes.ts')
const source = readFileSync(sourcePath, 'utf-8')

// 匹配 QUESTION_TYPES 数组中的条目：{ typeCode: 'X', label: 'Y', subject: 'Z' }
const entryRe = /\{\s*typeCode:\s*'([^']+)'\s*,\s*label:\s*'([^']+)'\s*,\s*subject:\s*'([^']+)'\s*\}/g
const QUESTION_TYPES = [...source.matchAll(entryRe)].map((m) => ({
  typeCode: m[1],
  label: m[2],
  subject: m[3],
}))

if (QUESTION_TYPES.length === 0) {
  console.error('❌ 未能从 questionTypes.ts 解析出任何题型条目，请检查常量格式')
  process.exit(1)
}

const SUBJECT_LABELS = {
  math: '数学',
  english: '英语',
  reserved: '保留',
}

const subjectOf = (s) => SUBJECT_LABELS[s] ?? s

const md = `# 题型定义表

> 此文件由 \`scripts/gen-type-docs.mjs\` 自动生成，请勿手动编辑。
> 数据来源：\`src/constants/questionTypes.ts\`

| typeCode | 名称 | 学科 | 说明 |
| :-- | :-- | :-- | :-- |
${QUESTION_TYPES.map(t =>
  `| \`${t.typeCode}\` | ${t.label} | ${subjectOf(t.subject)} | ${t.subject === 'reserved' ? '保留题型，当前无数据' : '已启用'} |`
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
