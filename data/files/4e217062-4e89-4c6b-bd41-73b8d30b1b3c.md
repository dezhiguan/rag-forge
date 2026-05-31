# 00 - 项目总规范

## 项目名称

**ai-rag-lab-platform**

## 项目定位

企业级 RAG 知识库实验平台。

项目目标不是一次性做一个大而全的系统，而是按版本逐步开发，让每个版本都能运行、能测试、能观察现象，最终用于**学习、简历和面试展示**。

## 版本化开发原则

1. **只做当前版本要求的功能**，不提前实现后续版本。
2. **不提前创建**后续版本的空 Controller、Service、页面、表结构、索引。
3. **不随意引入**当前版本用不到的依赖。
4. **不重写**已经能正常运行的代码。
5. 每次修改后，必须保证后端和前端都能正常启动。
6. 每次开发完成后，更新 `docs/05-development-plan.md`、`docs/06-debug-log.md`、`docs/07-change-log.md`。

## 当前版本边界原则

- **当前版本**：V4 关键词检索版（详见 `docs/05-development-plan.md`）。
- V4 允许：Elasticsearch、BM25、`/api/search/*`、Debug 页 `searchMode` 切换等。
- V4 禁止：Hybrid、RRF、Reranker、Query Rewrite、Evaluation、权限、多轮对话、V5+ 空实现。
- **不提前实现** V5 Hybrid、V6 Reranker、V7 Evaluation、V8 工程化等功能。

## 技术栈

### 后端

| 技术 | 说明 |
|------|------|
| Spring Boot 3 | Web 框架 |
| JDK 17 | 运行环境 |
| Maven | 构建（**当前为单体**，不使用 Maven 多模块） |
| PostgreSQL | 关系型元数据 |
| PgVector | 向量存储与检索 |
| Elasticsearch 8.x | V4 BM25 关键词检索 |
| MyBatis-Plus | ORM |
| Lombok | 样板代码简化 |
| Knife4j / Swagger | API 文档 |

### 前端

| 技术 | 说明 |
|------|------|
| Vue 3 | UI 框架 |
| TypeScript | 类型安全 |
| Vite | 构建与 dev server |
| Element Plus | 组件库 |
| Pinia | 状态管理 |
| Axios | HTTP 客户端 |

## 项目结构原则

当前阶段使用**简单单体结构**（单 `backend` Maven 工程），不按领域拆 Maven 子模块；后续是否拆分需等明确需要时再评估。

后端包结构：

```text
com.guan.rag
├── common          # 统一响应、异常
├── config          # 配置、Bean
├── controller      # 跨模块入口（系统、Dashboard、Model）
└── module
    ├── kb          # 知识库
    ├── document    # 文档与分块
    ├── embedding   # 向量化
    ├── retrieval   # 向量检索
    ├── chat        # 问答
    ├── debug       # RAG Debug
    ├── search      # V4 BM25 / ES
    └── sample      # 样例数据
```

## API Key 安全规范

1. **禁止**将真实 API Key 提交到 Git 仓库。
2. 敏感配置放在项目根目录 **`.env`**，且 **`.env` 必须加入 `.gitignore`**。
3. **`.env.example`** 只放占位符（如 `your-dashscope-api-key`），不放真实密钥。
4. VS Code / Cursor 调试后端时通过 `launch.json` 的 `envFile` 加载 `.env`。
5. 脚本通过 `scripts/lib/load_dotenv.py` 自动加载 `.env`。

## Git 提交规范

1. 仅在用户明确要求时创建 commit。
2. 提交信息聚焦「为什么」，1～2 句完整句子。
3. 不提交 `.env`、凭证文件。
4. 不使用 `--no-verify` 等跳过 hook 的选项（除非用户明确要求）。

## Cursor 开发约束

1. 开发前先阅读 `docs/` 下 9 个核心文档（尤其 `05-development-plan.md`、`08-ai-collaboration.md`）。
2. 会话恢复时先总结上下文，**确认前不要改代码**（见 `08-ai-collaboration.md`）。
3. 只改当前版本范围内的代码与文档。
4. 文档重整、Bug 修复、功能开发均遵守版本边界。
5. 协作角色分工见 `docs/08-ai-collaboration.md`。

## 本地开发环境（无 Docker 可选）

本项目**不强制**使用 `docker-compose.yml`；**实际连接以项目根 `.env` 为准**。

| 来源 | 用途 |
|------|------|
| `.env` | 本机真实环境（推荐）：PostgreSQL、远程 ES、API Key |
| `application.yml` + `application-dev.yml` | Spring 默认与 `${ES_HOSTS}` 等占位符 |
| `docker-compose.yml` | 可选：本机能跑 Docker 时一键起 PG/ES |

**典型 `.env` 示例：**

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=postgres
POSTGRES_USER=amy
POSTGRES_PASSWORD=***

ES_HOSTS=http://your-remote-host:9200
ES_USERNAME=elastic
ES_PASSWORD=***

DASHSCOPE_API_KEY=...
DEEPSEEK_API_KEY=...
```

**启动顺序（无 Docker）：**

```bash
# 1. 确保 PostgreSQL、ES 可用（与 .env 一致）
python3 scripts/probe-rag-services.py

# 2. 后端（VS Code: RagApplication，或 mvn spring-boot:run -Dspring-boot.run.profiles=dev）
# 3. 首次 BM25 前：curl -X POST http://localhost:8080/api/search/index/rebuild
# 4. 前端
cd frontend && npm run dev
```

**探测与回归脚本：**

```bash
python3 scripts/run-v4-bm25-smoke-test.py
python3 scripts/run-v3-context-filter-tests.py
```

## 相关文档

| 文档 | 说明 |
|------|------|
| `01-requirements.md` | 需求与版本范围 |
| `02-architecture.md` | 架构设计 |
| `05-development-plan.md` | 当前开发计划与验收 |
| `08-ai-collaboration.md` | AI 协作规范 |
