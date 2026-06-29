/**
 * developer-center.html（开发者中心原型）端到端用例
 * 对应文档：docs/developer-center-test-plan-V1.md（编号 D*-* 一一对应）
 *
 * 多视角：团队组织 / 个人组织 / 全平台视图（破玻璃治理态），覆盖每个功能点。
 * 被测为独立静态原型，用 file:// 加载，不依赖后端。
 * 运行：npx playwright test tests/e2e/developer-center.spec.ts
 */
import { test, expect, Page } from '@playwright/test';
import { fileURLToPath, pathToFileURL } from 'url';
import path from 'path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APP = pathToFileURL(path.resolve(HERE, '../../developer-center.html')).href;

const ORG_NAME = { team: '广州日不落科技', personal: '官德志 的个人组织', platform: '全平台视图' };
type Scope = keyof typeof ORG_NAME;

async function gotoOrg(page: Page, target: Scope) {
  for (let i = 0; i < 4; i++) {
    const cur = (await page.locator('#oName').textContent()) || '';
    if (cur.includes(ORG_NAME[target])) return;
    await page.locator('.org-chip').click();
  }
  throw new Error('未能切到 ' + target);
}
async function showTab(page: Page, t: 'keys' | 'api' | 'mcp') {
  await page.locator(`#tab-${t}`).click();
}

test.beforeEach(async ({ page }) => {
  await page.goto(APP);
  await expect(page.locator('#oName')).toBeVisible();
});

/* ============ D1 子 tab 切换 ============ */
test.describe('D1 子 tab 切换', () => {
  test('D1-01 默认进入 API keys 且高亮', async ({ page }) => {
    await expect(page.locator('#tab-keys')).toHaveClass(/on/);
    await expect(page.locator('#panel-keys')).toBeVisible();
  });
  test('D1-02 默认仅 keys 面板可见，其余隐藏', async ({ page }) => {
    await expect(page.locator('#panel-api')).toBeHidden();
    await expect(page.locator('#panel-mcp')).toBeHidden();
  });
  test('D1-03 共三个子 tab', async ({ page }) => {
    await expect(page.locator('.seg button')).toHaveCount(3);
  });
  test('D1-04 切到接口文档', async ({ page }) => {
    await showTab(page, 'api');
    await expect(page.locator('#panel-api')).toBeVisible();
    await expect(page.locator('#tab-api')).toHaveClass(/on/);
  });
  test('D1-05 切到 MCP 接入', async ({ page }) => {
    await showTab(page, 'mcp');
    await expect(page.locator('#panel-mcp')).toBeVisible();
    await expect(page.locator('#tab-mcp')).toHaveClass(/on/);
  });
  test('D1-06 高亮唯一（任一时刻只有一个 on）', async ({ page }) => {
    await showTab(page, 'mcp');
    await expect(page.locator('.seg button.on')).toHaveCount(1);
  });
  test('D1-07 切 tab 时其余面板隐藏（互斥）', async ({ page }) => {
    await showTab(page, 'api');
    await expect(page.locator('#panel-keys')).toBeHidden();
    await expect(page.locator('#panel-mcp')).toBeHidden();
  });
  test('D1-08 三 tab 文案正确', async ({ page }) => {
    await expect(page.locator('#tab-keys')).toContainText('API keys');
    await expect(page.locator('#tab-api')).toContainText('接口文档');
    await expect(page.locator('#tab-mcp')).toContainText('MCP 接入');
  });
  test('D1-09 来回切换状态正确（keys→api→mcp→keys）', async ({ page }) => {
    await showTab(page, 'api');
    await showTab(page, 'mcp');
    await showTab(page, 'keys');
    await expect(page.locator('#panel-keys')).toBeVisible();
    await expect(page.locator('#tab-keys')).toHaveClass(/on/);
  });
  test('D1-10 切组织不改变当前所在 tab', async ({ page }) => {
    await showTab(page, 'mcp');
    await gotoOrg(page, 'personal');
    await expect(page.locator('#panel-mcp')).toBeVisible();
  });
});

