import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.resolve(__dirname, '..')

function write(name, content) {
  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, name), content.trimStart(), 'utf8')
}

function repeatSentence(sentence, count) {
  return Array.from({ length: count }, (_, i) => `${sentence} 编号${i + 1}用于保持文本长度和语义稳定 `).join('')
}

function english(sentence, count) {
  return Array.from({ length: count }, (_, i) => `${sentence} Sequence ${i + 1} keeps this fixture deterministic. `).join('')
}

write('chunk-markdown-headings.md', `
# Intro
${repeatSentence('Intro 段落说明 RAGForge 多策略分块的验收背景，强调标题路径和章节边界', 6)}

## Setup
${repeatSentence('Setup 内容介绍知识库配置、清洗链路、默认分块策略和回退链', 6)}

### Install
${repeatSentence('Install 小节描述本地依赖、数据库迁移、Playwright headed 模式和 trace 归档', 6)}

## Usage
${repeatSentence('Usage 内容覆盖文档上传、异步处理、chunk 查询和索引写入', 6)}

### Debug Console
${repeatSentence('Debug Console 小节强调检索调试台、评测实验室和结果展示', 6)}

# Reference
${repeatSentence('Reference 章节记录验收断言、策略名称、参数 JSON 和 heading_path 字段', 6)}
`)

write('chunk-no-headings-plain.txt', repeatSentence('这是一段没有标题也没有明确标点结构的纯散文文本用于触发默认标题策略失败后进入递归或固定窗口回退链', 34))

write('chunk-mixed-table.md', `
开头段落说明表格策略需要把 Markdown 表格整体保存为一个 chunk，不能把任意数据行拆散。

| 指标 | MARKDOWN_HEADING | FIXED_WINDOW | TABLE_AWARE |
| --- | --- | --- | --- |
| chunkCount | 6 | 9 | 3 |
| avgLength | 420 | 256 | 510 |
| coverage | 0.70 | 0.65 | 0.98 |
| tableRows | 5 | 2 | 5 |

结尾段落补充说明周围普通文本可以独立分块，但表格本体应该保持完整。
${repeatSentence('表格之后的说明文字用于让混合文档长度达到真实处理规模', 6)}
`)

write('chunk-extra-long-paragraph.txt', repeatSentence('超长单段文本没有换行没有段落分隔需要递归策略按照 chunkSize 继续切分并保留前后 overlap', 96))

write('chunk-short-fragments.txt', Array.from({ length: 50 }, (_, i) => `短段${i + 1} 包含少量内容用于验证连续碎片会被策略合并而不是每段一个 chunk。`).join('\n\n'))

const codeBlock = (name) => [
  '```python',
  `def ${name}():`,
  '    total = 0',
  ...Array.from({ length: 28 }, (_, i) => `    total += ${i + 1}`),
  '    return total',
  '```',
].join('\n')

write('chunk-code-blocks.md', `
# Code Blocks
下面的文档包含多个 Python 代码块，验收要求 chunk 边界不能落在 fence 中间。

## Loader
${codeBlock('main')}

## Transformer
${codeBlock('transform')}

## Writer
${codeBlock('write_result')}
`)

write('chunk-semantic-topic-shift.txt', `
${repeatSentence('人工智能平台正在推动企业知识库检索升级，向量模型、RAG 应用、推理服务和工程自动化形成新的技术栈', 12)}

${repeatSentence('心脏病筛查强调临床路径、影像检查、药物管理、康复计划和长期风险控制，医疗团队需要稳定随访', 12)}

${repeatSentence('教育部发布课程改革方案，学校围绕素养目标、课堂评价、教师培训和数字教材建设推进教学质量提升', 12)}
`)

write('chunk-deeply-nested-list.md', `
# Nested List
- 一级项目 A
  - 二级项目 A1
    - 三级项目 A1a
      - 四级项目 A1a-i
        - 五级项目 A1a-i-alpha ${repeatSentence('嵌套列表内容用于验证 Markdown 结构保持', 4)}
- 一级项目 B
  - 二级项目 B1
    - 三级项目 B1a
      - 四级项目 B1a-i
        - 五级项目 B1a-i-alpha ${repeatSentence('继续补足五级嵌套列表的正文长度', 4)}
`)

write('chunk-bilingual-cn-en.txt', Array.from({ length: 12 }, (_, i) => (
  i % 2 === 0
    ? `中文段落 ${i + 1} 说明多语言知识库中的检索、清洗、分块和索引链路，要求策略在中英文交替时保持稳定。`
    : `English paragraph ${i + 1} describes multilingual retrieval, chunking, indexing, and evaluation stability in RAGForge.`
)).join('\n\n'))

write('chunk-large-table-only.md', [
  '| col1 | col2 | col3 | col4 | col5 | col6 | col7 | col8 |',
  '| --- | --- | --- | --- | --- | --- | --- | --- |',
  ...Array.from({ length: 30 }, (_, row) =>
    `| r${row + 1}c1 | r${row + 1}c2 | r${row + 1}c3 | r${row + 1}c4 | r${row + 1}c5 | r${row + 1}c6 | r${row + 1}c7 | r${row + 1}c8 |`),
].join('\n'))
