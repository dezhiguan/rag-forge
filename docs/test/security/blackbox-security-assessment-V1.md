# RAGForge 生产环境黑盒安全评估报告 · V1

| 项 | 内容 |
| --- | --- |
| 拟制日期 | 2026-07-03 |
| 版本 | V1 |
| 评估目标 | 生产站点 `https://ragforge.net`（后端 Spring Boot + Nginx，前端 Vue SPA） |
| 评估方式 | 授权黑盒 + 单账号认证态测试;**全程无损**(只读验证、无 DoS、无暴力、未落地破坏数据) |
| 授权 | 系统负责人书面授权,使用其本人 USER 账号(手机号 + 短信验证码登录) |
| 评估人 | 安全评估(自动化 + 人工判读) |

## 修订记录

| 版本 | 日期 | 修订说明 |
| --- | --- | --- |
| V1 | 2026-07-03 | 首次评估:外部黑盒 + 认证态 IDOR / JWT / 输入校验;形成 8 项 gap + 11 项已验证防护 |

## 交战规则(ROE)

- ✅ 允许:安全响应头/TLS 审查、端点探测、认证流程逻辑、越权(IDOR)只读验证、JWT 篡改验证、输入校验与错误处理审查
- ❌ 禁止:压测 / DoS、暴力破解、短信轰炸(发码仅触发 1 次)、任何写入或删除生产数据、影响其他用户
- 敏感证据(真实令牌 / 验证码 / 邮箱 / 手机号 / session_id / jti)**不入库**,本报告已脱敏

---

## 一、结论摘要

后端**授权与输入校验的服务端实现整体扎实**:JWT 校验无可绕过口子,KB / 文档 / 搜索三条数据链的越权(IDOR)均被正确拦截,错误处理干净无堆栈泄露。主要风险集中在**边界层配置**(缺 HTTP 安全响应头)与**令牌存储模型 + 无 CSP 的链式 XSS 风险**,以及若干低危项与一个登录错误提示语义 bug。

- 🟠 中危 × 2:缺安全响应头;access token 存于 JS 可读存储且无 CSP(链式 XSS 窃取令牌)
- 🟡 低危 × 4:`/sse` 未真正下线;令牌 aud/scope 命名违背最小权直觉;短信首次发送无图形验证码;Nginx 版本号泄露
- 🔵 提示 × 2:health 端点未认证暴露 traceId;登录错误提示语义错误(UX/bug)
- ✅ 已验证防护 × 11(见第四节)

---

## 二、发现清单

### SEC-BB-01 · 🟠中 · 主站缺失 HTTP 安全响应头

**证据**:主站响应头无 `Strict-Transport-Security`、`Content-Security-Policy`、`X-Frame-Options`、`X-Content-Type-Options`、`Referrer-Policy`;并回显 `Server: nginx/1.31.1`。HTTP 到 HTTPS 用 301 跳转但无 HSTS。

**影响**:
- 无 HSTS + 明文 301:首访存在 SSL-strip 降级窗口。
- 无 `X-Frame-Options` / CSP `frame-ancestors`:登录页可被 `<iframe>` 嵌套,存在点击劫持面。
- 无 `X-Content-Type-Options: nosniff`:MIME 嗅探面。

**建议**(Nginx 层):
```
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;
add_header X-Frame-Options "DENY" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Content-Security-Policy "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'" always;
server_tokens off;
```

### SEC-BB-02 · 🟠中 · Access token 置于 JS 可读存储 + 无 CSP(链式 XSS 窃取令牌)

**证据**:`POST /api/auth/login-mobile` 在 **响应体 JSON** 返回 `accessToken`(RS256 JWT,`expiresIn=900`),登录响应仅下发一个 **非 HttpOnly** 的 `rf_csrf` cookie,未见承载访问令牌的 HttpOnly 会话 cookie。前端因此只能将访问令牌存于 JS 可读存储(localStorage/内存)。

**影响**:配合 SEC-BB-01 缺失 CSP,一旦前端出现任一 XSS,攻击者脚本可直接读取访问令牌并冒用会话。属"存储模型 + 缺纵深防御"的链式风险。

