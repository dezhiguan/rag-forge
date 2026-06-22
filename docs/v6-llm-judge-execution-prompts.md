# V6 LLM-as-Judge 执行任务清单（Codex 提示词版）

> 编制：2026-06-23 · 架构师：@guandezhi
>
> 目标：把 RAGForge 接上 DeepSeek-V3 当裁判，离线 Golden Set + 在线 1% 抽样评测，看板做在 RAGForge 内部前端，**不依赖外部 Grafana / Prometheus 栈**。
>
> 用法：找到要执行的任务 → **复制 `=== COPY START ===` 与 `=== COPY END ===` 之间的全部内容** → 粘贴到 Codex
>
> 总工期：10 天 · 7 个独立 PR · 必须按 J1 → J7 顺序串行执行
>
> 配套设计：本设计在会话中已与架构师对齐，关键决策：
> - 看板在 RAGForge 内（不用 Grafana）
> - 复用 `eval_questions` 加字段（不新建 judge_questions 表）
> - 生产抽样默认 1%，Golden Set 每天 100 题自动回放
> - 沿用 T12 RAGFORGE_ROLE 多角色架构，新增 `role=judge` Deployment

---

- J2 ✅ 2026-06-23

## 依赖与顺序

```
J1 数据模型 ──▶ J2 DeepSeek 客户端 ──┐
                                      ├─▶ J4 JudgeWorker ──▶ J5 聚合 + API ──▶ J6 看板前端 ──▶ J7 配置 + 回放
                  J3 抽样 + MQ ──────┘
```

| 编号 | 名称 | 工期 | 状态 |
|---|---|---|---|
| J1 | 数据模型 V30 migration + DTO | 0.5d | ⏳ |
| J2 | DeepSeek 客户端 + 4 个 Prompt 模板 | 1.5d | ⏳ |
| J3 | JudgeSampler + MQ topic + AnswerService 异步发消息 | 1d | ⏳ |
| J4 | JudgeWorker（role=judge 新 Deployment + Consumer） | 2d | ⏳ |
| J5 | 聚合 cron + 看板 REST API | 1.5d | ⏳ |
| J6 | 看板前端 EvaluationQuality.vue + Case 详情页 | 2d | ⏳ |
| J7 | 抽样配置 UI + Golden Set 自动回放 cron | 1d | ⏳ |

---

## J1：数据模型 V30 migration + DTO

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者，跟 @guandezhi 架构师协作。本任务执行 V6 LLM-as-Judge
功能的第一个 PR：数据模型迁移。

任务：J1 V30 migration + DTO/Entity/Mapper
依赖：无
工期：0.5 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/backend/src/main/resources/db/migration/V28__answer_as_llm.sql
   （了解 answer_logs 表结构，judge_results.answer_log_id 关联此表）
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/model/entity/AnswerLog.java
3. /Users/amy/CursorProject/rag-forge/backend/src/main/resources/db/migration/  最新版本号查看（当前最高 V29，必须用 V30 连号）
4. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/model/entity/EvalQuestion.java（如有）
   eval_questions 表当前 schema，本任务要加 judge_enabled + judge_tags 字段

范围：
1. 写 V30__llm_judge.sql 含 3 张新表 + 1 个 ALTER：
   - judge_results
   - judge_metrics_daily
   - judge_sampling_config
   - ALTER eval_questions ADD COLUMN
2. 4 个 Entity 类 + Mapper（MyBatis-Plus）
3. 不写 Service / Controller（J5 才用）

字段设计（SQL 必须严格按此实现）：

-- V30__llm_judge.sql

