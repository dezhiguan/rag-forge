# 统一认证与权限 · Playwright 测试方案 V1

> 配套设计：`docs/unified-auth-redesign-V1.html`
> 覆盖范围：CareerMate × RAGForge × Auth Gateway 的注册 / 登录 / 凭证 / 资料 / 权限
> 文档分两部分：**A. 资深测试工程师视角的全量测试方案**；**B. 资深安全工程师视角的 10 个安全用例（可交付他人执行）**
> 版本 V1 · 2026-06-27

---

# Part A · 全量功能与权限测试方案（QA 视角）

## A0. 测试目标

1. 覆盖 V1 全部功能点：RAG 注册、多标识登录、凭证补全、本地资料、行级权限、菜单/按钮控权、`/me`、会话。
2. **多账号 + 权限矩阵**：不同主体看到不同数据、操作不同结果。
3. 主动猎 bug：竞态、唯一性边界、枚举泄露、前端隐藏但后端未拦、跨 audience 越权、分页计数泄露。
4. 双层验证：**UI（用户能不能看到/点到）** 与 **API（后端拦不拦得住）** 都要断言，二者结论必须一致。

## A1. 测试环境与基线数据（Fixtures）

### 被测服务（用环境变量参数化）

| 变量 | 含义 | 示例 |
|---|---|---|
| `RAG_WEB` | RAGForge 前端 | `https://rag.careermate.cn` |
| `RAG_API` | RAGForge 后端 | `${RAG_WEB}/api` |
| `CM_WEB` | CareerMate 前端 | `https://app.careermate.cn` |
| `GW_BASE` | Auth Gateway | `https://auth.careermate.cn` |
| `DEV_SMS_CODE` | dev 固定验证码 | `123456` |

### 预置账号（多账号矩阵的核心）

| 别名 | 来源 | 手机号 | 凭证状态 | 角色 | 用途 |
|---|---|---|---|---|---|
| **U_CM** | 仅 CareerMate 注册 | P1 | 无 username/email/password | 普通 | 验证"到 RAG 补全而非新建" |
| **U_RAG** | RAG 自助注册 | P2 | username+email+password 齐全 | 普通 | 主力普通用户 |
| **U_OTHER** | RAG 自助注册 | P3 | 齐全 | 普通 | 跨用户隔离的"别人" |
| **U_MERGED** | 由 U_CM 在 RAG 补全得到 | P1 | 补全后齐全 | 普通 | 合并后同一 `auth_users.id` |
| **ADMIN** | 平台账号 | P0 | 齐全 | 平台 ADMIN | 管理员对照组 |

### 预置知识库

| 别名 | owner | visibility | kb_type | 备注 |
|---|---|---|---|---|
| **KB_OWN** | U_RAG | private | normal | U_RAG 自有库（admin 全权） |
| **KB_OTHER** | U_OTHER | private | normal | 别人的私有库（U_RAG 不可见） |
| **KB_PUBLIC** | 平台 | public | normal | 公共库（普通用户只读） |
| **KB_SHARED** | U_OTHER | private | normal | 通过 `kb_acl` 授 U_RAG **read** |
| **KB_SYSTEM** | 平台 | private | SYSTEM | 系统库（任何人禁止，含 ADMIN） |

### Playwright 工程结构建议

```
tests/
  fixtures/
    accounts.ts        // 上述账号常量 + 登录辅助
    api.ts             // request 封装：带 token / 带 X-API-Key
    auth.ts            // 登录拿 token、写 storageState
  auth/                // A2 注册、A3 登录、A4 凭证、A5 资料
  authz/               // A6 菜单、A7 列表行级、A8 越权、A9 检索
  security/            // Part B 的 10 个 SEC 用例
playwright.config.ts   // projects: chromium；多 storageState 角色
```

**关键约定**：每个权限用例都要 **UI 断言 + 直连 API 断言** 双跑。UI 用 `page`，API 用独立 `request` context 注入对应主体的 `Authorization`，避免"前端藏了但接口裸奔"漏判。

---

## A2. 注册（含手机号合并）

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| TC-REG-01 全新注册 | 手机号 P_new 未注册 | RAG 注册页填 username/email/password，发短信、填 `DEV_SMS_CODE`，提交 | 注册成功，建新 `auth_users`，可立即登录 |
| TC-REG-02 手机号已存在→补全 | U_CM（P1，仅 CareerMate） | RAG 注册页用 **P1** + 短信验证，填 username/email/password | 提示"该手机号已注册，已关联并补全"；**不新建**账号；`auth_users.id` 与 U_CM 相同；CareerMate 仍可正常登录 |
| TC-REG-03 用户名重复 | username `wangxx` 已存在 | 再用 `wangxx` 注册 | 拒绝，提示用户名已被占用，无新账号 |
| TC-REG-04 邮箱重复 | email 已存在 | 用同邮箱注册 | 拒绝，提示邮箱已注册 |
| TC-REG-05 短信码错误 | — | 填错误验证码 | 拒绝，明确"验证码错误"，不建号 |
| TC-REG-06 短信码过期 | 验证码超 TTL | 用过期码 | 拒绝，提示已过期 |
| TC-REG-07 未验证手机直接提交 | — | 不发/不填短信码就提交 | 前端拦截 + 后端 400，必须短信验证 |
| TC-REG-08 弱密码/非法邮箱 | — | 密码 `123`、邮箱 `abc` | 校验失败，前后端一致拒绝 |
| TC-REG-09（猎 bug）大小写/空格 | email `A@B.com` 已注册 | 用 `a@b.com`、`wangxx `（尾空格）注册 | **必须视为同一标识**，拒绝创建重复账号（唯一性需归一化） |
| TC-REG-10（猎 bug）并发同手机号 | P_new 未注册 | 并发提交两次同手机号注册 | 仅 1 个账号成立，另一个被唯一约束拒绝，无重复 `auth_users` |

## A3. 登录（多标识）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-LOG-01 用户名登录 | U_RAG 用 username+password | 成功，`/me` 返回同一 userId |
| TC-LOG-02 邮箱登录 | 同账号用 email+password | 成功，解析到同一 `auth_users.id` |
| TC-LOG-03 手机号登录 | 同账号用 phone+password | 成功，同一 id |
| TC-LOG-04 手机验证码登录 | phone + `DEV_SMS_CODE` | 成功 |
| TC-LOG-05 错误密码 | 错误 password | 401，统一错误文案 |
| TC-LOG-06 仅手机号用户用密码登录 | U_CM（无密码） | 明确提示"未设置密码/请用验证码登录"，不报内部异常 |
| TC-LOG-07（猎 bug）错误提示一致性 | 不存在的账号 vs 存在但密码错 | 两者错误文案/状态码**不可区分**（防枚举），见 SEC-07 |
| TC-LOG-08 登出 | 登录后调 logout | 当前会话失效，受保护接口 401 |
| TC-LOG-09 退出所有设备 | 两端登录同账号，A 端 logout-all（校验密码） | 两端 token 全部失效 |
| TC-LOG-10 多会话并存 | 同账号在 A、B 两处先后登录 | **B 登录不挤掉 A**：A、B 两个 token 同时有效（当前为多会话策略）。⚠ 若未来改单点互斥，本用例断言需反转为"A 在 B 登录后变 401" |

**TC-LOG-10 Playwright 骨架：**

```ts
test('TC-LOG-10 多会话并存: B登录不挤掉A', async () => {
  const tA = await login(U_RAG.account, U_RAG.password);   // 浏览器A
  const tB = await login(U_RAG.account, U_RAG.password);   // 浏览器B
  const apiA = await asUser(tA);
  expect((await apiA.get(`${RAG_API}/v1/me`)).ok()).toBeTruthy(); // A 仍有效
  const apiB = await asUser(tB);
  expect((await apiB.get(`${RAG_API}/v1/me`)).ok()).toBeTruthy(); // B 也有效
});
```

