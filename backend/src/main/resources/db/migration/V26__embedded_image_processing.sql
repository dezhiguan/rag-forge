ALTER TABLE knowledge_bases
  ADD COLUMN IF NOT EXISTS image_processing_mode VARCHAR(16) NOT NULL DEFAULT 'OFF';

ALTER TABLE document_chunks
  ADD COLUMN IF NOT EXISTS chunk_metadata_json JSONB;
