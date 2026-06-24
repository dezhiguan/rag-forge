ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS rechunk_strategy VARCHAR(32),
  ADD COLUMN IF NOT EXISTS rechunk_params_json JSONB;