## A4. 凭证补全（安全中心，全部代理网关）

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| TC-CRED-01 设置密码 | U_CM（无密码） | 安全中心设密码 → 退出 → 用密码登录 | 登录成功，证明**凭证落网关**生效 |
| TC-CRED-02 绑定邮箱 | U_RAG 邮箱未绑 | 绑定并验证 | 邮箱写入 `auth_users`，之后可邮箱登录 |
| TC-CRED-03 修改登录用户名 | — | 改为新唯一 username | 成功；旧 username 不再能登录，新可登录 |
| TC-CRED-04 用户名冲突 | 目标名已被占 | 改成已存在 username | 拒绝，唯一校验 |
| TC-CRED-05 换绑手机（短信） | — | 换新手机号，短信验证 | 成功，新号可登录；旧号根据策略失效 |
| TC-CRED-06 改密码后旧 token 失效 | 已登录持有 token T | 改密码 → 用 T 调受保护接口 | T 失效（`password.changed` 事件撤销），见 SEC-04 |
| TC-CRED-07（猎 bug）凭证不落本地 | — | RAG 安全中心改密码后，查 RAG 本地库 | RAG 本地**无密码字段**；用 RAG"本地伪造"的密码不能登录（只有网关校验生效） |

## A5. 本地资料（个人设置，RAG 本地）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-PROF-01 改显示名 | 个人设置改 displayName | RAG 头像区显示新名；**不调网关** |
| TC-PROF-02 资料 App 间独立 | RAG 改 displayName 后看 CareerMate | CareerMate 显示名不受影响 |
| TC-PROF-03 显示名兜底 | displayName 为空的用户登录 | 头像区显示脱敏（如 `用户_8888`/`138****8888`），**不出现 `user:2`/`tn_admin`** |
| TC-PROF-04（猎 bug）显示名 XSS | displayName 填 `<img src=x onerror=alert(1)>` | 存储型 XSS 不触发，正确转义，见 SEC-10 |

## A6. 前端控权：菜单显隐（多角色）

| 用例 | 角色 | 期望侧边栏 |
|---|---|---|
| TC-MENU-01 普通用户菜单 | U_RAG | 仅 驾驶舱 / 知识库管理 / 检索调试台 / 应答调试台；性能诊断、评测、质量看板、模型&成本、API网关**不渲染** |
| TC-MENU-02 管理员菜单 | ADMIN | 全部菜单可见，含 模型&成本、API网关、平台管理 |
| TC-MENU-03 disabled 而非隐藏 | U_RAG 打开 KB_PUBLIC | "编辑/删除"按钮可见但 **disabled** + tooltip；自有 KB_OWN 上可用 |
| TC-MENU-04（猎 bug）隐藏≠安全 | U_RAG | 菜单虽隐藏，**直连**隐藏页对应 API（成本中心/API Key）→ 必须 403，见 SEC-02 |

## A7. 查询接口行级权限（核心多账号隔离）

| 用例 | 角色 | 步骤 | 期望 |
|---|---|---|---|
| TC-LIST-01 普通用户 KB 列表 | U_RAG | `GET /api/v1/kb`（普通用户走行级过滤入口；**勿用** `/api/v1/knowledge-bases` 别名，它对 USER 一律 403） | 仅返回 KB_OWN + KB_PUBLIC + KB_SHARED；**不含** KB_OTHER、KB_SYSTEM |
| TC-LIST-02 管理员 KB 列表 | ADMIN | 同上 | 返回全部 **非 SYSTEM** 库；不含 KB_SYSTEM |
| TC-LIST-03 myPermission 正确 | U_RAG | 检查列表每行 | KB_OWN=`admin`，KB_PUBLIC=`read`，KB_SHARED=`read` |
| TC-LIST-04（猎 bug）分页计数不泄露 | U_RAG | 看分页 total | total 仅等于可见集合数，不暴露"有多少看不到的库" |
| TC-LIST-05 不同用户结果不同 | U_RAG vs U_OTHER | 各自请求同一接口 | 两者返回集合不相交（除 KB_PUBLIC） |

## A8. 详情 / 越权 / 资源保护

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-DET-01 越权读他人私库 | U_RAG `GET /knowledge-bases/{KB_OTHER}` | **403**，记 `kb_access_denied`，见 SEC-01 |
| TC-DET-02 越权读文档 | U_RAG 读 KB_OTHER 下文档 id | 403（文档级回退 KB 权限） |
| TC-DET-03 越权写 public 库 | U_RAG 对 KB_PUBLIC 发起写/删 | 403（public 仅只读），见 SEC-09 |
| TC-DET-04 SYSTEM 库保护 | ADMIN 与 U_RAG 读/写 KB_SYSTEM | 双双 403（含 ADMIN），见 SEC-08… |
| TC-DET-05（猎 bug）ACL 过期 | KB_SHARED 的 acl `expires_at` 已过 | U_RAG 不再可读，按过期失效 |

## A9. 检索 / 应答范围

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-SE-01 不传 kbIds | U_RAG `POST /search` 不带 kbIds | 自动限定为其可读集合（含 public），不触达 KB_OTHER |
| TC-SE-02 传越权 kbId | U_RAG search 指定 KB_OTHER | 该 id 被过滤，记 `kb_access_denied`，结果不含其内容 |
| TC-SE-03 应答配额 | U_RAG 连续触发应答 | 达配额后被限流（若已实现），不崩 |

## A10. /me 聚合与跨 App 一致性

| 用例 | 期望 |
|---|---|
| TC-ME-01 字段聚合 | `/me` 含 网关身份(username/phone/email) + 本地(displayName/avatar) + role + capabilities |
| TC-ME-02 capabilities 驱动菜单 | 菜单显隐与 `/me.capabilities` 完全一致 |
| TC-ME-03 同人同 id | U_MERGED 在 CareerMate 与 RAG 的 userId/`auth_users.id` 相同 |

## A11. 跨 audience / token 隔离（猎 bug 重点）

| 用例 | 期望 |
|---|---|
| TC-TOK-01 拿 CareerMate token 调 RAG | audience 不符 → 401/403，见 SEC-03 |
| TC-TOK-02 token-exchange 后调用 | CareerMate 经 token-exchange 换 `ragforge-api` token 调 RAG → 成功且仅限授权 KB |
| TC-TOK-03 伪造签名 token | 自签 JWT（错误私钥）→ 401，JWKS 验签失败 |

---

# Part B · 安全工程师视角的 10 个 Playwright 安全用例（可交付执行）

> 这 10 个用例聚焦**认证与权限的安全边界**，每个含：目标 / 威胁 / 前置 / 步骤 / 通过判定。
> 代码骨架基于 Playwright `@playwright/test`，多数走 **API 层**（`request`）以验证真实后端边界，必要时配合 UI。
> 执行者只需替换 `fixtures/accounts.ts` 中的真实账号与 base URL。

### 公共辅助（fixtures/api.ts）

```ts
import { request, APIRequestContext } from '@playwright/test';

export const RAG_API = process.env.RAG_API!;
export const GW_BASE = process.env.GW_BASE!;

// 用账号密码登录，返回 access token
export async function login(account: string, password: string): Promise<string> {
  const ctx = await request.newContext();
  const res = await ctx.post(`${RAG_API}/auth/login`, {
    form: { account, password },
  });
  if (!res.ok()) throw new Error(`login failed: ${res.status()}`);
  return (await res.json()).access_token ?? (await res.json()).data?.accessToken;
}

// 带 token 的 API context
export async function asUser(token: string): Promise<APIRequestContext> {
  return request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
}
```

---

### SEC-01 · 水平越权 / IDOR：读他人私有知识库

- **威胁**：普通用户通过猜/枚举 `kbId` 直接访问他人私库（Broken Object Level Authorization）。
- **前置**：U_RAG 已登录；KB_OTHER 属于 U_OTHER（私有）。
- **通过判定**：详情 **403**，且 KB_OTHER 不出现在 U_RAG 的列表中。
- ⚠ **路径要点**：普通用户必须打 `/api/v1/kb/**`（行级过滤入口）才是真正的对象级越权测试；若打 `/api/v1/knowledge-bases/**` 别名，会因"角色不匹配"返回 403 而**假通过**，掩盖 `KbAccessGuard` 是否真生效。