/* ============ D2 组织切换与上下文同步 ============ */
test.describe('D2 组织切换与上下文同步', () => {
  test('D2-01 默认进入团队组织', async ({ page }) => {
    await expect(page.locator('#oName')).toContainText('广州日不落科技');
    await expect(page.locator('#oType')).toContainText('TEAM');
  });
  test('D2-02 切到个人组织名称/类型更新', async ({ page }) => {
    await gotoOrg(page, 'personal');
    await expect(page.locator('#oName')).toContainText('个人组织');
    await expect(page.locator('#oType')).toContainText('INDIVIDUAL');
  });
  test('D2-03 切到全平台视图', async ({ page }) => {
    await gotoOrg(page, 'platform');
    await expect(page.locator('#oName')).toContainText('全平台视图');
    await expect(page.locator('#oType')).toContainText('破玻璃');
  });
  test('D2-04 三档循环切换（team→personal→platform→team）', async ({ page }) => {
    await page.locator('.org-chip').click();
    await expect(page.locator('#oName')).toContainText('个人组织');
    await page.locator('.org-chip').click();
    await expect(page.locator('#oName')).toContainText('全平台');
    await page.locator('.org-chip').click();
    await expect(page.locator('#oName')).toContainText('广州日不落');
  });
  test('D2-05 接口文档 X-Org-Id 随组织变（团队=7）', async ({ page }) => {
    await showTab(page, 'api');
    await expect(page.locator('#ak-org')).toHaveText('7');
  });
  test('D2-06 接口文档 X-Org-Id 个人=15', async ({ page }) => {
    await gotoOrg(page, 'personal');
    await showTab(page, 'api');
    await expect(page.locator('#ak-org')).toHaveText('15');
  });
  test('D2-07 MCP X-Org-Id 与接口口径一致', async ({ page }) => {
    await gotoOrg(page, 'personal');
    await showTab(page, 'mcp');
    await expect(page.locator('#mcp-org')).toHaveText('15');
  });
  test('D2-08 全平台视图无具体组织 id（显示 —）', async ({ page }) => {
    await gotoOrg(page, 'platform');
    await showTab(page, 'api');
    await expect(page.locator('#ak-org')).toHaveText('—');
  });
  test('D2-09 头像底色随组织变（隐藏样式回归）', async ({ page }) => {
    const bg = () => page.locator('#oAva').evaluate((el) => getComputedStyle(el).backgroundColor);
    const team = await bg();
    await gotoOrg(page, 'platform');
    expect(await bg()).not.toEqual(team);
  });
  test('D2-10 往返切换上下文一致（team→platform→team 数据复原）', async ({ page }) => {
    const before = await page.locator('#keyRows').innerHTML();
    await gotoOrg(page, 'platform');
    await gotoOrg(page, 'team');
    expect(await page.locator('#keyRows').innerHTML()).toEqual(before);
  });
});

