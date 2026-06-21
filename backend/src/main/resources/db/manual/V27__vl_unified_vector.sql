-- T10-rewrite manual migration: qwen3-vl-embedding unified vector space.
-- This file is intentionally outside db/migration and must be executed manually.

TRUNCATE TABLE document_chunks;
UPDATE documents SET parse_status = 'PENDING', chunk_count = 0, error_msg = NULL;

ALTER TABLE document_chunks
  DROP COLUMN IF EXISTS content_vector,
  DROP COLUMN IF EXISTS image_vector,
  DROP COLUMN IF EXISTS image_key;

ALTER TABLE document_chunks
  ADD COLUMN vl_vector vector(2560);

ALTER TABLE document_chunks
  DROP CONSTRAINT IF EXISTS ck_chunk_modality;
ALTER TABLE document_chunks
  ADD CONSTRAINT ck_chunk_modality
    CHECK (chunk_modality IN ('TEXT', 'IMAGE'));

DROP INDEX IF EXISTS idx_chunks_content_vector_hnsw;
DROP INDEX IF EXISTS idx_chunks_image_vector_hnsw;

CREATE INDEX idx_chunks_vl_vector_hnsw
  ON document_chunks USING hnsw (vl_vector vector_cosine_ops)
  WITH (m=16, ef_construction=64);
