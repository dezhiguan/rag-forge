CREATE TABLE IF NOT EXISTS clean_profiles (
  id BIGSERIAL PRIMARY KEY,
  scope VARCHAR(16) NOT NULL,
  scope_id BIGINT NOT NULL,
  config JSONB NOT NULL,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_clean_profiles_scope
  ON clean_profiles (scope, scope_id);

ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS clean_report_json JSONB,
  ADD COLUMN IF NOT EXISTS clean_profile_id BIGINT;
