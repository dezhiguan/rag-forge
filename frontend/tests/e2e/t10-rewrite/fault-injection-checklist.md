# T10-rewrite Fault Injection Checklist

这些检查不提交破坏性代码，只在本地临时改动后运行对应用例，验证 E2E 能真实抓到回退。

| 编号 | 临时注入方式 | 运行用例 | 预期 |
|---|---|---|---|
| S1 | 临时注释 `DashScopeVlEmbeddingClient` 的 2560 维度校验 | `t10rw-e2e-12-db-dim-guard.spec.ts` | 必须 FAIL，证明维度兜底有效 |
| S2 | 临时把人工 SQL 中 `vl_vector vector(2560)` 改成 `vector(1024)` 并在测试库执行 | 启动后端 | 必须 fail-fast，提示先执行正确 V27 |
| S3 | 临时把 `image_no_text.png` 换成带文字水印版本 | `t10rw-e2e-06-text-search-no-text-image.spec.ts` | 不应因 BM25 PASS，因为用例固定 `strategy='vector'` |
| S4 | 临时注释 `ImagePipelineService` 写入 `vlVector` | `t10rw-e2e-02-image-ocr-single-chunk.spec.ts` | 必须 FAIL，raw chunk 维度为空 |
| S5 | 用 `modality='image'` + `queryImageBase64='dummy'` 调旧 `/api/v1/search` 契约 | `t10rw-e2e-13-deprecated-fields-compatible.spec.ts` | 必须 PASS，证明软兼容没有破坏 careermate/MCP |

PR 描述需要附每项的 trace 或截图，并说明临时改动已还原。
