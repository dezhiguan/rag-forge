ALTER TABLE document_chunks
  ADD COLUMN IF NOT EXISTS chunker_strategy VARCHAR(32),
  ADD COLUMN IF NOT EXISTS chunker_params_json JSONB,
  ADD COLUMN IF NOT EXISTS heading_path VARCHAR(512);

ALTER TABLE knowledge_bases
  ADD COLUMN IF NOT EXISTS chunker_profile_json JSONB;
