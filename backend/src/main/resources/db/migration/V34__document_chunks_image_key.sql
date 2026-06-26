-- V25 同名的 ADD COLUMN IF NOT EXISTS 在部分环境上没生效（云上 PG 实际不存在 image_key 列），
-- 导致 V8 / V8.1 的 entity 引入 imageKey 字段后，document_chunks 的 SELECT 走 image_key
-- 直接 500。这里再幂等地补一次。
ALTER TABLE document_chunks
  ADD COLUMN IF NOT EXISTS image_key VARCHAR(512);
