-- 压缩包上传：容器文档模型（zip / tar.gz）。
-- 设计稿：docs/dev/archive-upload-design.html（V1.2）。
-- 复用 documents 表承载容器与子文档，避免另建并行 CRUD。

-- 1) 容器语义列（全部 nullable，向后兼容，不回填历史）
ALTER TABLE documents
  ADD COLUMN parent_document_id BIGINT,
  ADD COLUMN archive_entry_path VARCHAR(1024),
  ADD COLUMN expand_summary     JSONB;

-- 子文档 → 容器；删容器级联删子文档（子文档 chunk/ES 由应用层 cleanupArtifacts 清理）
ALTER TABLE documents
  ADD CONSTRAINT fk_documents_parent
    FOREIGN KEY (parent_document_id) REFERENCES documents (id) ON DELETE CASCADE;

-- 列表下钻按 parent 过滤；仅子文档建索引（部分索引，容器与普通文档 parent 为 NULL 不占用）
CREATE INDEX idx_documents_parent
  ON documents (parent_document_id)
  WHERE parent_document_id IS NOT NULL;

-- 2) 放开 parse_status CHECK 约束，纳入容器状态机 EXPANDING / EXPANDED。
--    设计稿 §1 使用这两个容器态，但既有约束（V19 ck_documents_parse_status）不含，
--    不放开会导致落容器/领取时 CHECK 违约。此为设计稿 §4 的补全项。
ALTER TABLE documents
  DROP CONSTRAINT IF EXISTS ck_documents_parse_status;
ALTER TABLE documents
  ADD CONSTRAINT ck_documents_parse_status
    CHECK (parse_status IN
      ('PENDING','PROCESSING','COMPLETED','FAILED','REPROCESSING','EXPANDING','EXPANDED'));

COMMENT ON COLUMN documents.parent_document_id IS '压缩包子文档指向容器；容器与普通文档为 NULL';
COMMENT ON COLUMN documents.archive_entry_path IS '子文档在压缩包内的相对路径（展示 / 冲突提示，不参与去重身份）';
COMMENT ON COLUMN documents.expand_summary IS '仅容器：{totalEntries,registered,skipped[{path,reason}]}';