**建议**:优先落地 CSP(SEC-BB-01);评估将访问令牌下沉为 HttpOnly + Secure + SameSite cookie(与现有 `/refresh` 旋转配合),令牌不再经 JS 可达。若维持 Bearer 模型,则以严格 CSP + 短时效 + 旋转宽限(见 auth-session-hardening)为纵深。

### SEC-BB-03 · 🟡低 · `/sse` 端点未真正下线

**证据**:MCP 传输收口方案要求下线 `/sse`,但线上 `/sse` 仍响应:无 key 时 `401 API_KEY_MISSING`,带非法 key 时 `401 API_KEY_INVALID`,行为与 `/mcp` 完全一致 —— 说明路由仍挂载。

**影响**:残余攻击面 / 与设计口径不符。

**建议**:移除 `/sse` 路由映射(而非仅加鉴权),或显式返回 404/410。

### SEC-BB-04 · 🔵提示(UX/bug) · 登录与未认证错误提示语义错误

**证据**:对 `POST /api/login`、`/api/v1`、`/api/v1/auth/login`、`/api/auth/login`、`/api/user/login`(空体 / 不存在用户)**均返回同一句**:
```json
{"code":401,"msg":"登录状态已失效，请重新登录","errorCode":"UNAUTHORIZED"}
```
一个**从未登录**的调用者被告知"登录状态已失效",措辞错误;多条不同路由返回完全一致的响应,提示登录路由很可能未被真正命中、被全局认证过滤器前置拦截。

**影响**:非安全漏洞,但误导用户/前端;侧面暴露路由前置拦截结构。**正向副作用**:因存在/不存在用户返回一致,不产生用户名枚举。

**建议**:区分三种语义 —— 未认证访问受保护资源→"请先登录";凭证/验证码错误→"账号或验证码有误"(保持不区分账号是否存在,防枚举);会话确已过期→"登录已过期,请重新登录"。

### SEC-BB-05 · 🟡低 · 令牌 aud / scope 命名违背最小权直觉

**证据**:普通 USER 的访问令牌 `aud="ragforge-admin-api"`、`scopes=["rag:admin:read"]`;而 `/api/v1/me` 返回 `platformAdmin=false`、capabilities 为 `dashboard:read / kb:read / kb:own:write / search:run / answer:run / profile:write`(无管理能力)。

**影响**:能力门控本身正确(见 SEC-PASS-02/03),但令牌层用 `admin` 命名 USER 的受众与作用域,易在后续接入方按 scope 名做判断时被误读为管理权限,违背最小权限的可读性原则。

**建议**:重命名受众/作用域使其与实际权限一致(如 `ragforge-api` + `rag:read`),或在文档中固化"scope 名不等于能力,能力以服务端 capability 门控为准"。

### SEC-BB-06 · 🔵提示 · health 端点未认证暴露

**证据**:`/actuator/health` 未认证返回 `{"status":"UP","groups":["liveness","readiness"]}`;`/api/v1/health` 未认证返回内部 `traceId`。

**建议**:actuator health 收敛为 `never`/仅内网可达;确认 `/api/v1/health` 不外泄超出存活探针所需的信息。

### SEC-BB-07 · 🟡低 · 短信发送首次无图形验证码

**证据**:`POST /api/auth/sms/send` 首次请求即 `{"sent":true}`,无图形验证码前置;仅由限流兜底(前端可见错误码 `SMS_PHONE_DAY_LIMITED` / `SMS_IP_MINUTE_LIMITED` / `SMS_SEND_TOO_FREQUENT` / `SMS_PROVIDER_RATE_LIMITED`)。

**影响**:短信轰炸/短信费用滥用面,取决于限流阈值松紧(本次未压测验证阈值)。

**建议**:确认阈值足够紧(建议同号 ≤5 条/天、单号 ≥60s 间隔、同 IP ≤1 条/分);对异常频率触发图形/滑块验证码;失败到一定次数后对登录接口也上验证码(前端已具备 `captchaRequired` 逻辑,确认服务端强制)。

