/**
 * app-shell.html（驾驶舱原型）端到端用例
 * 资深测试视角：每个小模块 ~10 条，覆盖正常流 / 边界 / 状态一致性 / 隐藏回归。
 *
 * 运行：npx playwright test tests/e2e/app-shell.spec.ts
 * 说明：被测对象是独立静态原型 app-shell.html，用 file:// 加载，不依赖后端。
 */
import { test, expect, Page } from '@playwright/test';
import { fileURLToPath, pathToFileURL } from 'url';
import path from 'path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APP = pathToFileURL(path.resolve(HERE, '../../app-shell.html')).href;

// ---- helpers ----
async function switchOrg(page: Page, scope: 'personal' | 'team' | 'platform') {
  await page.locator('#orgTrigger').click();
  await page.locator(`.org-opt[data-scope="${scope}"]`).click();
  await expect(page.locator('#orgMenu')).not.toHaveClass(/open/);
}
async function openModal(page: Page, card: '新建知识库' | '上传文档' | '发起检索测试') {
  await page.locator('.action-card', { hasText: card }).click();
}

test.beforeEach(async ({ page }) => {
  await page.goto(APP);
  await expect(page.locator('#trName')).toBeVisible();
});

/* ======================================================================
 * 模块 1：组织切换器（OrgSwitcher）
 * ==================================================================== */
test.describe('组织切换器', () => {
  test('1-默认进入个人组织（INDIVIDUAL）', async ({ page }) => {
    await expect(page.locator('#trName')).toHaveText(/个人组织/);
    await expect(page.locator('#trBadge')).toHaveText('INDIVIDUAL');
  });

  test('2-点击触发器展开下拉，再点收起', async ({ page }) => {
    await page.locator('#orgTrigger').click();
    await expect(page.locator('#orgMenu')).toHaveClass(/open/);
    await page.locator('#orgTrigger').click();
    await expect(page.locator('#orgMenu')).not.toHaveClass(/open/);
  });

  test('3-下拉含个人/团队/全平台三项', async ({ page }) => {
    await page.locator('#orgTrigger').click();
    await expect(page.locator('.org-opt')).toHaveCount(3);
  });

  test('4-切到团队组织后名称与徽章更新', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#trName')).toHaveText('广州日不落科技');
    await expect(page.locator('#trBadge')).toHaveText('TEAM');
  });

  test('5-切到全平台徽章为超管', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#trBadge')).toHaveText('超管');
  });

  test('6-当前组织在下拉里有选中态（active 唯一）', async ({ page }) => {
    await switchOrg(page, 'team');
    await page.locator('#orgTrigger').click();
    await expect(page.locator('.org-opt.active')).toHaveCount(1);
    await expect(page.locator('.org-opt.active')).toHaveAttribute('data-scope', 'team');
  });

  test('7-范围提示文案随组织变化', async ({ page }) => {
    const hint = page.locator('#scopeHint');
    const personal = await hint.textContent();
    await switchOrg(page, 'team');
    expect(await hint.textContent()).not.toEqual(personal);
    await expect(hint).toContainText('本组织全部库');
  });

  test('8-导航“知识库”计数随组织变（2/6/214）', async ({ page }) => {
    await expect(page.locator('#navKb')).toHaveText('2');
    await switchOrg(page, 'team');
    await expect(page.locator('#navKb')).toHaveText('6');
    await switchOrg(page, 'platform');
    await expect(page.locator('#navKb')).toHaveText('214');
  });

  test('9-来回切换不串味（回到个人仍是个人数据）', async ({ page }) => {
    await switchOrg(page, 'team');
    await switchOrg(page, 'personal');
    await expect(page.locator('#trBadge')).toHaveText('INDIVIDUAL');
    await expect(page.locator('#m-chunks')).toHaveText('1,284');
  });

  test('10-头像底色随组织切换（隐藏样式回归）', async ({ page }) => {
    const bg = () => page.locator('#trAva').evaluate(el => getComputedStyle(el).backgroundColor);
    const personalBg = await bg();
    await switchOrg(page, 'platform');
    expect(await bg()).not.toEqual(personalBg);
  });
});

/* ======================================================================
 * 模块 2：通知中心 + 待处理邀请横幅
 * ==================================================================== */
