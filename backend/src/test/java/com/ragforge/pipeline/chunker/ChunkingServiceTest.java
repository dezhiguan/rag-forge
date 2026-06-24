package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.pipeline.chunker.ChunkParams;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {

  @Test
  void defaultProfileRoutesMarkdownToMarkdownHeading() {
    ChunkingService service =
        new ChunkingService(
            List.of(
                new RecursiveChunkerStrategy(),
                new FixedWindowChunkerStrategy(new TextChunker()),
                new MarkdownHeadingChunkerStrategy()),
            new ObjectMapper());
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(2L);
    doc.setFilename("guide.md");
    doc.setFileType("text/markdown");
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(2L);

    ChunkingResult result =
        service.split(
            doc,
            kb,
            """
            # Intro
            body
            ## Details
            more body
            """);

    assertThat(result.getStrategy()).isEqualTo("MARKDOWN_HEADING");
    assertThat(result.getChunks()).extracting(Chunk::getHeadingPath).contains("Intro", "Intro/Details");
  }

  @Test
  void splitWithStrategyUsesRequestedStrategyOnly() {
    ChunkingService service =
        new ChunkingService(
            List.of(
                new RecursiveChunkerStrategy(),
                new FixedWindowChunkerStrategy(new TextChunker()),
                new MarkdownHeadingChunkerStrategy()),
            new ObjectMapper());
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(2L);
    doc.setFilename("plain.txt");
    doc.setFileType("text/plain");
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(2L);

    ChunkingResult result =
        service.splitWithStrategy(
            doc,
            kb,
            "alpha beta gamma delta epsilon zeta eta theta iota kappa",
            "FIXED_WINDOW",
            new ChunkParams());

    assertThat(result.getStrategy()).isEqualTo("FIXED_WINDOW");
    assertThat(result.getChunks()).isNotEmpty();
  }
}