CREATE TABLE judge_results (
  id                     BIGSERIAL PRIMARY KEY,
  answer_log_id          BIGINT REFERENCES answer_logs(id) ON DELETE SET NULL,
  kb_ids                 BIGINT[] NOT NULL,
  query                  TEXT NOT NULL,

  -- 评分 0.0-1.0
  faithfulness           NUMERIC(4,3),
  context_precision      NUMERIC(4,3),
  context_recall         NUMERIC(4,3),
  answer_relevance       NUMERIC(4,3),
  completeness           NUMERIC(4,3),
  citation_accuracy      NUMERIC(4,3),
  overall_score          NUMERIC(4,3),

  -- 裁判过程
  judge_model            VARCHAR(64)  NOT NULL DEFAULT 'deepseek-chat',
  judge_prompt_version   VARCHAR(16)  NOT NULL DEFAULT 'v1',
  judge_reasoning        TEXT,
  judge_raw_response     JSONB,
  judge_latency_ms       INT,
  judge_cost_cny         NUMERIC(10,4),

  -- 状态
  status                 VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',
  failure_reason         VARCHAR(256),

  -- 来源
  source                 VARCHAR(16)  NOT NULL,  -- PRODUCTION / GOLDEN_SET / MANUAL
  golden_question_id     BIGINT REFERENCES eval_questions(id) ON DELETE SET NULL,
  tenant_id              VARCHAR(64) NOT NULL DEFAULT 'default',

  created_at             TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE judge_results
  ADD CONSTRAINT ck_judge_status CHECK (status IN ('COMPLETED','FAILED','SKIPPED','RUNNING'));
ALTER TABLE judge_results
  ADD CONSTRAINT ck_judge_source CHECK (source IN ('PRODUCTION','GOLDEN_SET','MANUAL'));

CREATE INDEX idx_judge_results_created ON judge_results(created_at DESC);
CREATE INDEX idx_judge_results_kb ON judge_results USING GIN(kb_ids);
CREATE INDEX idx_judge_results_score ON judge_results(overall_score)
  WHERE status='COMPLETED';
CREATE INDEX idx_judge_results_source ON judge_results(source, created_at DESC);
CREATE INDEX idx_judge_results_answer_log ON judge_results(answer_log_id);


CREATE TABLE judge_metrics_daily (
  date                       DATE NOT NULL,
  kb_id                      BIGINT,  -- NULL=全局聚合
  tenant_id                  VARCHAR(64) NOT NULL DEFAULT 'default',

  sample_count               INT NOT NULL DEFAULT 0,
  failed_count               INT NOT NULL DEFAULT 0,

  faithfulness_p50           NUMERIC(4,3),
  faithfulness_p95           NUMERIC(4,3),
  context_precision_p50      NUMERIC(4,3),
  context_precision_p95      NUMERIC(4,3),
  answer_relevance_p50       NUMERIC(4,3),
  answer_relevance_p95       NUMERIC(4,3),
  overall_p50                NUMERIC(4,3),
  overall_p95                NUMERIC(4,3),
  overall_mean               NUMERIC(4,3),
  overall_std                NUMERIC(4,3),

  total_cost_cny             NUMERIC(10,4) NOT NULL DEFAULT 0,
  updated_at                 TIMESTAMP NOT NULL DEFAULT NOW(),

  PRIMARY KEY(date, COALESCE(kb_id, -1), tenant_id)
);

CREATE INDEX idx_judge_metrics_daily_kb ON judge_metrics_daily(kb_id, date DESC);


CREATE TABLE judge_sampling_config (
  id              BIGSERIAL PRIMARY KEY,
  scope_type      VARCHAR(16) NOT NULL,    -- GLOBAL / KB / TENANT
  scope_id        BIGINT,                  -- NULL when scope_type=GLOBAL
  tenant_id       VARCHAR(64),             -- only used when scope_type=TENANT
  sample_rate     NUMERIC(4,3) NOT NULL,   -- 0.0-1.0
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_by      VARCHAR(128),

  CONSTRAINT ck_sampling_scope CHECK (scope_type IN ('GLOBAL','KB','TENANT')),
  CONSTRAINT ck_sampling_rate CHECK (sample_rate >= 0.0 AND sample_rate <= 1.0)
);

CREATE UNIQUE INDEX uk_sampling_global ON judge_sampling_config(scope_type)
  WHERE scope_type='GLOBAL';
CREATE UNIQUE INDEX uk_sampling_kb ON judge_sampling_config(scope_type, scope_id)
  WHERE scope_type='KB';
CREATE UNIQUE INDEX uk_sampling_tenant ON judge_sampling_config(scope_type, tenant_id)
  WHERE scope_type='TENANT';

-- 初始化全局抽样率
INSERT INTO judge_sampling_config (scope_type, sample_rate, updated_by)
VALUES ('GLOBAL', 0.01, 'system-init');


-- 复用 eval_questions 加字段
ALTER TABLE eval_questions
  ADD COLUMN IF NOT EXISTS judge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS judge_tags VARCHAR(128)[];

CREATE INDEX IF NOT EXISTS idx_eval_questions_judge_enabled
  ON eval_questions(judge_enabled) WHERE judge_enabled=TRUE;


Java Entity 设计（必须严格按此实现）：

@Data
@TableName("judge_results")
public class JudgeResult {
    @TableId(type=IdType.AUTO) Long id;
    Long answerLogId;
    Long[] kbIds;
    String query;
    BigDecimal faithfulness;
    BigDecimal contextPrecision;
    BigDecimal contextRecall;
    BigDecimal answerRelevance;
    BigDecimal completeness;
    BigDecimal citationAccuracy;
    BigDecimal overallScore;
    String judgeModel;
    String judgePromptVersion;
    String judgeReasoning;
    String judgeRawResponse;    // 存 JSON 字符串，MyBatis 转 JSONB
    Integer judgeLatencyMs;
    BigDecimal judgeCostCny;
    String status;               // 不用 enum，按字符串
    String failureReason;
    String source;
    Long goldenQuestionId;
    String tenantId;
    LocalDateTime createdAt;
}

@Data
@TableName("judge_metrics_daily")
public class JudgeMetricsDaily {
    LocalDate date;
    Long kbId;                   // 可空
    String tenantId;
    Integer sampleCount;
    Integer failedCount;
    BigDecimal faithfulnessP50;
    BigDecimal faithfulnessP95;
    BigDecimal contextPrecisionP50;
    BigDecimal contextPrecisionP95;
    BigDecimal answerRelevanceP50;
    BigDecimal answerRelevanceP95;
    BigDecimal overallP50;
    BigDecimal overallP95;
    BigDecimal overallMean;
    BigDecimal overallStd;
    BigDecimal totalCostCny;
    LocalDateTime updatedAt;
}

@Data
@TableName("judge_sampling_config")
public class JudgeSamplingConfig {
    @TableId(type=IdType.AUTO) Long id;
    String scopeType;            // GLOBAL / KB / TENANT
    Long scopeId;
    String tenantId;
    BigDecimal sampleRate;
    Boolean enabled;
    LocalDateTime updatedAt;
    String updatedBy;
}

// EvalQuestion 已有，只新增两个字段
@Data
@TableName("eval_questions")
public class EvalQuestion {
    // 已有字段保留 ...
    Boolean judgeEnabled;
    String[] judgeTags;
}


Mapper 设计：

@Mapper
public interface JudgeResultMapper extends BaseMapper<JudgeResult> {
    // 基本 CRUD 走 BaseMapper，不写自定义查询（J5 才用到）
}

@Mapper
public interface JudgeMetricsDailyMapper extends BaseMapper<JudgeMetricsDaily> {}

@Mapper
public interface JudgeSamplingConfigMapper extends BaseMapper<JudgeSamplingConfig> {}


禁止项：
- 不能写 Service 或 Controller（本任务只是 schema + ORM 层）
- 不能创建新的 eval_questions 替代表，必须 ALTER 现有表
- judge_results.kb_ids 必须用 PG 数组类型，不要 CSV 字符串
- judge_results.judge_raw_response 必须 JSONB 类型，不要 TEXT
- 不能给 judge_results 加 UPDATE 触发器 / 业务逻辑
- 不能修改 V28/V29 任何已有 migration 文件
- 必须 V30 版本号（不能跳号，当前最新是 V29，必须连号）

验收标准：
1. mvn test 全绿（含新加 Mapper 的基本 selectById/insert 测试）
2. Flyway 在干净 PG（含 V1~V29 schema）上 migrate up 成功，V30 跑通
3. 3 张新表 + eval_questions 的 2 个新列 全部存在
4. judge_sampling_config 全局默认值 0.01 已插入（SELECT * FROM judge_sampling_config 看到一行）
5. 单测 case：
   - JudgeResult insert + selectById 往返
   - JudgeMetricsDaily 联合主键（date + kb_id + tenant_id）冲突处理
   - JudgeSamplingConfig 唯一索引（scope_type=GLOBAL 只能一行）
6. 不破坏已有 mvn test（337+ 个测试仍全绿）

执行流程：
1. 先输出"我将创建/修改的文件清单"（≤10 个文件）
2. 提交计划等架构师确认
3. 写 V30 migration + Entity + Mapper + 单测
4. mvn test 跑全绿
5. PR 标题：feat(v6/J1): llm-judge schema migration + DTO
6. PR 描述写明：
   - 新增 3 张表的字段含义（贴 SQL 摘要）
   - eval_questions 加了什么字段为什么加
   - 单测覆盖率
7. 完成后在 docs/v6-llm-judge-execution-prompts.md 末尾追加：
   - J1 ✅ <commit-sha> 2026-MM-DD
```

`=== COPY END ===`

---

## J2：DeepSeek 客户端 + 4 个 Prompt 模板

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务执行 V6 LLM-as-Judge 第二个 PR：
DeepSeek 客户端 + 4 个评分 Prompt 模板。

任务：J2 DeepSeek 客户端 + Prompt 库
依赖：J1 已合并
工期：1.5 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/service/impl/LlmServiceImpl.java
   （现有 DashScope 客户端实现，DeepSeek 客户端要类似设计）
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/answer/AnswerService.java
   （了解评测对象：query / retrieval chunks / answer / citations 的字段）
3. /Users/amy/CursorProject/rag-forge/backend/src/main/resources/application.yml
   （app.dashscope 配置位置，DeepSeek 配置参考同样格式）

范围：
1. 新建包 com.ragforge.judge
2. DeepSeekClient：HTTP 客户端，调 DeepSeek-V3 OpenAI 兼容接口
3. JudgePromptLibrary：4 个 prompt 模板（faithfulness / context_precision / answer_relevance / composite）
4. JudgeScorer：调一次 DeepSeek + 解析 JSON 输出 + 错误处理
5. 不接入 MQ / AnswerService（J3+J4 才用）

字段设计（必须严格按此实现）：

application.yml 新增：
app:
  deepseek:
    api-key: ${DEEPSEEK_API_KEY:}
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}
    model: ${DEEPSEEK_MODEL:deepseek-chat}
    temperature: 0.0
    timeout-ms: 30000
    max-retries: 3
    retry-backoff-ms: 2000

Java 接口：

public interface JudgeScorer {
    JudgeScore score(JudgeContext context, ScoreDimension dimension);
}

public enum ScoreDimension {
    FAITHFULNESS,
    CONTEXT_PRECISION,
    ANSWER_RELEVANCE,
    COMPOSITE                  // 综合分（最后调一次，输出 overall + reasoning）
}

public class JudgeContext {
    String query;
    List<RetrievedChunk> chunks;   // 含 chunkId, content, score
    String answer;
    List<Citation> citations;
    String expectedAnswer;         // 可空（Golden Set 时填）
    Long[] expectedChunkIds;       // 可空
}

public class JudgeScore {
    ScoreDimension dimension;
    BigDecimal score;              // 0.0-1.0
    String reasoning;
    List<String> issues;
    String rawResponse;            // 完整 JSON
    Integer latencyMs;
    BigDecimal costCny;
    boolean success;
    String failureReason;
}

DeepSeekClient 实现：

@Component
@RequiredArgsConstructor
public class DeepSeekClient {
    private final ObjectMapper objectMapper;
    @Value("${app.deepseek.api-key}") String apiKey;
    @Value("${app.deepseek.base-url}") String baseUrl;
    @Value("${app.deepseek.model}") String model;
    @Value("${app.deepseek.temperature}") double temperature;
    @Value("${app.deepseek.timeout-ms}") int timeoutMs;
    @Value("${app.deepseek.max-retries}") int maxRetries;

    public ChatResult chat(String systemPrompt, String userPrompt) {
        // OpenAI 兼容 POST /v1/chat/completions
        // body: { model, messages, temperature=0, response_format={type:"json_object"} }
        // 重试 maxRetries 次，指数退避
        // 返回 ChatResult(content, promptTokens, completionTokens, latencyMs)
    }
}

public record ChatResult(
    String content,
    int promptTokens,
    int completionTokens,
    int latencyMs
) {
    public BigDecimal estimateCostCny() {
        // DeepSeek-V3: 输入 ¥0.001/1K tokens, 输出 ¥0.002/1K tokens
        return ...;
    }
}

4 个 Prompt 模板（按 RAGAS 风格，强制 JSON 输出）：

【Faithfulness Prompt】
system: 你是一个严格的 RAG 评测专家。
user:
你的任务：判断生成答案是否仅基于检索内容，无幻觉。

【输入】
Query: {query}
Retrieved Context:
{chunks_with_id_and_content}
Generated Answer: {answer}

【评分标准】
1.0 - 答案完全基于上下文，每个事实都有出处
0.7 - 大部分基于但有 1 处推测
0.4 - 部分基于上下文，部分是模型自己编的
0.0 - 答案与上下文无关或事实错误

【禁止偏好】
- 不要因答案啰嗦给高分（length bias）
- 不要因风格亲和给高分（style bias）
- 短答案不等于忠实度低

【输出严格 JSON】
{
  "score": 0.85,
  "reasoning": "≤80 字说明",
  "hallucinated_claims": ["列出具体幻觉点，没有则为空数组"]
}

【Context Precision Prompt】
你的任务：判断检索 top-K chunks 里多少是真相关的。
对每个 chunk 标 relevant=true/false，输出整体精度分。

输出 JSON:
{
  "score": 0.60,
  "reasoning": "≤80 字",
  "chunk_relevance": [
    {"chunk_id": 123, "relevant": true},
    {"chunk_id": 456, "relevant": false}
  ]
}

【Answer Relevance Prompt】
你的任务：判断答案是否回应了 query（不偏题、不答非所问）。

输出 JSON:
{
  "score": 0.75,
  "reasoning": "≤80 字",
  "off_topic_parts": ["列出离题段落，没有则为空数组"]
}

【Composite Prompt】（最后调一次，做综合判断 + 给改进建议）
你已知前三项分数：{faith}, {ctx_p}, {ans_r}
你的任务：基于检索内容 + 答案，给综合分 + 改进建议。

输出 JSON:
{
  "overall_score": 0.72,
  "reasoning": "≤120 字",
  "bottleneck": "RETRIEVAL / GENERATION / BOTH",
  "improvements": ["≤3 条具体建议"]
}


JudgeScorer 编排：

@Service
@RequiredArgsConstructor
public class DefaultJudgeScorer implements JudgeScorer {
    private final DeepSeekClient client;
    private final JudgePromptLibrary prompts;
    private final ObjectMapper objectMapper;

    @Override
    public JudgeScore score(JudgeContext ctx, ScoreDimension dim) {
        String systemPrompt = prompts.systemFor(dim);
        String userPrompt = prompts.userFor(dim, ctx);
        try {
            ChatResult chat = client.chat(systemPrompt, userPrompt);
            JsonNode json = objectMapper.readTree(chat.content());
            // 提取 score / reasoning / issues
            return JudgeScore.success(dim, json, chat);
        } catch (Exception e) {
            return JudgeScore.failed(dim, e.getMessage());
        }
    }
}

抗 bias 措施（必须实现）：
1. temperature=0.0 固定
2. 同题双跑取中位数：JudgeScorer 提供 scoreWithRetry(ctx, dim, runs=2) 方法
3. 如果两次差值 > 0.2，标记 stable=false（在 JudgeScore 加字段）

禁止项：
- 不能调 DashScope（必须独立 DeepSeek 客户端）
- 不能让裁判输出非 JSON（强制 response_format=json_object）
- 不能用 GET 请求（必须 POST chat/completions）
- 不能写 service 调 MQ（J3 才做）
- 不能跳过单测（每个 prompt 至少 2 个 case）
- temperature 不能 > 0（一致性要求）

验收标准：
1. mvn test 全绿
2. 单测覆盖：每个 Prompt 模板各 2 个 case（典型 + 边界）+ DeepSeek 客户端 mock 测试
3. 集成测试（@Tag("integration") + 真实 API key 时跑）：
   - 真调 DeepSeek，对 5 个手工 case 跑 4 个 dimension
   - 同题双跑 std < 0.1
4. 错误处理：API key 错误 → JudgeScore.failed 而不是抛异常
5. 成本估算正确（手算一个 case 对比代码估算）

执行流程：
1. 输出文件清单
2. 提交计划等架构师确认
3. 实现客户端 + Prompt + Scorer + 单测
4. mvn test 全绿
5. PR 标题：feat(v6/J2): DeepSeek judge client + 4 scoring prompts
6. 完成后在 docs/v6-llm-judge-execution-prompts.md 末尾追加：J2 ✅
```

`=== COPY END ===`

---

## J3：JudgeSampler + MQ topic + AnswerService 异步发消息

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务执行 V6 LLM-as-Judge 第三个 PR：
抽样判定 + MQ 发消息（AnswerService 不阻塞主链路）。

任务：J3 JudgeSampler + MQ
依赖：J1 已合并（judge_sampling_config 表）
工期：1 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/mq/DocumentProcessProducer.java
   （MQ Producer 实现参考，含 inline dispatch 模式）
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/answer/AnswerService.java
   （在 writeLog 之后插入异步发 MQ 调用）
3. /Users/amy/CursorProject/rag-forge/backend/src/main/resources/application.yml
   rocketmq 配置位置

范围：
1. 新建包 com.ragforge.judge.sampler
2. JudgeSampler：根据 judge_sampling_config 判定是否抽中
3. AnswerJudgeProducer：新增 MQ topic `ragforge-answer-judge`
4. AnswerService 在 writeLog 之后插入异步发消息（不阻塞 SSE 返回）
5. 不实现 Consumer（J4 才做）

字段设计：

MQ 消息体：

@Data
public class AnswerJudgeMessage {
    Long answerLogId;          // 唯一索引 answer_logs.id
    String source;             // PRODUCTION / GOLDEN_SET / MANUAL
    Long goldenQuestionId;     // 可空
    String forceSample;        // 'FORCE' / 'AUTO'，FORCE 时跳过抽样直接评测
    LocalDateTime requestedAt;
}

JudgeSampler 接口：

@Component
@RequiredArgsConstructor
public class JudgeSampler {
    private final JudgeSamplingConfigMapper configMapper;

    public SampleDecision decide(SampleRequest request) {
        // 1. 如果 source=GOLDEN_SET 或 forceSample=FORCE → KEEP（不查 config）
        // 2. 查 config 优先级：KB-level > TENANT-level > GLOBAL
        // 3. enabled=FALSE → SKIP
        // 4. 按 sample_rate 用 ThreadLocalRandom 决定 KEEP/SKIP
        // 5. 返回 SampleDecision(keep, configId, sampleRate)
    }
}

public record SampleRequest(
    Long answerLogId,
    Long[] kbIds,
    String tenantId,
    String source,
    boolean forceSample
) {}

public record SampleDecision(
    boolean keep,
    Long configId,
    BigDecimal effectiveSampleRate,
    String reason   // KEEP_BY_RATE / KEEP_BY_FORCE / KEEP_BY_GOLDEN / SKIP_DISABLED / SKIP_BY_RATE
) {}

AnswerJudgeProducer：

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerJudgeProducer {
    public static final String TOPIC = "ragforge-answer-judge";
    private final RocketMQTemplate rocketMQTemplate;
    private final JudgeSampler sampler;

    @Value("${ragforge.judge.dispatch-mode:mq}")
    private String dispatchMode;   // mq / inline / disabled（test 用）

    public void publishJudgeRequest(AnswerJudgeMessage msg, SampleRequest req) {
        if ("disabled".equalsIgnoreCase(dispatchMode)) {
            return;  // 完全禁用，单测/本地用
        }
        SampleDecision decision = sampler.decide(req);
        if (!decision.keep()) {
            log.debug("Judge skipped: answerLogId={}, reason={}", msg.getAnswerLogId(), decision.reason());
            return;
        }
        if ("inline".equalsIgnoreCase(dispatchMode)) {
            // 本地开发用，不发 MQ
            log.warn("INLINE_JUDGE_DISPATCH: answerLogId={}", msg.getAnswerLogId());
            return;
        }
        try {
            rocketMQTemplate.convertAndSend(TOPIC, msg);
            log.info("Sent judge request: answerLogId={}, sampleRate={}",
                msg.getAnswerLogId(), decision.effectiveSampleRate());
        } catch (Exception e) {
            log.error("Failed to send judge request: answerLogId={}", msg.getAnswerLogId(), e);
            // 静默吞掉，不影响主业务
        }
    }
}

application.yml 新增：

ragforge:
  judge:
    dispatch-mode: ${RAGFORGE_JUDGE_DISPATCH_MODE:mq}
    sampling-fallback-rate: 0.0   # config 表查不到时的兜底（默认 0%，不评测）

AnswerService 改造（在 writeLog 之后追加）：

private void publishJudgeAsync(AnswerRequest request, List<Long> kbIds, Long answerLogId) {
    try {
        SampleRequest req = new SampleRequest(
            answerLogId,
            kbIds.toArray(new Long[0]),
            tenantIdOrDefault(),
            request.getJudgeSource() != null ? request.getJudgeSource() : "PRODUCTION",
            request.isForceSample()
        );
        AnswerJudgeMessage msg = new AnswerJudgeMessage();
        msg.setAnswerLogId(answerLogId);
        msg.setSource(req.source());
        msg.setGoldenQuestionId(request.getGoldenQuestionId());
        msg.setForceSample(req.forceSample() ? "FORCE" : "AUTO");
        msg.setRequestedAt(LocalDateTime.now());
        answerJudgeProducer.publishJudgeRequest(msg, req);
    } catch (Exception e) {
        log.warn("Judge async publish failed (non-fatal): {}", e.getMessage());
        // 绝对不能往外抛，否则破坏主答案返回
    }
}

注意：writeLog 现在不返回 answer_log_id，需要改 AnswerLogMapper.insertAnswerLog 让 MyBatis-Plus useGeneratedKeys 把 id 回填到 log.id。

AnswerRequest DTO 新增字段（可选传）：
- String judgeSource     // PRODUCTION / GOLDEN_SET / MANUAL，默认 PRODUCTION
- Boolean forceSample    // 强制评测，跳过抽样判定，默认 false
- Long goldenQuestionId  // 来自 Golden Set 时填，可空

禁止项：
- AnswerService.publishJudgeAsync 必须 try-catch 吞掉所有异常，不能影响答案返回
- 必须在 writeLog 完成之后发 MQ（先写 answer_logs 再发，否则 Consumer 拿到 id 查不到日志）
- 不能在主线程同步等 MQ 确认（用 RocketMQTemplate.convertAndSend，不要 sendSync 带回调）
- JudgeSampler 决策不能查超过 1 次 DB（一次 query 拿出适用 config）
- 不能修改 J1 的 schema
- dispatch-mode=inline 仅本地用，prod profile 时启动校验必须为 mq（参考 DocumentProcessProducer 已有的 prod 守护，复用同样的 Profile 检查模式）

验收标准：
1. mvn test 全绿
2. 单测：
   - JudgeSampler 三层优先级（KB > TENANT > GLOBAL）
   - source=GOLDEN_SET 100% 抽中
   - forceSample=true 100% 抽中
   - sample_rate=0 永远 SKIP
   - sample_rate=1.0 永远 KEEP
   - JudgeSampler 1 万次抽样统计误差 < 5%
3. AnswerService 集成测试：
   - 正常 answer 不阻塞
   - MQ 发送失败时答案照常返回（mock RocketMQTemplate 抛异常验证）
4. application.yml 默认 dispatch-mode=mq
5. AnswerLog.id 能从 mapper 拿到（useGeneratedKeys 生效）

执行流程：
1. 输出文件清单
2. 实现 Sampler + Producer + AnswerService 改造 + 单测
3. mvn test 全绿
4. PR 标题：feat(v6/J3): judge sampler + async MQ dispatch
5. 完成后追加：J3 ✅
```

`=== COPY END ===`

---

## J4：JudgeWorker（role=judge 新 Deployment）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务执行 V6 LLM-as-Judge 第四个 PR：
JudgeWorker 独立 Deployment + MQ Consumer + 完整评测编排。

任务：J4 JudgeWorker
依赖：J2 + J3 已合并
工期：2 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/config/WorkerRoleCondition.java
   （T12 已有 role 条件，本任务加 JudgeRoleCondition）
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/mq/DocumentProcessConsumer.java
   （RocketMQ Consumer 实现参考）
3. /Users/amy/CursorProject/rag-forge/deploy/k8s/ragforge/backend-deployment.yaml
   （T12 已拆 api/worker 双 Deployment，本任务加第三个：judge）
4. J2 生成的 DeepSeekClient + JudgeScorer
5. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/mapper/AnswerLogMapper.java

范围：
1. 新建 JudgeRoleCondition（参考 WorkerRoleCondition）
2. AnswerJudgeConsumer：消费 ragforge-answer-judge topic
3. JudgeOrchestrator：4 个 dimension 编排 + 写 judge_results
4. K3s Deployment yaml 加 ragforge-judge
5. 失败处理 + 监控埋点

字段设计：

@Component
@Conditional(JudgeRoleCondition.class)
@RocketMQMessageListener(
    topic = AnswerJudgeProducer.TOPIC,
    consumerGroup = "ragforge-judge-consumer",
    consumeMode = ConsumeMode.CONCURRENTLY
)
public class AnswerJudgeConsumer implements RocketMQListener<AnswerJudgeMessage> {
    private final JudgeOrchestrator orchestrator;
    private final RagforgeMetrics metrics;

    @Override
    public void onMessage(AnswerJudgeMessage msg) {
        long start = System.nanoTime();
        try {
            orchestrator.judge(msg);
            metrics.recordJudgeSuccess(msg.getSource());
            metrics.recordJudgeDuration(msg.getSource(), System.nanoTime() - start);
        } catch (RuntimeException e) {
            metrics.recordJudgeFailed(msg.getSource(), e.getClass().getSimpleName());
            log.error("Judge failed: answerLogId={}", msg.getAnswerLogId(), e);
            // RocketMQ 会重试，重试上限到了进死信队列
            throw e;
        }
    }
}

@Service
@RequiredArgsConstructor
public class JudgeOrchestrator {
    private final AnswerLogMapper answerLogMapper;
    private final JudgeResultMapper judgeResultMapper;
    private final JudgeScorer scorer;
    private final ObjectMapper objectMapper;

    public void judge(AnswerJudgeMessage msg) {
        AnswerLog log = answerLogMapper.selectById(msg.getAnswerLogId());
        if (log == null) {
            log.warn("AnswerLog not found: {}", msg.getAnswerLogId());
            return;
        }

        JudgeContext ctx = buildContext(log);
        JudgeResult result = new JudgeResult();
        result.setAnswerLogId(msg.getAnswerLogId());
        result.setKbIds(parseKbIds(log));
        result.setQuery(log.getQuery());
        result.setSource(msg.getSource());
        result.setGoldenQuestionId(msg.getGoldenQuestionId());
        result.setStatus("RUNNING");
        result.setTenantId(log.getTenantId());
        result.setJudgeModel("deepseek-chat");
        result.setJudgePromptVersion("v1");

        // 调 4 个 dimension（前 3 个并发，composite 顺序）
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalLatency = 0;
        List<String> issues = new ArrayList<>();

        JudgeScore faith = scorer.score(ctx, ScoreDimension.FAITHFULNESS);
        applyScore(result, faith, "faithfulness");
        totalCost = totalCost.add(faith.getCostCny() != null ? faith.getCostCny() : BigDecimal.ZERO);
        totalLatency += orZero(faith.getLatencyMs());

        JudgeScore ctxP = scorer.score(ctx, ScoreDimension.CONTEXT_PRECISION);
        applyScore(result, ctxP, "context_precision");
        totalCost = totalCost.add(...);
        totalLatency += ...;

        JudgeScore ansR = scorer.score(ctx, ScoreDimension.ANSWER_RELEVANCE);
        applyScore(result, ansR, "answer_relevance");
        totalCost = totalCost.add(...);
        totalLatency += ...;

        // composite 拿前 3 个分数做综合
        JudgeContext compositeCtx = ctx.withPriorScores(faith, ctxP, ansR);
        JudgeScore overall = scorer.score(compositeCtx, ScoreDimension.COMPOSITE);
        result.setOverallScore(overall.getScore());
        result.setJudgeReasoning(overall.getReasoning());
        totalCost = totalCost.add(...);
        totalLatency += ...;

        result.setJudgeCostCny(totalCost);
        result.setJudgeLatencyMs((int) totalLatency);
        result.setJudgeRawResponse(buildRawJson(faith, ctxP, ansR, overall));

        if (anyFailed(faith, ctxP, ansR, overall)) {
            result.setStatus("FAILED");
            result.setFailureReason(firstFailure(...));
        } else {
            result.setStatus("COMPLETED");
        }

        judgeResultMapper.insert(result);
    }
}

JudgeContext.buildContext 必须从 answer_logs 反向重建：
- query：log.query
- chunks：解析 log.citations_snapshot 拿 chunkId 列表，再查 document_chunks 拿 content
- answer：log.answer
- citations：解析 log.citations_snapshot

Metrics 埋点（扩展 RagforgeMetrics）：

ragforge.judge.requests           counter labels: source
ragforge.judge.duration           timer labels: source
ragforge.judge.failed             counter labels: source, reason
ragforge.judge.score              gauge   labels: dimension, kb_id
ragforge.judge.cost               counter labels: source
ragforge.deepseek.tokens          counter labels: type=prompt/completion

K3s deploy/k8s/ragforge/backend-deployment.yaml 新增第三个 Deployment：

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ragforge-judge
  namespace: ragforge
  labels:
    app: ragforge-judge
    role: judge
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: backend
          image: docker.io/ragforge/backend:0.0.2
          envFrom:
            - configMapRef: { name: ragforge-backend-config }
            - secretRef: { name: ragforge-backend-env }
          env:
            - name: RAGFORGE_ROLE
              value: judge
            - name: SPRING_MAIN_WEB_APPLICATION_TYPE
              value: none
            - name: JAVA_TOOL_OPTIONS
              value: "-Xms256m -Xmx512m"  # judge worker 内存可以小
          resources:
            requests: { cpu: 100m, memory: 256Mi }
            limits: { cpu: 1, memory: 768Mi }

RoleCondition 扩展：

public class JudgeRoleCondition extends RoleCondition {
    @Override
    protected boolean matchesRole(String role) {
        return "judge".equals(role) || "all".equals(role);
    }
}

WorkerRoleCondition 不需要改（judge 是独立角色，不重叠）。

抗 bias 措施实现：
1. JudgeOrchestrator 提供"双跑取中位数"模式，通过环境变量 ragforge.judge.stability-check.enabled=true 启用
2. 启用时每个 dimension 调 2 次，差值 > 0.2 在 judgeRawResponse 加 "stable": false 标记
3. 默认不启用（成本翻倍）

失败处理：
- DeepSeek API 限流 → 单次重试由 DeepSeekClient 内部处理（J2 实现）
- 全部重试失败 → JudgeScore.failed → JudgeResult.status=FAILED 写库
- MQ 消费失败抛异常 → RocketMQ 重试 3 次后进死信队列
- 死信队列消息架构师手动处理（不自动重试）

禁止项：
- 不能让 JudgeWorker 启动 web server（必须 spring.main.web-application-type=none）
- 不能写 Controller（J5 才做）
- 不能让评测影响 answer_logs（只 SELECT，不 UPDATE）
- 不能用 Thread.sleep 限流（用 DeepSeek 客户端的 retry-backoff）
- 不能跳过失败写库（FAILED 状态也要落 judge_results，方便排查）
- 不能在主链路（api / worker pod）启动 AnswerJudgeConsumer（必须 @Conditional(JudgeRoleCondition)）

验收标准：
1. mvn test 全绿
2. 单测：
   - JudgeOrchestrator 4 个 dimension 全部成功 → status=COMPLETED
   - 任一 dimension 失败 → status=FAILED
   - answer_log 不存在 → 优雅返回不抛异常
3. 集成测试（@Tag("integration")）：
   - 真实跑一遍 mock answer_log → 真调 DeepSeek → 写库 → 验证 judge_results 一行
4. K3s YAML 跑通：
   - kubectl apply 成功
   - ragforge-judge pod 状态 Running，无 CrashLoopBackOff
   - 没启 Tomcat（验证 web-application-type=none 生效）
5. Conditional 测试：role=api → AnswerJudgeConsumer 不注册
6. Metrics 测试：调 RagforgeMetricsTest 加 judge 相关 case

执行流程：
1. 输出文件清单
2. 实现 Worker + Orchestrator + RoleCondition + K8s YAML + 单测
3. 集成测试用真 DeepSeek key 跑通一次
4. mvn test 全绿
5. PR 标题：feat(v6/J4): judge worker deployment + orchestration
6. 完成后追加：J4 ✅
```

`=== COPY END ===`

---

## J5：聚合 cron + 看板 REST API

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务执行 V6 LLM-as-Judge 第五个 PR：
后台聚合 + 看板查询 API。

任务：J5 聚合 + REST API
依赖：J1 已合并（judge_metrics_daily 表）
工期：1.5 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/security/KbAccessGuard.java
   （API 必须有权限校验）
2. J4 生成的 judge_results 表
3. 现有评测相关 Controller：EvaluationController 或类似

范围：
1. JudgeMetricsAggregator：@Scheduled 每 5 分钟聚合 judge_results → judge_metrics_daily
2. JudgeQueryService：提供看板查询接口
3. JudgeQualityController：暴露 5 个 GET endpoint
4. 鉴权用 @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")

字段设计：

聚合逻辑（@Scheduled(cron = "0 */5 * * * *")）：

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragforge.judge.aggregator-enabled", havingValue = "true", matchIfMissing = true)
public class JudgeMetricsAggregator {
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "judge-metrics-aggregator", lockAtMostFor = "PT4M")
    public void aggregate() {
        // 聚合最近 7 天，覆盖更新 judge_metrics_daily
        // 同时算全局（kb_id=NULL）和按 KB 切片
        // 用 PERCENTILE_CONT 算 P50/P95
        jdbcTemplate.execute("""
            INSERT INTO judge_metrics_daily (date, kb_id, tenant_id, sample_count, ...)
            SELECT
              DATE(created_at) as d,
              kb_id_value,
              tenant_id,
              COUNT(*) FILTER (WHERE status='COMPLETED'),
              COUNT(*) FILTER (WHERE status='FAILED'),
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY faithfulness),
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY faithfulness),
              ...
            FROM judge_results, UNNEST(kb_ids) WITH ORDINALITY AS k(kb_id_value, ord)
            WHERE created_at >= NOW() - INTERVAL '7 days'
            GROUP BY DATE(created_at), kb_id_value, tenant_id
            UNION ALL
            -- 全局（kb_id=NULL）
            SELECT DATE(created_at), NULL, tenant_id, ... FROM judge_results ...
            GROUP BY DATE(created_at), tenant_id
            ON CONFLICT (date, COALESCE(kb_id, -1), tenant_id) DO UPDATE
            SET sample_count = EXCLUDED.sample_count, ...
        """);
    }
}