### SEC-BB-08 · 🟡低 · Nginx 版本号泄露

**证据**:`Server: nginx/1.31.1`。**建议**:`server_tokens off`(并入 SEC-BB-01)。

---

## 三、已验证的防护(通过项,说明做得对)

| 编号 | 项 | 验证结论 |
| --- | --- | --- |
| SEC-PASS-01 | Actuator 敏感端点鉴权 | `/actuator/env`、`/heapdump`、`/beans`、`/configprops`、`/threaddump` 等**全部 401**;仅 `/actuator/health` 开放。无大小写鉴权绕过(`/ACTUATOR/*` 200 仅为 Nginx 回退到 SPA 首页 393B,非数据泄露;`/actuator/ENV` 进后端仍 401)。 |
| SEC-PASS-02 | KB 列表越权 | `/api/v1/kb` 仅返回登录者自有 KB(全部 `ownerUserId=7`),尽管 JWT 内 `rag_readable_kb_ids=[]`,归属由服务端实时计算。 |
| SEC-PASS-03 | KB 详情 IDOR | 遍历非自有 KB id(1/50/100/300/500/600/612/620/629)**全部 403**,仅自有 id 200。 |
| SEC-PASS-04 | 文档详情 IDOR | `/api/v1/documents/{id}` 对非自有 id(1/100/1000/3000/5000/7000/7500/7700/7800)**全部 403「无权访问」**。 |
| SEC-PASS-05 | 搜索 KB 归属过滤 | `POST /api/v1/search` 传入非自有 `kbIds`(600/500/1)返回 `200 success` 但**命中 0 条**,静默过滤无权库,不泄露他人内容。 |
| SEC-PASS-06 | 无 chunk 越权路由 | `/api/v1/chunk/{id}`、`/api/v1/chunks/{id}` 均 404,无按 chunkId 直取的旁路。 |
| SEC-PASS-07 | JWT 校验健壮 | `alg:none`、篡改 payload(改 user_id/sub)保留原签名、提权改 scopes/role、截断签名 —— **全部 401 拒绝**,仅原始令牌 200。 |
| SEC-PASS-08 | JWKS 不外泄 | 签发方 `auth.careermate.cn` 走内网、公网不解析,公钥不外泄,RS256→HS256 混淆不可行。 |
| SEC-PASS-09 | CORS 锁定 | 伪造 `Origin: https://evil.example.com` 无任何 `Access-Control-Allow-*` 回显。 |
| SEC-PASS-10 | 输入校验 & 错误处理 | 畸形 JSON / 类型混淆 / 超大 topK / 非数字路径参数 → 友好 400,**无堆栈或内部信息泄露**;注入探针(`' OR 1=1;-- <script>`)返回 200 正常处理无 500,查询参数化。TRACE/PUT/DELETE 405。 |
| SEC-PASS-11 | 无用户名枚举 + OTP 限流存在 | 登录对存在/不存在账号返回一致;短信侧具备按号/按 IP/频率的限流错误码。 |

---

## 四、后续待测(需扩大授权 / 多账号)

- 多账号跨组织越权(orgId 维度):本次仅单账号,建议用两个不同组织账号交叉验证 KB / 文档 / 搜索 / 通知的组织隔离。
- CSRF 实际强制:当前为 Bearer(Authorization 头)模型,浏览器不会自动附带该头,CSRF 非主要向量;若存在任何 cookie 承载的认证态,需单独验证服务端是否强制 `rf_csrf` 双提交。
- 短信/登录限流阈值压测:需在受控速率与授权窗口内验证阈值,避免影响生产。
- 会话生命周期:`/refresh` 旋转宽限、`/logout` 与"退出全部设备"是否即时吊销(需可控地消耗会话)。

---

> 复现脚本见同目录 `run-blackbox-suite.sh`(免认证)、`run-authenticated-suite.sh`(需 `RF_TOKEN`)、`jwt-tamper-test.py`(需 `RF_TOKEN`)。脚本不含任何真实凭据,令牌通过环境变量注入。