/* ============ D3 API keys — 组织态 ============ */
test.describe('D3 API keys 组织态', () => {
  test('D3-01 团队组织表头含「权限范围」', async ({ page }) => {
    await expect(page.locator('#keyHead')).toContainText('权限范围');
  });
  test('D3-02 团队组织 2 条 key', async ({ page }) => {
    await expect(page.locator('#keyRows tr')).toHaveCount(2);
  });
  test('D3-03 个人组织 1 条 key', async ({ page }) => {
    await gotoOrg(page, 'personal');
    await expect(page.locator('#keyRows tr')).toHaveCount(1);
  });
  test('D3-04 显示「创建 API key」按钮', async ({ page }) => {
    await expect(page.locator('#createWrap')).toBeVisible();
  });
  test('D3-05 每行有编辑/删除操作', async ({ page }) => {
    await expect(page.locator('#keyRows tr').first().locator('.row-act .del')).toBeVisible();
  });
  test('D3-06 key 脱敏展示（sk-***）', async ({ page }) => {
    await expect(page.locator('#keyRows .kcode').first()).toContainText('sk-');
    await expect(page.locator('#keyRows .kcode').first()).toContainText('****');
  });
  test('D3-07 key 带权限范围与可访问库标签', async ({ page }) => {
    await expect(page.locator('#keyRows .t-scope').first()).toBeVisible();
    await expect(page.locator('#keyRows .t-kb').first()).toBeVisible();
  });
  test('D3-08 切组织后 key 列表换成该组织的（团队≠个人）', async ({ page }) => {
    const team = await page.locator('#keyRows').textContent();
    await gotoOrg(page, 'personal');
    expect(await page.locator('#keyRows').textContent()).not.toEqual(team);
  });
  test('D3-09 描述强调「本组织」且密钥只在创建时可见', async ({ page }) => {
    await expect(page.locator('#keyDesc')).toContainText('本组织');
    await expect(page.locator('#keyDesc')).toContainText('创建时可见');
  });
  test('D3-10 scope 提示条带 X-Org-Id 绑定说明', async ({ page }) => {
    await expect(page.locator('#scopeNote')).toContainText('X-Org-Id');
  });
});

/* ============ D4 API keys — 全平台治理态 ============ */
test.describe('D4 API keys 全平台治理态', () => {
  test.beforeEach(async ({ page }) => { await gotoOrg(page, 'platform'); });
  test('D4-01 表头新增「所属组织」', async ({ page }) => {
    await expect(page.locator('#keyHead')).toContainText('所属组织');
  });
  test('D4-02 表头新增「状态」', async ({ page }) => {
    await expect(page.locator('#keyHead')).toContainText('状态');
  });
  test('D4-03 隐藏「创建 API key」按钮', async ({ page }) => {
    await expect(page.locator('#createWrap')).toBeHidden();
  });
  test('D4-04 操作只剩「吊销」，无编辑/删除', async ({ page }) => {
    await expect(page.locator('#keyRows')).toContainText('吊销');
    await expect(page.locator('#keyRows')).not.toContainText('✏️');
  });
  test('D4-05 跨组织聚合（出现多个不同所属组织）', async ({ page }) => {
    await expect(page.locator('#keyRows')).toContainText('广州日不落科技');
    await expect(page.locator('#keyRows')).toContainText('新引擎科技');
  });
  test('D4-06 疑似泄露 key 标红状态', async ({ page }) => {
    const row = page.locator('#keyRows tr', { hasText: '旧版导出脚本' });
    await expect(row).toContainText('疑似泄露');
  });
  test('D4-07 治理提示条变红且含「治理/吊销/审计」', async ({ page }) => {
    await expect(page.locator('#scopeNote')).toContainText('治理');
    await expect(page.locator('#scopeNote')).toContainText('审计');
  });
  test('D4-08 描述说明「不能在此建 key，请下钻组织」', async ({ page }) => {
    await expect(page.locator('#keyDesc')).toContainText('下钻');
  });
  test('D4-09 治理列表含全部 4 条 key', async ({ page }) => {
    await expect(page.locator('#keyRows tr')).toHaveCount(4);
  });
  test('D4-10 切回组织态恢复创建按钮与原表头（治理态不残留）', async ({ page }) => {
    await gotoOrg(page, 'team');
    await expect(page.locator('#createWrap')).toBeVisible();
    await expect(page.locator('#keyHead')).not.toContainText('所属组织');
  });
});