```ts
test('SEC-01 IDOR: 普通用户不能读他人私有KB', async () => {
  const token = await login(U_RAG.account, U_RAG.password);
  const api = await asUser(token);

  // 1) 列表里不应出现 KB_OTHER（普通用户入口 /v1/kb，行级过滤）
  const list = await (await api.get(`${RAG_API}/v1/kb`)).json();
  const ids = (list.data ?? list).map((k: any) => k.id);
  expect(ids).not.toContain(KB_OTHER.id);

  // 2) 直连详情必须 403（即使知道 id）——走 /v1/kb 才能验证对象级授权
  const detail = await api.get(`${RAG_API}/v1/kb/${KB_OTHER.id}`);
  expect(detail.status()).toBe(403);

  // 3) 其下文档同样 403
  const doc = await api.get(`${RAG_API}/v1/kb/${KB_OTHER.id}/documents`);
  expect([401, 403]).toContain(doc.status());
});
```

---

### SEC-02 · 垂直越权：普通用户直连管理员接口（绕过隐藏菜单）

- **威胁**：前端隐藏了 API网关/成本中心菜单，但后端接口未鉴权，普通用户直接打 API 即可越权。
- **前置**：U_RAG 已登录。
- **通过判定**：所有管理员专属接口对普通用户返回 **403**。

```ts
test('SEC-02 垂直越权: 普通用户打管理员接口必须403', async () => {
  const api = await asUser(await login(U_RAG.account, U_RAG.password));
  const adminOnly = [
    { m: 'get',  url: `${RAG_API}/v1/api-keys` },           // API Key 管理
    { m: 'post', url: `${RAG_API}/v1/api-keys` },           // 创建 Key
    { m: 'get',  url: `${RAG_API}/v1/model-center/cost` },  // 成本中心
    { m: 'post', url: `${RAG_API}/v1/maintenance/reindex` },// 维护
  ];
  for (const e of adminOnly) {
    const res = await (api as any)[e.m](e.url, { data: {} });
    expect(res.status(), `${e.m} ${e.url}`).toBe(403);
  }
});
```

---

### SEC-03 · JWT audience 混淆：跨服务 token 复用

- **威胁**：用 CareerMate 自身 audience 的 token 直接调 RAG 后台接口。
- **前置**：用 CareerMate 客户端登录拿到 `careermate-api` 的 access token。
- **通过判定**：RAG 拒绝（**401/403**），因 audience 不在 RAG 接受列表。

```ts
test('SEC-03 audience混淆: CareerMate token 不能调 RAG', async () => {
  // 取得 careermate-api 受众的 token（经 CareerMate 登录或网关 target_aud=careermate-api）
  const cmToken = await loginCareerMate(U_RAG.account, U_RAG.password);
  const api = await asUser(cmToken);
  const res = await api.get(`${RAG_API}/v1/knowledge-bases`);
  expect([401, 403]).toContain(res.status());
});
```

---

### SEC-04 · 会话撤销有效性：改密码后旧 token 立即失效

- **威胁**：改密码/登出后旧 access token 仍可用（撤销不生效）。
- **前置**：U_RAG 登录持有 token T。
- **通过判定**：改密码后用 T 调受保护接口 → **401**（`password.changed` 事件撤销生效）。

```ts
test('SEC-04 撤销生效: 改密码后旧token失效', async () => {
  const T = await login(U_RAG.account, U_RAG.password);
  const api = await asUser(T);
  expect((await api.get(`${RAG_API}/v1/me`)).ok()).toBeTruthy();

  // 改密码（经 RAG 安全中心代理到网关）
  const ok = await api.post(`${RAG_API}/auth/credential/set-password`, {
    data: { oldPassword: U_RAG.password, newPassword: U_RAG.password + 'X!' },
  });
  expect(ok.ok()).toBeTruthy();

  // 旧 token 必须失效（给撤销事件少量传播时间）
  await new Promise(r => setTimeout(r, 1500));
  const after = await api.get(`${RAG_API}/v1/me`);
  expect(after.status()).toBe(401);
});
```

---

### SEC-05 · 凭证落网关而非本地：本地无法伪造认证

- **威胁**：RAG 本地保存了密码/凭证，绕过网关即可认证（设计红线）。
- **前置**：U_CM（仅手机号，无密码）在 RAG 安全中心设置密码 PW。
- **通过判定**：设置后**经网关**用 PW 登录成功；且 RAG 本地数据层不持有可用于认证的密码（用错误的"本地态"无法登录）。

```ts
test('SEC-05 凭证落网关: 设密码后经网关登录成功', async () => {
  const t = await loginMobile(U_CM.phone, process.env.DEV_SMS_CODE!); // 手机码登录
  const api = await asUser(t);
  const PW = 'Str0ng#Pass1';
  const set = await api.post(`${RAG_API}/auth/credential/set-password`, { data: { newPassword: PW } });
  expect(set.ok()).toBeTruthy();

  // 经网关用新密码登录必须成功（证明落到 auth_users）
  const t2 = await login(U_CM.phone, PW);
  expect(t2).toBeTruthy();
  // 反向：RAG 本地若声称存了密码也不能独立认证——只有网关校验通过才发 token（由上一步隐式覆盖）
});
```

---

### SEC-06 · 唯一性归一化：大小写/空格账号接管

- **威胁**：`A@B.com` 与 `a@b.com`、`wangxx` 与 `wangxx ` 被当作不同标识，造成重复账号或接管。
- **前置**：email `user@x.com`、username `wangxx` 已存在。
- **通过判定**：注册/改名时大小写与首尾空格归一化，**冲突被拒**，不产生重复账号。

```ts
test('SEC-06 唯一性归一化: 大小写/空格不可绕过', async () => {
  const ctx = await request.newContext();
  for (const dup of ['USER@x.com', ' user@x.com ', 'WangXX', 'wangxx ']) {
    const res = await ctx.post(`${RAG_API}/auth/register`, {
      form: { email: dup.includes('@') ? dup : 'new@x.com',
              username: dup.includes('@') ? 'newname' : dup,
              password: 'Str0ng#1', phone: PHONE_NEW, smsCode: process.env.DEV_SMS_CODE! },
    });
    expect(res.status(), `dup=${dup}`).toBeGreaterThanOrEqual(400); // 必须被唯一约束拒绝
  }
});
```

---

### SEC-07 · 用户枚举：注册/登录/重置错误信息不泄露账号存在性

- **威胁**：通过错误文案/状态码/响应时间差异，枚举哪些手机号/邮箱已注册。
- **注意**：设计上"手机号已注册→提示去登录"是**有意行为**，安全工程师需验证其被**限定在已通过短信验证的前提下**，未验证时不得泄露存在性。
- **通过判定**：未完成短信验证时，对已存在与不存在的标识，**响应不可区分**；登录失败文案统一。

```ts
test('SEC-07 防枚举: 未验证态响应不可区分', async () => {
  const ctx = await request.newContext();
  const probe = async (account: string) =>
    ctx.post(`${RAG_API}/auth/login`, { form: { account, password: 'wrong-Pass1' } });

  const existing = await probe(U_RAG.email);
  const missing  = await probe('definitely-not-exist@x.com');
  expect(existing.status()).toBe(missing.status());           // 状态码一致
  expect(await existing.text()).toBe(await missing.text());   // 文案一致（不暴露"账号不存在"）
});
```

---

### SEC-08 · SYSTEM 知识库保护：任何角色不可触达

- **威胁**：`kb_type=SYSTEM` 被普通用户甚至 ADMIN 读写。
- **前置**：KB_SYSTEM 存在；分别用 U_RAG、ADMIN。
- **通过判定**：两种角色读/写 SYSTEM 库均 **403**；列表中均不出现。