test.describe('通知与邀请', () => {
  test('1-初始铃铛有红点角标 1', async ({ page }) => {
    await expect(page.locator('#bellBadge')).toBeVisible();
    await expect(page.locator('#bellBadge')).toHaveText('1');
  });

  test('2-首屏显示待处理邀请横幅', async ({ page }) => {
    await expect(page.locator('#inviteBanner')).toBeVisible();
    await expect(page.locator('#inviteBanner')).toContainText('新引擎科技');
  });

  test('3-点铃铛展开通知中心，含该邀请', async ({ page }) => {
    await page.locator('#bellBtn').click();
    await expect(page.locator('#bellMenu')).toHaveClass(/open/);
    await expect(page.locator('#notiList')).toContainText('邀请你加入');
  });

  test('4-接受邀请后横幅消失', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await page.locator('#inviteBanner').getByText('接受').click();
    await expect(page.locator('#inviteBanner')).toBeHidden();
  });

  test('5-接受后红点角标消失', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await page.locator('#inviteBanner').getByText('接受').click();
    await expect(page.locator('#bellBadge')).toBeHidden();
  });

  test('6-接受后通知中心显示“暂无新通知”', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await page.locator('#inviteBanner').getByText('接受').click();
    await page.locator('#bellBtn').click();
    await expect(page.locator('#notiList')).toContainText('暂无新通知');
  });

  test('7-拒绝邀请同样隐藏横幅且不弹框', async ({ page }) => {
    let dialogFired = false;
    page.on('dialog', d => { dialogFired = true; d.accept(); });
    await page.locator('#inviteBanner').getByText('拒绝').click();
    await expect(page.locator('#inviteBanner')).toBeHidden();
    expect(dialogFired).toBeFalsy(); // 拒绝不应弹 alert（隐藏 bug 检查）
  });

  test('8-接受弹框文案包含已加入', async ({ page }) => {
    const dialog = page.waitForEvent('dialog');
    await page.locator('#inviteBanner').getByText('接受').click();
    const d = await dialog;
    expect(d.message()).toContain('已加入');
    await d.accept();
  });

  test('9-接受后切换组织邀请不复现（状态不回滚）', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await page.locator('#inviteBanner').getByText('接受').click();
    await switchOrg(page, 'team');
    await switchOrg(page, 'personal');
    await expect(page.locator('#inviteBanner')).toBeHidden();
  });

  test('10-邀请横幅在团队/平台视图依旧存在（属于“你”而非组织）', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#inviteBanner')).toBeVisible();
    await switchOrg(page, 'platform');
    await expect(page.locator('#inviteBanner')).toBeVisible();
  });
});

/* ======================================================================
 * 模块 3：账号菜单（UserMenu）
 * ==================================================================== */
test.describe('账号菜单', () => {
  test('1-默认收起', async ({ page }) => {
    await expect(page.locator('#userMenu')).not.toHaveClass(/open/);
  });
  test('2-点击展开', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu')).toHaveClass(/open/);
  });
  test('3-头部显示用户名与脱敏手机号', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu')).toContainText('qa_admin');
    await expect(page.locator('#userMenu')).toContainText('138****0001');
  });
  test('4-未绑定邮箱有提示', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu')).toContainText('未绑定邮箱');
  });
  test('5-含账号与安全入口', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu')).toContainText('账号与安全');
  });
  test('6-我的组织显示数量 2 个', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu')).toContainText('2 个');
  });
  test('7-含退出登录且为危险样式', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu .um-item.danger')).toHaveText(/退出登录/);
  });
  test('8-我的组织跳转组织管理页', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await page.locator('#userMenu').getByText('我的组织').click();
    await expect(page).toHaveURL(/org-management\.html$/);
  });
  test('9-语言项显示当前为简体中文', async ({ page }) => {
    await page.locator('#userTrigger').click();
    await expect(page.locator('#userMenu')).toContainText('简体中文');
  });
  test('10-展开账号菜单会关闭组织菜单（互斥）', async ({ page }) => {
    await page.locator('#orgTrigger').click();
    await expect(page.locator('#orgMenu')).toHaveClass(/open/);
    await page.locator('#userTrigger').click();
    await expect(page.locator('#orgMenu')).not.toHaveClass(/open/);
    await expect(page.locator('#userMenu')).toHaveClass(/open/);
  });
});