注意：需要 ShedLock 防止多个 api pod 并发跑聚合（参考项目现有 ShedLock 用法）。

REST API：

@RestController
@RequestMapping("/api/v1/evaluation/quality")
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
@RequiredArgsConstructor
public class JudgeQualityController {
    private final JudgeQueryService queryService;
    private final KbAccessGuard kbAccessGuard;

    // 1. 总览：核心 KPI + 趋势
    @GetMapping("/overview")
    public Result<OverviewVo> overview(
        @RequestParam(defaultValue = "7") int days,
        @RequestParam(required = false) Long kbId
    ) {
        if (kbId != null && !kbAccessGuard.canRead(kbId)) {
            throw new BizException(403, "KB_ACCESS_DENIED");
        }
        return Result.ok(queryService.overview(days, kbId));
    }

    // 2. KB 切片
    @GetMapping("/by-kb")
    public Result<List<KbSliceVo>> byKb(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(queryService.byKb(days, kbAccessGuard.filterReadable(...)));
    }

    // 3. 最差 case 列表
    @GetMapping("/worst-cases")
    public Result<List<WorstCaseVo>> worstCases(
        @RequestParam(defaultValue = "10") int limit,
        @RequestParam(defaultValue = "7") int days,
        @RequestParam(required = false) Long kbId
    ) {
        return Result.ok(queryService.worstCases(limit, days, kbId));
    }

