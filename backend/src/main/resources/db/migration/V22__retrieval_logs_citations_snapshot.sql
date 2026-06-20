ALTER TABLE retrieval_logs
  ADD COLUMN IF NOT EXISTS citations_snapshot JSONB;
