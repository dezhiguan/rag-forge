package com.ragforge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.archive.ArchiveEntryConsumer;
import com.ragforge.archive.ArchiveErrorCodes;
import com.ragforge.archive.ArchiveException;
import com.ragforge.archive.ArchiveExpander;
import com.ragforge.archive.ExpandOutcome;
import com.ragforge.archive.ExpandedEntry;
import com.ragforge.common.ErrorMessages;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.OnConflict;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.service.ingest.IngestService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveExpandConsumerTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private ArchiveExpander archiveExpander;
  @Mock private ObjectStorage objectStorage;
  @Mock private IngestService ingestService;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  private ArchiveExpandConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer =
        new ArchiveExpandConsumer(
            documentMapper,
            archiveExpander,
            objectStorage,
            ingestService,
            knowledgeBaseMapper,
            new ObjectMapper());
  }

  private Document container() {
    Document d = new Document();
    d.setId(10L);
    d.setKbId(5L);
    d.setStorageBucket("local");
    d.setStorageKey("kb_5/uuid/pkg.zip");
    d.setFileType("zip");
    return d;
  }

  @Test
  void casNotClaimed_skipsSilently() {
    when(documentMapper.claimForExpansion(10L)).thenReturn(0);

    consumer.onMessage(10L);

    verify(documentMapper, never()).selectById(any());
    verify(documentMapper, never()).finishExpansion(any(), any(), any(), any());
  }

  @Test
  void claimedButVanished_returnsWithoutFinish() {
    when(documentMapper.claimForExpansion(10L)).thenReturn(1);
    when(documentMapper.selectById(10L)).thenReturn(null);

    consumer.onMessage(10L);

    verify(documentMapper, never()).finishExpansion(any(), any(), any(), any());
  }

  @Test
  void claimed_expandsRegistersChildAndFinishesExpanded() {
    when(documentMapper.claimForExpansion(10L)).thenReturn(1);
    when(documentMapper.selectById(10L)).thenReturn(container());
    KnowledgeBase kb = new KnowledgeBase();
    kb.setOrgId(1L);
    when(knowledgeBaseMapper.selectById(5L)).thenReturn(kb);
    when(objectStorage.get(any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(archiveExpander.expand(any(), any(), any()))
        .thenAnswer(
            inv -> {
              ArchiveEntryConsumer sink = inv.getArgument(2);
              sink.consume(
                  new ExpandedEntry(
                      "docs/a.pdf", "a.pdf", "hello".getBytes(StandardCharsets.UTF_8), "abc123"));
              ExpandOutcome o = new ExpandOutcome();
              o.incrementTotal();
              o.incrementRegistered();
              return o;
            });

    consumer.onMessage(10L);

    // 子文档落 OSS
    verify(objectStorage).put(eq("local"), anyString(), any(), any());
    // 子文档经 IngestService 登记，parent 回指、SKIP 去重、contentMd5 传递
    ArgumentCaptor<IngestCommand> cmd = ArgumentCaptor.forClass(IngestCommand.class);
    verify(ingestService).register(cmd.capture());
    assertThat(cmd.getValue().getKbId()).isEqualTo(5L);
    assertThat(cmd.getValue().getParentDocumentId()).isEqualTo(10L);
    assertThat(cmd.getValue().getArchiveEntryPath()).isEqualTo("docs/a.pdf");
    assertThat(cmd.getValue().getOnConflict()).isEqualTo(OnConflict.SKIP);
    assertThat(cmd.getValue().getIdentity().getContentMd5()).isEqualTo("abc123");
    assertThat(cmd.getValue().getContentType()).isEqualTo("application/pdf");
    // 容器置 EXPANDED，写 summary
    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(documentMapper).finishExpansion(eq(10L), eq("EXPANDED"), summary.capture(), isNull());
    assertThat(summary.getValue()).contains("\"totalEntries\":1").contains("\"registered\":1");
  }

  @Test
  void archiveException_finishesFailedWithCode() {
    when(documentMapper.claimForExpansion(10L)).thenReturn(1);
    when(documentMapper.selectById(10L)).thenReturn(container());
    when(objectStorage.get(any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(archiveExpander.expand(any(), any(), any()))
        .thenThrow(new ArchiveException(ArchiveErrorCodes.SUSPICIOUS_RATIO, "bomb"));

    consumer.onMessage(10L);

    verify(documentMapper)
        .finishExpansion(
            eq(10L),
            eq("FAILED"),
            isNull(),
            eq(ErrorMessages.toChinese(ArchiveErrorCodes.SUSPICIOUS_RATIO)));
  }

  @Test
  void genericException_finishesFailedCorrupted() {
    when(documentMapper.claimForExpansion(10L)).thenReturn(1);
    when(documentMapper.selectById(10L)).thenReturn(container());
    when(objectStorage.get(any(), any())).thenThrow(new RuntimeException("oss down"));

    consumer.onMessage(10L);

    verify(documentMapper)
        .finishExpansion(
            eq(10L), eq("FAILED"), isNull(), eq(ErrorMessages.toChinese(ArchiveErrorCodes.CORRUPTED)));
  }
}