```ts
test('SEC-08 SYSTEM库保护: 含ADMIN也禁止', async () => {
  for (const u of [U_RAG, ADMIN]) {
    const api = await asUser(await login(u.account, u.password));
    expect((await api.get(`${RAG_API}/v1/knowledge-bases/${KB_SYSTEM.id}`)).status()).toBe(403);
    expect((await api.post(`${RAG_API}/v1/knowledge-bases/${KB_SYSTEM.id}/documents`, { data: {} })).status()).toBe(403);
    const list = await (await api.get(`${RAG_API}/v1/knowledge-bases`)).json();
    expect((list.data ?? list).map((k: any) => k.id)).not.toContain(KB_SYSTEM.id);
  }
});
```

---

### SEC-09 · 公共库只读：普通用户不可写/删 public 库

- **威胁**：可读的 public 库被普通用户写入或删除（读写权限混淆）。
- **前置**：U_RAG 可读 KB_PUBLIC。
- **通过判定**：读 **200**；写/删/改可见性均 **403**。

```ts
test('SEC-09 public库只读: 写删必须403', async () => {
  const api = await asUser(await login(U_RAG.account, U_RAG.password));
  expect((await api.get(`${RAG_API}/v1/knowledge-bases/${KB_PUBLIC.id}`)).status()).toBe(200);

  const writes = [
    api.post(`${RAG_API}/v1/knowledge-bases/${KB_PUBLIC.id}/documents`, { data: { name: 'x' } }),
    api.delete(`${RAG_API}/v1/knowledge-bases/${KB_PUBLIC.id}`),
    api.put(`${RAG_API}/v1/knowledge-bases/${KB_PUBLIC.id}`, { data: { visibility: 'private' } }),
  ];
  for (const w of writes) expect((await w).status()).toBe(403);
});
```

---

### SEC-10 · 存储型 XSS：显示名注入

- **威胁**：displayName 存储恶意脚本，在他人/管理端列表渲染时执行（存储型 XSS）。
- **前置**：U_RAG 已登录，能改个人资料。
- **通过判定**：脚本被转义存储/输出；渲染页面**不弹窗**、无脚本执行。

```ts
test('SEC-10 存储型XSS: 显示名不可执行脚本', async ({ page }) => {
  const api = await asUser(await login(U_RAG.account, U_RAG.password));
  const payload = `<img src=x onerror="window.__xss=1">`;
  await api.put(`${RAG_API}/v1/profile`, { data: { displayName: payload } });

  let dialog = false;
  page.on('dialog', d => { dialog = true; d.dismiss(); });

  await loginViaUI(page, U_RAG);          // 触发头像/资料渲染
  await page.goto(`${process.env.RAG_WEB}/`);
  const xss = await page.evaluate(() => (window as any).__xss === 1);
  expect(xss).toBeFalsy();
  expect(dialog).toBeFalsy();
  // 断言输出被转义而非原样注入
  await expect(page.locator('text=onerror=')).toHaveCount(0);
});
```

---

## 附：执行与判定说明

- **优先级**：SEC-01/02/03/04/08 为阻断级（任一失败即视为权限/认证存在高危缺陷）。
- **环境**：建议在隔离的预发环境执行；`dev` 固定验证码、dev API Key 仅用于本地，**勿在生产用例中启用**。
- **结果留存**：每个用例保存请求/响应（脱敏）与截图，便于复现与回归。
- **回归门禁**：Part B 的 10 个用例建议纳入 CI 的认证/权限回归门禁，凭证或权限相关改动必须全绿方可合并。

---

# Part C · CareerMate 侧认证与权限测试（QA 视角）

> CareerMate 本期按现状不动（手机号注册登录一体），但其认证边界、用户数据隔离、审计与限流仍需回归覆盖。

## C0. 补充环境与账号

| 变量/别名 | 含义 | 示例 |
|---|---|---|
| `CM_API` | CareerMate 后端 | `${CM_WEB}/api` |
| **CM_U1** | CareerMate 手机用户（=U_CM，手机 P1） | 业务数据归属主体 |
| **CM_U2** | 另一 CareerMate 手机用户（手机 P3） | 跨用户隔离的"别人" |

## C1. 匿名 / 认证边界

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-CM-01 匿名白名单 | 不带 token 访问 `/api/health`、`/api/auth/login`、`/api/auth/sms/send`、`/api/auth/mobile/login`、`/api/auth/password-reset/*`、`/api/v1/events/**` | 放行（200/业务码），不要求登录 |
| TC-CM-02 受保护接口需登录 | 不带 token 访问任意业务 `/api/**`（非白名单） | 401，统一错误体 |
| TC-CM-03 仅 ACTIVE 用户可认证 | 用 `status!=ACTIVE` 用户的有效 JWT 调业务接口 | 拒绝（账号非激活） |
| TC-CM-04 401 自动登出 | 前端持过期/无效 token 触发业务请求 | 清本地会话并跳 `#/login` |
| TC-CM-05（猎 bug）`/api/**` 兜底 | 构造未显式声明的 `/api/x` 路径 | 命中 `.authenticated()` 兜底，未登录 401（不得意外 permitAll） |

## C2. 用户数据隔离（横向越权）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-CM-06 简历/任务归属 | CM_U1 创建简历/任务，记录 id | 仅 CM_U1 可读写 |
| TC-CM-07 越权读他人数据 | CM_U2 用自己 token 按 id 读 CM_U1 的简历/任务/JD匹配 | 403 或查不到（按 `user_id` 隔离，不可跨用户） |
| TC-CM-08 越权改他人数据 | CM_U2 改 CM_U1 资源 | 403，且 CM_U1 数据未被篡改 |
| TC-CM-09（猎 bug）id 枚举 | CM_U2 遍历相邻 id | 全部 403/空，不泄露他人数据存在性与内容 |

## C3. 短信限流与安全审计

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-CM-10 短信限流 | 对同手机号/同 IP 高频 `sms/send` | 触发 `SmsAuthRateLimiter`（手机号、IP、手机号+IP 维度）限流 |
| TC-CM-11 审计落库不含敏感 | 注册/登录/资料更新/重置后查 `security_audit_logs` | 有摘要记录，且**不含**密码、验证码、token、完整简历/JD |
| TC-CM-12 改密码撤销传播 | CM_U1 改密码 → 用旧 token 调业务 | 旧 token 失效（`password.changed` webhook 撤销） |

---

# Part D · MCP 权限测试（RAGForge MCP Server）

> RAGForge 暴露 `/mcp/**`、`/sse`。鉴权：**Bearer JWT 或 `X-API-Key`**；`ApiKeyInterceptor` 拦截 `/api/v1/search`、`/api/v1/internal/**`、`/mcp/**`、`/sse`，服务账号上下文以 `allowed_kb_ids` 限定可读写 KB。

## D0. 补充账号

| 别名 | 类型 | 范围 | 用途 |
|---|---|---|---|
| **SA_KEY_16** | API Key 服务账号 | `allowed_kb_ids=[KB_PUBLIC, 16]` | 合法 MCP 调用 |
| **SA_KEY_NONE** | API Key 服务账号 | `allowed_kb_ids=[]` | 无授权 KB 的边界 |
| **SA_KEY_DISABLED** | API Key | `enabled=false` | 停用校验 |
| `RAG_MCP` | MCP 入口 | `${RAG_WEB}/mcp` / `${RAG_WEB}/sse` | — |

## D1. MCP / SSE 认证边界

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-MCP-01 匿名调用拒绝 | 不带 token、不带 `X-API-Key` 访问 `/mcp/**`、`/sse` | 401（既无 JWT 也无 API Key） |
| TC-MCP-02 JWT 主体调用 | 带普通用户 JWT 调 MCP 检索工具 | 通过认证，但 KB 范围受其 `KbAccessGuard` 限制 |
| TC-MCP-03 有效 API Key 调用 | 带 `X-API-Key: SA_KEY_16` 调 MCP search | 安装 `SERVICE_ACCOUNT` 上下文，成功 |
| TC-MCP-04 停用 Key | `X-API-Key: SA_KEY_DISABLED` | 401（`enabled=false`） |
| TC-MCP-05 无效/伪造 Key | 随机 `X-API-Key` | 401 |
| TC-MCP-06 dev Key 隔离 | 用 `sk-ragforge-dev` | 仅 `dev` profile 生效；预发/生产 profile 必须 401 |

