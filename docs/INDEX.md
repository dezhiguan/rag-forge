# RAGForge 文档索引

> 文档已按 **原型 / 开发 / 测试 / 部署** 四类归档。权威信息以 [`architecture.md`](architecture.md) 和当前代码为准。
> 演进脉络见 [`CHANGELOG.md`](CHANGELOG.md)。标 🕰️ 为历史归档(口径过时,仅供追溯)。

## 顶层(权威)

| 文档 | 说明 |
| --- | --- |
| [architecture.md](architecture.md) | **架构权威文档**,优先阅读 |
| [CHANGELOG.md](CHANGELOG.md) | 版本演进时间线(平台 V4→V6 + 认证/权限 V1→V2) |

## prototype/ — 原型与设计稿(多为历史)

| 文档 | 说明 |
| --- | --- |
| permission-plan.html | 权限重构设计稿:从按平台控权 → 组织自治 |
| unified-auth-redesign-V1.html | 🕰️ 统一认证设计稿 V1(部分被去租户 V2 反转) |
| RAGForge-优化设计文档-V5.html | 🕰️ V5 优化设计(含已废弃方案,以 architecture.md 为准) |
| RAGForge-架构设计文档-V4.html | 🕰️ 平台 V4 架构稿(被 V5 取代) |
| rag-service-design.html | 🕰️ 0→1 原始设计(旧技术栈,最过时) |

## dev/ — 实现说明 / 任务 / 路线 / 设计规格

| 文档 | 说明 |
| --- | --- |
| auth-and-permissions.md | 认证与权限实现说明(Spring Security + JWT + API Key + KB ACL) |
| security-and-multitenancy.md | 安全与组织模型(注:tenant 已按 V2 移除) |
| current-architecture-and-refactor-roadmap.md | 架构体检 + 现状校准 + 重构路线 |
| model-cost-center-design.md | 模型注册表 & 成本中心设计 |
| tenant-removal-and-org-permissions-V2.md | 去租户 + GitHub 式组织模型(V2,现行) |
| tasks.md | V5 任务追踪(T1-T12) |
| v5-execution-tasks.md | V5 执行任务清单 + V6 已知 gap |
| v6-llm-judge-execution-prompts.md | V6 LLM-as-Judge 执行清单(J1-J7) |
| cursor-prompts.md | 🕰️ 0→1 历史 Cursor prompt(旧技术栈) |

## test/ — 测试计划 / 用例 / 验收 / 排障

| 文档 | 说明 |
| --- | --- |
| retrieval-quality-test-plan-V1.md | 检索质量 E2E 用例(含 qwen3-rerank) |
| ragforge-test-plan.html | 性能与容量测试计划 |
| model-cost-center-test-cases.md | 模型成本中心 E2E 用例 |
| org-permission-test-plan-V2.md | 组织权限测试计划 V2(现行) |
| org-permissions-test-plan-V1.md | 🕰️ 组织权限 V1(被 V2 取代) |
| org-view-test-plan-V1.md | 组织视角 E2E 用例 |
| unified-auth-test-plan-V1.md | 统一认证测试方案 V1 |
| v5-acceptance-playwright-cases.md | V5/V6 验收 Playwright 用例 |
| v5-acceptance-t11-summary.md | T11 验收套件总结 |
| T11-headed-test-report.md | T11 有头执行报告 |
| v6-stuck-running-recovery.sql | V6 Judge 卡 RUNNING 排障脚本 |

## deploy/ — 部署 / 运维 / 监控

| 文档 | 说明 |
| --- | --- |
| **deployment-architecture.md** | **部署架构(k3s,权威)** |
| oss-cors-setup.md | 阿里云 OSS 直传 CORS 配置 |
| skywalking-business-logs.md | SkyWalking 业务日志约定 |
| grafana-v5.json | V5 Grafana 面板(未导入目标集群) |
| deployment-three-tier.md | 🕰️ docker-compose 三层(历史,以 architecture 为准) |
| deployment-migration-runbook.md | 🕰️ 三层首次部署/切流 Runbook(历史) |
| deployment-app-cluster.md | 🕰️ 单主机多副本形态(历史) |
