# T10-rewrite Manual Migration Runbook

## 何时执行

只在 PR1、PR2、PR3 全部合入并发布前执行。执行顺序必须是：

1. 备份 PostgreSQL。
2. 人工执行 `db/manual/V27__vl_unified_vector.sql`。
3. 验证表结构和数据状态。
4. 上线 T10-rewrite 代码。
5. 启动 `prod,rebuild` profile 触发 `FullRebuildRunner`。

## 执行前备份

至少备份 `documents` 和 `document_chunks`：

```bash
kubectl exec -n <namespace> <postgres-pod> -- \
  pg_dump -U <user> -d <db> -t documents -t document_chunks \
  > ragforge-v27-before-vl-unified-vector.sql
```

## 执行迁移

```bash
kubectl cp backend/src/main/resources/db/manual/V27__vl_unified_vector.sql \
  <namespace>/<postgres-pod>:/tmp/V27__vl_unified_vector.sql

kubectl exec -n <namespace> <postgres-pod> -- \
  psql -U <user> -d <db> -v ON_ERROR_STOP=1 \
  -f /tmp/V27__vl_unified_vector.sql
```

## 验证 SQL

确认 chunks 已清空：

```sql
SELECT count(*) AS chunk_count FROM document_chunks;
```

确认文档全部等待重建：

```sql
SELECT parse_status, count(*)
FROM documents
GROUP BY parse_status
ORDER BY parse_status;
```

确认 `vl_vector` 列存在：

```sql
SELECT column_name, udt_name
FROM information_schema.columns
WHERE table_name = 'document_chunks'
  AND column_name = 'vl_vector';
```

确认旧列已删除：

```sql
SELECT column_name
FROM information_schema.columns
WHERE table_name = 'document_chunks'
  AND column_name IN ('content_vector', 'image_vector', 'image_key');
```

确认 HNSW 索引存在：

```sql
SELECT indexname
FROM pg_indexes
WHERE tablename = 'document_chunks'
  AND indexname = 'idx_chunks_vl_vector_hnsw';
```

## 重建

迁移验证通过后再启动重建：

```bash
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=prod,rebuild
```
