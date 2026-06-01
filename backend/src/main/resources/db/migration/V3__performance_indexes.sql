CREATE INDEX IF NOT EXISTS idx_documents_kb_status_created
  ON documents (kb_id, parse_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_documents_parse_status
  ON documents (parse_status);

CREATE INDEX IF NOT EXISTS idx_document_chunks_kb_doc
  ON document_chunks (kb_id, doc_id);

CREATE INDEX IF NOT EXISTS idx_document_chunks_doc_chunk
  ON document_chunks (doc_id, chunk_index);

CREATE INDEX IF NOT EXISTS idx_retrieval_logs_created_at
  ON retrieval_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_eval_experiments_created_at
  ON eval_experiments (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_bases_status_created
  ON knowledge_bases (status, created_at DESC);