## D2. 服务账号 scope 与 allowed_kb_ids

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-MCP-07 限定可读 KB | SA_KEY_16 经 MCP 检索，不传 kbIds | 仅在 `[KB_PUBLIC,16]` 内召回，不触达 KB_OTHER |
| TC-MCP-08 越权 KB 过滤 | SA_KEY_16 指定 KB_OTHER 检索 | 该 id 被 `filterReadable` 过滤，记 `kb_access_denied`，结果不含其内容 |
| TC-MCP-09 无授权 KB | SA_KEY_NONE 检索 | 可读集合为空，返回空且不报内部异常 |
| TC-MCP-10 SYSTEM 不可达 | 服务账号尝试 KB_SYSTEM | 403/过滤，服务账号同样禁止 SYSTEM |

## D3. 限流

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-MCP-11 分钟级限流 | 对 SA_KEY_16 在一分钟内超 `rate_limit` 次调用 | 超额返回限流（Redis key `ragforge:ratelimit:`） |
| TC-MCP-12 限流 fail-open | 模拟 Redis 不可用时调用 | 放行业务并记 warning（可用性优先，需在用例标注此为有意权衡） |

---

# Part E · CareerMate → RAGForge 的 MCP 调用权限（跨服务）

> CareerMate 作为 MCP Client 调 RAGForge。当前路径：`RagForgeClient` 读当前请求 Bearer，经网关 `oauth/token-exchange` 换 `ragforge-api` / `rag:search` 的短 TTL token，再调 RAGForge。Agent 工具侧有 `AgentToolPermission` / `AgentToolRiskLevel`，高风险写入经 pending-action / 工具卡片承接。

## E1. token-exchange 受众与范围

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-X-01 正确换票 | CM_U1 在 CareerMate 触发需检索的 Agent 动作 | 换得 `aud=ragforge-api`、`scope=rag:search` 的 token，TTL 短（默认 600s），有缓存（默认 300s） |
| TC-X-02 不复用自身 token | 抓取 RAGForge 收到的 token | audience 不是 `careermate-api`（不直接复用面向自身的 token） |
| TC-X-03 过期换票失效 | 使用超 TTL 的 exchange token 调 RAGForge | 401，需重新换票 |
| TC-X-04 范围限定 KB | 换票后 token 的 `rag_readable_kb_ids` | 仅授权 KB（如 `[16]`），与 CareerMate Agent 应得范围一致 |

## E2. MCP 工具调用仅触达授权 KB

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-X-05 授权 KB 检索 | CareerMate Agent 检索 KB 16 | 成功返回该 KB 内容 |
| TC-X-06 越权 KB 被拒 | 让 Agent 请求 KB 17（未授权） | RAGForge `filterReadable` 过滤，记 `kb_access_denied`，结果不含 KB17 |
| TC-X-07 跨租户隔离 | CareerMate（tenant=careermate）token 触达他租户 KB | 被租户/ACL 隔离拒绝 |

## E3. Agent 工具权限与风险（CareerMate 侧策略执行）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-X-08 工具权限声明 | 检查注册工具的 `AgentToolPermission`/`RiskLevel` | 与工具实际行为一致（读=READ_USER_DATA，写=WRITE_USER_DATA 等） |
| TC-X-09 高风险写需确认 | 触发 HIGH 风险写入工具（如生成/覆盖简历） | 经 **pending-action / 工具卡片**承接，不自动直接落库 |
| TC-X-10 业务仍按当前用户校验 | Agent 调用写工具时 | 业务服务仍以当前用户上下文与资源归属校验，Agent 不能越权写他人数据 |

## E4. 委托链审计（规划字段验证）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-X-11 调用主体可追溯 | CareerMate Agent 经 MCP 检索后查 RAGForge `retrieval_logs`/`answer_logs` | 记录 `principal_id`（如 `service-account:careermate-agent` 或 `api-key:{id}`）、`tenant_id`、`kb_ids`、`trace_id` |
| TC-X-12（规划）委托用户落地 | 委托场景下 | `delegated_user_id`/`consent_id` 若已标准化，应从 token claims 落到检索/应答日志（未落地则标记为 V2 待办） |

---

# Part B 扩展 · MCP / 跨服务安全用例（SEC-11 ~ SEC-14）

> 与前 10 个同等交付标准；聚焦 MCP 入口与跨服务调用的安全边界。

### SEC-11 · MCP 匿名访问拒绝

- **威胁**：`/mcp`、`/sse` 未鉴权即可被外部直接调用检索内部 KB。
- **通过判定**：无 JWT 且无 `X-API-Key` 一律 **401**。

```ts
test('SEC-11 MCP匿名访问拒绝', async () => {
  const ctx = await request.newContext();
  for (const url of [`${process.env.RAG_WEB}/mcp`, `${process.env.RAG_WEB}/sse`]) {
    const res = await ctx.post(url, { data: {} });
    expect([401, 403]).toContain(res.status());
  }
});
```

### SEC-12 · 服务账号越权 KB（allowed_kb_ids 强隔离）

- **威胁**：API Key 服务账号检索其 `allowed_kb_ids` 之外的 KB。
- **通过判定**：越权 KB 被过滤，结果绝不含其内容，并记 `kb_access_denied`。

```ts
test('SEC-12 服务账号越权KB被过滤', async () => {
  const ctx = await request.newContext({ extraHTTPHeaders: { 'X-API-Key': SA_KEY_16.key } });
  const res = await ctx.post(`${RAG_API}/v1/search`, {
    data: { query: 'x', kbIds: [KB_OTHER.id] },   // 不在 allowed_kb_ids
  });
  // 要么 403，要么 200 但结果为空且不含 KB_OTHER 任何片段
  if (res.ok()) {
    const body = await res.json();
    const hits = JSON.stringify(body);
    expect(hits).not.toContain(KB_OTHER.marker); // KB_OTHER 内独有标记串
  } else {
    expect(res.status()).toBe(403);
  }
});
```

### SEC-13 · token-exchange 范围越权 / 受众混淆

- **威胁**：CareerMate 直接拿自身 `careermate-api` token 调 RAGForge，或换票后越范围访问。
- **通过判定**：未换票的 careermate token 被 RAGForge 拒；换票 token 仅限授权 KB。

```ts
test('SEC-13 跨服务受众与范围隔离', async () => {
  // 1) 未换票：careermate-api 受众 token 直调 RAGForge → 拒绝
  const cmTok = await loginCareerMate(CM_U1.phone, process.env.DEV_SMS_CODE!);
  const direct = await (await asUser(cmTok)).get(`${RAG_API}/v1/search?query=x`);
  expect([401, 403]).toContain(direct.status());

  // 2) 换票 token 越授权 KB → 过滤/拒绝
  const xTok = await tokenExchange(cmTok, 'ragforge-api', 'rag:search'); // 仅含 KB 16
  const over = await (await asUser(xTok)).post(`${RAG_API}/v1/search`, { data: { query: 'x', kbIds: [17] } });
  if (over.ok()) expect(JSON.stringify(await over.json())).not.toContain('kb17-marker');
  else expect(over.status()).toBe(403);
});
```

### SEC-14 · Agent 高风险写不可自动越权落库

- **威胁**：Agent 工具绕过 pending-action 直接执行高风险写，或写入他人数据。
- **通过判定**：HIGH 风险写需经确认承接；且即便确认，也只能写当前用户自己的资源。

