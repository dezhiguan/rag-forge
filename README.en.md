# RAGForge · RAG Knowledge Engine

<p align="left">
  <a href="README.md">简体中文</a> ·
  <a href="README.en.md">English</a>
</p>

> Retrieval infrastructure for RAG applications — document ingestion, multimodal embedding, hybrid retrieval, reranking, RAG answering, quality evaluation and unified auth. It returns traceable retrieval results (`query → chunks + scores + citations`) through a clean REST API and an MCP server for upstream agents to consume.

[![Live Site](https://img.shields.io/badge/Live%20Site-ragforge.net-2EA043?logo=googlechrome&logoColor=white)](https://ragforge.net)
[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20%2B%20pgvector-4169E1?logo=postgresql&logoColor=white)](https://github.com/pgvector/pgvector)
[![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.15-005571?logo=elasticsearch&logoColor=white)](https://www.elastic.co/)
[![RocketMQ](https://img.shields.io/badge/RocketMQ-5.x-D77310?logo=apacherocketmq&logoColor=white)](https://rocketmq.apache.org/)
[![MCP](https://img.shields.io/badge/MCP-Spring%20AI%201.0-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-ai/reference/)
[![LLM](https://img.shields.io/badge/LLM-DashScope%20%2F%20DeepSeek-FF6A00)](https://dashscope.aliyun.com/)
[![Deploy](https://img.shields.io/badge/Deploy-k3s%20%2B%20ACR-326CE5?logo=kubernetes&logoColor=white)](docs/deploy/deployment-architecture.md)

---

## Overview

RAGForge is a **knowledge-retrieval infrastructure service** for RAG applications. It handles document ingestion, parsing, cleaning, chunking, (multimodal) embedding, keyword indexing, hybrid retrieval, reranking, RAG answering, retrieval debugging, quality evaluation, unified authentication and API delivery.

It is not a chatbot — its core output is **traceable retrieval results**:

```text
query -> chunks + scores + citations + metadata + stage latency (rewrite/vector/keyword/rerank/total)
```

Upstream agents, Q&A systems or business apps can call RAGForge to fetch candidate context (`/api/v1/search`), use the built-in RAG answering (`/api/v1/answer`), or wire its retrieval into an AI toolchain over MCP. RAGForge is the knowledge-retrieval foundation for [CareerMate (an AI job-search agent)](https://github.com/dezhiguan/careermate).

## Features

- **Knowledge base & document management** — PDF, Markdown, TXT, Word/OOXML and more, parsed with Tika.
- **Async document pipeline** — upload → store → RocketMQ → parse → clean → chunk → embed → index into PG/ES, with status tracking throughout.
- **Unified multimodal vector space** — text and images are encoded into the same **2560-dim** space (DashScope `qwen3-vl-embedding`), enabling text-to-image and mixed retrieval.
- **5 retrieval strategies** — `vector` / `keyword` / `hybrid (RRF)` / `rewrite (rewrite + multi-recall)` / `full (rewrite + hybrid + rerank)`, each with its own concurrency limiting and timeout guard.
- **RAG answering** — `POST /api/v1/answer`, SSE streaming answer with citations.
- **Retrieval debug console** — compare recall and ranking across strategies, weights and TopK.
- **Quality evaluation + LLM-as-Judge** — build eval sets to observe recall/ranking/failure cases; an offline golden set and online sampling are scored automatically by DeepSeek acting as judge, with a built-in quality dashboard.
- **Unified auth & permissions** — admin APIs use Bearer JWT issued by the Auth Gateway (RS256 + JWKS verification); supports password / SMS-code login, token refresh, logout, logout-everywhere and password reset; roles `ADMIN` / `KB_EDITOR` / `KB_VIEWER` / `SERVICE_ACCOUNT`; KB access is controlled at fine grain via `kb_acl`, JWT claims and the organization model; session-revocation / password-change events are synced over an HMAC webhook with a Redis revocation list.
- **Organization model** — GitHub-style "personal + organization" collaboration (the earlier tenant model has been removed); KBs are owned by `owner_user_id` / `org_id`, with org invitations and notifications.
- **API key management** — controlled access for external systems and MCP tools, with enable/disable, service-account context, KB scope (`allowed_kb_ids`) and per-minute Redis rate limiting.
- **MCP Server** — built on Spring AI MCP (WebMVC SSE), exposing three tools: `search_knowledge`, `list_knowledge_bases`, `answer_with_citations`.
- **Model registry & cost center** — models are registered centrally (`model_config`) and metered/priced per model and per organization (`model_usage_daily`); rewrite and answer support runtime model resolution with fallback.
- **Metadata-filtered retrieval** — requests support `filter.chunkType` and similar parameters.
- **Direct text ingestion** — `POST /api/v1/documents` (text channel) ingests already-parsed text directly, avoiding a second parse.

## Architecture

```text
                         +------------------------------+
                         |         Vue 3 Frontend       |
                         | Login / Dashboard / KB /     |
                         | Debug / Eval / Answer / API  |
                         +---------------+--------------+
                                         | HTTPS
                                         v
   +-----------------+        +------------------------------+
   |  Auth Gateway   |<-------|       Spring Boot Backend     |
   | (standalone IdP)| JWKS   |  (one image, RAGFORGE_ROLE)   |
   |  issues JWT     |  +HMAC |  ┌────────┬─────────┬───────┐ |
   +-----------------+ webhook|  │  api   │ worker  │ judge │ |
                              |  │ REST/  │ MQ doc  │ LLM-as│ |
                              |  │ Search/│ process │ Judge │ |
                              |  │ Answer │ consumer│ eval  │ |
                              |  └───┬────┴────┬────┴───┬───┘ |
                              +------|---------|--------|------+
                          async job  |   model |  judge |
                                     v   calls v        v
                        +------------------+  +----------------------+
                        |    RocketMQ      |  |  DashScope / DeepSeek |
                        | document-process |  |  embedding / rewrite /|
                        +--------+---------+  |  answer / rerank/judge |
                                 |            +----------------------+
                                 v
            +-------------------------------------------------+
            |                   Data Layer                    |
            | PostgreSQL + pgvector (vl_vector 2560) /         |
            | Elasticsearch (BM25) / Redis                     |
            +-------------------------------------------------+
```

Auth flow:

```text
Browser
  -> RAGForge /api/auth/*  (proxied to Auth Gateway)
  -> access token + HttpOnly refresh cookie
  -> RAGForge /api/v1/* with Bearer JWT
  -> JwtVerifier: JWKS verify + issuer/audience + revocation list + role/scope + KB ACL
```

External retrieval flow:

```text
Client / Agent / MCP
  -> /api/v1/search | /api/v1/answer | /sse
  -> Bearer JWT or X-API-Key
  -> SERVICE_ACCOUNT / user context
  -> filter by readable KB scope
```

## Tech Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5.15 |
| AI / MCP | Spring AI 1.0.0 (`spring-ai-starter-mcp-server-webmvc`) |
| Security | Spring Security, in-house JWT verification (RS256 + JWKS), HMAC webhook, Redis revocation list |
| ORM | MyBatis-Plus 3.5.16 |
| Database | PostgreSQL + pgvector 0.1.6 |
| Vector search | pgvector (`vl_vector` 2560-dim) |
| Keyword search | Elasticsearch 8.15.x (BM25, IK analyzer, falls back to standard) |
| Message queue | RocketMQ (spring-boot-starter 2.3.3) |
| Cache / rate limit / revocation / lock | Redis, Caffeine, ShedLock 5.16 |
| Document parsing | Apache Tika 2.9.3 |
| Object storage | Aliyun OSS SDK 3.18.3 (abstraction layer, local disk by default) |
| Observability | Micrometer + Prometheus, SkyWalking Agent 9.3 |
| Embedding / Rewrite / Answer / Rerank | DashScope (`qwen3-vl-embedding` / `qwen-turbo` / `qwen-plus` / `qwen3-rerank`) |
| LLM-as-Judge | DeepSeek (`deepseek-v4-flash`) |
| Frontend | Vue 3.4, Vite 5, Vue Router 4, Element Plus 2, Axios (plain JavaScript) |
| Deployment | k3s (app tier), standalone data node, Nginx entry tier |

## Retrieval Strategies

All strategies share a single `RetrievalService` (debug console, evaluation and the diagnostics page reuse the same pipeline) and record stage latency (rewrite / vector / keyword / rerank / total).

| Strategy | Pipeline | Rerank | Default concurrency | Default timeout |
| --- | --- | --- | --- | --- |
| `vector` (default) | pgvector cosine similarity | no | 5 | 8s |
| `keyword` | Elasticsearch BM25 | no | 20 | 5s |
| `hybrid` | vector + keyword in parallel + RRF (RRF_K=10) | no | 5 | 12s |
| `rewrite` | query rewrite (qwen-turbo) + multi-recall vector | no | 3 | 10s |
| `full` | rewrite → multi-query hybrid (RRF) → DashScope rerank | **yes** | 1 | 15s |

- `full` is the only strategy that calls rerank; its default concurrency is 1 to keep a heavy chain from starving the online service.
- A bounded retrieval thread pool (`retrieval-` prefix) runs the multi-recall legs of hybrid/full; rate-limit hits return 429 and timeouts return 504.

## Multimodal & Vector Space

- Text and images are encoded into a **single 2560-dim vector space** (DashScope `qwen3-vl-embedding` multimodal endpoint); the vector column is `document_chunks.vl_vector vector(2560)`.
- **Known engineering trade-off**: pgvector 0.8.x caps HNSW/IVF index dimensions at 2000, so the 2560-dim vectors **currently have no approximate index and vector search runs as a sequential scan**. At the current scale (~100k chunks) latency is acceptable; significant growth would call for dimensionality reduction, PQ quantization or a dedicated vector store. This trade-off is recorded in `backend/src/main/resources/db/manual/V27__vl_unified_vector.sql`.
- Image documents go through a dedicated pipeline (`ImagePipelineService`) with optional OCR (`qwen-vl-ocr`) and image captioning.

## Models & Cost Center

| Purpose | Model | Vendor | Notes |
| --- | --- | --- | --- |
| EMBEDDING | `qwen3-vl-embedding` | DashScope | unified 2560-dim for text + image |
| REWRITE | `qwen-turbo` | DashScope | query rewrite, runtime resolution |
| ANSWER | `qwen-plus` | DashScope | RAG answering / debug console, runtime resolution |
| RERANK | `qwen3-rerank` | DashScope | only invoked by `full` |
| OCR | `qwen-vl-ocr` | DashScope | optional, image pipeline |
| JUDGE | `deepseek-v4-flash` | DeepSeek | LLM-as-Judge evaluation |

- Models are registered in `model_config` (code / vendor / purpose / pricing / primary-fallback); daily usage and cost roll up into `model_usage_daily` (with `org_id` for per-organization attribution).
- Runtime resolution (primary → fallback → any enabled) is wired for **REWRITE / ANSWER**; EMBEDDING / RERANK / OCR / JUDGE use the configured default model and participate in metering/pricing.
- Historical note: an early design planned a local Python rerank microservice (`reranker/`, jina-reranker); it has been replaced by online DashScope `qwen3-rerank`. The `reranker/` directory is kept as a historical/optional placeholder and is **not deployed in production**.

## Auth & Permission Model

| Entry | Auth | Notes |
| --- | --- | --- |
| `/api/auth/**` | public | proxies the Auth Gateway: login / refresh / logout / password reset / userinfo |
| `/api/v1/health`, `/actuator/health` | public | health checks |
| `/api/v1/.well-known/...jwks.json` | public | RAGForge backend client-assertion public key |
| `/api/v1/events/**` | HMAC | Auth Gateway event webhook (HmacSHA256 + timestamp) |
| `/api/v1/search`, `/api/v1/answer`, `/mcp/**`, `/sse` | JWT or API Key | external retrieval / RAG answering / MCP |
| other `/api/v1/**` | JWT | admin APIs |

Roles:

| Role | Capabilities |
| --- | --- |
| `ADMIN` | access non-SYSTEM KBs, manage API keys, run maintenance (break-glass elevation writes audit) |
| `KB_EDITOR` | read/write authorized KBs, run evaluation and diagnostics |
| `KB_VIEWER` | read authorized KBs and run retrieval debugging |
| `SERVICE_ACCOUNT` | created by an API key, limited to the key's allowed KBs |

KB access is decided by a single `KbAccessGuard`: SYSTEM KBs are never accessible → ADMIN (break-glass) may access any non-SYSTEM KB → owner allowed → PUBLIC KBs readable → organization KBs by org role → fall back to JWT claims (`rag_readable/writable_kb_ids`) or `kb_acl`.

See [docs/dev/auth-and-permissions.md](docs/dev/auth-and-permissions.md).

## Project Structure

```text
rag-forge/
├── backend/                 # Spring Boot backend (api / worker / judge share one image, distinguished by RAGFORGE_ROLE)
├── frontend/                # Vue 3 frontend (plain JS)
├── reranker/                # historical/optional Python reranker placeholder (not deployed; rerank uses DashScope)
├── docker/                  # local middleware config
├── deploy/                  # k3s manifests, Nginx, env templates and deploy scripts
├── docs/
│   ├── architecture.md      # authoritative architecture doc
│   ├── prototype/           # early design drafts and prototypes (historical)
│   ├── dev/                 # implementation notes, tasks, roadmap, design specs
│   ├── test/                # test plans, cases, acceptance reports, recovery
│   └── deploy/              # deployment, ops, monitoring
├── docker-compose*.yml      # local middleware / legacy single-host orchestration
└── deploy.sh                # deploy script
```

## Frontend Pages

Main routes (visibility controlled by role/scope; real permissions are enforced by the backend):

- **Login / Register / ResetPassword**
- **Dashboard (`/`)** — overview of KBs, documents, chunks and recent activity.
- **KnowledgeBase (`/knowledge`) / KnowledgeDocuments** — KB and document management.
- **UploadWizard (`/uploads/wizard`)** — document upload wizard.
- **DocumentDetail (`/document/:id`)** — parsing status and chunk results.
- **DebugConsole (`/debug`)** — retrieval strategy debugging.
- **AnswerPlayground (`/answer`)** — RAG answering (streaming + citations).
- **EvaluationLab (`/eval`) / EvaluationQuality (`/evaluation/quality`)** — eval sets and LLM-as-Judge quality dashboard.
- **ModelCostCenter (`/models`)** — model registry and cost dashboard.
- **DeveloperCenter (`/api`)** — API keys and external access.
- **Organizations (`/orgs`) / AccountSettings (`/account`)** — org management and account settings.
- **PerformanceProbe (`/perf-probe`)** — retrieval latency diagnostics.
- **Forbidden (`/403`)**

## Quick Start (Local Dev)

### 1. Start middleware

```bash
docker compose up -d   # PostgreSQL+pgvector / Elasticsearch / RocketMQ
```

Default ports: PostgreSQL `5433`, Elasticsearch `9200`, RocketMQ NameServer `9876`, Broker `10911`. Redis must be provided separately (auth revocation, API-key rate limiting and ShedLock all depend on it).

### 2. Configure environment

```bash
cp backend/.env.example backend/.env
```

Minimum settings:

```properties
DASHSCOPE_API_KEY=your-dashscope-api-key
DEEPSEEK_API_KEY=your-deepseek-api-key        # required for LLM-as-Judge
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5433
POSTGRES_DB=ragforge
POSTGRES_USER=ragforge
POSTGRES_PASSWORD=ragforge
ELASTICSEARCH_HOST=localhost
ELASTICSEARCH_PORT=9200
ROCKETMQ_NAMESRV_ADDR=127.0.0.1:9876
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
```

Admin login and JWT auth additionally require Auth Gateway config (issuer / audience / JWKS / token proxy / client-assertion key / HMAC secret); see [docs/dev/auth-and-permissions.md](docs/dev/auth-and-permissions.md). Under the local `dev` profile, file storage defaults to local disk.

### 3. Run the backend

```bash
cd backend && mvn spring-boot:run
# health: http://localhost:8080/api/v1/health
```

### 4. Run the frontend

```bash
cd frontend && npm install && npm run dev
# frontend: http://localhost:5173
```

## API Examples

Search (JWT or API key):

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-ragforge-dev" \
  -d '{
    "query": "What Java project experience does the candidate have?",
    "strategy": "full",
    "topK": 5,
    "filter": { "chunkType": ["resume", "project"] }
  }'
```

RAG answering (SSE streaming):

```bash
curl -N -X POST http://localhost:8080/api/v1/answer \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-ragforge-dev" \
  -d '{ "query": "Summarize the candidate'\''s backend stack", "kbId": 16 }'
```

Notes:

- `sk-ragforge-dev` is provided by `DevApiKeyConfig` only under the `dev` profile; in production create `sk-rf-*` keys in the Developer Center (`/api`) or the `api_keys` table.
- API-key requests become a `SERVICE_ACCOUNT` context filtered by `allowed_kb_ids`; default rate limit is 100/min, fail-open if Redis is down.
- Plain admin APIs accept only Auth-Gateway-issued JWTs, not API keys.

## MCP Server

Built on Spring AI MCP (WebMVC SSE), server name `ragforge-mcp-server`, exposing three tools:

- `searchKnowledgeBase` — search a KB by strategy
- `listKnowledgeBases` — list accessible KBs
- `answerWithCitations` — RAG answer with citations

SSE subscribe endpoint `/sse`, message endpoint `/mcp/message`; both are filtered by the caller's readable KB scope.

## Database Migrations

The backend uses Flyway (`baseline-version=26`, `out-of-order=true`); migrations live in `backend/src/main/resources/db/migration`, latest is `V51`. Key evolution:

- `V1` initial 9 tables; `V4/V7` chunk_type; `V6` HNSW index (the 1024-dim era).
- `V9/V10/V12` KB owner/visibility, `kb_acl`, API-key extension.
- `V19~V26` identity fields, cleaning profiles, chunker profiles, multimodal image chunks.
- `V28` RAG answering (`answer_logs`); `V30/V32` LLM-as-Judge (`judge_results` etc.).
- `V35~V38` model cost center + rerank primary/fallback fix (`qwen3-rerank` as primary).
- `V42` JWT revocation list; `V44` break-glass audit; `V45` organization model (drops `tenant_id`).
- **Manual migration** `db/manual/V27__vl_unified_vector.sql`: switches the vector column from `vector(1024)` to the unified `vl_vector(2560)` (run outside Flyway).

## Deployment

Production uses **physical three-tier separation**, but the **app tier runs on k3s** (no longer docker-compose):

| Tier | Role | Components |
| --- | --- | --- |
| Data tier (standalone node) | state | PostgreSQL + pgvector, Elasticsearch, Redis, RocketMQ (bare-metal, not in k8s) |
| Entry tier (standalone node) | ingress | host Nginx: static frontend + `/api/` reverse-proxied to the app-tier NodePort |
| App tier (single-node k3s) | compute | all pods in the `ragforge` namespace |

The `ragforge` namespace (**one backend image, role switched by `RAGFORGE_ROLE`**):

| Deployment | Role | Replicas | Service |
| --- | --- | --- | --- |
| `ragforge-api` | REST / Search / Answer | 3 | NodePort `8080:31090` |
| `ragforge-frontend` | frontend (in-cluster) | 2 | NodePort `80:31002` |
| `ragforge-worker` | RocketMQ document consumer | 1 | none (background) |
| `ragforge-judge` | LLM-as-Judge | 1 | none (background) |

Entry path: `domain (443) → entry Nginx → app-tier NodePort 31090 → ragforge-backend Service → api pod`. The Auth Gateway runs in a separate namespace in the same cluster; RAGForge consumes its JWKS and token proxy over in-cluster DNS (`auth-gateway.auth-gateway.svc:8090`).

File storage is currently node-local `hostPath /data/files` (fine on a single node); the OSS abstraction (`ObjectStorage`) is in place and should be switched to OSS/NAS before multi-node scaling. k3s manifests are in [`deploy/k8s/ragforge/`](deploy/k8s/ragforge/); full details in [docs/deploy/deployment-architecture.md](docs/deploy/deployment-architecture.md).

> The historical deployment docs (docker-compose three-tier) are kept under `docs/deploy/` and marked as historical; **`deployment-architecture.md` is authoritative.**

## Documentation

- 📐 [Architecture (authoritative)](docs/architecture.md)
- 🛠️ Dev: [Auth & permissions](docs/dev/auth-and-permissions.md) · [Security & org model](docs/dev/security-and-multitenancy.md) · [Model cost center](docs/dev/model-cost-center-design.md) · [Refactor roadmap](docs/dev/current-architecture-and-refactor-roadmap.md)
- 🚀 Deploy: [Deployment architecture (k3s, authoritative)](docs/deploy/deployment-architecture.md) · [OSS CORS](docs/deploy/oss-cors-setup.md) · [SkyWalking business logs](docs/deploy/skywalking-business-logs.md)
- 🧪 Test: [Retrieval quality](docs/test/retrieval-quality-test-plan-V1.md) · [V5 acceptance cases](docs/test/v5-acceptance-playwright-cases.md)
- 🗂️ [Version timeline (CHANGELOG)](docs/CHANGELOG.md) · [Docs index](docs/INDEX.md)

## Status

RAGForge is live (see the [live site](https://ragforge.net)); ingestion, (multimodal) indexing, retrieval, RAG answering, debugging, evaluation, LLM-as-Judge, API keys, unified auth, organization permissions and the k3s deployment pipeline are all working.

Current online validation scale (approx.):

```text
~10,000 documents / ~100,000 chunks / 8 knowledge bases
```

Actual capacity depends on document length, chunking parameters, embedding throughput, ES/PG settings and resources. Vector search currently runs as a sequential scan (see [Multimodal & Vector Space](#multimodal--vector-space)), so it best fits small-to-medium knowledge-base retrieval.

Roadmap in [docs/dev/current-architecture-and-refactor-roadmap.md](docs/dev/current-architecture-and-refactor-roadmap.md): vector index optimization, switching file storage to OSS, deeper api/worker/judge resource isolation, richer evaluation metrics, observability and alerting.

## License

No open-source license is declared yet. Please contact the author for reuse.