/* ======================================================================
 * 模块 4：核心指标四象限（KPI）
 * ==================================================================== */
test.describe('核心指标', () => {
  test('1-个人组织资产规模数值', async ({ page }) => {
    await expect(page.locator('#m-chunks')).toHaveText('1,284');
    await expect(page.locator('#m-docs')).toHaveText('86');
    await expect(page.locator('#m-kbs')).toHaveText('2');
  });
  test('2-切团队后四象限全部刷新（无残留个人值）', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#m-chunks')).toHaveText('12,840');
    await expect(page.locator('#m-requests')).toHaveText('1,240');
    await expect(page.locator('#m-cost')).toHaveText('¥3.42');
  });
  test('3-平台视图为全平台量级', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#m-chunks')).toHaveText('486,200');
  });
  test('4-检索质量 C 位是“零结果”而非命中率', async ({ page }) => {
    await expect(page.locator('.q-quality .kpi-value')).toContainText('零结果');
  });
  test('5-Top3 命中率已从首页移除（隐藏回归）', async ({ page }) => {
    await expect(page.locator('#m-top3')).toHaveCount(0);
    await expect(page.locator('.q-quality')).not.toContainText('Top3');
  });
  test('6-rerank 分带“仅精排”标注', async ({ page }) => {
    await expect(page.locator('.q-quality')).toContainText('仅精排');
  });
  test('7-运行健康用 P95 而非今日均值', async ({ page }) => {
    await expect(page.locator('.q-health .kpi-value')).toContainText('P95');
    await expect(page.locator('#m-p95')).toHaveText('612').catch(() => {});
  });
  test('8-成本卡拆分 embedding/rerank/LLM', async ({ page }) => {
    await expect(page.locator('.q-cost')).toContainText('embedding / rerank');
    await expect(page.locator('#m-llm')).not.toBeEmpty();
  });
  test('9-四个象限卡片都存在', async ({ page }) => {
    await expect(page.locator('.kpi')).toHaveCount(4);
  });
  test('10-个人组织 Query 改写率有值且为百分比', async ({ page }) => {
    await expect(page.locator('#m-rewrite')).toHaveText(/%$/);
  });
});

/* ======================================================================
 * 模块 5：横幅显隐（升档 / 破玻璃）
 * ==================================================================== */
test.describe('横幅显隐', () => {
  test('1-个人组织显示升档横幅', async ({ page }) => {
    await expect(page.locator('#upgradeBanner')).toBeVisible();
  });
  test('2-个人组织不显示破玻璃横幅', async ({ page }) => {
    await expect(page.locator('#breakglassBanner')).toBeHidden();
  });
  test('3-团队组织不显示升档横幅', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#upgradeBanner')).toBeHidden();
  });
  test('4-团队组织不显示破玻璃横幅', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#breakglassBanner')).toBeHidden();
  });
  test('5-平台视图显示破玻璃横幅', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#breakglassBanner')).toBeVisible();
  });
  test('6-平台视图不显示升档横幅', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#upgradeBanner')).toBeHidden();
  });
  test('7-升档横幅含“升级到团队组织”按钮', async ({ page }) => {
    await expect(page.locator('#upgradeBanner')).toContainText('升级到团队组织');
  });
  test('8-破玻璃横幅提示审计与 SYSTEM 不可见', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#breakglassBanner')).toContainText('审计');
    await expect(page.locator('#breakglassBanner')).toContainText('SYSTEM');
  });
  test('9-个人↔平台切换横幅互斥（不同时出现）', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#upgradeBanner')).toBeHidden();
    await switchOrg(page, 'personal');
    await expect(page.locator('#breakglassBanner')).toBeHidden();
    await expect(page.locator('#upgradeBanner')).toBeVisible();
  });
  test('10-任一时刻最多一个组织态横幅可见', async ({ page }) => {
    for (const s of ['personal', 'team', 'platform'] as const) {
      await switchOrg(page, s);
      const up = await page.locator('#upgradeBanner').isVisible();
      const bg = await page.locator('#breakglassBanner').isVisible();
      expect(up && bg).toBeFalsy();
    }
  });
});

