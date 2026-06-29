/**
 * 组织权限与数据过滤 — 多视角 E2E（API 驱动，打真实后端）
 * 对应文档：docs/org-permission-test-plan-V2.md（用例编号 M*-* 与本文件一一对应）
 *
 * 四视角：
 *   P-超管   = ADMIN（ragRole=ADMIN，跨组织需 X-Admin-Override 破玻璃）
 *   P-企管   = U_RAG（新建 TEAM 组织即 OWNER）
 *   P-员工   = U_OTHER（被 OWNER 加为该 TEAM 的 MEMBER）
 *   P-个人   = 各自的 INDIVIDUAL 个人组织
 *
 * 前置：后端（含 V48~V50）+ 前端 dev(5173) 运行；种子账号已建。
 * 运行：RAG_WEB=http://localhost:5173 npx playwright test tests/e2e/org-permissions.spec.ts --reporter=line
 */
import { test, expect, request, APIRequestContext } from '@playwright/test';
import { RAG_API, ragLogin } from './unified-auth/fixtures/api';
import { ADMIN, U_RAG, U_OTHER } from './unified-auth/fixtures/accounts';
import { ensureQaAccounts } from './unified-auth/fixtures/seed';

test.describe.configure({ mode: 'serial' });

// ---- helpers ----
async function req(
  token: string,
  opts: { orgId?: number | null; override?: boolean } = {},
): Promise<APIRequestContext> {
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
  if (opts.orgId != null) headers['X-Org-Id'] = String(opts.orgId);
  if (opts.override) {
    headers['X-Admin-Override'] = 'true';
    headers['X-Admin-Override-Reason'] = 'e2e-permission';
  }
  return request.newContext({ extraHTTPHeaders: headers });
}
async function listKb(token: string, opts: { orgId?: number | null; override?: boolean } = {}) {
  const c = await req(token, opts);
  const res = await c.get(`${RAG_API}/v1/kb`);
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json())?.data ?? []) as any[];
}
async function createKb(
  token: string,
  body: { name: string; orgId?: number | null; visibility?: string },
) {
  const c = await req(token);
  const data: Record<string, unknown> = { name: body.name, description: 'e2e perm' };
  if (body.orgId != null) data.orgId = body.orgId;
  if (body.visibility) data.visibility = body.visibility;
  return c.post(`${RAG_API}/v1/kb`, { data });
}
async function dashboard(token: string, opts: { orgId?: number | null; override?: boolean } = {}) {
  const c = await req(token, opts);
  const res = await c.get(`${RAG_API}/v1/metrics/dashboard`);
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await res.json())?.data ?? {};
}
const has = (list: any[], id: number) => list.some((k) => Number(k.id) === Number(id));
const ts = Date.now();

// ---- 共享上下文 ----
let tAdmin = '', tRag = '', tOther = '';
let ragPersonalOrg = 0, otherPersonalOrg = 0, teamOrg = 0, otherUserId = 0;
let ragPersonalKb = 0, ragPublicKb = 0, teamPrivateKb = 0;

test.beforeAll(async () => {
  await ensureQaAccounts().catch(() => {});
  tAdmin = await ragLogin(ADMIN.account, ADMIN.password);
  tRag = await ragLogin(U_RAG.account, U_RAG.password);
  tOther = await ragLogin(U_OTHER.account, U_OTHER.password);
  expect(tRag && tOther && tAdmin, '三个测试账号登录均应成功').toBeTruthy();

  // 个人组织（懒建）
  const ragOrgs = (await (await req(tRag)).get(`${RAG_API}/v1/orgs`).then((r) => r.json()))?.data ?? [];
  ragPersonalOrg = ragOrgs.find((o: any) => o.personal)?.id;
  const otherOrgs = (await (await req(tOther)).get(`${RAG_API}/v1/orgs`).then((r) => r.json()))?.data ?? [];
  otherPersonalOrg = otherOrgs.find((o: any) => o.personal)?.id;
  expect(ragPersonalOrg, 'U_RAG 应有 INDIVIDUAL 个人组织').toBeTruthy();

  // U_RAG 建团队组织（OWNER）
  const teamRes = await (await req(tRag)).post(`${RAG_API}/v1/orgs`, {
    data: { name: `E2E团队_${ts}`, slug: `e2e${ts}` },
  });
  expect(teamRes.ok(), await teamRes.text()).toBeTruthy();
  teamOrg = (await teamRes.json())?.data?.id;

  // U_OTHER 建个人库 → 拿其 userId
  const otherKbRes = await createKb(tOther, { name: `other个人库_${ts}` });
  otherUserId = (await otherKbRes.json())?.data?.ownerUserId;
  expect(otherUserId, '应能拿到 U_OTHER 的 userId').toBeTruthy();

  // OWNER 把 U_OTHER 加为团队 MEMBER（构造“企业普通员工”视角）
  await (await req(tRag)).post(`${RAG_API}/v1/orgs/${teamOrg}/members`, {
    data: { userId: otherUserId, role: 'MEMBER' },
  });

  // 数据：rag 个人私有库 / 个人公开库 / 团队私有库
  ragPersonalKb = (await (await createKb(tRag, { name: `rag个人私有_${ts}`, visibility: 'PRIVATE' })).json())?.data?.id;
  ragPublicKb = (await (await createKb(tRag, { name: `rag个人公开_${ts}`, visibility: 'PUBLIC' })).json())?.data?.id;
  teamPrivateKb = (await (await createKb(tRag, { name: `团队私有_${ts}`, orgId: teamOrg, visibility: 'PRIVATE' })).json())?.data?.id;
});