```ts
test('SEC-14 Agent高风险写需确认且不可越权', async ({ page }) => {
  await loginCareerMateUI(page, CM_U1);
  // 触发高风险写工具（如覆盖简历）
  await triggerHighRiskAgentTool(page, 'generate_resume_from_jd');
  // 必须出现待确认的工具卡片 / pending-action，而非已直接落库
  await expect(page.locator('[data-test=pending-action]')).toBeVisible();
  // 后端断言：未确认前目标资源未被写入
  const api = await asUser(await loginCareerMate(CM_U1.phone, process.env.DEV_SMS_CODE!));
  const before = await api.get(`${CM_API}/v1/resumes/latest`);
  expect((await before.json()).data?.autoWritten).toBeFalsy();
  // 且任何情况下不能指定他人 userId 落库（越权写）——见 TC-X-10
});
```

---

## 附：新增部分的执行说明

- **阻断级补充**：SEC-11、SEC-12、SEC-13 为阻断级（MCP 暴露面与跨服务越权属高危）。
- **CareerMate 侧**：本期不改 CareerMate 功能，但 Part C 用例纳入回归，确保统一认证改造未破坏其既有隔离与审计。
- **MCP 标记串**：为可靠断言"越权 KB 内容未泄露"，各 KB 预置一段**独有标记文本**（如 `KB_OTHER.marker`），检索结果中出现即判定泄露。
- **profile 依赖**：Part E 的 Agent/委托用例需要后端先落地 token-exchange 与 pending-action；未落地项标记为 V2，先以 `test.fixme` 占位。

---

# Part F · V1.1 增量回归（针对近期改动 + 你点名的薄弱项）

> 本章补充：① **忘记密码/密码重置**（原方案缺失）；② **登出吊销**（已修复，强化为多断言）；③ **登录发码预校验**（新功能）；④ **中文用户名 / 确认密码**（新功能）；⑤ **权限与 A/B 数据隔离 / 搜索隔离**的补强与既有用例勘误。
> 版本 V1.1 · 2026-06-27

## F0. 既有用例勘误（路由变更导致，务必同步修正）

> 普通用户的 KB 入口是 **`/api/v1/kb/**`**（行级过滤 + 含 USER 角色）；**`/api/v1/knowledge-bases/**` 是仅管理角色的别名**（走未过滤 `listAll`，对 USER 一律 403）。凡"用普通用户验证对象级越权/行级过滤"的用例，**必须打 `/api/v1/kb`**，否则会因角色拦截 403 而**假通过**。

| 受影响用例 | 原路径 | 应改为 |
|---|---|---|
| TC-LIST-01（已就地改） | `/api/v1/knowledge-bases` | `/api/v1/kb` |
| SEC-01（已就地改） | `/v1/knowledge-bases/{id}` | `/v1/kb/{id}` |
| TC-DET-01/02/03、TC-SE-02 | `/knowledge-bases/{id}` 等 | 普通用户主体统一改 `/api/v1/kb/{id}`（ADMIN 对照仍可用别名）|
| SEC-08/09 | `/v1/knowledge-bases/{id}` | 普通用户分支改 `/api/v1/kb/{id}`；ADMIN 分支保留别名以验证"连管理员也禁 SYSTEM" |

## F1. 忘记密码 / 密码重置（原方案缺失，补全）

> 流程：RAG `/auth/reset` 页 → `POST /api/auth/password/reset/init {account, phone}` → 短信验证 `/verify {account, phone, code}` 拿 `reset_ticket` → `/confirm {reset_ticket, newPassword}` 落网关并 bump session_version。

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-PWD-01 正常重置闭环 | U_RAG 走 init→短信 `DEV_SMS_CODE`→verify→confirm 设新密码 | 成功；**旧密码登录 401、新密码登录 200**（证明凭证落网关并更新） |
| TC-PWD-02 重置需 ragforge 准入 | 仅 CareerMate 成员（无 ragforge membership）经 RAG 重置 | 拒绝（`PasswordResetService` 的 membership 门槛），不泄露内部异常 |
| TC-PWD-03 重置后旧 token 失效 | 重置前持有 token T，完成重置后用 T 调 `/api/v1/me` | **401**（session_version bump / `password.changed` 撤销传播），与 SEC-04 同机制 |
| TC-PWD-04 错误/过期短信码 | verify 用错误码或超 TTL 码 | 拒绝，明确"验证码错误/已过期"，不发 ticket |
| TC-PWD-05 重置限流 | 同账号/同手机号高频 init/verify | 触发 `PASSWORD_RESET_LOCKED`，提示稍后再试 |
| TC-PWD-06 ticket 一次性 & 绑定 | 用已消费的 `reset_ticket` 再次 confirm；或拿 A 的 ticket 给 B 用 | 双双拒绝（ticket 一次性、与账号绑定，不可复用/跨账号） |
| TC-PWD-07（猎 bug）重置防枚举 | 对**不存在**账号 init vs 存在账号 init | 响应状态码/文案**不可区分**，未通过短信验证前不暴露账号存在性（呼应 SEC-07） |

## F2. 登录发码预校验（新功能：未注册号不白发短信）

> 网关 `sms/send` 在 `scene=login && app=ragforge` 时先校验 membership：未注册返回 **409 `SMS_LOGIN_NOT_REGISTERED`** 且不发短信。

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-SMS-01 未注册号登录发码 | RAG 登录页用未注册手机号点"获取验证码" | **409 `SMS_LOGIN_NOT_REGISTERED`**，不下发短信；前端提示"请先点击立即注册" |
| TC-SMS-02 已注册号登录发码 | 已注册号点获取验证码 | 200，正常下发 |
| TC-SMS-03 注册场景不预校验 | 未注册号 `scene=register` 发码 | 200（否则无法注册），不被准入拦截 |
| TC-SMS-04 CareerMate 不受影响 | `scene=login` 不带 `app`（或 `app=careermate`）的未注册号 | 200 正常发码（登录注册一体语义保留） |
| TC-SMS-05（安全权衡）枚举被限流兜住 | 对大量手机号高频探测登录发码 | 单点可区分"是否注册"属**有意权衡**，但高频探测须触发 `SmsAuthRateLimiter` 限流（用例需标注此权衡） |

## F3. 中文用户名（新功能）

> 用户名正则放宽为 `^[A-Za-z0-9_一-龥]{2,32}$`（注册 + 安全中心改名一致）。

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-UN-01 中文名注册+登录 | 注册 username=`张三丰`，完成短信验证 | 注册成功；**用 `张三丰`+密码可登录**，`/me.username` 回显中文 |
| TC-UN-02 两字中文名 | username=`李雷`（2 字） | 通过（下限为 2） |
| TC-UN-03 非法字符拒绝 | username 含空格 / emoji / `<script>` / 长度>32 | 前后端一致 400 `USERNAME_FORMAT_INVALID` |
| TC-UN-04 安全中心改中文名 | 已登录用户改名为中文唯一名 | 成功；旧名不可登录、新名可登录 |
| TC-UN-05 中文名唯一性 | 用已存在中文名注册/改名 | 409 `USERNAME_TAKEN`，含首尾空格归一化后仍判重 |

## F4. 确认密码（新增前端校验）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-CP-01 两次不一致 | 注册页密码与确认密码不同 | 前端拦截"两次输入的密码不一致"，**不发请求** |
| TC-CP-02 一致正常提交 | 两次一致 | 正常进入注册请求 |
| TC-CP-03（猎 bug）确认密码非授权依据 | 直连 `POST /api/auth/register` 不带 confirmPassword | 后端不依赖确认密码字段，仍按密码强度校验（确认密码仅前端体验，不构成后端校验项） |

## F5. 登出吊销（已修复，强化多断言 + 粒度隔离）

> 修复点：登出事件携带 access token `jti`；`session.revoked` 带 `user_id`（登出全部）时按 user 级吊销；并修复 `event_outbox.markDelivered` 绑 `Instant` 致登出事务回滚的连带缺陷。