    // 4. case 详情
    @GetMapping("/case/{judgeResultId}")
    public Result<CaseDetailVo> caseDetail(@PathVariable Long judgeResultId) {
        return Result.ok(queryService.caseDetail(judgeResultId));
    }

    // 5. 成本
    @GetMapping("/cost")
    public Result<CostSummaryVo> cost(@RequestParam(defaultValue = "30") int days) {
        return Result.ok(queryService.cost(days));
    }
}

VO 设计：

public class OverviewVo {
    KpiVo kpis;                   // 当前 + 趋势
    List<TrendPointVo> trend;     // 时序数据点
    AnomalyVo anomaly;            // 自动告警
    SampleStatsVo samples;        // 抽样统计
}

public class KpiVo {
    BigDecimal overallScore;
    BigDecimal overallChange;     // vs 上周期
    BigDecimal faithfulness;
    BigDecimal contextPrecision;
    BigDecimal answerRelevance;
    Integer retrievalLatencyP95Ms;
    BigDecimal costLastPeriodCny;
}

public class TrendPointVo {
    LocalDate date;
    BigDecimal overall;
    BigDecimal faithfulness;
    BigDecimal contextPrecision;
    BigDecimal answerRelevance;
    Integer sampleCount;
}

public class KbSliceVo {
    Long kbId;
    String kbName;
    BigDecimal overallScore;
    BigDecimal trend;             // vs 上周期
    Integer sampleCount;
}

