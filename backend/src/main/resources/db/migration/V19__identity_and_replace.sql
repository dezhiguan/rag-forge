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

UPDATE documents
SET parse_status = UPPER(parse_status)
WHERE parse_status IS NOT NULL;

ALTER TABLE documents
  DROP CONSTRAINT IF EXISTS ck_documents_parse_status;
ALTER TABLE documents
  ADD CONSTRAINT ck_documents_parse_status
    CHECK (parse_status IN ('PENDING','PROCESSING','COMPLETED','FAILED','REPROCESSING'));

ALTER TABLE IF EXISTS answer_logs
  ADD COLUMN citations_snapshot JSONB;

ALTER TABLE eval_questions
  ADD COLUMN expected_text_snippets JSONB;
