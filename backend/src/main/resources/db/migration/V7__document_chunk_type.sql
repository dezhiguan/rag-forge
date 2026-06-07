-- documents 表添加 chunk_type 字段，用于文本上传时指定 chunk 语义类型
ALTER TABLE documents ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(50);
