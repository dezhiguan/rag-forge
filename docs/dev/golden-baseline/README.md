# 平台级黄金集基准 — 可复现资产

配套设计见 `docs/dev/golden-set-baseline-plan-v1.html`（定版 v1.0）。

本目录是「平台基准库·v1」100 题检索质量基准的**可复现来源**：语料生成器、题库、加载器。
运行前提：`is_core` 列（迁移 V57）已部署，且以平台超管身份操作。

## 内容
- `gen_corpus.py` — 生成 10 类全格式真实语料（Markdown/PDF/Word/HTML/TXT/CSV + PNG/JPG/GIF/WEBP）
  到 `corpus/`，并打包 `platform-baseline-corpus.zip` / `.tar.gz`。内容为 RAGForge 平台真实技术知识
  （事实取自架构文档，无 PII、无填充）。
- `gen_questions.py` — 生成 `golden_questions.json`（100 题；难度 22/22/16/20/20；格式×难度双标签；
  否定题 20 条问库中确无的事实）。
- `golden_questions.json` — 已生成的 100 题（`judgeEnabled=true`、`isCore=true`）。
- `load_golden.mjs` — 幂等加载器：建库（org_id=0、imageProcessingMode=ON）→ 上传语料 → 等入库 →
  建数据集 → 批量导入 100 题 → 可选触发回放。

## 使用
```bash
python3 gen_corpus.py         # 生成语料到 corpus/
python3 gen_questions.py      # 生成/刷新 golden_questions.json
node load_golden.mjs          # 建库+上传+导入（不回放）
node load_golden.mjs --replay # 同上并触发一次组织回放（单次上限 50 题；夜间 cron 跑全量 100）
```

## 度量与冻结
- 回放走现有 Golden Set Replay → LLM-as-Judge（context_precision/recall + faithfulness/answer_relevance
  → overall），点亮现有质量看板；不新建看板/指标/cron。
- `is_core=TRUE` 的题被后端守卫锁定（编辑/删除抛 `403 CORE_QUESTION_LOCKED`），前端显示 🔒；
  `is_core` 仅平台超管可置。
