-- 为 document_chunks 新增 chunk_type 列，支持求职场景的元数据精准检索
ALTER TABLE document_chunks
    ADD COLUMN chunk_type VARCHAR(50) DEFAULT NULL;

-- 复合索引（kb_id 已有单列索引）
CREATE INDEX idx_chunks_kb_type ON document_chunks (kb_id, chunk_type);