public class WorstCaseVo {
    Long judgeResultId;
    Long answerLogId;
    String query;
    BigDecimal overallScore;
    LocalDateTime createdAt;
    String topIssue;              // 最严重的问题摘要
}

public class CaseDetailVo {
    Long judgeResultId;
    String query;
    String answer;
    List<ChunkSnapshotVo> chunks; // 来自 citations_snapshot
    Map<String, BigDecimal> scores;
    String judgeReasoning;
    List<String> improvements;
    String bottleneck;            // RETRIEVAL / GENERATION / BOTH
}

public class CostSummaryVo {
    BigDecimal totalCny;
    BigDecimal dailyAverageCny;
    BigDecimal monthlyProjectedCny;
    Integer totalCalls;
    Integer failedCalls;
    Map<String, BigDecimal> costBySource;  // PRODUCTION / GOLDEN_SET / MANUAL
}

异常告警逻辑：
- AnomalyVo.detect()：
  - 当前 overall_score 比上周期低 > 5% → severity=WARN
  - 比上周期低 > 10% → severity=CRITICAL
  - failed_count / sample_count > 5% → severity=WARN
- 简单实现，不引入复杂统计学

性能保证：
- /overview、/by-kb、/cost 必须查 judge_metrics_daily（聚合表），不直接扫 judge_results
- /worst-cases 走 judge_results 但用 idx_judge_results_score 索引（status=COMPLETED + ORDER BY overall_score LIMIT 10）
- /case/{id} 走 PK 查询