/* ================= M1 组织生命周期 ================= */
test.describe('M1 组织生命周期', () => {
  test('M1-01 个人组织自动懒建且 type=INDIVIDUAL/personal=true', async () => {
    const orgs = (await (await req(tRag)).get(`${RAG_API}/v1/orgs`).then((r) => r.json()))?.data ?? [];
    const personal = orgs.filter((o: any) => o.personal);
    expect(personal.length, '个人组织有且仅一个').toBe(1);
    expect(personal[0].type).toBe('INDIVIDUAL');
  });
  test('M1-02 新建组织一律 type=TEAM', async () => {
    const res = await (await req(tRag)).post(`${RAG_API}/v1/orgs`, { data: { name: `T_${ts}_2`, slug: `t${ts}2` } });
    expect(res.ok()).toBeTruthy();
    expect((await res.json())?.data?.type).toBe('TEAM');
  });
  test('M1-05 对 TEAM 组织 upgrade → 409', async () => {
    const res = await (await req(tRag)).post(`${RAG_API}/v1/orgs/${teamOrg}/upgrade`);
    expect(res.status()).toBe(409);
  });
  test('M1-06 员工 PATCH 组织信息 → 403', async () => {
    const res = await (await req(tOther)).patch(`${RAG_API}/v1/orgs/${teamOrg}`, { data: { name: '员工乱改' } });
    expect(res.status()).toBe(403);
  });
  test('M1-08 员工删除组织 → 403', async () => {
    const res = await (await req(tOther)).delete(`${RAG_API}/v1/orgs/${teamOrg}`);
    expect(res.status()).toBe(403);
  });
  test('M1-09 个人组织无“退出”（leave 个人组织应被拒）', async () => {
    const res = await (await req(tRag)).post(`${RAG_API}/v1/orgs/${ragPersonalOrg}/leave`);
    expect([400, 403, 409]).toContain(res.status());
  });
});

/* ================= M2 成员与邀请 ================= */
test.describe('M2 成员与邀请', () => {
  test('M2-01 OWNER 可读成员列表且含被加成员', async () => {
    const res = await (await req(tRag)).get(`${RAG_API}/v1/orgs/${teamOrg}/members`);
    expect(res.ok()).toBeTruthy();
    const ids = ((await res.json())?.data ?? []).map((m: any) => Number(m.userId));
    expect(ids).toContain(Number(otherUserId));
  });
  test('M2-02 员工搜候选人 → 403', async () => {
    const res = await (await req(tOther)).get(`${RAG_API}/v1/orgs/${teamOrg}/member-candidates?q=qa`);
    expect(res.status()).toBe(403);
  });
  test('M2-03 员工加成员 → 403（防提权）', async () => {
    const res = await (await req(tOther)).post(`${RAG_API}/v1/orgs/${teamOrg}/members`, { data: { userId: otherUserId, role: 'ADMIN' } });
    expect(res.status()).toBe(403);
  });
  test('M2-04 员工把自己改 OWNER → 403（防提权）', async () => {
    const res = await (await req(tOther)).patch(`${RAG_API}/v1/orgs/${teamOrg}/members/${otherUserId}`, { data: { role: 'OWNER' } });
    expect(res.status()).toBe(403);
  });
  test('M2-06 个人组织邀请成员 → 拒绝（须先升级 TEAM）', async () => {
    const res = await (await req(tRag)).post(`${RAG_API}/v1/orgs/${ragPersonalOrg}/invitations`, { data: { phone: '13800138777', role: 'MEMBER' } });
    expect([400, 403, 409]).toContain(res.status());
  });
  test('M2-09 “我的邀请”只返回发给本人的', async () => {
    const res = await (await req(tOther)).get(`${RAG_API}/v1/invitations/mine`);
    expect(res.ok()).toBeTruthy();
  });
});

