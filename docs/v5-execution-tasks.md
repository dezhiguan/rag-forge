# RAGForge V5 执行任务清单（Codex 提示词版）

> 编制：2026-06-20 · 架构师：@guandezhi
> 用法：找到要执行的任务 → **复制 `=== COPY START ===` 与 `=== COPY END ===` 之间的全部内容** → 粘贴到 Codex
> 配套设计：[RAGForge-优化设计文档-V5.html](./RAGForge-优化设计文档-V5.html)

---

## 依赖与顺序

```
P0 基础层（必须按顺序，T1+T2 可并行）
  T1 OSS SPI ──┐
                ├──▶ T3 IngestService 重构 ──▶ T4 双通道上传 + Worker 状态机
  T2 V19 迁移 ─┘

P1（T4 完成后并行）
  T5 presigned B 通道 · T6 重处理 + 状态 UI · T7 citations_snapshot

P2~P4（T4 完成后高度并行）
  T8 数据清洗 · T9 多策略分块 · T10 多模态一期 · T11 Answer-as-LLM

P5
  T12 文档 + 加固 + 监控
```

| 编号 | 名称 | 依赖 | 工期 | 状态 |
|---|---|---|---|---|
| T1 | ObjectStorage SPI + 阿里云 OSS | — | 1.5d | ⏳ |
| T2 | V19 迁移 + DTO | — | 0.5d | ⏳ |
| T3 | IngestService.register + 硬覆盖 | T1+T2 | 2d | ⏳ |
| T4 | 双通道上传 + Worker 状态机 | T3 | 1.5d | ⏳ |
| T5 | presigned B 通道 | T4 | 1d | ⏳ |
| T6 | 重处理 + 状态 UI | T4 | 1d | ⏳ |
| T7 | citations_snapshot + 评测补丁 | T2 | 1d | ⏳ |
| T8 | 数据清洗管道 | T4 | 2d | ⏳ |
| T9 | 多策略分块 | T4 | 2.5d | ⏳ |
| T10 | 多模态一期 | T1 | 3d | ⏳ |
| T11 | Answer-as-LLM | RetrievalService | 3d | ⏳ |
| T12 | 文档 + 加固 + 监控 | T11 | 2d | ⏳ |

**MVP 里程碑**：T1+T2+T3+T4 ≈ 5.5 天 → boss-scraper 114 条 JD 可用硬覆盖增量更新跑起来。

---
---

## T1：ObjectStorage SPI + 阿里云 OSS 实现

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者，跟 @guandezhi 架构师协作。架构师已出 V5 设计与任务拆分，你按本提示词执行 T1。

必读上下文（先全部读完再开工）：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§7.1、§7.2、§7.5、§7.6
2. /Users/amy/CursorProject/rag-forge/docs/architecture.md  了解 V4 当前文件存储现状
3. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/service/FileStorageService.java
4. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/service/impl/LocalFileStorageService.java
5. /Users/amy/CursorProject/rag-forge/backend/pom.xml  确认是否已有 aliyun-sdk-oss

任务：T1 ObjectStorage SPI + 阿里云 OSS 实现
依赖：无
工期：1 天

范围：
1. 新建包 com.ragforge.storage
2. 定义 ObjectStorage 接口（SPI）
3. 两个实现类：AliyunOssStorage / LocalDiskStorage
4. 配置开关 storage.backend = aliyun | local
5. LocalDiskStorage 仅用于本地开发兜底；生产默认 aliyun
6. 不动 DocumentServiceImpl / FileStorageService 现有调用方（兼容期，T4 才换调用方）
7. 不做灰度 / 双写 / 回落（架构师明确决定）

字段设计（必须严格按此实现）：

public interface ObjectStorage {
    PutResult put(String bucket, String key, InputStream in, ObjectMeta meta);
    InputStream get(String bucket, String key);
    ObjectMeta head(String bucket, String key);            // 返回 null 表示对象不存在
    String presignedGet(String bucket, String key, Duration ttl);
    String presignedPut(String bucket, String key, Duration ttl, ObjectMeta meta);
    void delete(String bucket, String key);                // 幂等，对象不存在不抛异常
    boolean exists(String bucket, String key);
}

public class ObjectMeta {
    String contentType;
    Long sizeBytes;
    String etag;
    Map<String,String> userMeta;
}

public class PutResult {
    String bucket;
    String key;
    String etag;
    Long sizeBytes;
}

配置示例 application-dev.yml：
storage:
  backend: aliyun                    # aliyun | local
  aliyun:
    endpoint: oss-cn-shenzhen.aliyuncs.com
    bucket: ragforge-dev
    accessKeyId: ${ALIYUN_OSS_AK}
    accessKeySecret: ${ALIYUN_OSS_SK}
  local:
    rootPath: /data/files

application-prod.yml 必须 backend: aliyun。
application-local.yml 可以 backend: local。

禁止项：
- 不要改 DocumentServiceImpl / FileStorageService 任何一行（T4 才会替换调用方）
- 不要迁移存量数据（留给 T12）
- AK/SK 严禁写死，必须从 application.yml + 环境变量读
- LocalDiskStorage 不能改 V4 的目录结构，否则 V4 老文件读不到
- 不引入 OkHttp / WebClient 等新 HTTP 客户端，复用项目已有的依赖
- 不要实现 DualWriteStorage / 任何形式的双写/灰度/回落（架构师明确决定单写直切）
- AliyunOssStorage 启动时必须做一次 bucket 连通性自检（HeadBucket），失败直接 fail-fast 不让应用起来

验收标准：
1. mvn test 全绿
2. 单测覆盖：AliyunOssStorage 用 LocalStack/Mock，覆盖 put/get/head/delete/exists 全部分支
3. 集成测试：100MB PDF 流式 put → head 校验 size 一致 → get 流式读出 → md5 与原文件一致
4. backend=local 时上传/下载行为与 V4 完全一致（跑 V4 现有端到端用例全绿）
5. backend=aliyun 时 AK/SK 错误 → 应用启动失败（fail-fast），不能起来后才出错