禁止项：
- 不能跳过权限校验 KbAccessGuard
- 不能让聚合 cron 在没有 ShedLock 保护下并行跑
- 不能直接扫 judge_results 算 P95（必须用聚合表）
- 不能让 /case 接口返回完整 judgeRawResponse JSON（太大，前端用不到，截断 reasoning + improvements 即可）
- VO 字段不能用 Map<String, Object>（必须类型化）

验收标准：
1. mvn test 全绿
2. 单测：
   - JudgeMetricsAggregator 聚合正确性（造 100 行 judge_results 跑一次聚合验证 P50/P95 数值）
   - 5 个 API 各 2 个 case（典型 + 权限拒绝）
3. ShedLock 测试：模拟两个 pod 并发触发 cron，只有一个成功获取锁
4. 性能测试：100K 行 judge_results 时 /overview 响应 < 200ms（走聚合表）
5. 异常告警逻辑：模拟分数下降 7% / 12% 各算一次，severity 正确

执行流程：
1. 输出文件清单
2. 实现 Aggregator + QueryService + Controller + VO + 单测
3. mvn test 全绿
4. PR 标题：feat(v6/J5): judge metrics aggregator + quality dashboard API
5. 完成后追加：J5 ✅
```

`=== COPY END ===`

---

## J6：看板前端 EvaluationQuality.vue + Case 详情页

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务执行 V6 LLM-as-Judge 第六个 PR：
RAGForge 内嵌看板前端页面。

任务：J6 看板前端
依赖：J5 已合并（5 个 REST API 可用）
工期：2 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/frontend/src/views/Dashboard.vue
   （现有驾驶舱页面，了解项目 Vue 风格 + chart 库使用）
2. /Users/amy/CursorProject/rag-forge/frontend/src/views/EvaluationLab.vue
   （评测实验室页，本任务的页面要并列加进同一菜单）
3. /Users/amy/CursorProject/rag-forge/frontend/src/router/index.js
4. /Users/amy/CursorProject/rag-forge/frontend/package.json
   看现有 chart 库（echarts / chart.js / vue-chartjs / apexcharts 哪个），复用现有的
5. J5 输出的 5 个 API contract

范围：
1. 新建路由 /evaluation/quality 和 /evaluation/quality/case/:id
2. EvaluationQuality.vue：主看板（KPI 卡 + 趋势图 + KB 切片 + 最差 case + 成本）
3. EvaluationQualityCase.vue：case 详情页
4. 加菜单入口（EvaluationLab 旁边）
5. 不做配置 UI（J7 才做）

UI 布局（必须严格按此实现）：

【EvaluationQuality.vue】主看板

页面顶部：时间范围选择器（7天 / 30天 / 90天）+ KB 筛选下拉

第一行：4 个 KPI 卡（响应式 4 列）
- 综合质量    [0.82] [↓ -0.03]
- 答案忠实度   [0.78] [↓ ⚠️]
- 上下文精度   [0.91] [→ 持平]
- 答案相关性   [0.85] [↑ +0.01]

第二行：趋势折线图（一张大图，4 条线，可勾选显示哪条）
- X 轴：日期
- Y 轴：分数 0.0-1.0
- 4 条线：overall / faithfulness / context_precision / answer_relevance
- 鼠标 hover 显示当日 sample_count + 各项数值

第三行（两列布局）：
左侧：KB 切片表格
- 列：KB 名 / 分数 / 趋势 / 样本数
- 默认按分数升序（最差的在上）
- 点击行跳转 /evaluation/quality?kbId=xxx

右侧：最差 10 个 case 列表
- 每个 case 显示：query 截断 + 分数 + 时间
- 点击跳转 /evaluation/quality/case/{judgeResultId}

第四行：成本卡
- 累计调用次数 / 累计成本 / 日均 / 月度预测
- 简单条形图按来源 stack：PRODUCTION / GOLDEN_SET / MANUAL

告警 banner（条件渲染）：
- AnomalyVo.severity != null 时在页面顶部显示红色/黄色 banner

【EvaluationQualityCase.vue】详情页

顶部：返回按钮 + Case ID + 评分卡（小一号，水平）

主体上半部分：
左：Query 原文 + Generated Answer（高亮引用 [n]）
右：评分明细（4 个 dimension + 综合分 + bottleneck 标签）

主体下半部分：
检索 chunks 列表（卡片样式）
- 每个 chunk：chunkId + score + content + 是否被裁判判定 relevant
- relevant=false 的 chunk 灰色 + 标记 ⚠️ 偏题

底部：DeepSeek 裁判 reasoning（折叠面板，默认收起）+ improvements 列表

Vue 实现要点：
- 用 composition API（与项目现有风格一致）
- chart 库复用项目现有的（看 package.json，大概率是 echarts 或 chart.js）
- 颜色规范：
  - 分数 ≥ 0.8 绿色
  - 0.6-0.8 黄色
  - < 0.6 红色
  - 趋势 ↑ 绿 / ↓ 红 / → 灰
- 时间格式统一：YYYY-MM-DD HH:mm（不要"几小时前"模糊化）

路由（frontend/src/router/index.js）：
{
  path: '/evaluation/quality',
  name: 'EvaluationQuality',
  component: () => import('../views/EvaluationQuality.vue'),
  meta: { requiresAuth: true, roles: ['ADMIN', 'KB_EDITOR'] }
},
{
  path: '/evaluation/quality/case/:id',
  name: 'EvaluationQualityCase',
  component: () => import('../views/EvaluationQualityCase.vue'),
  meta: { requiresAuth: true, roles: ['ADMIN', 'KB_EDITOR'] }
}

菜单（看现有 layout 怎么注册菜单，加一项"质量看板"在评测实验室旁边）

API 封装（frontend/src/api/quality.js 或类似）：
- fetchOverview(days, kbId)
- fetchByKb(days)
- fetchWorstCases(limit, days, kbId)
- fetchCaseDetail(id)
- fetchCost(days)

错误处理：
- 401 → 跳登录
- 403 → 显示"无权访问该 KB"
- 500 → toast 错误 + 看板显示 placeholder（"数据加载失败，请刷新重试"）
- 没数据（sample_count=0）→ 友好提示"暂无评测数据，请检查 Golden Set 是否启用"

禁止项：
- 不能新加 chart 库依赖（必须用项目现有的）
- 不能让看板每次进入都同步等所有 API（用 Promise.allSettled 并行 + 各自 loading state）
- 不能让 case 详情页阻塞渲染（chunks 多时可分批 / 折叠）
- 不能用 v-html 渲染裁判 reasoning（XSS 风险，纯文本即可）
- 不能加全局 polling（用户手动刷新或切换时间范围才重拉）

验收标准：
1. npm run build 成功，bundle size 增量 < 100KB
2. 路由可达：登录后能进 /evaluation/quality
3. 4 个 API 调用全部成功（mock 数据下也能渲染）
4. 响应式：1280 / 1440 / 1920 三种屏幕尺寸下不溢出
5. Playwright e2e（一条最小用例）：
   - 登录 → 进入 /evaluation/quality → 等待 KPI 卡渲染 → 点击最差 case → 详情页加载完成
6. 颜色规范验证：手工造 3 条 mock 数据（高/中/低分），分别看到绿/黄/红
7. 异常态：sample_count=0 显示友好提示而不是 NaN
8. 权限：KB_EDITOR 只能看自己有权限的 KB 切片

执行流程：
1. 输出文件清单（≤8 个文件）
2. 看项目 chart 库选型
3. 写 EvaluationQuality.vue + Case.vue + API 封装 + 路由
4. npm run build + 浏览器手动跑一遍
5. 加 Playwright e2e 用例
6. PR 标题：feat(v6/J6): in-app quality dashboard pages
7. 完成后追加：J6 ✅
```