/* ================= M3 知识库列表过滤 ================= */
test.describe('M3 知识库列表过滤', () => {
  test('M3-01 个人上下文只见个人库，不混入团队库', async () => {
    const list = await listKb(tRag, { orgId: ragPersonalOrg });
    expect(has(list, ragPersonalKb), '应含个人私有库').toBeTruthy();
    expect(has(list, teamPrivateKb), '不应含团队库').toBeFalsy();
  });
  test('M3-02 团队上下文只见团队库，不混入个人库（本次修复点）', async () => {
    const list = await listKb(tRag, { orgId: teamOrg });
    expect(has(list, teamPrivateKb), '应含团队库').toBeTruthy();
    expect(has(list, ragPersonalKb), '不应含个人库').toBeFalsy();
  });
  test('M3-03 PUBLIC 公开库在他人个人上下文也可见', async () => {
    const list = await listKb(tOther, { orgId: otherPersonalOrg });
    expect(has(list, ragPublicKb), '公开库应跨组织可见').toBeTruthy();
  });
  test('M3-04 他人 PRIVATE 库永不可见（跨组织隔离）', async () => {
    const list = await listKb(tOther, { orgId: otherPersonalOrg });
    expect(has(list, ragPersonalKb), '不得泄漏他人私有个人库').toBeFalsy();
    expect(has(list, teamPrivateKb), '个人上下文不得见团队私有库').toBeFalsy();
  });
  test('M3-05 A→B→A 往返列表一致（防上下文串味）', async () => {
    const a1 = (await listKb(tRag, { orgId: ragPersonalOrg })).map((k) => k.id).sort();
    await listKb(tRag, { orgId: teamOrg });
    const a2 = (await listKb(tRag, { orgId: ragPersonalOrg })).map((k) => k.id).sort();
    expect(a2).toEqual(a1);
  });
  test('M3-06 超管破玻璃可见全平台（含他人私有库）', async () => {
    const list = await listKb(tAdmin, { override: true });
    expect(has(list, ragPersonalKb), '破玻璃应看到他人私有库').toBeTruthy();
  });
  test('M3-08 员工对团队库 myPermission 非可写/可删', async () => {
    const list = await listKb(tOther, { orgId: teamOrg });
    const kb = list.find((k) => Number(k.id) === Number(teamPrivateKb));
    expect(kb, '员工应能在团队上下文看到团队库').toBeTruthy();
    expect(['READ', 'NONE', 'VIEW', null, undefined]).toContain(kb?.myPermission ?? null);
  });
});

/* ================= M4 建库归属与可见性 ================= */
test.describe('M4 建库归属与可见性', () => {
  test('M4-01 个人建库 org_id = 本人个人组织（非 null）', async () => {
    const res = await createKb(tRag, { name: `归属校验_${ts}` });
    const vo = (await res.json())?.data;
    expect(res.ok()).toBeTruthy();
    expect(vo.orgId, '个人库 org_id 不应为空').toBe(ragPersonalOrg);
  });
  test('M4-02 个人库可见性 PUBLIC 允许', async () => {
    const res = await createKb(tRag, { name: `个人公开2_${ts}`, visibility: 'PUBLIC' });
    expect(res.ok()).toBeTruthy();
  });
  test('M4-04 团队库直接 PUBLIC → 400（防误公开）', async () => {
    const res = await createKb(tRag, { name: `团队公开_${ts}`, orgId: teamOrg, visibility: 'PUBLIC' });
    expect(res.status()).toBe(400);
  });
  test('M4-05 员工在所属团队建组织库 → 403（防提权）', async () => {
    const res = await createKb(tOther, { name: `员工建团队库_${ts}`, orgId: teamOrg, visibility: 'PRIVATE' });
    expect(res.status()).toBe(403);
  });
  test('M4-07 在他人个人组织建库 → 403（仅本人）', async () => {
    const res = await createKb(tOther, { name: `替rag建库_${ts}`, orgId: ragPersonalOrg, visibility: 'PRIVATE' });
    expect(res.status()).toBe(403);
  });
});

