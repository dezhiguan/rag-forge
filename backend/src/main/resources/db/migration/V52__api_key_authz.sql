-- 批次1：API Key 授权模型扩展（additive，向后兼容；本批不改变鉴权行为）
-- scope_mode：可读范围 ORG_ALL(默认) | KB_LIST
-- access_level：权限级别 READ(本期仅此) | READ_WRITE(暂不开放)
-- expires_at：过期时间（空=永不过期）
-- key_hash：SHA-256(hex)，批次2 起按 hash 鉴权、明文回退兼容
-- key_prefix：展示用前缀（如 sk-rf-xxxxxx）

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS scope_mode   VARCHAR(16) NOT NULL DEFAULT 'ORG_ALL';
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS access_level VARCHAR(16) NOT NULL DEFAULT 'READ';
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS expires_at   TIMESTAMP   NULL;
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS key_hash     VARCHAR(64) NULL;
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS key_prefix   VARCHAR(16) NULL;

-- 展示用前缀回填（存量明文 key 前 12 位）
UPDATE api_keys
   SET key_prefix = substring(api_key FROM 1 FOR 12)
 WHERE key_prefix IS NULL AND api_key IS NOT NULL;

COMMENT ON COLUMN api_keys.scope_mode   IS 'ORG_ALL(默认)|KB_LIST：Key 可读范围';
COMMENT ON COLUMN api_keys.access_level IS 'READ(本期仅此)|READ_WRITE：权限级别';
COMMENT ON COLUMN api_keys.expires_at   IS '过期时间；空=永不过期';
COMMENT ON COLUMN api_keys.key_hash     IS 'SHA-256(hex)；批次2 起按 hash 鉴权，明文回退兼容';
COMMENT ON COLUMN api_keys.key_prefix   IS '展示用前缀，如 sk-rf-xxxxxx';