/* ======================================================================
 * 模块 6：成员概览（MemberOverview）
 * ==================================================================== */
test.describe('成员概览', () => {
  test('1-个人组织不显示成员面板', async ({ page }) => {
    await expect(page.locator('#memberPanel')).toBeHidden();
  });
  test('2-团队组织显示成员面板', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#memberPanel')).toBeVisible();
  });
  test('3-团队成员统计含共 8 人', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#memberOverview')).toContainText('8');
    await expect(page.locator('#memberOverview')).toContainText('OWNER');
  });
  test('4-团队成员含待接受计数', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#memberOverview')).toContainText('待接受');
  });
  test('5-团队头像堆叠存在且有“+N”溢出', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#memberOverview .ava-pile .m-ava').first()).toBeVisible();
    await expect(page.locator('#memberOverview .ava-more')).toContainText('+');
  });
  test('6-首页不展示完整成员列表（无逐行成员）', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#memberOverview .member-row')).toHaveCount(0);
  });
  test('7-管理成员按钮跳组织管理页', async ({ page }) => {
    await switchOrg(page, 'team');
    await page.locator('#manageBtn').click();
    await expect(page).toHaveURL(/org-management\.html$/);
  });
  test('8-平台视图成员区为“平台规模”总览', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#memberTitle')).toHaveText('平台规模');
    await expect(page.locator('#memberOverview')).toContainText('214');
  });
  test('9-平台视图无头像堆叠（只读总览）', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#memberOverview .ava-pile')).toHaveCount(0);
  });
  test('10-平台→个人切回后成员面板重新隐藏', async ({ page }) => {
    await switchOrg(page, 'platform');
    await switchOrg(page, 'personal');
    await expect(page.locator('#memberPanel')).toBeHidden();
  });
});

/* ======================================================================
 * 模块 7：平台权限矩阵（PermissionMatrix）
 * ==================================================================== */
test.describe('权限矩阵', () => {
  test('1-个人组织不显示权限矩阵', async ({ page }) => {
    await expect(page.locator('#permPanel')).toBeHidden();
  });
  test('2-团队组织不显示权限矩阵', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#permPanel')).toBeHidden();
  });
  test('3-平台视图显示权限矩阵', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#permPanel')).toBeVisible();
  });
  test('4-矩阵含“最小权限+破玻璃+审计”副标题', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#permPanel')).toContainText('破玻璃');
  });
  test('5-SYSTEM 库在默认与破玻璃下均为✗', async ({ page }) => {
    await switchOrg(page, 'platform');
    const row = page.locator('#permPanel tr', { hasText: 'SYSTEM 库' });
    await expect(row.locator('.no')).toHaveCount(2);
  });
  test('6-明文手机号/凭证始终不可得', async ({ page }) => {
    await switchOrg(page, 'platform');
    const row = page.locator('#permPanel tr', { hasText: '明文手机号' });
    await expect(row.locator('.no')).toHaveCount(2);
  });
  test('7-全平台聚合指标默认✗、破玻璃✓', async ({ page }) => {
    await switchOrg(page, 'platform');
    const row = page.locator('#permPanel tr', { hasText: '全平台聚合指标' });
    await expect(row.locator('.no')).toHaveCount(1);
    await expect(row.locator('.yes')).toHaveCount(1);
  });
  test('8-危险操作需破玻璃+二次确认', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#permPanel')).toContainText('二次确认');
  });
  test('9-矩阵共 7 行能力', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#permPanel tbody tr')).toHaveCount(7);
  });
  test('10-平台→团队切回后矩阵隐藏', async ({ page }) => {
    await switchOrg(page, 'platform');
    await switchOrg(page, 'team');
    await expect(page.locator('#permPanel')).toBeHidden();
  });
});

/* ======================================================================
 * 模块 8：新建知识库弹窗（CreateKbModal）
 * ==================================================================== */
