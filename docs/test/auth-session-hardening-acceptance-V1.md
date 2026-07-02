# 会话续期加固 + 记住我 30 天 — 合并/上线验收清单 V1

| 项 | 值 |
|---|---|
| 拟制日期 | 2026-07-03 |
| 版本 | V1 |
| 适用分支 | rag-forge `feature/auth-session-hardening`、auth-gateway `feature/refresh-grace-remember` |
| 关联文档 | `docs/dev/security-and-multitenancy.md` §8、`docs/CHANGELOG.md` 认证特性线 V3 |

## 修订记录

| 版本 | 日期 | 修订内容 | 拟制 |
|---|---|---|---|
| V1 | 2026-07-03 | 首版:合并前验收 + 上线后观察项 | @guandezhi |

## 分阶段目标

| 阶段 | 内容 | 目标日期 |
|---|---|---|
| S1 | 本清单 A/B 组全过,两分支合并 main | 2026-07-04 |
| S2 | dev 环境联调(网关+RAGForge 同环境),C 组全过 | 2026-07-05 |
| S3 | 生产发布(先网关含 V10 迁移,再 RAGForge),D 组观察 7 天 | 2026-07-08 起 |

---

## A. 合并前静态门禁(两仓库均需)

- [ ] auth-gateway:`mvn test` 全绿(基线 129 用例,含新增 `refreshReissuesTokensWhenReusedWithinRotationGrace`)
- [ ] rag-forge 后端:`cd backend && mvn test-compile` 通过;`AuthProxyControllerTest`/`AuthGatewayProxyClientTest` 绿
- [ ] rag-forge 前端:`npm run build` 通过
- [ ] 迁移检查:auth-gateway `V10__session_refresh_ttl.sql` 为纯 ADD COLUMN(可空、无回填、可安全回滚)

## B. 接口契约(curl / httpie,dev 网关)

| # | 场景 | 预期 |
|---|---|---|
| B1 | `POST /auth/login/password` 带 `remember=true` | 响应含 `refresh_expires_in=2592000`;`auth_sessions.refresh_ttl_seconds=2592000` |
| B2 | 同上 `remember=false` 或缺省 | `refresh_expires_in=604800` |
| B3 | 同一 refresh token 3 秒内连刷 2 次 | 两次均 200(第二次为宽限期补发);事件流出现 `refresh.grace_reuse`;**不出现** `replay_detected`;族未吊销 |
| B4 | 同一 refresh token 旋转 61s 后再用 | 401 `REFRESH_REPLAY_DETECTED`;族内全部 token 吊销 |
| B5 | `remember=true` 会话执行一次 `/auth/token/refresh` | 新 token `refresh_expires_in` 仍为 2592000(TTL 继承,滑动) |
| B6 | RAGForge `POST /api/auth/login`(remember:true) | `Set-Cookie: rf_refresh` 的 Max-Age=2592000;`rf_csrf` 同步 |
| B7 | RAGForge `GET /api/v1/me` 无 token | HTTP **401**(非 200+body401) |

## C. 前端行为(Playwright,dev 环境)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| C1 | 静默续期无感 | 登录后将网关 access TTL 临时调为 120s;停留仪表盘 5 分钟 | 期间无"登录已过期"提示;Network 中每 ~30-45s 出现一次成功 `/api/auth/refresh`;操作不中断 |
| C2 | 被动 401 兜底 | 登录后用 route 拦截将下一个 `/api/v1/*` 响应改 401 | 自动 `/refresh` + 原请求重放成功;无 toast、不跳登录 |
| C3 | 多标签页并发 | 同浏览器开 3 个标签页登录态,等 token 到期 | 三页均续期成功且 `/refresh` 实际只发一次(Web Locks);无任何页被踢 |
| C4 | 网络抖动不踢人 | 用 `context.setOffline(true)` 断网 30s 后恢复,期间触发续期 | 仅 toast"网络不稳定,登录续期失败";**会话保留**,恢复网络后操作正常 |
| C5 | 会话真过期 | 后端吊销该会话(logout-all)后触发任一请求 | toast"登录已过期" + 跳 `/login?reason=expired&redirect=<原路径>`;登录后回跳 |
| C6 | 记住我 30 天 | 勾选登录 → DevTools Application 查 `rf_refresh` Expires | ≈30 天;不勾选 ≈7 天 |
| C7 | 硬刷新恢复 | 登录后 F5 | 会话恢复,不回登录页 |
| C8 | 长上传中途过期 | access TTL 调 60s,上传大文件 | 上传完成不报 401(uploadRequest 续期重放) |
| C9 | 登出联动 | 两标签页登录,A 页退出 | B 页会话同步清除(BroadcastChannel) |
| C10 | SSE 跟随续期 | 停留超过一个 token 周期,观察 `/notifications/stream` | 续期后连接以新 token 重建;无持续 401 重连风暴 |

> C 组用例按项目惯例仅本地/dev 验证,**不提交** spec 文件入库。

## D. 上线后观察(7 天)

- [ ] 网关事件:`refresh.grace_reuse` 有少量(多标签页正常);`refresh.replay_detected` 应接近 0——若持续出现需排查真实重放
- [ ] 前端误踢:收集"登录已过期"用户反馈应归零;Nginx `/api/auth/refresh` 4xx 比例明显下降
- [ ] 回滚预案:两端互相兼容(旧网关忽略 remember、新代理对缺失 refresh_expires_in 回退 7 天),可独立回滚;V10 列保留不影响旧版本

## 风险与已知遗留

1. `rf_csrf`/`X-CSRF-Token` 后端未校验(装饰性,SameSite=Lax 兜底)——已挂 CHANGELOG V3 遗留,单独排期。
2. 旋转宽限期使旧 refresh token 在 60s 内可重复兑换,属业界标准取舍(Auth0 reuse interval 同型);如安全评审要求可将 `AUTH_REFRESH_ROTATION_GRACE_SECONDS` 调小或置 0。