`=== COPY END ===`

---

## J7：抽样配置 UI + Golden Set 自动回放 cron

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务执行 V6 LLM-as-Judge 最后一个 PR：
抽样率管理 UI + Golden Set 每日自动回放。

任务：J7 抽样配置 + Golden Set 回放
依赖：J6 已合并
工期：1 天

必读上下文：
1. J3 的 JudgeSampler + judge_sampling_config 表
2. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/answer/AnswerService.java
   （Golden Set 回放需要模拟调用 /api/v1/answer，复用 AnswerService 接口）
3. /Users/amy/CursorProject/rag-forge/frontend/src/views/EvaluationLab.vue
   （eval_questions 现有 UI，judge_enabled 切换在这里加）

范围：
1. JudgeSamplingController：管理 sampling config 的 CRUD
2. EvaluationQuality.vue 加配置抽屉（侧边面板）
3. EvaluationLab.vue 加 judge_enabled 切换
4. GoldenSetReplayJob：每天 03:00 跑
5. 成本守门：月度超阈值告警

字段设计：

REST API：

@RestController
@RequestMapping("/api/v1/evaluation/quality/sampling")
@PreAuthorize("hasRole('ADMIN')")    // 只有 ADMIN 能改抽样率
@RequiredArgsConstructor
public class JudgeSamplingController {
    private final JudgeSamplingConfigMapper configMapper;
    private final RagAuthContextHolder authContextHolder;

    @GetMapping
    public Result<List<JudgeSamplingConfig>> list() { ... }