/* ============ D5 接口文档 tab ============ */
test.describe('D5 接口文档', () => {
  test.beforeEach(async ({ page }) => { await showTab(page, 'api'); });
  test('D5-01 接入信息含 Base URL', async ({ page }) => {
    await expect(page.locator('#panel-api')).toContainText('Base URL');
    await expect(page.locator('#panel-api')).toContainText('/api/v1');
  });
  test('D5-02 认证方式为 Bearer', async ({ page }) => {
    await expect(page.locator('#panel-api')).toContainText('Bearer');
  });
  test('D5-03 核心接口 4 条', async ({ page }) => {
    await expect(page.locator('#panel-api .ep')).toHaveCount(4);
  });
  test('D5-04 含检索接口 POST /search', async ({ page }) => {
    const row = page.locator('#panel-api .ep', { hasText: '/search' });
    await expect(row.locator('.m-post')).toBeVisible();
  });
  test('D5-05 含知识库列表 GET /kb', async ({ page }) => {
    const row = page
      .locator('#panel-api .ep')
      .filter({ has: page.locator('.ep-path', { hasText: /^\/kb$/ }) });
    await expect(row.locator('.m-get')).toBeVisible();
  });
  test('D5-06 cURL 示例含 X-Org-Id 与 /search', async ({ page }) => {
    await expect(page.locator('#curlBox')).toContainText('X-Org-Id');
    await expect(page.locator('#curlBox')).toContainText('/search');
  });
  test('D5-07 复制按钮点击变「已复制」', async ({ page }) => {
    const btn = page.locator('#panel-api .pre-copy');
    await btn.click();
    await expect(btn).toHaveText('已复制');
  });
  test('D5-08 数据范围随组织变（团队=广州日不落）', async ({ page }) => {
    await expect(page.locator('#ak-scope')).toContainText('广州日不落');
  });
  test('D5-09 个人上下文数据范围变化', async ({ page }) => {
    await gotoOrg(page, 'personal');
    await expect(page.locator('#ak-scope')).toContainText('个人组织');
  });
  test('D5-10 接口方法徽章 POST/GET 都存在', async ({ page }) => {
    await expect(page.locator('#panel-api .m-post').first()).toBeVisible();
    await expect(page.locator('#panel-api .m-get').first()).toBeVisible();
  });
});

/* ============ D6 MCP 接入 tab ============ */
test.describe('D6 MCP 接入', () => {
  test.beforeEach(async ({ page }) => { await showTab(page, 'mcp'); });
  test('D6-01 含 MCP Server URL', async ({ page }) => {
    await expect(page.locator('#panel-mcp')).toContainText('/mcp');
  });
  test('D6-02 协议标注为 MCP', async ({ page }) => {
    await expect(page.locator('#panel-mcp')).toContainText('MCP');
  });
  test('D6-03 适用客户端 3 项', async ({ page }) => {
    await expect(page.locator('#panel-mcp .ep')).toHaveCount(3);
  });
  test('D6-04 含 Claude 与 CareerMate 客户端', async ({ page }) => {
    await expect(page.locator('#panel-mcp')).toContainText('Claude');
    await expect(page.locator('#panel-mcp')).toContainText('CareerMate');
  });
  test('D6-05 mcpServers 配置含 X-Org-Id', async ({ page }) => {
    await expect(page.locator('#mcpBox')).toContainText('mcpServers');
    await expect(page.locator('#mcpBox')).toContainText('X-Org-Id');
  });
  test('D6-06 可用 MCP 工具 4 个', async ({ page }) => {
    await expect(page.locator('.mcp-tool')).toHaveCount(4);
  });
  test('D6-07 含 search_knowledge 工具', async ({ page }) => {
    await expect(page.locator('#panel-mcp')).toContainText('search_knowledge');
  });
  test('D6-08 含 list_knowledge_bases 工具', async ({ page }) => {
    await expect(page.locator('#panel-mcp')).toContainText('list_knowledge_bases');
  });
  test('D6-09 配置复制按钮点击变「已复制」', async ({ page }) => {
    const btn = page.locator('#panel-mcp .pre-copy');
    await btn.click();
    await expect(btn).toHaveText('已复制');
  });
  test('D6-10 MCP 组织 id 随组织切换（个人=15）', async ({ page }) => {
    await gotoOrg(page, 'personal');
    await showTab(page, 'mcp');
    await expect(page.locator('#mcp-org')).toHaveText('15');
  });
});
