package com.ragforge.service;

import com.ragforge.model.dto.LlmGenerateRequest;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmService {

  Map<String, Object> generate(LlmGenerateRequest request);

  default StreamResult streamGenerate(
      LlmGenerateRequest request, Integer maxTokens, Consumer<String> onDelta) {
    Map<String, Object> result = generate(request);
    String content = String.valueOf(result.getOrDefault("content", ""));
    if (onDelta != null && !content.isBlank()) {
      onDelta.accept(content);
    }
    return new StreamResult(
        content,
        number(result.get("promptTokens")),
        number(result.get("completionTokens")),
        number(result.get("totalMs")));
  }

  record StreamResult(String content, int promptTokens, int completionTokens, long latencyMs) {}

  private static int number(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    return 0;
  }
}