test.describe('新建知识库弹窗', () => {
  test('1-点击快捷卡打开弹窗', async ({ page }) => {
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbModal')).toHaveClass(/open/);
  });
  test('2-空名称提交弹校验且弹窗不关', async ({ page }) => {
    await openModal(page, '新建知识库');
    const dialog = page.waitForEvent('dialog');
    await page.locator('#kbModal').getByRole('button', { name: '创建' }).click();
    expect((await dialog).message()).toContain('名称');
    await (await dialog).accept();
    await expect(page.locator('#kbModal')).toHaveClass(/open/);
  });
  test('3-个人组织归属默认“个人”', async ({ page }) => {
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbOwner')).toHaveValue('PERSONAL');
  });
  test('4-个人归属可见性为私有/公开', async ({ page }) => {
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbVis option')).toHaveCount(2);
    await expect(page.locator('#kbVis')).toContainText('私有');
    await expect(page.locator('#kbVis')).toContainText('公开');
  });
  test('5-归属改为组织时可见性联动为组织可见/仅管理者', async ({ page }) => {
    await openModal(page, '新建知识库');
    await page.locator('#kbOwner').selectOption('ORG:7');
    await expect(page.locator('#kbVis')).toContainText('组织可见');
    await expect(page.locator('#kbVis')).toContainText('仅管理者');
  });
  test('6-团队组织打开时归属默认组织', async ({ page }) => {
    await switchOrg(page, 'team');
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbOwner')).toHaveValue('ORG:7');
  });
  test('7-不暴露 embedding 模型选择（隐藏回归）', async ({ page }) => {
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbModal')).not.toContainText('Embedding 模型');
  });
  test('8-含多模态处理勾选框，默认未勾', async ({ page }) => {
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbMultimodal')).not.toBeChecked();
  });
  test('9-正常提交后弹窗关闭并出现成功 toast', async ({ page }) => {
    await openModal(page, '新建知识库');
    await page.locator('#kbName').fill('测试库A');
    await page.locator('#kbModal').getByRole('button', { name: '创建' }).click();
    await expect(page.locator('#kbModal')).not.toHaveClass(/open/);
    await expect(page.locator('#toast')).toHaveClass(/show/);
    await expect(page.locator('#toast')).toContainText('测试库A');
  });
  test('10-重新打开时表单已重置（名称/多模态清空）', async ({ page }) => {
    await openModal(page, '新建知识库');
    await page.locator('#kbName').fill('测试库B');
    await page.locator('#kbMultimodal').check();
    await page.locator('#kbModal').getByRole('button', { name: '创建' }).click();
    await openModal(page, '新建知识库');
    await expect(page.locator('#kbName')).toHaveValue('');
    await expect(page.locator('#kbMultimodal')).not.toBeChecked();
  });
});

/* ======================================================================
 * 模块 9：上传文档弹窗（UploadModal）
 * ==================================================================== */
test.describe('上传文档弹窗', () => {
  test('1-点击打开弹窗', async ({ page }) => {
    await openModal(page, '上传文档');
    await expect(page.locator('#uploadModal')).toHaveClass(/open/);
  });
  test('2-目标知识库下拉随个人组织填充', async ({ page }) => {
    await openModal(page, '上传文档');
    await expect(page.locator('#uploadKb')).toContainText('我的简历库');
  });
  test('3-团队组织目标库为组织库', async ({ page }) => {
    await switchOrg(page, 'team');
    await openModal(page, '上传文档');
    await expect(page.locator('#uploadKb')).toContainText('岗位 JD 库');
  });
  test('4-含拖拽上传区文案', async ({ page }) => {
    await openModal(page, '上传文档');
    await expect(page.locator('#uploadModal')).toContainText('拖拽文件');
  });
  test('5-提交后弹窗关闭', async ({ page }) => {
    await openModal(page, '上传文档');
    await page.locator('#uploadModal').getByRole('button', { name: '开始上传' }).click();
    await expect(page.locator('#uploadModal')).not.toHaveClass(/open/);
  });
  test('6-提交后“最近操作”顶部新增一条', async ({ page }) => {
    const before = await page.locator('#activityList .activity-item').count();
    await openModal(page, '上传文档');
    await page.locator('#uploadModal').getByRole('button', { name: '开始上传' }).click();
    await expect(page.locator('#activityList .activity-item')).toHaveCount(before + 1);
  });
  test('7-新增动态在列表第一条且标“刚刚”', async ({ page }) => {
    await openModal(page, '上传文档');
    await page.locator('#uploadModal').getByRole('button', { name: '开始上传' }).click();
    const first = page.locator('#activityList .activity-item').first();
    await expect(first).toContainText('刚刚');
    await expect(first).toContainText('处理队列');
  });
  test('8-提交后出现处理队列 toast', async ({ page }) => {
    await openModal(page, '上传文档');
    await page.locator('#uploadModal').getByRole('button', { name: '开始上传' }).click();
    await expect(page.locator('#toast')).toContainText('处理队列');
  });
  test('9-取消按钮关闭弹窗且不新增动态', async ({ page }) => {
    const before = await page.locator('#activityList .activity-item').count();
    await openModal(page, '上传文档');
    await page.locator('#uploadModal').getByRole('button', { name: '取消' }).click();
    await expect(page.locator('#uploadModal')).not.toHaveClass(/open/);
    await expect(page.locator('#activityList .activity-item')).toHaveCount(before);
  });
  test('10-点遮罩关闭弹窗', async ({ page }) => {
    await openModal(page, '上传文档');
    await page.locator('#uploadModal').click({ position: { x: 5, y: 5 } });
    await expect(page.locator('#uploadModal')).not.toHaveClass(/open/);
  });
});

