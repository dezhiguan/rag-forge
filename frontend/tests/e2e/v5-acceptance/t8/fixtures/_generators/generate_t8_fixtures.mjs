import fs from 'node:fs'
import path from 'node:path'

const fixturesDir = path.resolve(new URL('.', import.meta.url).pathname)

const NBSP = '\u00A0'
const ZWSP = '\u200B'
const IDEO_SPACE = '\u3000'

const cleanUnicode = [
  `ASCII baseline ${NBSP}NBSP${ZWSP}ZWSP${IDEO_SPACE}IDEO${' '}${NBSP}and fullwidth chars`,
  `数字转换：１２３`,
  `兼容字符测试：㈠`,
  `全角英文：ｆｕｌｌｗｉｌｄｔｈ`,
  `中文标点：， 。 ！`,
  '零宽测试：before' + ZWSP + 'after',
].join('\n')

function buildNoisyPages(pageCount = 25) {
  const pages = []
  const body = '这是正文主体内容，用于模拟包含业务文本的文档。仅用于清洗验证，不含敏感信息。'
  const repeatedLine1Prefix = '广州市某科技公司 第 %d 页'
  const repeatedLine2 = '请勿外传'
  const repeatedLine3 = '仅供内部参考'
  const watermark = '保密 内部资料'

  for (let p = 1; p <= pageCount; p++) {
    const lines = [
      repeatedLine1Prefix.replace('%d', String(p)),
      repeatedLine2,
      repeatedLine3,
      watermark,
      `${body}（第${String(p).padStart(2, '0')}页）`,
      `段落正文第 ${p} 行内容，包含重复页脚与噪声。`,
      `附注：第 ${p} 页`,
      repeatedLine2,
      repeatedLine3,
    ]
    pages.push(lines.join('\n'))
  }
  return pages.join('\f')
}

const cleanPii = [
  '手机号样例: 13812345678',
  '第二个手机号: 13987654321',
  '身份证号: 440103199001011234',
  '另一个身份证号: 520102198812123456',
  '电子邮件: alice@example.com',
  '备选邮箱: bob.brown@ragforge.example.net',
  '银行卡: 6222 0202 0001 2345',
  '备用银行卡: 6250 1234 5678 9012',
  '地址字段（故意不命中）: 深圳市南山区科技南路 100 号',
].join('\n')

const cleanPure200 =
  '本基线文件用于验证清洗对正常文本的影响。它包含约两百字，覆盖中文叙述、数字和少量英文术语。' +
  '文档仅包含有价值的知识表达，不包含页眉页脚、隐私数据以及可识别噪声。' +
  '解析后应保持核心语义完整，不受清洗策略影响。' +
  '该文本会作为长度对照，帮助确认清洗后的分块结果符合预期。' +
  '继续补充一些内容，确保总字符量到达接近两百字：' +
  '检索、向量化、分词、分块与重排共同支撑了问答链路。'

function buildEmptyAfterStrip() {
  const lines = [
    '广州市某科技公司 第 1 页',
    '请勿外传',
    '保密 内部资料',
    '13812345678',
    'alice@example.com',
    '440103199001011234',
    '6222 0202 0001 2345',
    '广州市某科技公司 第 1 页',
    '保密 内部资料',
    '仅供内部参考',
  ]
  return lines.join('\n')
}

const cleanToc = [
  '目录',
  '第一章 引言 ............ 3',
  '第二章 架构 ............ 12',
  '第三章 实现 ............ 25',
  '保密 内部资料',
  '\f',
  '正文第一段...正文第一部分，验证 TOC 与水印在第一张页面的清洗。',
  '保密 内部资料',
  '\f',
  '保密 内部资料',
  '\f',
  '正文第二段...',
  '保密 内部资料',
].join('\n')

function cleanNoisyContent() {
  return buildNoisyPages(8)
}

const mixedEverything = [cleanNoisyContent(), cleanPii, cleanUnicode].join('\n\n-----\n\n')

function repeatToSize(content, targetBytes) {
  const chunks = []
  let total = 0
  while (total < targetBytes) {
    chunks.push(content)
    total = Buffer.byteLength(chunks.join('\n'), 'utf8')
  }
  return chunks.join('\n')
}

const performanceFixture = repeatToSize(mixedEverything, 200 * 1024) + '\nEND'

const fixtures = {
  'clean-unicode-zerowidth.txt': cleanUnicode,
  'clean-noisy-header-footer.txt': buildNoisyPages(),
  'clean-pii-zh.txt': cleanPii,
  'clean-toc-watermark.txt': cleanToc,
  'clean-mixed-everything.txt': mixedEverything,
  'clean-pure-content.txt': cleanPure200,
  'clean-empty-after-strip.txt': buildEmptyAfterStrip(),
  'clean-perf-200kb.txt': performanceFixture,
}

for (const [name, content] of Object.entries(fixtures)) {
  fs.writeFileSync(path.resolve(fixturesDir, '..', name), content)
}
