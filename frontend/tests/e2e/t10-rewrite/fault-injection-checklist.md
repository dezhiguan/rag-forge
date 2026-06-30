# T10-rewrite Fault Injection Checklist

这些检查不提交破坏性代码，只在本地临时改动后运行对应用例，验证 E2E 能真实抓到回退。

## Execution Results

- 执行日期：2026-06-22
- 执行环境：本地 RAGForge API `127.0.0.1:8080`，auth-gateway `127.0.0.1:8090`，真实 PostgreSQL / ES / DashScope；未启动本地 RocketMQ。
- 后端启动参数：`--spring.flyway.validate-on-migrate=false`
- 历史阻塞与修复：
  - 不带临时参数启动时，Flyway V28 checksum mismatch 阻止本地 API 启动。
  - 修复前 `/api/v1/documents` 上传在 `IngestService.afterCommit -> DocumentProcessProducer.send` 阶段依赖 RocketMQ producer；云 NameServer `{开发ECS-IP}:9876` 超时，导致 Playwright 依赖上传的用例在准备阶段失败。
  - 修复后本地 `.env` 使用 `RAGFORGE_DOCUMENT_PROCESS_DISPATCH_MODE=inline`，上传注册后异步 inline 处理文档，不启动本地 MQ、不依赖云 MQ，`t10rw-e2e-13...spec.ts` 已通过。

| 编号 | 实际结果 | 证据文件 | 备注 |
|---|---|---|---|
| S1 | NOT RUN | 暂无 | inline dispatch 已修复上传链路阻塞；本轮未执行维度校验注入。 |
| S2 | NOT RUN | 暂无 | 需要临时执行破坏式 V27 变体；本轮未对本地库做该破坏性注入。 |
| S3 | NOT RUN | 暂无 | inline dispatch 已修复上传链路阻塞；本轮未执行带水印 fixture 注入。 |
| S4 | NOT RUN | 暂无 | inline dispatch 已修复上传链路阻塞；本轮未执行注释 `vlVector` 写入的破坏性注入。 |
| S5 | PASS | `frontend/test-results/t10-rewrite/fault-injection/S5-e2e13-pass-trace.zip` | `t10rw-e2e-13-deprecated-fields-compatible.spec.ts` 完整通过，验证 `modality='image'` + `queryImageBase64='dummy'` 旧 `/api/v1/search` 契约保持 HTTP 200 且返回结果。 |

| 编号 | 临时注入方式 | 运行用例 | 预期 |
|---|---|---|---|
| S1 | 临时注释 `DashScopeVlEmbeddingClient` 的 2560 维度校验 | `t10rw-e2e-12-db-dim-guard.spec.ts` | 必须 FAIL，证明维度兜底有效 |
| S2 | 临时把人工 SQL 中 `vl_vector vector(2560)` 改成 `vector(1024)` 并在测试库执行 | 启动后端 | 必须 fail-fast，提示先执行正确 V27 |
| S3 | 临时把 `image_no_text.png` 换成带文字水印版本 | `t10rw-e2e-06-text-search-no-text-image.spec.ts` | 不应因 BM25 PASS，因为用例固定 `strategy='vector'` |
| S4 | 临时注释 `ImagePipelineService` 写入 `vlVector` | `t10rw-e2e-02-image-ocr-single-chunk.spec.ts` | 必须 FAIL，raw chunk 维度为空 |
| S5 | 用 `modality='image'` + `queryImageBase64='dummy'` 调旧 `/api/v1/search` 契约 | `t10rw-e2e-13-deprecated-fields-compatible.spec.ts` | 必须 PASS，证明软兼容没有破坏 careermate/MCP |

PR 描述需要附每项的 trace 或截图，并说明临时改动已还原。
