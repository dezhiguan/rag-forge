-- RAGForge initial schema (9 tables)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_bases (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    embedding_model VARCHAR(50)  NOT NULL DEFAULT 'text-embedding-v4',
    chunk_size      INT          NOT NULL DEFAULT 512,
    chunk_overlap   INT          NOT NULL DEFAULT 64,
    doc_count       INT          NOT NULL DEFAULT 0,
    chunk_count     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE documents (
    id           BIGSERIAL PRIMARY KEY,
    kb_id        BIGINT       NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    filename     VARCHAR(255) NOT NULL,
    file_path    VARCHAR(500) NOT NULL,
    file_size    BIGINT,
    file_type    VARCHAR(20),
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'pending',
    chunk_count  INT          NOT NULL DEFAULT 0,
    error_msg    TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_documents_kb_id ON documents (kb_id);

CREATE TABLE document_chunks (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    kb_id           BIGINT    NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    chunk_index     INT       NOT NULL,
    content         TEXT      NOT NULL,
    content_vector  vector(1024),
    token_count     INT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_document_chunks_doc_id ON document_chunks (doc_id);
CREATE INDEX idx_document_chunks_kb_id ON document_chunks (kb_id);
CREATE INDEX idx_chunks_vector ON document_chunks
    USING ivfflat (content_vector vector_cosine_ops) WITH (lists = 100);

CREATE TABLE retrieval_logs (
    id                BIGSERIAL PRIMARY KEY,
    query             TEXT         NOT NULL,
    rewritten_queries TEXT,
    strategy          VARCHAR(30),
    kb_ids            VARCHAR(200),
    top_k             INT,
    result_count      INT,
    latency_ms        INT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE eval_datasets (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    kb_id           BIGINT       NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    question_count  INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_eval_datasets_kb_id ON eval_datasets (kb_id);

CREATE TABLE eval_questions (
    id               BIGSERIAL PRIMARY KEY,
    dataset_id       BIGINT NOT NULL REFERENCES eval_datasets (id) ON DELETE CASCADE,
    question         TEXT   NOT NULL,
    expected_doc_ids TEXT
);

CREATE INDEX idx_eval_questions_dataset_id ON eval_questions (dataset_id);

CREATE TABLE eval_experiments (
    id                   BIGSERIAL PRIMARY KEY,
    dataset_id           BIGINT       NOT NULL REFERENCES eval_datasets (id) ON DELETE CASCADE,
    strategy             VARCHAR(30),
    enable_query_rewrite BOOLEAN      NOT NULL DEFAULT TRUE,
    enable_reranker      BOOLEAN      NOT NULL DEFAULT TRUE,
    top3_hit_rate        DECIMAL(5, 2),
    top5_hit_rate        DECIMAL(5, 2),
    avg_latency_ms       INT,
    status               VARCHAR(20)  NOT NULL DEFAULT 'running',
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_eval_experiments_dataset_id ON eval_experiments (dataset_id);

CREATE TABLE eval_results (
    id                  BIGSERIAL PRIMARY KEY,
    experiment_id       BIGINT        NOT NULL REFERENCES eval_experiments (id) ON DELETE CASCADE,
    question_id         BIGINT        NOT NULL REFERENCES eval_questions (id) ON DELETE CASCADE,
    hit                 BOOLEAN       NOT NULL DEFAULT FALSE,
    hit_at              INT,
    recalled_chunk_ids  TEXT,
    score               DECIMAL(5, 4),
    latency_ms          INT
);

CREATE INDEX idx_eval_results_experiment_id ON eval_results (experiment_id);
CREATE INDEX idx_eval_results_question_id ON eval_results (question_id);

CREATE TABLE api_keys (
    id          BIGSERIAL PRIMARY KEY,
    key_name    VARCHAR(100) NOT NULL,
    api_key     VARCHAR(64)  NOT NULL UNIQUE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    rate_limit  INT          NOT NULL DEFAULT 100,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
