-- 批次3：凭证安全基线收口（M8-01/M8-08）——api_keys 不再保存完整明文 key。
-- 顺序：先回填 key_hash / key_prefix（保证 hash 鉴权可用），再清空明文列。
-- 鉴权链路已是 hash 优先（ApiKeyInterceptor.findValidApiKey），清明文后过渡不断（M8-10）。

-- 1) 明文列放开 NOT NULL（后续新建 key 不再写明文）
ALTER TABLE api_keys ALTER COLUMN api_key DROP NOT NULL;

-- 2) 回填存量 key 的 hash（sha256 为 PostgreSQL 11+ 内置函数）与展示前缀
UPDATE api_keys
   SET key_hash = encode(sha256(convert_to(api_key, 'UTF8')), 'hex')
 WHERE key_hash IS NULL AND api_key IS NOT NULL;

UPDATE api_keys
   SET key_prefix = substring(api_key FROM 1 FOR 12)
 WHERE key_prefix IS NULL AND api_key IS NOT NULL;

-- 3) 清空明文（此后库内仅 hash + 前缀；明文仅创建时返回一次）
UPDATE api_keys SET api_key = NULL WHERE api_key IS NOT NULL;

COMMENT ON COLUMN api_keys.api_key IS '已废弃：明文不再入库（仅创建时一次性返回）；鉴权走 key_hash';