执行流程：
1. 先输出"我将改动的关键文件清单"（≤8 个文件）
2. 提交实施计划 + 不确定项（如 OSS SDK 版本、bucket 命名规范），等架构师确认
3. 写代码 + 单测 + 集成测试
4. 跑验收用例，截图/日志贴 PR
5. PR 标题：feat(v5/T1): ObjectStorage SPI + 阿里云 OSS
6. PR 描述里贴 V5 文档 §7.2/§7.5/§7.6 引用
7. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T1 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- 遇到任何不确定项先问，不要猜
- 代码片段用 ```java 包裹
````

`=== COPY END ===`

---

## T2：V19 数据库迁移 + 公共 DTO

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T2。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§9.2、§9.3、§9.4、§10
2. /Users/amy/CursorProject/rag-forge/backend/src/main/resources/db/migration/  查看已有 V1~V12 迁移
3. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/model/entity/Document.java

任务：T2 V19 数据库迁移 + 公共 DTO
依赖：无
工期：0.5 天

范围：
1. 写一个 Flyway 迁移文件 V19__identity_and_replace.sql
2. 新增 Java DTO：IngestCommand / Identity / OnConflict / IngestResult
3. 不写业务逻辑（T3 才用）

字段设计（SQL 必须严格按此实现）：

-- V19__identity_and_replace.sql

ALTER TABLE documents
  ADD COLUMN external_id    VARCHAR(128),
  ADD COLUMN source_url     VARCHAR(1024),
  ADD COLUMN content_md5    VARCHAR(64),
  ADD COLUMN storage_bucket VARCHAR(128),
  ADD COLUMN ingest_source  VARCHAR(64);

CREATE UNIQUE INDEX uk_doc_kb_external
  ON documents (kb_id, external_id)
  WHERE external_id IS NOT NULL;

CREATE UNIQUE INDEX uk_doc_kb_url
  ON documents (kb_id, source_url)
  WHERE source_url IS NOT NULL AND external_id IS NULL;

CREATE UNIQUE INDEX uk_doc_kb_md5
  ON documents (kb_id, content_md5)
  WHERE content_md5 IS NOT NULL AND external_id IS NULL AND source_url IS NULL;

ALTER TABLE documents
  DROP CONSTRAINT IF EXISTS ck_documents_parse_status;
ALTER TABLE documents
  ADD CONSTRAINT ck_documents_parse_status
    CHECK (parse_status IN ('PENDING','PROCESSING','COMPLETED','FAILED','REPROCESSING'));

ALTER TABLE answer_logs
  ADD COLUMN citations_snapshot JSONB;

ALTER TABLE eval_questions
  ADD COLUMN expected_text_snippets JSONB;

Java DTO（必须严格按此实现）：

public class IngestCommand {
    Long kbId;
    String storageBucket;
    String storageKey;
    String filename;
    Long sizeBytes;
    String contentType;
    Identity identity;            // 至少有一个字段非空
    OnConflict onConflict;        // 默认 REJECT
    String ingestSource;
    String indexedContent;        // 可选
    String chunkType;
    Map<String,Object> metadata;
}

public class Identity {
    String externalId;
    String sourceUrl;
    String contentMd5;
}

public enum OnConflict { REJECT, SKIP, REPLACE }

public class IngestResult {
    Long documentId;
    Status status;                // CREATED / SKIPPED / REPLACED
    String message;
    enum Status { CREATED, SKIPPED, REPLACED }
}

禁止项：
- 不能用 DEFAULT 把现有 doc 都填上假身份
- 不能用 ALTER TYPE 改 parse_status，必须用 CHECK 约束
- 不能删除 expected_chunk_ids 旧字段（T7 还会用）
- DTO 不能加 Lombok 之外的注解（避免影响 T3 的灵活性）
- 不要写 Mapper / Service 逻辑（只是字段 + DTO）

验收标准：
1. Flyway 在已有 V4 库（含 V1~V12）上能干净 migrate up，无失败
2. 唯一索引验证（手写 SQL 测试）：
   - 同 kb_id 下重复 external_id 第二次 INSERT 报 PG 错误码 23505
   - external_id 为 NULL 时唯一索引不生效（可重复插入）
3. parse_status='REPROCESSING' INSERT 不被 CHECK 约束拒绝
4. DTO 单测：OnConflict 三个枚举值 JSON 序列化/反序列化正常

执行流程：
1. 列出要新增的文件（应该只有 1 个 SQL + 4 个 Java 文件）
2. 写代码
3. 跑 mvn flyway:migrate + 单测
4. PR 标题：feat(v5/T2): V19 identity & replace migration + DTO
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T2 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- SQL 用 ```sql 包裹，Java 用 ```java 包裹
````

`=== COPY END ===`

---

## T3：IngestService.register + 身份解析 + 硬覆盖流程

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T3。本任务是 V5 的核心，会决定上传链路的最终形态。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§9.5 全部、§9.7 七条约束、§9.6 Worker 视角
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/service/impl/DocumentServiceImpl.java（看 V4 现有 upload / replaceDocument / uploadText 实现）
3. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/mq/  （看现有 RocketMQ Producer / Consumer）
4. T1 交付：com.ragforge.storage.ObjectStorage
5. T2 交付：IngestCommand / Identity / OnConflict / IngestResult

任务：T3 IngestService.register + 身份解析 + 硬覆盖流程
依赖：T1 + T2 已合入
工期：2 天

范围：
1. 新建 com.ragforge.service.ingest.IngestService 接口 + IngestServiceImpl 实现
2. 实现 identity 三层优先级解析（externalId → sourceUrl → contentMd5）
3. 实现 REJECT / SKIP / REPLACE 三条路径
4. REPLACE 路径事务设计：DB chunks 删除 + doc UPDATE 在事务内；ES/OSS/MQ 全部走 afterCommit
5. 不实现 Controller（T4 负责）
6. 不实现 Worker（T4 负责）
7. 当前 DocumentServiceImpl 的 upload 方法保持不动（T4 才替换调用方）

核心代码骨架（必须严格按此结构）：

@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IngestService {

    @Transactional
    public IngestResult register(IngestCommand cmd) {
        Document existing = resolveByIdentity(cmd.getKbId(), cmd.getIdentity());

        if (existing == null) {
            return doCreate(cmd);
        }

        boolean md5Same = Objects.equals(existing.getContentMd5(), cmd.getIdentity().getContentMd5());

        switch (cmd.getOnConflict()) {
            case REJECT:
                throw new BizException(409, "DOC_IDENTITY_CONFLICT");
            case SKIP:
                return IngestResult.skipped(existing.getId());
            case REPLACE:
                return md5Same
                    ? IngestResult.skipped(existing.getId())
                    : doReplace(existing, cmd);
            default:
                throw new IllegalStateException();
        }
    }

    private Document resolveByIdentity(Long kbId, Identity id) {
        // 三层优先级：externalId 优先，命中则返回；否则 sourceUrl；否则 contentMd5
        // 注意：每一层都是 WHERE (kbId, fieldX) match AND (前面字段都是 NULL 才查后面层) 才符合 V19 唯一索引语义
    }

    private IngestResult doCreate(IngestCommand cmd) {
        Document doc = new Document();
        // 填字段，parse_status = PENDING
        documentMapper.insert(doc);
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() {
                    mqProducer.send(PROCESS_TOPIC, doc.getId());
                }
            });
        return IngestResult.created(doc.getId());
    }

    private IngestResult doReplace(Document old, IngestCommand cmd) {
        final String oldBucket = old.getStorageBucket();
        final String oldKey    = old.getStorageKey();
        final Long   oldDocId  = old.getId();

        // 事务内只动 DB
        documentMapper.updateStatus(oldDocId, "REPROCESSING");
        chunkMapper.deleteByDocumentId(oldDocId);
        documentMapper.replaceFields(oldDocId, cmd);     // storage_key、md5、size、filename、chunk_count=0
        documentMapper.updateStatus(oldDocId, "PENDING");

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() {
                    esIndexer.deleteChunksOfDoc(oldDocId);
                    objectStorage.delete(oldBucket, oldKey);   // 立刻删，不留历史
                    mqProducer.send(PROCESS_TOPIC, oldDocId);
                }
            });

        return IngestResult.replaced(oldDocId);
    }
}

禁止项：
- 不能在事务里调 ES / OSS / MQ（必须 afterCommit）
- REPLACE 路径不能改 docId，必须用 UPDATE
- 不能引入 version / is_current / superseded_by 字段（V5 明确不要版本化）
- SKIP 路径不要重新发 MQ（无副作用）
- 不要在 IngestServiceImpl 里直接读文件或算 md5（这些是 Controller / 客户端的责任）
- 不要改 DocumentServiceImpl 任何一行
- 不要给 IngestService 加 reprocess 方法（T6 才做）

验收标准：
1. mvn test 全绿
2. JUnit 必须覆盖：
   - identity 三层优先级解析（externalId 优先于 url 优先于 md5）
   - REJECT / SKIP / REPLACE 三条路径分支
   - REPLACE 事务回滚测试：mock objectStorage.delete 抛异常，验证 chunks 已删，但因为 ES/OSS/MQ 在 afterCommit，删除前事务已提交，所以 chunks 删除是生效的；需要测试事务内 chunkMapper.deleteByDocumentId 抛异常时，doc 状态没被改
   - 并发 REPLACE：2 个线程同时 REPLACE 同一 docId，最终 chunks 内容来自后到的那次（用 @Transactional + 行锁验证）
3. 集成测试（用 TestContainer 起 PG + ES + RocketMQ）：
   - 同一 externalId 第二次 REPLACE → 老 OSS key 已删、ES 老索引已删、新 chunks 已生成
   - md5 一致 + onConflict=SKIP → 返回 SKIPPED，0 次 embedding 调用

执行流程：
1. 列出会新建/改动的文件（≤10 个）
2. 提交实施计划：特别说明 resolveByIdentity 的三层 SQL 写法（一次 OR 还是三次单独查），等架构师确认
3. 写代码 + 单测 + 集成测试
4. PR 标题：feat(v5/T3): IngestService.register with identity-based hard overwrite
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T3 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- 关键的事务边界用伪代码画一张时序图
````

`=== COPY END ===`

---

## T4：双通道上传 Controller + Worker 状态机改造

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T4。本任务把 T1+T2+T3 的成果串到 V4 的真实 Controller / Worker 上，是 MVP 的最后一公里。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§7.3、§9.6
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/controller/DocumentController.java
3. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/mq/  （DocumentProcessConsumer 现有实现）
4. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/search/RetrievalService.java
5. T1/T2/T3 全部交付物

任务：T4 双通道上传 A 通道 + Worker 状态机
依赖：T3 已合入
工期：1.5 天

范围：
1. 改造 POST /api/v1/documents：A 通道，multipart 流式 PUT 到 OSS → 组 IngestCommand → 调 IngestService.register
2. 入参增加 identity / onConflict / ingestSource；出参增加 status: CREATED|SKIPPED|REPLACED
3. fileSize > 50MB 时返回 413 + presignUrl 提示
4. 改造 DocumentProcessConsumer：CAS 守卫 + 5 分钟卡死复活
5. 改造 RetrievalService：默认过滤 parse_status = COMPLETED
6. 不实现 presigned B 通道（T5）
7. 不实现 reprocess 接口（T6）

接口设计：

POST /api/v1/documents
Header: X-Api-Key 或 Bearer JWT
Content-Type: multipart/form-data
Parts:
  - file: <binary>
  - meta: JSON 字符串
    {
      "kbId": 16,
      "identity": { "externalId": "...", "sourceUrl": "...", "contentMd5": "..." },
      "onConflict": "REJECT|SKIP|REPLACE",
      "ingestSource": "boss-scraper",
      "chunkType": "JD",
      "metadata": {...}
    }

Response 200:
  { "documentId": 9912, "status": "CREATED|SKIPPED|REPLACED" }
Response 409:
  { "error": "DOC_IDENTITY_CONFLICT", "existingDocId": 8810 }
Response 413:
  { "error": "FILE_TOO_LARGE_FOR_RELAY",
    "presignUrl": "/api/v1/uploads/presign",
    "limitMb": 50 }

Worker CAS（必须严格按此 SQL）：

UPDATE documents
SET parse_status = 'PROCESSING', updated_at = NOW()
WHERE id = ?
  AND (parse_status = 'PENDING'
       OR (parse_status = 'PROCESSING' AND updated_at < NOW() - INTERVAL '5 minutes'));
-- 返回受影响行数 0 即 ACK 跳过

application.yml 必须加：
spring.servlet.multipart:
  max-file-size: 50MB
  max-request-size: 60MB
  file-size-threshold: 1MB

A 通道 Controller 关键流程：
1. 鉴权（JWT 或 API Key）
2. 校验 KB 写权限（复用 KbAccessGuard.canWrite）
3. 算 content_md5（DigestInputStream 边读边算，不要 file.getBytes() 全读进内存）
4. 生成 storage_key：{tenantId}/{kbId}/{uuid}/{originalFilename}
5. 流式 objectStorage.put(bucket, key, in, meta)
6. 组 IngestCommand 调 ingestService.register(cmd)
7. 返回 IngestResult

禁止项：
- 不能让 multipart 进堆内存（必须用 file-size-threshold 1MB 走临时文件）
- 不能用 file.getBytes() 一次性读全（流式处理）
- Worker 不能查 chunks 表来判断是否需要"删旧"（chunks 已被 IngestService 删掉）
- 不能去掉 V4 现有的 parseStatus 字段
- A 通道遇到 fileSize > 50MB 必须返回 413，不能直接 500 或 OOM
- 不能在 Controller 里直接调 PG / ES（必须通过 IngestService）
- 不要修改 IngestService（T3 已经设计好）
- 不要给 Worker 加判断"这是 REPLACE 还是 CREATE"的逻辑（Worker 不该知道）

验收标准：
1. mvn test 全绿
2. Playwright（前端 E2E）：
   - 浏览器上传 5MB PDF → 接口 200 + status=CREATED → 60s 内 DocumentDetail 显示 chunk_count > 0
   - 同一份 PDF 二次上传 + onConflict=REJECT → 接口 409
3. 命令行（curl 脚本）：
   - REPLACE 同一 externalId 但改了内容 → status=REPLACED → 老 OSS key 不存在（aliyun head 404）→ 新 chunks 进 ES
4. 杀掉 Worker 后 5 分钟，新 Worker 启动能自动接管 PROCESSING 卡住的 doc
5. 上传 60MB 文件 → 接口 413 + 响应体包含 presignUrl
6. V4 现有 200 条 E2E 全绿（保证向下兼容）

执行流程：
1. 列出会改动的文件（≤8 个）
2. 提交实施计划，特别说明：流式 md5 + 流式 OSS PUT 的具体写法（DigestInputStream 包装链）
3. 写代码 + Playwright 测试
4. PR 标题：feat(v5/T4): dual-channel upload (A relay) + worker state machine
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T4 ✅ <commit-sha> 2026-MM-DD
6. MVP 达成：发个消息通知架构师，附 boss-scraper 测试用 curl 命令

输出格式：
- 用中文回复
- 关键的流式 IO 用伪代码画清楚
````

`=== COPY END ===`

---

## T5：presigned B 通道（大文件支持）

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T5。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§7.3、§7.5
2. T1 交付：ObjectStorage.presignedPut / head
3. T3 交付：IngestService.register
4. T4 交付：A 通道 Controller（参考其鉴权与 IngestCommand 组装方式）

任务：T5 presigned B 通道
依赖：T4 已合入
工期：1 天

范围：
1. 新接口 POST /api/v1/uploads/presign：签发 uploadToken + OSS 预签名 PUT URL（TTL 15min）
2. 新接口 POST /api/v1/documents/register：校验 uploadToken（Redis 单次）+ headObject 校验 + 调 IngestService.register
3. 不修改 A 通道
4. uploadToken 用 Redis 存储，键 ragforge:upload:token:{token}，TTL 同 OSS presigned URL

接口设计：

POST /api/v1/uploads/presign
Body:
  { "kbId": 16, "filename": "big.pdf", "contentType": "application/pdf", "declaredSize": 83886080 }
Response:
  { "uploadToken": "uplt_xxx",
    "presignedPutUrl": "https://...",
    "storageBucket": "ragforge-dev",
    "storageKey": "tn_xxx/kb_16/uplt_xxx/big.pdf",
    "expiresAt": "2026-06-20T10:45:00Z" }

POST /api/v1/documents/register
Body:
  { "uploadToken": "uplt_xxx",
    "kbId": 16,
    "identity": {...},
    "onConflict": "REPLACE",
    "ingestSource": "web-upload-large",
    "metadata": {...} }
Response: 同 A 通道 IngestResult

校验逻辑（必须严格按此）：
1. Redis 单次消费 uploadToken（GETDEL），不存在 → 409 TOKEN_INVALID
2. token 的 kbId 必须与请求 kbId 一致 → 否则 403
3. objectStorage.head(bucket, key) 必须返回非 null → 否则 422 UPLOAD_NOT_FOUND
4. head 返回的 size 必须等于 declaredSize → 否则 422 SIZE_MISMATCH
5. 通过校验后组 IngestCommand 调 ingestService.register

禁止项：
- uploadToken 必须 Redis 单次使用（GETDEL 原子操作），不能复用
- 不能信任客户端声明的 size / contentType，必须 headObject 校验
- uploadToken 不能跨租户复用（payload 里必须带 tenantId，register 阶段校验）
- 不能修改 IngestService.register（T3 已经稳定）
- 不能用 UUID 直接做 uploadToken，必须用 JWT 或加密 token 保证不可伪造
- 不能让 presigned URL TTL 超过 1 小时

验收标准：
1. Playwright：80MB 文件 → 自动走 B 通道 → 上传成功 → DocumentDetail 显示
2. 同一 uploadToken 重放调 /documents/register → 409 TOKEN_INVALID
3. 客户端声明 size=1MB 但实际上传 10MB → register 阶段 422 SIZE_MISMATCH
4. 用 KB=15 的 token 调 register 时声明 kbId=16 → 403
5. presigned URL 过期后 PUT → OSS 直接 403（OSS 自身机制，不在后端测）

执行流程：
1. 列出会新增的文件（≤5 个）
2. 写代码 + 单测 + Playwright
3. PR 标题：feat(v5/T5): presigned upload channel B
4. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T5 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- Redis 操作必须用 GETDEL 原子命令，不能 GET + DEL 两次调用
````

`=== COPY END ===`

---

## T6：重处理按钮 + DocumentDetail 状态徽标

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T6。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§9.6、§12
2. /Users/amy/CursorProject/rag-forge/frontend/src/views/DocumentDetail.vue（V4 当前实现）
3. T4 交付：parse_status 状态机（PENDING/PROCESSING/REPROCESSING/FAILED/COMPLETED）

任务：T6 重处理按钮 + DocumentDetail 状态徽标 + 身份字段展示
依赖：T4 已合入
工期：1 天

范围：
1. 新接口 POST /api/v1/documents/{id}/reprocess
2. 前端 DocumentDetail.vue 改造：
   a. 状态徽标：PROCESSING / REPROCESSING / FAILED / COMPLETED 四种 + 颜色区分
   b. "重新处理"按钮：仅 FAILED 状态显示，二次确认 modal
   c. 身份字段展示区：externalId / sourceUrl / contentMd5 / ingestSource
3. 不改其他页面

后端接口（必须严格按此）：

POST /api/v1/documents/{id}/reprocess
权限：KbAccessGuard.canWriteDocument(id)
逻辑：
  doc = documentMapper.selectById(id)
  if doc == null → 404
  if doc.parseStatus IN ('PENDING','PROCESSING','REPROCESSING') → 409 ALREADY_IN_PROGRESS
  # 只允许 FAILED 或 COMPLETED 触发
  documentMapper.updateStatus(id, 'PENDING')
  TransactionSynchronizationManager.afterCommit:
    mqProducer.send(PROCESS_TOPIC, id)
  return { documentId, status: 'PENDING' }

前端 DocumentDetail.vue 关键变更：

<template>
  <!-- 顶部加状态徽标 -->
  <StatusBadge :status="doc.parseStatus" />

  <!-- 身份字段展示区 -->
  <section v-if="doc.externalId || doc.sourceUrl">
    <h4>身份信息</h4>
    <div>externalId: {{ doc.externalId || '—' }}</div>
    <div>sourceUrl: <a v-if="doc.sourceUrl" :href="doc.sourceUrl" target="_blank">{{ doc.sourceUrl }}</a></div>
    <div>来源通道: {{ doc.ingestSource || '—' }}</div>
    <div>内容 md5: <code>{{ doc.contentMd5 }}</code></div>
  </section>

  <!-- 重新处理按钮 -->
  <button
    v-if="doc.parseStatus === 'FAILED'"
    @click="confirmReprocess">
    重新处理
  </button>
</template>

StatusBadge 颜色规范：
- PENDING:      灰色 + "排队中"
- PROCESSING:   蓝色脉冲动画 + "处理中"
- REPROCESSING: 橙色脉冲动画 + "重新处理中"
- COMPLETED:    绿色 + "已完成"
- FAILED:       红色 + "失败"

禁止项：
- 只允许 FAILED / COMPLETED 状态触发 reprocess，PROCESSING/PENDING/REPROCESSING 中拒绝（防止并发）
- 状态徽标不能挡住下载按钮（靠左对齐）
- "重新处理"按钮必须二次确认（Element Plus 的 MessageBox），避免误点重跑 embedding 烧钱
- 不能改其他页面（Dashboard / KnowledgeBase 暂不动）
- 不能新增字段到 Document entity（V19 已经有了）
- 不能让前端轮询频率高于 5 秒/次（PROCESSING 状态下轮询用 5s）

验收标准：
1. Playwright：人造 FAILED → 进 DocumentDetail → 看到红色失败徽标 + 重新处理按钮 → 点击 → 确认 modal → 状态变 PENDING → 5s 内变 PROCESSING → 30s 后 COMPLETED
2. Playwright：PROCESSING 中的 doc 调 reprocess 接口 → 409
3. 视觉走查：四种状态徽标颜色和文案符合规范
4. 身份字段为空时整个"身份信息"区不展示（v-if 生效）

执行流程：
1. 列出会改动的文件（≤4 个）
2. 写代码 + Playwright
3. PR 标题：feat(v5/T6): reprocess endpoint + DocumentDetail status & identity UI
4. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T6 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- StatusBadge 拆成独立组件 components/StatusBadge.vue
````

`=== COPY END ===`

---

## T7：AnswerLog citations_snapshot + 评测稳定性补丁

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T7。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§9.7 ⑦
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/service/EvalExperimentService.java（V4 评测引擎）
3. T2 交付：answer_logs.citations_snapshot 字段、eval_questions.expected_text_snippets 字段

任务：T7 AnswerLog citations_snapshot + 评测稳定性补丁
依赖：T2 已合入（T11 还没做也没关系，先把字段写入逻辑准备好）
工期：1 天

范围：
1. 在 V4 现有的 RetrievalLogService（如有写应答日志的地方）里，把召回的 chunks 的 text 快照存入 citations_snapshot
2. EvaluationLab 评测引擎升级：
   a. 评测题录入时支持 expected_text_snippets 字段
   b. 运行评测时同时支持两种匹配：
      - 老题：按 expected_chunk_ids 精确匹配（向下兼容）
      - 新题：按 expected_text_snippets 在 chunks 表做 ILIKE 近似匹配
   c. 失败样本展示页同时展示两种匹配的结果

字段示例（citations_snapshot 结构）：

[
  { "chunkId": 9912, "docId": 882, "textSnippet": "前 300 字...", "score": 0.81 },
  { "chunkId": 9913, "docId": 882, "textSnippet": "前 300 字...", "score": 0.76 }
]

ILIKE 近似匹配伪代码：

for snippet in question.expectedTextSnippets:
    hit = chunks.stream()
            .anyMatch(c -> c.content.contains(snippet))  # 或 normalized + ILIKE
    if hit:
        return true

禁止项：
- 不能删除 expected_chunk_ids 旧字段（保留向下兼容）
- 不能改 EvaluationLab 现有 API 的入参（向下兼容）
- 文本快照不能超过 300 字（避免 JSONB 字段膨胀）
- ILIKE 匹配必须先做文本归一化（去空白、小写、unicode 归一），不能直接 raw 比对
- 不能新增表（只在现有表加列，T2 已经加好）

验收标准：
1. mvn test 全绿
2. JUnit：硬覆盖 doc 后，老 AnswerLog 历史回看仍能显示完整引用文本（chunks 已删，但 snapshot 还在）
3. 集成测试：
   - 创建一个评测集，10 道题用 expected_chunk_ids、10 道题用 expected_text_snippets
   - REPLACE 其中 5 个 doc（chunk_id 全部失效）
   - 重跑评测，新题型 Top1/MRR 仍可计算，老题型有 5 道因为 chunk_id 失效变成 0 命中（符合预期）
4. 前端 EvaluationLab 失败样本页能显示两种匹配的具体内容

执行流程：
1. 列出会改动的文件（≤6 个）
2. 写代码 + 单测 + 集成测试
3. PR 标题：feat(v5/T7): citations snapshot + evaluation chunk-id resilience
4. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T7 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- ILIKE 归一化函数封装成 TextNormalizer 工具类
````

`=== COPY END ===`

---

## T8：数据清洗管道（Cleaner）

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T8。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§4 全节
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/pipeline/  （现有 parser/chunker/embedder/indexer）
3. T4 已合入：Worker 状态机和 ingest 链路已稳定

任务：T8 数据清洗管道 L1+L2+L3 + KB 配置 + 清洗对比 UI
依赖：T4 已合入
工期：2 天

范围：
1. 新建 com.ragforge.pipeline.cleaner 包
2. Cleaner SPI 接口 + 三层实现：
   a. L1 Normalize：unicode NFKC 归一化、控制符过滤、换行收敛、全半角统一
   b. L2 Denoise：页眉页脚识别（位置 + 跨页重复度）、水印、目录 TOC
   c. L3 PII Mask：手机 / 身份证 / 邮箱 / 银行卡 → 哈希或 * 替换
3. KB 级 clean_profile 配置（V13 迁移，按 V5 文档 §4.3）
4. Worker 管道插入位置：Parser → Cleaner → Chunker → ...
5. DocumentDetail 新增"清洗对比"面板（左原文 / 右清洗后 / 高亮 diff）
6. 不做 L4 LLM Rewrite

Cleaner SPI（必须严格按此）：

public interface Cleaner {
    String name();           // L1_NORMALIZE / L2_DENOISE / L3_PII_MASK
    boolean enabled(CleanProfile profile);
    CleanResult clean(RawText raw, CleanProfile profile);
}

public class CleanResult {
    String cleanedText;
    List<RemovedRegion> removedRegions;  // 删了哪些（offset + reason）
    Map<String,Integer> piiHits;         // {phone:3, idCard:1}
    int llmTokensUsed;                   // L4 才用，L1-L3 都是 0
}

public class CleanProfile {
    boolean l1Enabled = true;
    boolean l2Enabled = true;
    boolean l3Enabled = true;
    boolean l4Enabled = false;            // V5 一期硬关闭
    PiiPolicy piiPolicy = PiiPolicy.MASK;  // MASK / HASH / REJECT
    boolean skipClean = false;             // 白名单跳过
}

V13 迁移：

CREATE TABLE clean_profiles (
  id BIGSERIAL PRIMARY KEY,
  scope VARCHAR(16) NOT NULL,          -- TENANT / KB
  scope_id BIGINT NOT NULL,
  config JSONB NOT NULL,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

ALTER TABLE documents
  ADD COLUMN clean_report_json JSONB,
  ADD COLUMN clean_profile_id BIGINT;

PII 规则（必须覆盖）：
- 手机号：1[3-9]\d{9} → 138****1234
- 身份证：\d{17}[\dXx] → 校验位 + 前 6 后 4 保留
- 邮箱：[a-zA-Z0-9._%+-]+@... → a***@domain.com
- 银行卡：\d{16,19} → 前 4 后 4

禁止项：
- 不要在 Cleaner 里调 LLM（L4 留给 V6）
- KB 级 skip_clean=true 必须生效，不能漏判
- PII 必须在 chunk 入库前完成，document_chunks.content 不能存原始 PII
- removedRegions 必须存到 documents.clean_report_json，供前端对比展示
- 不能改 Parser / Chunker / Embedder / Indexer 现有逻辑
- 文档级"跳过清洗"开关不能影响 PII（合规优先）

验收标准：
1. mvn test 全绿
2. 单测覆盖 L1/L2/L3 各 30 条边界用例
3. 50 份 PDF 评测：清洗后 chunk 重复率下降 ≥ 40%，召回 Top1 提升 ≥ 8%
4. PII 单测：手机号 / 身份证 / 邮箱 / 银行卡 各 20 条变体规则覆盖（带空格、带连字符、半角全角）
5. Playwright：DocumentDetail 清洗对比面板能左右对照、高亮 diff
6. 一份带敏感信息的简历测试：document_chunks 表里查不到原始手机号

执行流程：
1. 列出会改动的文件（≤12 个）
2. 提交实施计划：特别说明页眉页脚的识别算法（基于段落位置 + TF-IDF 跨页重复度）
3. 写代码 + 单测 + 50 文档 A/B 验证
4. PR 标题：feat(v5/T8): data cleaning pipeline L1-L3
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T8 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- PII 正则单独写一个 PiiPatterns 类
````

`=== COPY END ===`

---

## T9：多策略分块（Chunker Family）

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T9。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§5 全节
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/pipeline/chunker/（V4 现有 FixedWindow 实现）
3. /Users/amy/CursorProject/rag-forge/frontend/src/views/EvaluationLab.vue
4. T4 已合入

任务：T9 多策略分块 SPI + 5 实现 + KB 配置 + 分块 A/B 试验页
依赖：T4 已合入
工期：2.5 天

范围：
1. ChunkerStrategy SPI + 5 实现：
   a. FIXED_WINDOW（保留 V4 实现，重命名 + 套接口）
   b. RECURSIVE（新增，新默认）
   c. MARKDOWN_HEADING
   d. SEMANTIC（依赖 query embedding，需要 DashScope embed 调用）
   e. TABLE_AWARE
2. KB 级 chunker_profile（V14 迁移）
3. document_chunks 增 chunker_strategy / chunker_params_json / heading_path
4. EvaluationLab 新增"分块 A/B 试验"页
5. 文档级"重新分块"按钮（FAILED 之外的状态也允许触发，但用户要二次确认）

ChunkerStrategy SPI（必须严格按此）：

public interface ChunkerStrategy {
    String name();    // FIXED_WINDOW / RECURSIVE / MARKDOWN_HEADING / SEMANTIC / TABLE_AWARE
    boolean supports(DocumentMeta meta);
    List<Chunk> split(CleanedText text, ChunkParams params);
}

public class ChunkParams {
    int chunkSize = 500;
    int overlap = 50;
    List<String> separators;            // RECURSIVE 用
    Integer maxHeadingLevel;            // MARKDOWN_HEADING 用
    Double simThreshold;                // SEMANTIC 用 (默认 0.65)
    TablePolicy tablePolicy;            // WHOLE / ROW
}

public class Chunk {
    String content;
    int seq;
    String headingPath;                 // 如 "前言/第二章/2.1 背景"
    Map<String,Object> chunkParamsJson;
}

V14 迁移：

ALTER TABLE document_chunks
  ADD COLUMN chunker_strategy VARCHAR(32),
  ADD COLUMN chunker_params_json JSONB,
  ADD COLUMN heading_path VARCHAR(512);

ALTER TABLE knowledge_bases
  ADD COLUMN chunker_profile_json JSONB;

KB 级 chunker_profile_json 示例：
{
  "default": "RECURSIVE",
  "fallbackChain": ["MARKDOWN_HEADING", "RECURSIVE"],
  "params": { "chunkSize": 500, "overlap": 50, "simThreshold": 0.65 }
}

A/B 试验页接口：

POST /api/v1/evaluation/chunker-ab
Body:
  { "evalDatasetId": 12,
    "strategies": ["RECURSIVE", "MARKDOWN_HEADING", "SEMANTIC"],
    "params": {...} }
Response:
  { "results": [
    { "strategy": "RECURSIVE", "top1": 0.62, "mrr": 0.71, "avgChunkLen": 480, "totalChunks": 12340 },
    ...
  ] }

禁止项：
- 现有 9800 文档不要自动重切，必须用户手动触发"重新分块"按钮
- SEMANTIC 默认关闭，不能成为系统级默认策略
- Chunker SPI 返回的 chunks 必须保持原文顺序
- TABLE_AWARE 不能依赖 PDFBox 的特定版本（用 Tika 已暴露的 metadata）
- 不要改 Cleaner / Embedder / Indexer 现有逻辑
- A/B 试验页跑出来的 chunks 不能写入主表（用 temp table 或内存）

验收标准：
1. mvn test 全绿
2. 单测：MARKDOWN_HEADING 对 README.md 切分能保留正确的 heading_path（多级嵌套）
3. 单测：RECURSIVE 在 separators=["\n\n","\n","。",","] 时能按优先级切
4. 单测：SEMANTIC 相邻句相似度 > 0.65 应合并，< 0.65 应切断
5. Playwright：EvaluationLab 选 3 种策略跑同一评测集，结果表对比 Top1/MRR/平均 chunk 长度
6. Playwright：DocumentDetail 点"重新分块" → 二次确认 → 状态变 REPROCESSING → 完成后 chunker_strategy 字段更新

执行流程：
1. 列出会改动的文件（≤15 个）
2. 提交实施计划：特别说明 SEMANTIC 的 embed 调用如何限流（用现有 Caffeine 缓存）
3. 写代码 + 单测 + Playwright
4. PR 标题：feat(v5/T9): chunker family (5 strategies) + KB profile + A/B lab
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T9 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- 每种策略单独一个文件，方便后续维护
````

`=== COPY END ===`

---

## T10：多模态一期（图片 + 扫描件）

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T10。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§8 全节
2. T1 交付：ObjectStorage（图片用同一套）
3. T4 已合入：Worker 状态机

任务：T10 多模态一期：图片/扫描件 OCR + 图像描述 + image_vector 索引
依赖：T1 已合入
工期：3 天

范围：
1. MIME 路由：Worker 拿到 doc 后按 contentType 分流
   - text/* application/pdf → TextPipeline（V4 现有）
   - image/* → ImagePipeline（新增）
2. ImagePipeline：
   a. PaddleOCR 提取文本 → 作为 OCR_TEXT 类 chunks 入库
   b. Qwen-VL-Max 生成图像描述 → 作为 IMAGE_DESC 类 chunks 入库
   c. BGE-VL embedding → 写入 image_vector 列
3. V17 迁移：chunk_modality / image_vector / image_key + HNSW 索引
4. RetrievalService 多向量 RRF 融合（text 0.7 / image 0.3，KB 可配）
5. DebugConsole 支持"图问图"（上传图片做 query）
6. UploadWizard 页面（识别 MIME 给提示）
7. 不做音视频

V17 迁移：

ALTER TABLE document_chunks
  ADD COLUMN chunk_modality VARCHAR(16) DEFAULT 'TEXT',
  ADD COLUMN image_vector vector(1024),
  ADD COLUMN image_key VARCHAR(512);

CREATE INDEX idx_chunks_image_vector_hnsw
  ON document_chunks USING hnsw (image_vector vector_cosine_ops)
  WITH (m=16, ef_construction=64)
  WHERE chunk_modality IN ('IMAGE_DESC','OCR_TEXT');

chunk_modality 枚举：TEXT / OCR_TEXT / IMAGE_DESC / IMAGE_NO_OCR

RetrievalService 多模态扩展（必须严格按此语义）：

if (modality == "text" or modality is null):
    只查 text_vector
elif (modality == "image"):
    只查 image_vector，用 CLIP 编码 query
elif (modality == "both"):
    分别查两个 vector，RRF 融合（默认 0.7/0.3）

禁止项：
- 不做音视频（推到二期）
- 图像向量与文本向量必须分两列，不能共用 embedding 列
- OCR 失败的图片不能让整个文档失败，标记 chunk_modality=IMAGE_NO_OCR 继续走描述
- 不能让 PaddleOCR / Qwen-VL 调用挂在主线程（必须异步）
- API 配额（DashScope）必须有限流，不能并发起飞
- 不能改 V4 现有 TextPipeline 任何一行

验收标准：
1. mvn test 全绿
2. 上传 1 张架构图 PNG → DocumentDetail 显示：OCR 文本 chunks + Qwen-VL 描述 chunks + 缩略图
3. DebugConsole "图问图"：上传同款架构图作 query，能命中该图片的 IMAGE_DESC chunk
4. 上传一份带图片的 PDF → text chunks 和 image chunks 都生成
5. 多模态 RRF 融合：modality=both 时检索结果同时出现文本和图像 chunk
6. OCR 故意失败（mock 抛异常）→ 文档状态仍是 COMPLETED，但有 IMAGE_NO_OCR chunk

执行流程：
1. 列出会改动的文件（≤20 个，跨多个包）
2. 提交实施计划：特别说明 PaddleOCR 部署方式（嵌入 Java 还是独立微服务，推荐独立微服务复用 reranker/ 目录的模式）
3. 写代码 + 单测 + E2E
4. PR 标题：feat(v5/T10): multimodal phase 1 (image OCR + VL + image vector)
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T10 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- 模型调用必须封装成 SPI，方便切换 Qwen-VL / InternVL
````

`=== COPY END ===`

---

## T11：Answer-as-LLM

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T11。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  重点：§6 全节、§9.7 ⑦（citations_snapshot 已由 T7 完成字段，本任务负责写入）
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/search/RetrievalService.java
3. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/mcp/  （V4 现有 MCP server）
4. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/service/LlmService.java
5. T2 + T7 已合入（citations_snapshot 字段已就绪）

任务：T11 Answer-as-LLM 应答层
依赖：现有 RetrievalService
工期：3 天

范围：
1. AnswerService + PromptBuilder + CitationLinker + GuardRails
2. POST /api/v1/answer（SSE 流式）
3. 新页面 AnswerPlayground
4. MCP 工具 answerWithCitations
5. KB 级 answer_mode = OFF | PREVIEW | ON（默认 OFF）
6. V15 迁移（answer_logs 表 + KB 应答字段）

V15 迁移：

CREATE TABLE answer_logs (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  principal_id VARCHAR(128),
  kb_ids BIGINT[] NOT NULL,
  query TEXT NOT NULL,
  answer TEXT NOT NULL,
  citations_snapshot JSONB,            -- T7 字段语义
  retrieval_strategy VARCHAR(32),
  prompt_tokens INT,
  completion_tokens INT,
  retrieval_latency_ms INT,
  llm_latency_ms INT,
  total_latency_ms INT,
  trace_id VARCHAR(128),
  created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_answer_logs_tenant_time ON answer_logs(tenant_id, created_at);

ALTER TABLE knowledge_bases
  ADD COLUMN answer_mode VARCHAR(16) NOT NULL DEFAULT 'OFF',
  ADD COLUMN answer_model VARCHAR(64),
  ADD COLUMN prompt_template_id BIGINT;

接口设计：

POST /api/v1/answer  (SSE 流式)
Body:
  { "kbIds": [16, 17],
    "query": "Java 25-30k 常见技术栈？",
    "retrievalStrategy": "full",
    "answerMode": "ON",
    "stream": true,
    "citationStyle": "INLINE",
    "maxTokens": 800 }

SSE chunks：
  event: retrieval
  data: { ...SearchResponse... }

  event: token
  data: { "delta": "广州 25-30k Java 岗位..." }

  event: complete
  data: { "answer": "...", "citations": [...], "tokens": {...}, "latency": {...} }

  event: error
  data: { "error": "NO_CITATIONS", "message": "..." }

GuardRails 必须拦截：
1. 应答无引用（citations 为空）→ 422 NO_CITATIONS
2. 应答里 PII 泄漏 → 422 PII_LEAK
3. 检索召回 0 条 → 直接返回"未在知识库中找到相关内容"

MCP 工具：

@Tool(name = "answerWithCitations",
      description = "用 RAGForge 知识库回答问题，返回带引用的答案")
public AnswerResponse answer(String query, List<Long> kbIds) { ... }

禁止项：
- 不存对话历史（不做 ChatMemory）
- 应答必须带 citations，CitationLinker 校验为空时返回 422
- V5 一期不接入 tenant_quotas 计费（推到 T12）
- 不能让 PREVIEW 模式默认开启（KB 级 opt-in，DB 默认 OFF）
- 不能在 PromptBuilder 里硬编码模型名（必须从 KB.answerModel 读）
- 不能复用 V4 LlmService（专门为应答场景写 AnswerLlmClient SPI）

验收标准：
1. mvn test 全绿
2. 100 条评测 query → 应答必带引用，引用准确率 ≥ 90%
3. 100 条 query 抽 20 条人工检查"无中生有"率 < 3%
4. SSE 流式：首字耗时 < 800ms
5. MCP 工具：Claude Desktop 能调通（在 mcp-config 里加 server 配置后能直接 use tool）
6. answer_mode=OFF 时调 /answer → 403 ANSWER_DISABLED
7. answer_logs 每次调用都写一条，citations_snapshot 不为空

执行流程：
1. 列出会改动的文件（≤18 个）
2. 提交实施计划：特别说明 PromptBuilder 模板结构（系统 prompt + few-shot + chunks + query）
3. 写代码 + 单测 + 评测验证
4. PR 标题：feat(v5/T11): Answer-as-LLM with citation guard rails
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T11 ✅ <commit-sha> 2026-MM-DD

输出格式：
- 用中文回复
- AnswerLlmClient 默认实现走 DashScope Qwen-Max
````

`=== COPY END ===`

---

## T12：文档收口 + 多实例加固 + 监控

`=== COPY START ===`

````
角色：你是 RAGForge 项目的 Cursor 执行者。架构师已出 V5 设计，你按本提示词执行 T12，这是 V5 的收口任务。

必读上下文：
1. /Users/amy/CursorProject/rag-forge/docs/RAGForge-优化设计文档-V5.html  全部
2. /Users/amy/CursorProject/rag-forge/docs/architecture.md  V4 当前文档（待升级）
3. /Users/amy/CursorProject/rag-forge/docs/tasks.md  V4 任务追踪（待续写）
4. /Users/amy/CursorProject/rag-forge/docker-compose.yml  V4 现有部署编排
5. T1~T11 全部已合入

任务：T12 文档收口 + 多实例加固 + 监控
依赖：T11 已合入
工期：2 天

范围：

1. 文档收口：
   a. 更新 docs/architecture.md 到 V5（覆盖 OSS、Cleaner、多策略分块、多模态、Answer、身份去重）
   b. 续写 docs/tasks.md，把 T1~T12 全部记录为已完成
   c. 新建 docs/security-and-multitenancy.md：把 V9~V12 已有的多租户/ACL/JWT/audit 工作完整沉淀
      （这部分代码做了但文档没跟上，架构师上次审视漏看的部分，价值最大）

2. 多实例加固：
   a. Worker 角色拆分：
      - API 实例（只接 HTTP，不消费 MQ）
      - Worker-Text（消费 doc-process-text topic）
      - Worker-Image（消费 doc-process-image topic）
   b. docker-compose 拆出三组服务
   c. application.yml 加 role 配置：role=api / worker-text / worker-image / all

3. 监控：
   a. 引入 micrometer-registry-prometheus
   b. 暴露 /actuator/prometheus
   c. 业务指标必须覆盖：
      - ragforge.ingest.{created, skipped, replaced, rejected}（counter）
      - ragforge.worker.processing_duration（timer，按 strategy 标签）
      - ragforge.worker.failed（counter，按 reason 标签）
      - ragforge.answer.tokens（counter，按 type=prompt/completion）
      - ragforge.answer.citation_rate（gauge）
      - ragforge.kb_access_denied（V4 已有，确保继续工作）
   d. 提供一份 Grafana JSON 模板放在 docs/grafana-v5.json

docs/security-and-multitenancy.md 必须包含（必须按此章节结构）：
1. 身份模型：JWT、SERVICE_ACCOUNT、admin 三种 principal
2. 租户隔离：tenant_id 在 knowledge_bases / retrieval_logs / answer_logs 的传播
3. ACL 粒度：KB 级 kb_acl、文档级（通过 kb_acl 解析）
4. 审计字段：principal_id / delegated_user_id / scope_used / consent_id / trace_id
5. SERVICE_ACCOUNT scope 预授权 vs user 查 ACL 的两条路径
6. 实战示例：如何为 CareerMate Agent 颁发 scope=kb:16:read 的 service account token

禁止项：
- 不要在这一步引入 OpenTelemetry 全链路（推到 V6）
- Worker 角色拆分不能改业务代码，只动 deploy 编排和 @Conditional 注解
- docs/security-and-multitenancy.md 必须从现有代码反查实际行为，不能照抄 V5 设计稿
- 不要新增 Prometheus 外的监控栈（Pushgateway / StatsD 都不要）
- 不要改现有 V9~V12 迁移
- Worker 拆分后默认部署形态仍是 role=all（向下兼容单机部署）

验收标准：
1. mvn test 全绿
2. docs/architecture.md 包含 V5 全部能力且没有过时口径残留
3. docs/security-and-multitenancy.md 至少 600 字，引用代码路径（KbAccessGuard 等）
4. docker-compose-app.yml 三角色拆分能跑起来：双 API 实例 + 双 Worker-Text 同时跑 boss-scraper 1000 条 → 无并发冲突、无重复 chunks
5. Grafana 面板：能看到 ingest / worker / answer 三组指标的实时曲线
6. /actuator/prometheus 暴露的指标至少 6 个业务指标可见

执行流程：
1. 列出会改动/新建的文件（≤20 个）
2. 文档先写，后做代码加固（文档约束代码）
3. PR 标题：chore(v5/T12): docs consolidation + multi-instance hardening + metrics
4. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T12 ✅ <commit-sha> 2026-MM-DD
5. V5 全部完成后，给架构师发个总结消息

输出格式：
- 用中文回复
- docs/security-and-multitenancy.md 用 Markdown 写
- Grafana JSON 用 Grafana 11.x 兼容格式
````

`=== COPY END ===`

---

## 完成记录

> 每个任务完成后在此追加，格式：`- T1 ✅ <commit-sha> 2026-MM-DD`

- T1 ⏳
- T2 ⏳
- T3 ⏳
- T4 ⏳
- T5 ⏳
- T6 ⏳
- T7 ⏳
- T8 ⏳
- T9 ⏳
- T10 ⏳
- T11 ⏳
- T12 ⏳
- T1 ✅ d33b8ec 2026-06-20
- T2 ✅ 299963f 2026-06-20
- T3 ✅ 3de6ad1 2026-06-20
- T4 ✅ 46a4908 2026-06-20