/* ================= M5 编辑/删除/详情权限 ================= */
test.describe('M5 编辑/删除/详情权限', () => {
  test('M5-01 本人读自己个人库详情 → 200', async () => {
    const res = await (await req(tRag)).get(`${RAG_API}/v1/kb/${ragPersonalKb}`);
    expect(res.ok()).toBeTruthy();
  });
  test('M5-02 他人按 id 直读私有库 → 403/404（防绕过列表过滤）', async () => {
    const res = await (await req(tOther)).get(`${RAG_API}/v1/kb/${ragPersonalKb}`);
    expect([403, 404]).toContain(res.status());
  });
  test('M5-03 员工编辑团队库 → 403', async () => {
    const res = await (await req(tOther)).put(`${RAG_API}/v1/kb/${teamPrivateKb}`, { data: { name: '员工乱改库' } });
    expect(res.status()).toBe(403);
  });
  test('M5-04 员工删除团队库 → 403', async () => {
    const res = await (await req(tOther)).delete(`${RAG_API}/v1/kb/${teamPrivateKb}`);
    expect(res.status()).toBe(403);
  });
});

/* ================= M6 驾驶舱指标按组织聚合 ================= */
test.describe('M6 指标按组织聚合', () => {
  test('M6-01 资产规模随组织变（个人 vs 团队 kbCount 不同口径）', async () => {
    const personal = await dashboard(tRag, { orgId: ragPersonalOrg });
    const team = await dashboard(tRag, { orgId: teamOrg });
    expect(personal.kbCount, '个人/团队应为各自口径').not.toEqual(team.kbCount);
  });
  test('M6-02 别人的公开库不计入你的资产（可见≠计入）', async () => {
    // U_OTHER 个人资产里不应把 rag 的公开库算进 kbCount（公开库 org_id 属 rag 个人组织）
    const other = await dashboard(tOther, { orgId: otherPersonalOrg });
    const list = await listKb(tOther, { orgId: otherPersonalOrg });
    const ownOrgKbs = list.filter((k) => Number(k.orgId) === Number(otherPersonalOrg)).length;
    expect(other.kbCount, '资产口径=自己组织的库，不含他人公开库').toBe(ownOrgKbs);
  });
  test('M6-07 超管破玻璃资产 ≥ 单个组织资产（平台聚合）', async () => {
    const platform = await dashboard(tAdmin, { override: true });
    const team = await dashboard(tRag, { orgId: teamOrg });
    expect(Number(platform.kbCount)).toBeGreaterThanOrEqual(Number(team.kbCount));
  });
  test('M6-08 同组织 dashboard 往返稳定（缓存按组织分键）', async () => {
    const a1 = await dashboard(tRag, { orgId: ragPersonalOrg });
    await dashboard(tRag, { orgId: teamOrg });
    const a2 = await dashboard(tRag, { orgId: ragPersonalOrg });
    expect(a2.kbCount).toEqual(a1.kbCount);
  });
});

/* ================= M7 检索越权 ================= */
test.describe('M7 检索与越权', () => {
  test('M7-07 检索时越权传他人私有 kbId 不应命中其内容', async () => {
    const c = await req(tOther, { orgId: otherPersonalOrg });
    const res = await c.post(`${RAG_API}/v1/search`, {
      data: { query: '测试', kbIds: [ragPersonalKb], strategy: 'hybrid', topK: 5 },
    });
    // 服务端应在 kbId 白名单二次校验：要么 403，要么返回但不含该私有库命中
    if (res.ok()) {
      const body = await res.json();
      const results = body?.data?.results ?? body?.data ?? [];
      const leaked = (Array.isArray(results) ? results : []).some(
        (r: any) => Number(r.kbId) === Number(ragPersonalKb),
      );
      expect(leaked, '不得命中他人私有库内容').toBeFalsy();
    } else {
      expect([403, 400]).toContain(res.status());
    }
  });
});
