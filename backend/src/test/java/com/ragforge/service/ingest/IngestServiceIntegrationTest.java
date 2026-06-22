package com.ragforge.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.OnConflict;
import com.ragforge.model.entity.Document;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.support.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.postgresql.util.PSQLState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = IngestServiceIntegrationTest.TestConfig.class,
    properties = {
      "spring.profiles.active=test",
      "mybatis-plus.configuration.map-underscore-to-camel-case=true"
    })
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
class IngestServiceIntegrationTest extends BaseIntegrationTest {

  @Autowired private IngestService ingestService;

  @Autowired private DocumentMapper documentMapper;

  @SpyBean private DocumentChunkMapper chunkMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @MockBean private DocumentProcessProducer mqProducer;

  @MockBean private EsIndexService esIndexService;

  @MockBean private ObjectStorage objectStorage;

  @BeforeEach
  void cleanTables() {
    reset(chunkMapper, mqProducer, esIndexService, objectStorage);
    jdbcTemplate.update("DELETE FROM document_chunks");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM knowledge_bases");
  }

  @Test
  void v19PartialUniqueIndexMatchesIdentityRules() {
    Long kbId = insertKb("partial-unique");

    insertDocument(kbId, "doc-a", "key-a", "ext-a", null, null, "COMPLETED");
    assertThatThrownBy(
            () -> insertDocument(kbId, "doc-b", "key-b", "ext-a", null, null, "COMPLETED"))
        .isInstanceOf(DuplicateKeyException.class)
        .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo(PSQLState.UNIQUE_VIOLATION.getState()));

    insertDocument(kbId, "doc-c", "key-c", null, null, null, "COMPLETED");
    insertDocument(kbId, "doc-d", "key-d", null, null, null, "COMPLETED");