| 用例 | 步骤 | 期望（每条都要断言） |
|---|---|---|
| TC-LOG-08（强化）单会话登出 | 登录持 T → `POST /api/auth/logout` | (a) 接口 **200**（非 500）；(b) 同 T 调 `/me` → **401**；(c) 网关 `auth_sessions.revoked_at` 已写入；(d) `event_outbox` 该事件 `status=DELIVERED`；(e) RAG Redis `revoked:jti:{T.jti}` 命中 |
| TC-LOG-08b 单会话只杀当前会话 | 同用户两处登录 T1/T2，仅登出 T1 | T1→401，**T2 仍 200**（jti 粒度，不误伤其它会话） |
| TC-LOG-09（强化）退出所有设备 | T1/T2 两会话，T1 发起 logout-all（校验密码） | T1、T2 **均 401**；Redis `revoked:user:{uid}`（user-revoked-after）命中 |
| TC-LOG-11（猎 bug）投递失败不回滚登出 | 临时停掉 RAG 后端再登出 | 登出仍 **200** 且网关会话被撤销（`markFailed` 不抛、不回滚）；令牌失效靠下次投递/会话校验兜底——标注为可用性权衡 |
| TC-LOG-12 改密走同机制 | 改密后查 `event_outbox` + Redis | `user.password.changed` 投递 DELIVERED，user 级吊销键命中，旧 token 全部 401 |

## F6. 权限生效 & A/B 数据隔离 & 搜索隔离（你点名的核心补强）

> 重点回答："权限是否真生效""A 的数据会不会被 B 看见""搜索有没有按用户隔离"。**每条均双跑：A 主体应成功/可见，B 主体应 403/不可见/不串内容。**

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-AUTHZ-01 USER 可检索（修复回归） | U_RAG `POST /api/v1/search`（带 `search:run`） | **200**（修复前为 403）；结果仅来自其可读 KB |
| TC-AUTHZ-02 别名仅管理角色 | U_RAG `GET /api/v1/knowledge-bases` vs `/api/v1/kb` | 别名 **403**（设计预期）；`/api/v1/kb` **200** |
| TC-AUTHZ-03 capability 与后端一致 | 对 U_RAG 的 `/me.capabilities` 中**没有**的能力对应接口逐一直连 | 全部 403，前端隐藏与后端拦截结论一致（呼应 SEC-02） |
| TC-ISO-01 资料隔离 | A 改 displayName 后，查 B 的 `/me` 与头像 | B 完全不受影响；B 任何接口都拿不到 A 的 displayName/邮箱/手机号 |
| TC-ISO-02 文档隔离 | B `GET /api/v1/kb/{A_私库}/documents` 及其下文档详情 | **403**（文档级回退 KB 权限），B 看不到 A 私库任何文档 |
| TC-ISO-03 搜索跨用户不串内容 | A、B 各建私有库，各自放入**独有标记串** `A.marker`/`B.marker`；A、B **不传 kbIds** 搜同一通用 query | A 结果含 `A.marker`、**绝不含** `B.marker`；B 反之（搜索按 `allReadableKbIds` 行级隔离） |
| TC-ISO-04 越权指定他库检索 | A `POST /api/v1/search {kbIds:[B_私库]}` | 该 id 被 `filterReadable` 过滤，记 `kb_access_denied`，结果不含 `B.marker` |
| TC-ISO-05 建库归属正确 | U_RAG 建库 | owner=自己、`myPermission=admin`、`visibility=private`；**B 列表与详情均不可见**该库 |
| TC-ISO-06 公共库读写边界 | A 读 KB_PUBLIC 200；A 对其写/删/改可见性 | 写删改一律 **403**（公共库只读，呼应 SEC-09） |
| TC-ISO-07（猎 bug）分页计数不泄露 | A 的 `/api/v1/kb` 分页 total | 仅等于 A 可见集合数，不暴露存在多少不可见库（呼应 TC-LIST-04） |

## F7. 优先级与门禁补充

- **阻断级新增**：TC-PWD-01/03、TC-LOG-08/08b/09、TC-ISO-02/03 为阻断级（密码重置闭环、登出吊销、A/B 数据与搜索隔离任一失败即高危）。
- **回归门禁**：F5（登出吊销）+ F6（隔离）建议与 Part B 一并纳入 CI 认证/权限门禁；凭证、会话、KB 权限相关改动必须全绿方可合并。
- **标记串约定**：F6 复用 Part B 的"各 KB 独有标记串"约定，搜索结果出现对方标记串即判定隔离失效。

---

# Part G · V1.2 增量：近期新增功能 + 超级管理员破玻璃权限（Playwright，可交付 codex 执行）

> 本章针对近期改动新增 **20 个用例**：**G1 新增功能（10）** + **G2 超级管理员 / 破玻璃（10）**。
> 版本 V1.2 · 2026-06-28

## G0. 关键约定与公共辅助

- **破玻璃（break-glass）**：平台 ADMIN 默认按普通用户口径（自有 + public + acl），**不读他人私有库**；仅当请求显式携带头 `X-Admin-Override: true`（可加 `X-Admin-Override-Reason: <原因>`）时才提权到"全部非 SYSTEM 库"，且**每次写审计**：日志 `AUDIT admin_kb_break_glass adminUserId=.. reason=.. traceId=..` + 数据表 `admin_access_audit(action='kb_break_glass')`。**SYSTEM 库（kb_type=SYSTEM）即使破玻璃也禁**。
- **补充账号/资源**（复用 Part A 命名）：`ADMIN`（平台超管）、`U_RAG`/`U_OTHER`（两个不同手机号的普通用户）、`KB_OWN`（U_RAG 私有）、`KB_OTHER`（U_OTHER 私有）、`KB_PUBLIC`（public）、`KB_SYSTEM`（kb_type=SYSTEM）。
- **前端入口**：普通用户 KB 走 `/api/v1/kb`（行级过滤）；`/api/v1/knowledge-bases` 是仅管理角色的别名。

```ts
// fixtures：带破玻璃头的 ADMIN context
export async function asAdminBreakGlass(token: string, reason = 'qa-break-glass') {
  return request.newContext({ extraHTTPHeaders: {
    Authorization: `Bearer ${token}`,
    'X-Admin-Override': 'true',
    'X-Admin-Override-Reason': reason,
  }});
}
// 断言审计落库（需后端 DB 或一个只读审计查询端点；无端点时退化为校验后端日志）
```

## G1. 近期新增功能（10）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-NEW-01 建库默认私有归属 | U_RAG `POST /api/v1/kb` 建库 | 返回 `visibility=PRIVATE`、`ownerUserId=U_RAG`、`myPermission=admin`；U_OTHER 列表/详情均不可见该库 |
| TC-NEW-02 公共库前端只读收口（UI） | U_RAG 打开知识库管理 | 公共库行**不渲染**"编辑/删除"，显示灰色「只读」标签；自有库行有"编辑/删除" |
| TC-NEW-03 上传目标仅可写库（UI） | U_RAG 看上传下拉 | 只列其**可写**库（不含 public）；无可写库时隐藏上传区并提示"先创建知识库" |
| TC-NEW-04 SEC-09 直连上传拦截 | U_RAG 对 KB_PUBLIC 直连 `POST /api/v1/documents/register`、`POST /api/v1/uploads/presign` | **均 403**（非 500）；与 DELETE/PUT 同口径 |
| TC-NEW-05 显示名兜底（API 层） | U_RAG 登录响应 + `GET /api/v1/me` 的 `displayName` | **不匹配** `^user:\d+$`；为脱敏手机号（`138****0934`）或 `用户_{id}`，不暴露内部标识 |
| TC-NEW-06 检索次数按本人统计 | U_RAG 连发 N 次 `/api/v1/search`，再查 `/api/v1/metrics/dashboard` | "今日检索请求"= N（仅本人）；U_OTHER 的 dashboard 不受影响（其计数不含 U_RAG 的检索） |
| TC-NEW-07 仪表盘按用户隔离 | U_RAG `GET /api/v1/metrics/dashboard` | 知识库数 = **自有库数**（非全平台）；"最近操作"仅含自有库文档动态 + 本人检索，**不含**他人操作/评测 |
| TC-NEW-08 改密吊销（SEC-04 复测） | U_RAG 登录持 T → `POST /api/auth/credential/set-password` | 旧 T 调 `/api/v1/me` → **401**；`revoked:user:{uid}` 命中；新密码可登 |
| TC-NEW-09 登录发码预校验 | 未注册手机号在 RAG 登录页发码（`scene=login` 经 RAG 代理带 `app=ragforge`） | **409 `SMS_LOGIN_NOT_REGISTERED`**，不下发；`scene=register` 或不带 app 的 CareerMate 路径不受影响 |
| TC-NEW-10 文案去"企业级" | 登录页文案、侧边栏、浏览器 `<title>` | 均**不含**"企业级 RAG 知识引擎"；`<title>` 为 `RAGForge · 知识检索引擎` |

