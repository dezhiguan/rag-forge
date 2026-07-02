# LLM-as-Judge / 质量看板 — 排查发现与设计缺口

> 编制 2026-07-02 · 来源:质量看板"组织视角恒空 + golden 回放不出分"的端到端排查(代码 + 线上 Pod 日志 + API 实测)。

## 摘要

排查质量看板"暂无评测数据"过程中发现并**已修复 1 个真实缺陷**,并识别出 **1 个组织维度可见性设计缺口**。

---

## 1. 已修复:Golden Set 回放被知识库"应答开关"拦截(ANSWER_DISABLED)

**现象**:管理员触发 Golden Set「立即回放」后,质量看板始终无新数据;回放接口返回 `success:0`(实为异步 ack,真实结果在异步日志)。

**根因**(线上 API Pod 日志坐实):
```
GoldenSetReplayJob: Golden replay starting: 2 questions
GoldenSetReplayJob: Golden replay failed for q=16: ANSWER_DISABLED
GoldenSetReplayJob: Golden replay done: success=0 failed=2
```
`GoldenSetReplayJob` 逐题调 `AnswerService.answerSync()` 生成答案供 judge 评分,但走了与**生产应答同一个** `enforceAnswerMode` 守卫。而**新建知识库的 `answerMode` 默认是 OFF**,`enforceAnswerMode` 对 `answerMode=OFF` **无条件抛 `ANSWER_DISABLED`** → 回放每题失败 → 不发 judge 消息 → judge_results 无写入 → 看板恒空。

**修复**(`AnswerService.answerInternal`):对 `judgeSource=GOLDEN_SET` 的**评测回放绕过** `enforceAnswerMode`(评测是内部质量评估,不应受知识库对外应答开关限制);**生产应答仍受守卫**。含单测 `AnswerServiceTest#goldenSetReplay_bypassesAnswerModeOff` / `#productionAnswer_answerModeOff_throwsDisabled`。

**验证**:修复部署后重跑回放 → `Golden replay done: success=2 failed=0` → judge Pod 消费打分 → 质量看板(全平台视图)出现该两题、评分 0.72。

> 附:judge Pod(`RAGFORGE_ROLE=judge`)一直正常消费,只是 **RocketMQ Java 客户端日志默认写独立文件 `rocketmq_client.log`,不进 stdout**,故 `kubectl logs` 看不到消费痕迹,一度误判为"消费者未启动"。判定 judge 是否处理应以 `judge_results` 写入为准,而非 stdout。

---

## 2. 设计缺口:质量看板按"KB→org_id"过滤,组织维度可见性受限

**事实**(代码核实):
- `judge_results` / `answer_logs` 的 `tenant_id` 已是**死列**,写入恒为 `"default"`(租户模型已移除),**读路径不按 tenant_id 过滤**。
- `judge_results` 表**无 org_id 列**,组织归属只能经 `kb_ids → knowledge_bases.org_id` 间接推导。
- 读路径 `JudgeQualityController.currentOrgScope()`:
  - 破玻璃 admin(全平台视图,`X-Admin-Override`)→ scope=null → 不过滤,看到全部;
  - 否则按 `X-Org-Id` → `knowledge_bases WHERE org_id=:orgId OR visibility='PUBLIC'` 的 KB 集合过滤;
  - **`X-Org-Id` 缺失/非数字 → scope 为空集 → overview/by-kb/worst-cases/cost 全部短路返回空。**

**影响**:
- 个人组织/未带 `X-Org-Id` 的上下文 → 质量看板恒空(非 bug,是空 scope 短路)。
- 历史评测数据(含早期 E2E 种子)的 KB 若 `org_id` 不属于当前组织且非 PUBLIC → 组织视角看不到,**只有全平台视图(破玻璃)能看到**。
- 组织维度的质量对照(如测试用例集里"F 按组织")在现状下**基本只能靠全平台视图验证**;真正的按组织隔离依赖"golden 数据集的 KB 其 org_id 正确归属到该组织"。

**关键文件**:
- 写:`judge/GoldenSetReplayJob.java`、`answer/AnswerService.java`(writeLog/publishJudgeAsync,tenant 恒 default)、`judge/JudgeOrchestrator.java`(judge_results.tenant_id 继承 default)。
- 读:`controller/JudgeQualityController.java`(currentOrgScope)、`service/impl/JudgeQueryServiceImpl.java`(按 scopeKbIds 过滤)、`security/JwtAuthenticationFilter.java`(X-Org-Id→OrgContextHolder)。

**建议(供架构决策,未实施)**:
1. 若质量看板要支持**真正的组织维度**:给 `judge_results` 增 `org_id` 列(写入时按 KB 归属或请求上下文落定),读路径直接按 org_id 过滤,弱化对"KB 可读集合"的依赖。
2. 或明确产品口径:质量看板为**平台级**能力(仅全平台视图可见),组织视角不展示——则前端应对组织上下文给出"平台级功能"提示而非空白。
3. 清理死列 `tenant_id`(单独迁移)。

---

## 3. 数据卫生

- tenant=default 的历史 judge 数据为**混合**:早期合成种子(如 `Worst case query 0/1/2`)+ 疑似真实测试查询 + 本次排查产生的 golden 评测。
- 现有 admin 清理端点 `DELETE /api/v1/admin/e2e/judge-result?tenantId=default` 是**按 tenant 批量删**(会连真实/新数据一起删),不适合做精确清理;精确删除合成种子需 `DELETE FROM judge_results WHERE query LIKE 'Worst case query%'` 级别的 DB 操作,需谨慎评审。
