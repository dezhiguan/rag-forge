ALTER TABLE document_chunks
  ADD COLUMN IF NOT EXISTS chunk_modality VARCHAR(16) DEFAULT 'TEXT',
  ADD COLUMN IF NOT EXISTS image_vector vector(1024),
  ADD COLUMN IF NOT EXISTS image_key VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_chunks_image_vector_hnsw
  ON document_chunks USING hnsw (image_vector vector_cosine_ops)
  WITH (m=16, ef_construction=64)
  WHERE chunk_modality IN ('IMAGE_DESC','OCR_TEXT');
