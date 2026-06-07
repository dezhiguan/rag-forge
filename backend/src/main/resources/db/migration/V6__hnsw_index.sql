-- flyway:nonTransactional
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chunks_vector_hnsw
    ON document_chunks
    USING hnsw (content_vector vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