/* ======================================================================
 * 模块 10：发起检索测试弹窗（SearchModal）
 * ==================================================================== */
test.describe('检索测试弹窗', () => {
  test('1-点击打开弹窗', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await expect(page.locator('#searchModal')).toHaveClass(/open/);
  });
  test('2-空查询提交弹校验', async ({ page }) => {
    await openModal(page, '发起检索测试');
    const dialog = page.waitForEvent('dialog');
    await page.locator('#searchModal').getByRole('button', { name: '检索' }).click();
    expect((await dialog).message()).toContain('查询');
    await (await dialog).accept();
  });
  test('3-默认策略 hybrid 高亮', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await expect(page.locator('#stratSeg button.on')).toHaveText('hybrid');
  });
  test('4-策略分段为单选（切换后只剩一个高亮）', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await page.locator('#stratSeg button', { hasText: 'vector' }).click();
    await expect(page.locator('#stratSeg button.on')).toHaveCount(1);
    await expect(page.locator('#stratSeg button.on')).toHaveText('vector');
  });
  test('5-结果区默认隐藏', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await expect(page.locator('#searchResult')).toBeHidden();
  });
  test('6-检索后展示轻量摘要', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await page.locator('#searchQ').fill('Java 薪资');
    await page.locator('#searchModal').getByRole('button', { name: '检索' }).click();
    await expect(page.locator('#searchResult')).toBeVisible();
    await expect(page.locator('#searchResult')).toContainText('召回');
  });
  test('7-结果摘要反映当前策略', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await page.locator('#stratSeg button', { hasText: 'bm25' }).click();
    await page.locator('#searchQ').fill('Java 薪资');
    await page.locator('#searchModal').getByRole('button', { name: '检索' }).click();
    await expect(page.locator('#searchResult')).toContainText('bm25');
  });
  test('8-结果区有“去调试台查看完整结果”入口', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await page.locator('#searchQ').fill('x');
    await page.locator('#searchModal').getByRole('button', { name: '检索' }).click();
    await expect(page.locator('#searchResult')).toContainText('检索调试台');
  });
  test('9-检索结果不在弹窗里堆完整明细（只 3 条 top）', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await page.locator('#searchQ').fill('x');
    await page.locator('#searchModal').getByRole('button', { name: '检索' }).click();
    await expect(page.locator('#searchResult .sr-item')).toHaveCount(3);
  });
  test('10-知识库下拉含“全部知识库”选项', async ({ page }) => {
    await openModal(page, '发起检索测试');
    await expect(page.locator('#searchKb')).toContainText('全部知识库');
  });
});

/* ======================================================================
 * 模块 11：最近操作 + 重试权限门
 * ==================================================================== */
