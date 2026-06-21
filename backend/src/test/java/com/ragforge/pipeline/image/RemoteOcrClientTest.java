package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RemoteOcrClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void extractOcrText_prefersProcessedText() throws Exception {
    String json =
        """
        {"output":{"choices":[{"message":{"content":[
          {"ocr_result":{"processed_text":"首选文字"},"text":"兜底文字"}
        ]}}]}}
        """;

    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree(json))).isEqualTo("首选文字");
  }

  @Test
  void extractOcrText_fallsBackToText() throws Exception {
    String json =
        """
        {"output":{"choices":[{"message":{"content":[
          {"text":"兜底文字"}
        ]}}]}}
        """;

    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree(json))).isEqualTo("兜底文字");
  }

  @Test
  void extractOcrText_supportsOldFlatFormat() throws Exception {
    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree("{\"data\":{\"text\":\"旧格式\"}}")))
        .isEqualTo("旧格式");
  }
}
