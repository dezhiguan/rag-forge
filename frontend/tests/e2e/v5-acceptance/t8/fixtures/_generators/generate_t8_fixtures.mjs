import fs from 'node:fs'
import path from 'node:path'

const fixturesDir = path.resolve(new URL('.', import.meta.url).pathname)

const NBSP = '\u00A0'
const ZWSP = '\u200B'
const IDEO_SPACE = '\u3000'

// NOTE on page separators:
// L1NormalizeCleaner strips ASCII control chars (form-feed \f included) BEFORE L2 runs,
// so \f cannot be used as a page boundary in plain-text fixtures. L2DenoiseCleaner.splitPages
// also recognises standalone "第 N 页" / "Page N" lines as page boundaries, and those survive L1.
// All multi-page fixtures therefore use standalone "第 N 页" lines as page separators.
const pageBreak = (n) => `第 ${n} 页`

// Valid Chinese ID numbers (correct ISO 7064 / GB 11643 checksum digit).
// L3PiiMaskCleaner.maskIdCardIfValid only masks IDs whose checksum is valid.
const ID_PRIMARY = '440103199001011236'
const ID_SECONDARY = '52010219881212345X'

const cleanUnicode = [
  `ASCII baseline ${NBSP}NBSP${ZWSP}ZWSP${IDEO_SPACE}IDEO${' '}${NBSP}and fullwidth chars`,
  `数字转换：１２３`,
  `兼容字符测试：㈠`,
  `全角英文：ｆｕｌｌｗｉｄｔｈ`,
  `中文标点：， 。 ！`,
  '零宽测试：before' + ZWSP + 'after',
].join('\n')

function buildNoisyPages(pageCount = 25) {
  const header1 = '广州市某科技公司'
  const header2 = '财务部绝密档案'
  const watermark = '保密 内部资料'
  const footer1 = '请勿外传'
  const footer2 = '仅供内部参考'
  const blocks = []
  for (let p = 1; p <= pageCount; p++) {
    const block = [
      header1,
      header2,
      watermark,
      `第 ${p} 节正文：这是用于清洗验证的业务知识文本，编号 ${p}-A，应当保留。`,
      `段落正文第 ${p} 行内容，承载真实知识表达，不属于页眉页脚噪声。`,
      footer1,
      footer2,
    ].join('\n')
    blocks.push(block)
    blocks.push(pageBreak(p))
  }
  return blocks.join('\n')
}

const cleanPii = [
  '手机号样例: 13812345678',
  '第二个手机号: 13987654321',
  `身份证号: ${ID_PRIMARY}`,
  `另一个身份证号: ${ID_SECONDARY}`,
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
  // Two identical pages composed entirely of repeated header/footer/watermark + PII.
  // After L1+L2 strip the repeated edge lines and L3 masks PII, almost no content remains.
  const header = '广州市某科技公司'
  const watermark = '保密 内部资料'
  const footer1 = '请勿外传'
  const footer2 = '仅供内部参考'
  const lines = []
  for (let p = 1; p <= 2; p++) {
    lines.push(header, watermark, footer1, footer2, pageBreak(p))
  }
  // Pure PII lines (will be masked, not removed, but carry no real knowledge content)
  lines.push('13812345678', 'alice@example.com', ID_PRIMARY, '6222 0202 0001 2345')
  return lines.join('\n')
}

const cleanToc = [
  '目录',
  '第一章 引言 ............ 3',
  '第二章 架构 ............ 12',
  '第三章 实现 ............ 25',
  pageBreak(1),
  '保密 内部资料',
  '正文第一段，验证 TOC 与水印在首页之后被正确清洗。',
  pageBreak(2),
  '保密 内部资料',
  '正文第二段内容，继续撰写以形成有效正文段落。',
  pageBreak(3),
  '保密 内部资料',
  '正文第三段内容，继续撰写以形成有效正文段落。',
  pageBreak(4),
  '保密 内部资料',
  '正文第四段内容，继续撰写以形成有效正文段落。',
  pageBreak(5),
].join('\n')

const mixedEverything = [buildNoisyPages(8), cleanPii, cleanUnicode].join('\n\n-----\n\n')

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

console.log('Generated T8 fixtures:', Object.keys(fixtures).join(', '))