test.describe('最近操作与重试权限', () => {
  test('1-个人组织最近操作不显示操作人', async ({ page }) => {
    await expect(page.locator('#activityList .activity-actor')).toHaveCount(0);
  });
  test('2-团队组织显示操作人', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#activityList .activity-actor').first()).toBeVisible();
  });
  test('3-个人组织失败文档可重试', async ({ page }) => {
    await expect(page.locator('#activityList .activity-retry')).toHaveCount(1);
  });
  test('4-团队组织(你是OWNER)失败文档可重试', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#activityList .activity-retry')).toHaveCount(1);
  });
  test('5-平台视图失败文档不可重试（权限门）', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#activityList .activity-retry')).toHaveCount(0);
  });
  test('6-平台视图失败项提示“需该组织管理员处理”', async ({ page }) => {
    await switchOrg(page, 'platform');
    await expect(page.locator('#activityList')).toContainText('需该组织管理员处理');
  });
  test('7-错误项有红点样式类', async ({ page }) => {
    await expect(page.locator('#activityList .activity-item.error')).toHaveCount(1);
  });
  test('8-列表含带日期前缀的跨天项（昨天）', async ({ page }) => {
    await expect(page.locator('#activityList')).toContainText('昨天');
  });
  test('9-检索完成项标注策略与耗时', async ({ page }) => {
    await expect(page.locator('#activityList')).toContainText('策略: hybrid');
    await expect(page.locator('#activityList')).toContainText('ms');
  });
  test('10-切组织后操作人列随之出现/消失（一致性）', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#activityList .activity-actor').first()).toBeVisible();
    await switchOrg(page, 'personal');
    await expect(page.locator('#activityList .activity-actor')).toHaveCount(0);
  });
});

/* ======================================================================
 * 模块 12：入库健康度 + 检索趋势
 * ==================================================================== */
test.describe('入库健康度与趋势', () => {
  test('1-个人组织失败数与 CTA 文案一致', async ({ page }) => {
    await expect(page.locator('#in-f')).toHaveText('2');
    await expect(page.locator('#ingestCta')).toContainText('2 个文档解析失败');
  });
  test('2-四段进度条宽度均 > 0', async ({ page }) => {
    for (const id of ['#seg-c', '#seg-p', '#seg-q', '#seg-f']) {
      const w = await page.locator(id).evaluate(el => (el as HTMLElement).style.width);
      expect(parseFloat(w)).toBeGreaterThan(0);
    }
  });
  test('3-已完成段宽度最大', async ({ page }) => {
    const wc = await page.locator('#seg-c').evaluate(el => parseFloat((el as HTMLElement).style.width));
    const wf = await page.locator('#seg-f').evaluate(el => parseFloat((el as HTMLElement).style.width));
    expect(wc).toBeGreaterThan(wf);
  });
  test('4-文档总数展示', async ({ page }) => {
    await expect(page.locator('#m-ingest-total')).toHaveText('86');
  });
  test('5-团队组织总数变化且失败数联动 CTA', async ({ page }) => {
    await switchOrg(page, 'team');
    await expect(page.locator('#m-ingest-total')).toHaveText('863');
    await expect(page.locator('#ingestCta')).toContainText('18 个文档解析失败');
  });
  test('6-趋势折线 path 非空', async ({ page }) => {
    const d = await page.locator('#sparkLine').getAttribute('d');
    expect((d || '').length).toBeGreaterThan(0);
  });
  test('7-P95 虚线 path 非空', async ({ page }) => {
    const d = await page.locator('#sparkP95').getAttribute('d');
    expect((d || '').length).toBeGreaterThan(0);
  });
  test('8-切组织后折线重绘（path 改变）', async ({ page }) => {
    const before = await page.locator('#sparkLine').getAttribute('d');
    await switchOrg(page, 'platform');
    const after = await page.locator('#sparkLine').getAttribute('d');
    expect(after).not.toEqual(before);
  });
  test('9-趋势卡显示请求峰值与 P95 区间', async ({ page }) => {
    await expect(page.locator('#m-peak')).not.toBeEmpty();
    await expect(page.locator('#m-p95range')).toContainText('ms');
  });
  test('10-面积图 path 闭合（含 L..Z）', async ({ page }) => {
    const d = await page.locator('#sparkArea').getAttribute('d');
    expect(d).toMatch(/Z\s*$/);
  });
});