    insertDocument(kbId, "doc-e", "key-e", null, "https://example.com/a", null, "COMPLETED");
    assertThatThrownBy(
            () ->
                insertDocument(
                    kbId, "doc-f", "key-f", null, "https://example.com/a", null, "COMPLETED"))
        .isInstanceOf(DuplicateKeyException.class)
        .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo(PSQLState.UNIQUE_VIOLATION.getState()));

    insertDocument(kbId, "doc-g", "key-g", null, null, "md5-a", "REPROCESSING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT parse_status FROM documents WHERE filename = 'doc-g'", String.class))
        .isEqualTo("REPROCESSING");
  }

  @Test
  void registerResolvesIdentityByExternalIdThenSourceUrlThenMd5() {
    Long kbId = insertKb("identity-priority");
    Long externalDoc =
        insertDocument(kbId, "external", "key-external", "ext-1", "url-1", "md5-1", "COMPLETED");
    Long urlDoc = insertDocument(kbId, "url", "key-url", null, "url-2", "md5-2", "COMPLETED");
    Long md5Doc = insertDocument(kbId, "md5", "key-md5", null, null, "md5-3", "COMPLETED");

    assertThat(ingestService.register(command(kbId, "new-a", "key-a", identity("ext-1", "url-2", "md5-3"), OnConflict.SKIP)).getDocumentId())
        .isEqualTo(externalDoc);
    assertThat(ingestService.register(command(kbId, "new-b", "key-b", identity(null, "url-2", "md5-3"), OnConflict.SKIP)).getDocumentId())
        .isEqualTo(urlDoc);
    assertThat(ingestService.register(command(kbId, "new-c", "key-c", identity(null, null, "md5-3"), OnConflict.SKIP)).getDocumentId())
        .isEqualTo(md5Doc);
  }

  @Test
  void rejectSkipAndReplaceBranchesKeepSideEffectsAfterCommit() {
    Long kbId = insertKb("branches");
    Long docId =
        insertDocument(kbId, "old", "old-key", "ext-branch", null, "old-md5", "COMPLETED");
    insertChunk(docId, kbId, "old chunk");

    IngestCommand reject =
        command(kbId, "reject", "reject-key", identity("ext-branch", null, "new-md5"), OnConflict.REJECT);
    assertThatThrownBy(() -> ingestService.register(reject))
        .isInstanceOf(BizException.class)
        .hasMessage("DOC_IDENTITY_CONFLICT");

    IngestResult skipped =
        ingestService.register(
            command(kbId, "skip", "skip-key", identity("ext-branch", null, "new-md5"), OnConflict.SKIP));
    assertThat(skipped.getStatus()).isEqualTo(IngestResult.Status.SKIPPED);
    assertThat(skipped.getDocumentId()).isEqualTo(docId);
    verifyNoInteractions(mqProducer, esIndexService, objectStorage);

    IngestResult replaced =
        ingestService.register(
            command(kbId, "new", "new-key", identity("ext-branch", null, "new-md5"), OnConflict.REPLACE));
    assertThat(replaced.getStatus()).isEqualTo(IngestResult.Status.REPLACED);
    assertThat(replaced.getDocumentId()).isEqualTo(docId);
    assertThat(countChunks(docId)).isZero();
    Document doc = documentMapper.selectById(docId);
    assertThat(doc.getFilename()).isEqualTo("new");
    assertThat(doc.getStorageKey()).isEqualTo("new-key");
    assertThat(doc.getParseStatus()).isEqualTo("PENDING");
    assertThat(doc.getContentMd5()).isEqualTo("new-md5");
    verify(esIndexService).deleteByDocId(eq(docId));
    verify(objectStorage).delete(eq("test-bucket"), eq("old-key"));
    verify(mqProducer).send(eq(docId));
  }

  @Test
  void replaceCommitsDbBeforeAfterCommitObjectStorageFailure() {
    Long kbId = insertKb("after-commit-failure");
    Long docId =
        insertDocument(kbId, "old", "old-key", "ext-after", null, "old-md5", "COMPLETED");
    insertChunk(docId, kbId, "old chunk");
    doThrow(new RuntimeException("oss down")).when(objectStorage).delete("test-bucket", "old-key");

    assertThatThrownBy(
            () ->
                ingestService.register(
                    command(kbId, "new", "new-key", identity("ext-after", null, "new-md5"), OnConflict.REPLACE)))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("oss down");

    assertThat(countChunks(docId)).isZero();
    Document doc = documentMapper.selectById(docId);
    assertThat(doc.getFilename()).isEqualTo("new");
    assertThat(doc.getStorageKey()).isEqualTo("new-key");
    assertThat(doc.getParseStatus()).isEqualTo("PENDING");
    verify(esIndexService).deleteByDocId(eq(docId));
    verify(mqProducer, never()).send(eq(docId));
  }

  @Test
  void replaceRollsBackWhenChunkDeleteFailsInsideTransaction() {
    Long kbId = insertKb("rollback");
    Long docId =
        insertDocument(kbId, "old", "old-key", "ext-rollback", null, "old-md5", "COMPLETED");
    insertChunk(docId, kbId, "old chunk");
    doThrow(new RuntimeException("delete failed")).when(chunkMapper).deleteByDocumentId(docId);

    assertThatThrownBy(
            () ->
                ingestService.register(
                    command(kbId, "new", "new-key", identity("ext-rollback", null, "new-md5"), OnConflict.REPLACE)))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("delete failed");

    Document doc = documentMapper.selectById(docId);
    assertThat(doc.getFilename()).isEqualTo("old");
    assertThat(doc.getStorageKey()).isEqualTo("old-key");
    assertThat(doc.getParseStatus()).isEqualTo("COMPLETED");
    assertThat(countChunks(docId)).isEqualTo(1);
    verifyNoInteractions(mqProducer, esIndexService, objectStorage);
  }

  private Long insertKb(String name) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO knowledge_bases (name, description, embedding_model, chunk_size, chunk_overlap, status)
        VALUES (?, 'it', 'qwen3-vl-embedding', 512, 64, 'active')
        RETURNING id
        """,
        Long.class,
        name);
  }

  private Long insertDocument(
      Long kbId,
      String filename,
      String storageKey,
      String externalId,
      String sourceUrl,
      String contentMd5,
      String parseStatus) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO documents (
          kb_id, filename, file_path, file_size, file_type, file_md5, parse_status,
          chunk_count, external_id, source_url, content_md5, storage_bucket, ingest_source
        )
        VALUES (?, ?, ?, 10, 'application/pdf', ?, ?, 0, ?, ?, ?, 'test-bucket', 'it')
        RETURNING id
        """,
        Long.class,
        kbId,
        filename,
        storageKey,
        contentMd5,
        parseStatus,
        externalId,
        sourceUrl,
        contentMd5);
  }

  private void insertChunk(Long docId, Long kbId, String content) {
    jdbcTemplate.update(
        "INSERT INTO document_chunks (doc_id, kb_id, chunk_index, content) VALUES (?, ?, 0, ?)",
        docId,
        kbId,
        content);
  }

  private long countChunks(Long docId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM document_chunks WHERE doc_id = ?", Long.class, docId);
  }

  private IngestCommand command(
      Long kbId, String filename, String storageKey, Identity identity, OnConflict onConflict) {
    IngestCommand cmd = new IngestCommand();
    cmd.setKbId(kbId);
    cmd.setStorageBucket("new-bucket");
    cmd.setStorageKey(storageKey);
    cmd.setFilename(filename);
    cmd.setSizeBytes(20L);
    cmd.setContentType("application/pdf");
    cmd.setIdentity(identity);
    cmd.setOnConflict(onConflict);
    cmd.setIngestSource("it");
    cmd.setChunkType("recursive");
    return cmd;
  }

  private Identity identity(String externalId, String sourceUrl, String contentMd5) {
    Identity identity = new Identity();
    identity.setExternalId(externalId);
    identity.setSourceUrl(sourceUrl);
    identity.setContentMd5(contentMd5);
    return identity;
  }

  private String rootSqlState(Throwable t) {
    Throwable cursor = t;
    while (cursor != null) {
      if (cursor instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
      cursor = cursor.getCause();
    }
    return null;
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @MapperScan("com.ragforge.mapper")
  @Import(IngestServiceImpl.class)
  static class TestConfig {
    @Bean
    RagforgeMetrics ragforgeMetrics() {
      return new RagforgeMetrics(new SimpleMeterRegistry());
    }
  }
}