    @PostMapping
    public Result<JudgeSamplingConfig> upsert(@RequestBody SamplingUpsertRequest req) {
        // 校验 sample_rate ∈ [0, 1]
        // 超过 0.1 (10%) 必须含 confirm=true 参数（防止误操作把成本拉爆）
        if (req.getSampleRate().compareTo(new BigDecimal("0.1")) > 0 && !req.isConfirmed()) {
            throw new BizException(400, "SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM");
        }
        ...
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { ... }
}

@Data
public class SamplingUpsertRequest {
    String scopeType;        // GLOBAL / KB / TENANT
    Long scopeId;
    String tenantId;
    BigDecimal sampleRate;
    Boolean enabled;
    boolean confirmed;       // 超过 10% 时必须 true
}

@RestController
@RequestMapping("/api/v1/evaluation/golden-set")
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
public class GoldenSetController {
    @PostMapping("/replay")
    public Result<ReplayResultVo> replayNow(
        @RequestParam(required = false) Long datasetId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        // 手动触发回放，立即跑（异步）
        ...
    }
}

GoldenSetReplayJob：

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragforge.judge.golden-replay.enabled", havingValue = "true", matchIfMissing = true)
public class GoldenSetReplayJob {
    private final EvalQuestionMapper questionMapper;
    private final AnswerService answerService;

    @Scheduled(cron = "${ragforge.judge.golden-replay.cron:0 0 3 * * *}")
    @SchedulerLock(name = "judge-golden-replay", lockAtMostFor = "PT2H")
    public void replay() {
        List<EvalQuestion> questions = questionMapper.selectList(
            new LambdaQueryWrapper<EvalQuestion>().eq(EvalQuestion::getJudgeEnabled, true)
        );
        log.info("Golden replay starting: {} questions", questions.size());

        int success = 0, failed = 0;
        for (EvalQuestion q : questions) {
            try {
                AnswerRequest req = new AnswerRequest();
                req.setQuery(q.getQuery());
                req.setKbIds(parseKbIds(q));
                req.setJudgeSource("GOLDEN_SET");
                req.setGoldenQuestionId(q.getId());
                req.setForceSample(true);    // 强制评测（绕过抽样判定）
                answerService.answerSync(req);   // 同步调用版本
                success++;
            } catch (Exception e) {
                log.warn("Golden replay failed for q={}: {}", q.getId(), e.getMessage());
                failed++;
            }
            // 限速：每题间隔 500ms 避免拉爆 DashScope
            Thread.sleep(500);
        }

        log.info("Golden replay done: success={} failed={}", success, failed);
    }
}

注意：AnswerService 当前只有 SSE 版本，本任务要加一个 answerSync(AnswerRequest)
返回完整 AnswerResponse 同步版本，复用现有 answer 逻辑但不流式。
回放跑得慢点没关系（每题 5-15 秒），用同步阻塞简单。

application.yml：

ragforge:
  judge:
    aggregator-enabled: ${RAGFORGE_JUDGE_AGGREGATOR_ENABLED:true}
    golden-replay:
      enabled: ${RAGFORGE_JUDGE_GOLDEN_REPLAY_ENABLED:true}
      cron: ${RAGFORGE_JUDGE_GOLDEN_REPLAY_CRON:0 0 3 * * *}
    cost-guard:
      monthly-budget-cny: ${RAGFORGE_JUDGE_MONTHLY_BUDGET:200}
      warn-threshold: 0.8     # 用到 80% 预算开始警告
      critical-threshold: 1.0  # 用到 100% 拉警报

成本守门（J5 cost API 扩展或单独 cron 任务）：

@Component
public class JudgeCostGuard {
    @Scheduled(cron = "0 0 * * * *")  // 每小时检查
    public void check() {
        BigDecimal currentMonth = queryService.costThisMonth();
        BigDecimal budget = costGuardConfig.getMonthlyBudget();
        BigDecimal ratio = currentMonth.divide(budget, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            log.error("JUDGE_COST_CRITICAL: 本月成本 {} CNY 已超预算 {} CNY", currentMonth, budget);
            // 写 system_alerts 表（如有）或简单 log error
        } else if (ratio.compareTo(new BigDecimal("0.8")) >= 0) {
            log.warn("JUDGE_COST_WARN: 本月成本 {} CNY 已用预算 {}%", currentMonth, ratio.multiply(BigDecimal.valueOf(100)));
        }
    }
}

前端改造：

【EvaluationQuality.vue】右上角加 "设置" 按钮，点击打开抽屉：
- 全局抽样率 slider（0% - 10%）
- 超过 5% 时显示成本预估告警
- 各 KB 单独覆盖列表（add/edit/delete）
- 当前 Golden Set 启用题数 + "立即回放" 按钮（手动触发）
- 月度预算配置（仅显示，改要找架构师）

【EvaluationLab.vue】eval_questions 列表加一列：
- "Golden Set" 开关（toggle judge_enabled）
- "Tags" 多选（judge_tags）

禁止项：
- sampling 改动必须 ADMIN role（@PreAuthorize("hasRole('ADMIN')")）
- 不能让 sample_rate > 0.1 直接生效（必须 confirmed=true）
- 不能允许 sample_rate < 0 或 > 1
- 回放 cron 必须 ShedLock（防多 api pod 并发跑）
- 不能让回放阻塞 api pod 主线程（用 @Async 或单独 cron pool）
- 不能跳过 Thread.sleep 限速（DashScope QPS 有上限）
- 成本守门不能自动停止评测（只警告，不阻塞）

验收标准：
1. mvn test 全绿
2. 单测：
   - sample_rate=0.15 + confirmed=false → 400
   - sample_rate=0.15 + confirmed=true → 200
   - 非 ADMIN 改 sampling → 403
   - GoldenSetReplayJob 跑 5 题 dry-run（mock answerService）
3. ShedLock 测试：双 pod 模拟，只有一个进 replay
4. Cost guard：mock 成本 ¥160 (80%) → warn log；mock ¥210 (105%) → error log
5. 前端：
   - 抽样设置抽屉打开，slider 改动后保存成功
   - 超过 5% 看到 cost 预估
   - EvaluationLab eval_questions 切换 judge_enabled 立即生效
6. 集成：手动触发 /api/v1/evaluation/golden-set/replay?limit=3，3 题完成后 judge_results 多 3 行 source=GOLDEN_SET

执行流程：
1. 输出文件清单
2. 实现 Controller + Job + UI + 单测
3. mvn test + npm run build 全绿
4. PR 标题：feat(v6/J7): sampling config UI + golden set replay
5. 完成后追加：J7 ✅ → V6 LLM-as-Judge MVP 完成
```

`=== COPY END ===`

---

## 完成节奏建议

| 阶段 | PR | 累计天数 | 你能看到的效果 |
|---|---|---|---|
| 阶段 1 | J1 | 0.5 | DB schema OK |
| 阶段 2 | J2 | 2 | DeepSeek 客户端能单点调通 |
| 阶段 3 | J3 | 3 | AnswerService 接 MQ，但 Consumer 还没有 |
| 阶段 4 | J4 | 5 | **完整闭环跑通**：调 /answer → MQ → JudgeWorker → DeepSeek → 写库 |
| 阶段 5 | J5 | 6.5 | API 返回看板数据 |
| 阶段 6 | J6 | 8.5 | **可演示**：前端看板能看到数字 |
| 阶段 7 | J7 | 9.5 | 完整可持续运行 |

## 关键风险提醒

1. **DeepSeek API key 必须提前办好**（J2 集成测试要用）
2. **Golden Set 30-100 题要架构师手工准备**（J7 跑批的输入）
3. **每个 PR 必须 mvn test 全绿才能合并**（不要 J4 留 bug 等 J5 fix）
4. **J4 上云时记得在 K3s ConfigMap 加 DEEPSEEK_API_KEY**（Secret 里加）
5. **生产抽样率 ≤ 1%**（默认值已对，UI 上 ADMIN 改超过 10% 必须二次确认）

---

文件已保存到 `docs/v6-llm-judge-execution-prompts.md`，你按 J1 → J7 顺序复制 prompt 给 Codex 执行即可。
J1 ✅ <commit-sha> 2026-06-23
J2 ✅ 2026-06-23