## G2. 超级管理员 / 破玻璃权限（10）

| 用例 | 步骤 | 期望 |
|---|---|---|
| TC-ADM-01 默认不见他人私库（列表） | ADMIN `GET /api/v1/kb`（**不带**破玻璃头） | 仅自有 + public，**不含** KB_OTHER |
| TC-ADM-02 默认不可读他人私库（详情） | ADMIN `GET /api/v1/kb/{KB_OTHER}`（无头） | **403** |
| TC-ADM-03 破玻璃可见全部 | ADMIN 带 `X-Admin-Override` 调列表/详情 | 列表**含** KB_OTHER；`GET /api/v1/kb/{KB_OTHER}` → **200** |
| TC-ADM-04 破玻璃必留审计 | ADMIN 每次带破玻璃头请求 | 后端日志出现 `AUDIT admin_kb_break_glass adminUserId={ADMIN} reason=..`；`admin_access_audit` 表新增 1 行（`action='kb_break_glass'`、含 trace_id） |
| TC-ADM-05 SYSTEM 始终禁 | ADMIN **即使破玻璃**，读/写 `/api/v1/kb/{KB_SYSTEM}` | **403**；列表中也不出现 KB_SYSTEM |
| TC-ADM-06 默认不可写他人私库 | ADMIN 无头对 KB_OTHER：`PUT` 改配置 / 删除 / 上传文档 | **均 403**；带破玻璃头后可写（且每次留审计） |
| TC-ADM-07 检索范围随破玻璃 | ADMIN 不传 kbIds 调 `/api/v1/search`，对比无头 vs 破玻璃头 | 无头 → 仅自有 + public；破玻璃 → 含全部非 SYSTEM（结果可触达 KB_OTHER 内容） |
| TC-ADM-08 仪表盘随破玻璃切换 | ADMIN `GET /api/v1/metrics/dashboard` 无头 vs 破玻璃头 | 无头 = **自有范围**计数；破玻璃 = **全平台**计数（缓存按主体分键 `U:{uid}` / `ADMIN`，两者不串） |
| TC-ADM-09 提权仅本请求生效 | ADMIN 先带破玻璃头请求一次，**紧接着不带头**再请求列表 | 第二次又回到默认（不见 KB_OTHER）——提权不跨请求泄漏（请求级 ThreadLocal 清除） |
| TC-ADM-10 非管理员伪造头无效 | U_RAG（普通用户）带 `X-Admin-Override: true` 调 `/api/v1/kb` | 头被忽略，仍按普通用户口径（不提权、不写审计） |

**关键骨架（破玻璃 + 分请求隔离 + 审计）：**

```ts
test('TC-ADM-01/03/09 破玻璃前后可见性 + 仅本请求生效', async () => {
  const adminTok = await login(ADMIN.account, ADMIN.password);

  // 默认：看不到他人私库
  const normal = await asUser(adminTok);
  let ids = idsOf(await (await normal.get(`${RAG_API}/v1/kb`)).json());
  expect(ids).not.toContain(KB_OTHER.id);
  expect((await normal.get(`${RAG_API}/v1/kb/${KB_OTHER.id}`)).status()).toBe(403);

  // 破玻璃：看得到
  const bg = await asAdminBreakGlass(adminTok, 'qa-TC-ADM-03');
  ids = idsOf(await (await bg.get(`${RAG_API}/v1/kb`)).json());
  expect(ids).toContain(KB_OTHER.id);
  expect((await bg.get(`${RAG_API}/v1/kb/${KB_OTHER.id}`)).status()).toBe(200);

  // 紧接着不带头：又回到默认（提权不跨请求）
  ids = idsOf(await (await normal.get(`${RAG_API}/v1/kb`)).json());
  expect(ids).not.toContain(KB_OTHER.id);
});

test('TC-ADM-05 SYSTEM 即使破玻璃也禁', async () => {
  const bg = await asAdminBreakGlass(await login(ADMIN.account, ADMIN.password));
  expect((await bg.get(`${RAG_API}/v1/kb/${KB_SYSTEM.id}`)).status()).toBe(403);
  const ids = idsOf(await (await bg.get(`${RAG_API}/v1/kb`)).json());
  expect(ids).not.toContain(KB_SYSTEM.id);
});
```

## G3. 优先级与门禁

- **阻断级**：TC-ADM-01/02/05/09/10（管理员默认隔离 + SYSTEM 禁 + 提权不泄漏 + 伪造头无效）、TC-NEW-04/08（上传 403、改密吊销）——任一失败即为权限/认证高危。
- **审计可观测性**：TC-ADM-04 若无只读审计查询端点，退化为校验后端日志中的 `AUDIT admin_kb_break_glass` 与 `admin_access_audit` 表行数（直连 DB）。
- 建议 G1+G2 一并纳入 CI 认证/权限回归门禁；KbAccessGuard、MetricsService、凭证/会话相关改动必须全绿方可合并。

## G4. 本轮执行勘误（区分"产品缺陷"与"测试期望/数据"）

> 一轮回归后确认：部分"未通过"并非产品缺陷，而是测试 token/数据/期望问题。以下为修正后的判定口径。

- **SEC-04 / TC-NEW-08 改密吊销**：✅ 产品**已修复并复测**（全新账号：改密后旧 token 调 `/me` 立即 **401**、`revoked:user:{uid}` 命中）。此前个别账号"仍 200"是**测试时服务未重启到修复**或**复用了缓存的旧 token**。harness 须在登出/改密用例后**清 token 缓存**再断言。
- **TC-MENU-04 隐藏菜单 API**：✅ 用**有效** USER token 打 `/api/v1/admin/api-keys` = **403**（SecurityConfig 已配 accessDeniedHandler）。返回 **401 的前提是 token 失效/缺失**（走 401 入口）——属测试 token 问题，不是 401/403 口径 bug。期望维持 403，但前置须用有效非管理员 token。
- **TC-CM-01 CareerMate 匿名白名单**：`/api/v1/events/**` 在认证白名单（**匿名可达**），但 webhook handler **强制 HMAC 验签**，无签名返回 **401 invalid signature 是正确的**。用例期望应改为：「匿名可达 handler，但缺签名 → 401」，而非"放行"。
- **TC-DET-01 / SEC-01 / TC-LIST-01·05 / TC-ISO-02·04 双普通用户隔离**：需要**两个不同手机号**注册的普通用户。`15813320829` 与 `qa_u_rag` 因**手机号为关联键**合并到同一 `auth_users.id=5`，无法构成 A/B。fixtures 须改为**两个独立手机号**各自注册的 U_RAG / U_OTHER。
- **TC-CM-04 无效 token 自动登出不彻底**：✅ 产品**已修复**（CareerMate `router.beforeEach` 未认证跳登录时显式 `authStore.clearAuth()`，清掉残留/注入的失效 token）。
- **CareerMate 短信类用例（TC-CM-06/07/08/09/12、TC-PWD-01/03、SEC-13/14、TC-X-*）**：失败因 CareerMate 短信接口返回"短信服务暂时不可用"，属**环境**问题；需 dev 固定验证码或可用短信通道后重跑。
